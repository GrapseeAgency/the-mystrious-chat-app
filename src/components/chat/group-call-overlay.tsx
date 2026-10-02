// Pulse - GROUP voice + video calls (mesh WebRTC, WhatsApp/Signal-style).
//
// Signaling contract (socket service `gcall:*`):
//   gcall:join { conversationId, kind, user:{id,name,color,avatar} }
//   gcall:state { callId, conversationId, kind, hostId, startedAt, members[] }
//   gcall:offer { conversationId, callId, from, to, kind, sdp }   - targeted
//   gcall:answer { conversationId, callId, from, to, sdp }        - targeted
//   gcall:ice { conversationId, callId, from, to, candidate, sdpMid, sdpMLineIndex }
//   gcall:leave { conversationId, from }
//   gcall:ring (HTTP relay) · gcall:ended · gcall:full
//
// Mesh direction is DETERMINISTIC and stateless: between any two members the
// one with the lexicographically SMALLER id creates the offer. No glare, no
// join-order bookkeeping, safe under simultaneous joins. One
// RTCPeerConnection per remote member, all fed from one local MediaStream.
//
// Rings reach ONLINE members through the `/notify` relay (`gcall:ring`) and
// OFFLINE members through remote push - the starting client POSTs
// /api/conversations/[id]/calls/ring which fans both out. Joining an ONGOING
// call NEVER re-rings (ring:false path).
//
// Exports:
//   useGroupCallSession({...}) - join/leave/roster/media state machine.
//   <GroupCallOverlay session={...} /> - full-screen glass group call UI.
//   <GroupCallRingBanner session={...} /> - incoming ring + ongoing banners.
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { Mic, MicOff, PhoneCall, PhoneOff, Users, Video, VideoOff } from 'lucide-react'
import { toast } from 'sonner'
import type { CallKind } from '@/lib/call-types'
import { formatCallDuration } from '@/components/chat/call-overlay'
import { apiJson } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { spring, prefersReducedMotion } from '@/lib/motion'
import { usePulseCallChannel } from '@/components/chat/pulse-realtime-provider'
import { UserAvatar } from '@/components/chat/user-avatar'
import { cn } from '@/lib/utils'

// contracts 

export type GroupCallUiState = 'idle' | 'joining' | 'active' | 'ended'

export interface GroupCallMemberView {
  id: string
  name: string
  color: string
  avatar: string | null
}

export interface GroupCallSessionControls {
  state: GroupCallUiState
  kind: CallKind
  conversationId: string
  /** Roster INCLUDING me, join-ordered (while in the call). */
  members: GroupCallMemberView[]
  /** Live incoming ring (null = none). Rings surface on ANY open room. */
  ring: { conversationId: string; caller: GroupCallMemberView; kind: CallKind; title: string } | null
  /** True while the OPEN conversation has a live call I am NOT in. */
  ongoingElsewhere: boolean
  ongoingMembers: GroupCallMemberView[]
  ongoingKind: CallKind
  /** Non-null = honest glass error card replaces the call UI. */
  error: string | null
  summary: string | null
  durationSec: number
  micEnabled: boolean
  cameraEnabled: boolean
  localStream: MediaStream | null
  /** Streams keyed by remote member id. */
  remoteStreams: Map<string, MediaStream>
  /** Start a NEW group call in the open conversation (rings everyone). */
  startCall: (kind: CallKind) => void
  /** Accept an incoming ring (ring already sent - never re-rings). */
  joinCall: () => void
  /** Silently join the ONGOING call in the open room - never re-rings. */
  joinOngoing: () => void
  dismissRing: () => void
  ignoreOngoing: () => void
  leaveCall: () => void
  dismissError: () => void
  dismissSummary: () => void
  toggleMic: () => void
  toggleCamera: () => void
}

export interface GroupCallSessionOptions {
  meId: string
  meName: string
  meColor?: string
  meAvatar?: string | null
  conversationId: string
  /** False = not a group conversation; 1:1 rooms never mount the group flow. */
  enabled: boolean
}

interface RingPayload {
  conversationId: string
  kind: CallKind
  caller: GroupCallMemberView
  title: string
}

const STUN_SERVERS: RTCConfiguration = {
  iceServers: [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
  ],
}

// the hook 

export function useGroupCallSession(options: GroupCallSessionOptions): GroupCallSessionControls {
  const { meId, meName, meColor = 'emerald', meAvatar = null, conversationId, enabled } = options
  const { subscribeCallEvents, emitCallEvent, callChannelConnected } = usePulseCallChannel()

  const [state, setState] = useState<GroupCallUiState>('idle')
  const [kind, setKind] = useState<CallKind>('voice')
  const [members, setMembers] = useState<GroupCallMemberView[]>([])
  const [ring, setRing] = useState<GroupCallSessionControls['ring']>(null)
  const [ongoingElsewhere, setOngoingElsewhere] = useState(false)
  const [ongoingMembers, setOngoingMembers] = useState<GroupCallMemberView[]>([])
  const [ongoingKind, setOngoingKind] = useState<CallKind>('voice')
  const [error, setError] = useState<string | null>(null)
  const [summary, setSummary] = useState<string | null>(null)
  const [durationSec, setDurationSec] = useState(0)
  const [micEnabled, setMicEnabled] = useState(true)
  const [cameraEnabled, setCameraEnabled] = useState(true)
  const [localStream, setLocalStream] = useState<MediaStream | null>(null)
  const [remoteStreams, setRemoteStreams] = useState<Map<string, MediaStream>>(new Map())

  // Mutable mirrors so the stable socket listener sees fresh values.
  const stateRef = useRef<GroupCallUiState>('idle')
  const kindRef = useRef<CallKind>('voice')
  const joinedConvRef = useRef<string | null>(null)
  const joinedAtRef = useRef(0)
  const localStreamRef = useRef<MediaStream | null>(null)
  const peersRef = useRef<Map<string, RTCPeerConnection>>(new Map())
  const pendingIceRef = useRef<Map<string, RTCIceCandidateInit[]>>(new Map())
  const optionsRef = useRef({ meId, meName, meColor, meAvatar, conversationId })
  const ringRef = useRef<GroupCallSessionControls['ring']>(null)
  const joinIntentRef = useRef(false)

  useEffect(() => {
    optionsRef.current = { meId, meName, meColor, meAvatar, conversationId }
  }, [meId, meName, meColor, meAvatar, conversationId])
  useEffect(() => {
    stateRef.current = state
  }, [state])

  const setStateBoth = useCallback((next: GroupCallUiState) => {
    stateRef.current = next
    setState(next)
  }, [])

  // media 
  const acquireMedia = useCallback(async (wanted: CallKind): Promise<CallKind> => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: wanted === 'video' ? { width: { ideal: 1280 }, height: { ideal: 720 } } : false,
      })
      localStreamRef.current = stream
      setLocalStream(stream)
      setCameraEnabled(wanted === 'video')
      return wanted
    } catch (err) {
      const name = err instanceof DOMException ? err.name : ''
      if (wanted === 'video' && (name === 'NotFoundError' || name === 'OverconstrainedError' || name === 'NotReadableError')) {
        toast('Camera unavailable - joining as a voice call')
        setKind('voice')
        kindRef.current = 'voice'
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
        localStreamRef.current = stream
        setLocalStream(stream)
        setCameraEnabled(false)
        return 'voice'
      }
      throw err
    }
  }, [])

  const teardownMedia = useCallback(() => {
    const stalePeers = Array.from(peersRef.current.values())
    peersRef.current = new Map()
    for (const pc of stalePeers) {
      pc.onicecandidate = null
      pc.ontrack = null
      try {
        pc.close()
      } catch {
        // already closed
      }
    }
    const stream = localStreamRef.current
    localStreamRef.current = null
    for (const track of stream?.getTracks() ?? []) track.stop()
    pendingIceRef.current = new Map()
    joinedConvRef.current = null
    joinedAtRef.current = 0
    joinIntentRef.current = false
    setLocalStream(null)
    setRemoteStreams(new Map())
    setMembers([])
    setDurationSec(0)
    setMicEnabled(true)
    setCameraEnabled(true)
  }, [])

  const ensurePeerConnection = useCallback(
    (peerId: string): RTCPeerConnection => {
      const existing = peersRef.current.get(peerId)
      if (existing) return existing
      const pc = new RTCPeerConnection(STUN_SERVERS)
      const conv = joinedConvRef.current ?? optionsRef.current.conversationId
      pc.onicecandidate = (event) => {
        if (!event.candidate) return
        emitCallEvent('gcall:ice', {
          conversationId: conv,
          from: optionsRef.current.meId,
          to: peerId,
          kind: kindRef.current,
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
        })
      }
      pc.ontrack = (event) => {
        const [stream] = event.streams
        if (!stream) return
        setRemoteStreams((prev) => {
          const next = new Map(prev)
          next.set(peerId, stream)
          return next
        })
      }
      const stream = localStreamRef.current
      if (stream) for (const track of stream.getTracks()) pc.addTrack(track, stream)
      peersRef.current.set(peerId, pc)
      return pc
    },
    [emitCallEvent],
  )

  /**
   * Roster sync for a call I'm in. Deterministic mesh rule: I offer to every
   * roster member whose id sorts BELOW mine and has no pc yet; members that
   * sort above will offer me. Departed members get their pc closed.
   */
  const applyRoster = useCallback(
    (roster: GroupCallMemberView[], rosterConvId: string) => {
      const me = optionsRef.current.meId
      const others = roster.filter((m) => m.id !== me)
      setMembers(roster)
      setOngoingMembers(roster)

      const departing = Array.from(peersRef.current).filter(([peerId]) => !others.some((m) => m.id === peerId))
      if (departing.length > 0) {
        for (const [peerId, pc] of departing) {
          peersRef.current.delete(peerId)
          pendingIceRef.current.delete(peerId)
          pc.onicecandidate = null
          pc.ontrack = null
          try {
            pc.close()
          } catch {
            // already closed
          }
        }
        setRemoteStreams((prev) => {
          const next = new Map(prev)
          for (const [peerId] of departing) next.delete(peerId)
          return next
        })
      }

      if (joinedAtRef.current === 0) {
        joinedAtRef.current = Date.now()
        setDurationSec(0)
      }
      if (stateRef.current === 'joining') setStateBoth('active')

      for (const member of others) {
        if (peersRef.current.has(member.id)) continue
        if (me < member.id) {
          // I own the offer for this pair.
          void (async () => {
            try {
              const pc = ensurePeerConnection(member.id)
              const offer = await pc.createOffer({
                offerToReceiveAudio: true,
                offerToReceiveVideo: kindRef.current === 'video',
              })
              await pc.setLocalDescription(offer)
              emitCallEvent('gcall:offer', {
                conversationId: rosterConvId,
                from: me,
                to: member.id,
                kind: kindRef.current,
                sdp: offer.sdp ?? '',
              })
            } catch (err) {
              console.error('[gcall] offer create failed:', err instanceof Error ? err.message : err)
            }
          })()
        }
      }
    },
    [emitCallEvent, ensurePeerConnection, setStateBoth],
  )

  // actions 
  const startJoin = useCallback(
    (targetConvId: string, wanted: CallKind, ringOthers: boolean) => {
      if (!enabled) return
      if (!targetConvId) return
      if (stateRef.current !== 'idle') return
      if (!callChannelConnected) {
        toast.error('You are offline - calls need a connection')
        return
      }
      haptic(14)
      joinIntentRef.current = true
      kindRef.current = wanted
      setKind(wanted)
      setError(null)
      setSummary(null)
      setRing(null)
      ringRef.current = null
      setStateBoth('joining')
      void (async () => {
        try {
          await acquireMedia(wanted)
          const opts = optionsRef.current
          joinedConvRef.current = targetConvId
          emitCallEvent('gcall:join', {
            conversationId: targetConvId,
            kind: wanted,
            user: { id: opts.meId, name: opts.meName, color: opts.meColor, avatar: opts.meAvatar },
          })
          if (ringOthers) {
            // Ring the other members (online → socket banner, offline → push).
            void apiJson(`/api/conversations/${targetConvId}/calls/ring`, {
              method: 'POST',
              body: JSON.stringify({ userId: opts.meId, kind: wanted }),
            }).catch(() => toast.error('Could not ring other members'))
          }
        } catch {
          setStateBoth('idle')
          joinIntentRef.current = false
          joinedConvRef.current = null
          setError('Microphone access is needed for calls. Check the browser permissions and try again.')
        }
      })()
    },
    [acquireMedia, callChannelConnected, emitCallEvent, enabled, setStateBoth],
  )

  const startCall = useCallback(
    (wanted: CallKind) => startJoin(optionsRef.current.conversationId, wanted, true),
    [startJoin],
  )

  const joinCall = useCallback(() => {
    const target = ringRef.current
    if (!target || stateRef.current !== 'idle') return
    haptic(14)
    // The ring already went out - joining must NOT re-ring.
    startJoin(target.conversationId, target.kind, false)
  }, [startJoin])

  const joinOngoing = useCallback(() => {
    if (stateRef.current !== 'idle') return
    haptic(14)
    setOngoingElsewhere(false)
    startJoin(optionsRef.current.conversationId, ongoingKind, false)
  }, [ongoingKind, startJoin])

  const leaveCall = useCallback(() => {
    const conv = joinedConvRef.current
    const current = stateRef.current
    haptic(12)
    if (current === 'joining' || current === 'active') {
      if (conv) {
        emitCallEvent('gcall:leave', { conversationId: conv, from: optionsRef.current.meId })
      }
      const elapsed = joinedAtRef.current > 0 ? (Date.now() - joinedAtRef.current) / 1000 : 0
      teardownMedia()
      setStateBoth('ended')
      setSummary(elapsed >= 1 ? `You left · ${formatCallDuration(Math.round(elapsed))}` : 'You left')
    }
  }, [emitCallEvent, setStateBoth, teardownMedia])

  const dismissError = useCallback(() => {
    setError(null)
    setStateBoth('idle')
  }, [setStateBoth])

  const dismissSummary = useCallback(() => {
    setSummary(null)
    setStateBoth('idle')
  }, [setStateBoth])

  const dismissRing = useCallback(() => {
    ringRef.current = null
    setRing(null)
  }, [])

  const ignoreOngoing = useCallback(() => {
    setOngoingElsewhere(false)
  }, [])

  const toggleMic = useCallback(() => {
    const stream = localStreamRef.current
    if (!stream) return
    const next = !stream.getAudioTracks().every((t) => t.enabled)
    for (const track of stream.getAudioTracks()) track.enabled = next
    setMicEnabled(next)
    haptic(6)
  }, [])

  const toggleCamera = useCallback(() => {
    const stream = localStreamRef.current
    if (!stream || kindRef.current !== 'video') return
    const videoTracks = stream.getVideoTracks()
    if (videoTracks.length === 0) return
    const next = !videoTracks.every((t) => t.enabled)
    for (const track of videoTracks) track.enabled = next
    setCameraEnabled(next)
    haptic(6)
  }, [])

  // signaling listener (stable) 
  const handleGroupCallEvent = useCallback(
    (event: string, raw: unknown) => {
      if (raw === null || typeof raw !== 'object') return
      const payload = raw as Record<string, unknown>
      const me = optionsRef.current.meId

      if (event === 'gcall:ring') {
        const convId = typeof payload.conversationId === 'string' ? payload.conversationId : ''
        if (!convId) return
        const callerRaw = payload.caller
        if (typeof callerRaw !== 'object' || callerRaw === null) return
        const callerObj = callerRaw as Record<string, unknown>
        if (typeof callerObj.id === 'string' && callerObj.id === me) return
        const ringPayload: GroupCallSessionControls['ring'] = {
          kind: payload.kind === 'video' ? 'video' : 'voice',
          title: typeof payload.title === 'string' && payload.title ? payload.title : '',
          caller: {
            id: typeof callerObj.id === 'string' ? callerObj.id : '',
            name: typeof callerObj.name === 'string' && callerObj.name ? callerObj.name : 'Someone',
            color: typeof callerObj.color === 'string' && callerObj.color ? callerObj.color : 'emerald',
            avatar: typeof callerObj.avatar === 'string' ? callerObj.avatar : null,
          },
        }
        // Only ring when idle - a participant never sees their own ring.
        if (stateRef.current === 'idle') {
          ringRef.current = ringPayload
          setRing(ringPayload)
          haptic(30)
        }
        return
      }

      if (event === 'gcall:state') {
        const convId = typeof payload.conversationId === 'string' ? payload.conversationId : ''
        if (!convId || convId !== optionsRef.current.conversationId) return
        if (!Array.isArray(payload.members)) return
        const roster: GroupCallMemberView[] = []
        for (const item of payload.members) {
          if (typeof item !== 'object' || item === null) continue
          const obj = item as Record<string, unknown>
          if (typeof obj.id !== 'string' || !obj.id) continue
          roster.push({
            id: obj.id,
            name: typeof obj.name === 'string' && obj.name ? obj.name : 'Someone',
            color: typeof obj.color === 'string' && obj.color ? obj.color : 'emerald',
            avatar: typeof obj.avatar === 'string' ? obj.avatar : null,
          })
        }
        const iAmMember = roster.some((m) => m.id === me)
        if (iAmMember) {
          setOngoingElsewhere(false)
          applyRoster(roster, convId)
        } else if (stateRef.current === 'idle' && !joinedConvRef.current) {
          if (typeof payload.kind === 'string') setOngoingKind(payload.kind === 'video' ? 'video' : 'voice')
          setOngoingMembers(roster)
          setOngoingElsewhere(true)
        }
        return
      }

      // Targeted signaling below requires me to be a participant.
      if (!joinedConvRef.current) return

      if (event === 'gcall:offer') {
        const from = typeof payload.from === 'string' ? payload.from : ''
        const convId = typeof payload.conversationId === 'string' ? payload.conversationId : ''
        const sdp = typeof payload.sdp === 'string' ? payload.sdp : ''
        if (!from || from === me || convId !== joinedConvRef.current || !sdp) return
        void (async () => {
          try {
            const pc = ensurePeerConnection(from)
            await pc.setRemoteDescription({ type: 'offer', sdp })
            const answer = await pc.createAnswer()
            await pc.setLocalDescription(answer)
            emitCallEvent('gcall:answer', {
              conversationId: convId,
              from: me,
              to: from,
              sdp: answer.sdp ?? '',
            })
            const queued = pendingIceRef.current.get(from)?.splice(0) ?? []
            for (const candidate of queued) {
              try {
                await pc.addIceCandidate(candidate)
              } catch {
                // stale candidate - ICE recovers
              }
            }
          } catch (err) {
            console.error('[gcall] offer apply failed:', err instanceof Error ? err.message : err)
          }
        })()
        return
      }

      if (event === 'gcall:answer') {
        const from = typeof payload.from === 'string' ? payload.from : ''
        const sdp = typeof payload.sdp === 'string' ? payload.sdp : ''
        if (!from || from === me || !sdp) return
        void (async () => {
          try {
            const pc = peersRef.current.get(from)
            if (!pc) return
            await pc.setRemoteDescription({ type: 'answer', sdp })
            const queued = pendingIceRef.current.get(from)?.splice(0) ?? []
            for (const candidate of queued) {
              try {
                await pc.addIceCandidate(candidate)
              } catch {
                // stale candidate
              }
            }
          } catch (err) {
            console.error('[gcall] answer apply failed:', err instanceof Error ? err.message : err)
          }
        })()
        return
      }

      if (event === 'gcall:ice') {
        const from = typeof payload.from === 'string' ? payload.from : ''
        const candidateString = typeof payload.candidate === 'string' ? payload.candidate : ''
        if (!from || from === me || !candidateString) return
        const candidate: RTCIceCandidateInit = {
          candidate: candidateString,
          sdpMid: typeof payload.sdpMid === 'string' ? payload.sdpMid : null,
          sdpMLineIndex:
            typeof payload.sdpMLineIndex === 'number' && Number.isFinite(payload.sdpMLineIndex)
              ? Math.floor(payload.sdpMLineIndex)
              : null,
        }
        const pc = peersRef.current.get(from)
        if (pc && pc.remoteDescription) {
          void pc.addIceCandidate(candidate).catch(() => {
            // stale candidate - ignore
          })
        } else {
          const queue = pendingIceRef.current.get(from) ?? []
          queue.push(candidate)
          pendingIceRef.current.set(from, queue)
        }
        return
      }

      if (event === 'gcall:ended') {
        if (joinedConvRef.current) {
          teardownMedia()
          setStateBoth('ended')
          setSummary('Call ended')
        }
        setOngoingElsewhere(false)
        setOngoingMembers([])
        return
      }

      if (event === 'gcall:full') {
        if (joinIntentRef.current) {
          teardownMedia()
          setStateBoth('idle')
          toast.error('That call is full (8 max)')
        }
        return
      }
    },
    [applyRoster, emitCallEvent, ensurePeerConnection, setStateBoth, teardownMedia],
  )

  useEffect(() => {
    if (!enabled) return
    return subscribeCallEvents(handleGroupCallEvent)
  }, [enabled, handleGroupCallEvent, subscribeCallEvents])

  // Duration ticker while active.
  useEffect(() => {
    if (state !== 'active') return
    const timer = setInterval(() => {
      if (joinedAtRef.current > 0) {
        setDurationSec(Math.max(0, Math.floor((Date.now() - joinedAtRef.current) / 1000)))
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [state])

  // Probe for an ongoing call on mount + while idle (a late-opening room or
  // reload learns about the call without a live ring). Honest socket read.
  useEffect(() => {
    if (!enabled || !conversationId) return
    let cancelled = false
    const probe = async () => {
      if (stateRef.current !== 'idle' || joinedConvRef.current) return
      try {
        const res = await fetch(`/api/group-call-state?conversationId=${encodeURIComponent(conversationId)}`, {
          signal: AbortSignal.timeout(2500),
        })
        if (!res.ok) return
        const body = (await res.json()) as { members?: unknown; kind?: unknown }
        if (cancelled) return
        if (Array.isArray(body.members) && body.members.length > 0) {
          const roster: GroupCallMemberView[] = []
          for (const item of body.members) {
            if (typeof item !== 'object' || item === null) continue
            const obj = item as Record<string, unknown>
            if (typeof obj.id !== 'string' || !obj.id) continue
            roster.push({
              id: obj.id,
              name: typeof obj.name === 'string' && obj.name ? obj.name : 'Someone',
              color: typeof obj.color === 'string' && obj.color ? obj.color : 'emerald',
              avatar: typeof obj.avatar === 'string' ? obj.avatar : null,
            })
          }
          if (roster.length > 0 && !roster.some((m) => m.id === meId)) {
            if (body.kind === 'video') setOngoingKind('video')
            setOngoingMembers(roster)
            setOngoingElsewhere(true)
          } else if (roster.some((m) => m.id === meId)) {
            setOngoingElsewhere(false)
          }
        } else {
          setOngoingElsewhere(false)
        }
      } catch {
        // socket down - banner simply won't show
      }
    }
    void probe()
    const timer = setInterval(() => void probe(), 20_000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [conversationId, enabled, meId])

  // Left the room mid-call → leave honestly.
  useEffect(() => {
    return () => {
      const conv = joinedConvRef.current
      if (conv) {
        emitCallEvent('gcall:leave', { conversationId: conv, from: optionsRef.current.meId })
      }
      for (const pc of peersRef.current.values()) {
        try {
          pc.close()
        } catch {
          // already closed
        }
      }
      for (const track of localStreamRef.current?.getTracks() ?? []) track.stop()
    }
  }, [emitCallEvent])

  return {
    state,
    kind,
    conversationId,
    members,
    ring,
    ongoingElsewhere,
    ongoingMembers,
    ongoingKind,
    error,
    summary,
    durationSec,
    micEnabled,
    cameraEnabled,
    localStream,
    remoteStreams,
    startCall,
    joinCall,
    joinOngoing,
    dismissRing,
    ignoreOngoing,
    leaveCall,
    dismissError,
    dismissSummary,
    toggleMic,
    toggleCamera,
  }
}

// video attachment helpers 

function RemoteVideo({ stream, className }: { stream: MediaStream; className?: string }) {
  const ref = useRef<HTMLVideoElement | null>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.srcObject = stream
    return () => {
      el.srcObject = null
    }
  }, [stream])
  return <video ref={ref} autoPlay playsInline className={className} />
}

function LocalVideo({ stream, className }: { stream: MediaStream; className?: string }) {
  const ref = useRef<HTMLVideoElement | null>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.srcObject = stream
    return () => {
      el.srcObject = null
    }
  }, [stream])
  return <video ref={ref} autoPlay playsInline muted className={className} />
}

// the overlay 

export function GroupCallOverlay({
  session,
  title,
  me,
}: {
  session: GroupCallSessionControls
  title: string
  me: { name: string; color: string; avatar: string | null }
}) {
  const reduced = prefersReducedMotion()
  if (session.state === 'idle' && !session.error && !session.summary) return null

  const remoteEntries = Array.from(session.remoteStreams.entries())
  const roster = session.members.length > 0 ? session.members : [{ id: 'me', name: me.name, color: me.color, avatar: me.avatar }]
  const remoteMembers = roster.filter((m) => m.id !== me.id && m.id !== 'me')
  const tileCount = roster.length
  const gridCols =
    tileCount <= 1 ? 'grid-cols-1' : tileCount <= 4 ? 'grid-cols-2' : 'grid-cols-2 sm:grid-cols-3'

  return (
    <AnimatePresence>
      <motion.div
        initial={reduced ? { opacity: 0 } : { opacity: 0, scale: 0.98 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={reduced ? { opacity: 0 } : { opacity: 0, scale: 0.98 }}
        transition={spring()}
        className="fixed inset-0 z-[95] flex flex-col bg-black/85 backdrop-blur-2xl"
        role="dialog"
        aria-label="Group call"
      >
        <header className="flex items-center justify-between px-5 pb-3 pt-5 text-white">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold">{title}</p>
            <p className="text-xs text-white/60" aria-live="polite">
              {session.state === 'joining'
                ? 'Connecting…'
                : session.state === 'active'
                  ? `${session.kind === 'video' ? 'Video' : 'Voice'} call · ${formatCallDuration(session.durationSec)}`
                  : session.summary ?? ''}
            </p>
          </div>
          <div className="flex items-center gap-1.5 rounded-full bg-white/10 px-3 py-1.5 text-xs text-white/80">
            <Users className="h-3.5 w-3.5" aria-hidden />
            <span>{session.members.length || '…'}</span>
          </div>
        </header>

        <div className="min-h-0 flex-1 px-4 pb-2">
          {session.error ? (
            <div className="mx-auto mt-8 max-w-md rounded-3xl border border-white/15 bg-white/10 p-6 text-center text-white">
              <p className="text-sm leading-relaxed">{session.error}</p>
              <button
                onClick={session.dismissError}
                className="mt-5 rounded-full bg-white/15 px-5 py-2.5 text-sm font-medium hover:bg-white/25"
              >
                Close
              </button>
            </div>
          ) : session.state === 'ended' && session.summary ? (
            <div className="mx-auto mt-8 max-w-md rounded-3xl border border-white/15 bg-white/10 p-6 text-center text-white">
              <p className="text-sm">{session.summary}</p>
              <button
                onClick={session.dismissSummary}
                className="mt-5 rounded-full bg-white/15 px-5 py-2.5 text-sm font-medium hover:bg-white/25"
              >
                Done
              </button>
            </div>
          ) : (
            <div className={cn('grid h-full w-full content-start gap-3', gridCols)}>
              {/* Self tile */}
              <div className="relative aspect-[3/4] max-h-full overflow-hidden rounded-3xl border border-white/15 bg-white/5">
                {session.kind === 'video' && session.localStream && session.cameraEnabled ? (
                  <LocalVideo stream={session.localStream} className="h-full w-full object-cover" />
                ) : (
                  <div className="flex h-full w-full items-center justify-center">
                    <UserAvatar name={me.name} color={me.color} size={56} avatar={me.avatar} />
                  </div>
                )}
                <div className="absolute inset-x-0 bottom-0 flex items-center justify-between bg-gradient-to-t from-black/70 to-transparent px-3 py-2 text-xs text-white">
                  <span className="truncate">You</span>
                  {!session.micEnabled && <MicOff className="h-3.5 w-3.5 text-rose-300" aria-label="Mic muted" />}
                </div>
              </div>
              {/* Remote tiles (audio-only members keep avatar tiles - honest) */}
              {remoteMembers.map((member) => {
                const stream = remoteEntries.find(([id]) => id === member.id)?.[1]
                return (
                  <div
                    key={member.id}
                    className="relative aspect-[3/4] max-h-full overflow-hidden rounded-3xl border border-white/15 bg-white/5"
                  >
                    {session.kind === 'video' && stream ? (
                      <RemoteVideo stream={stream} className="h-full w-full object-cover" />
                    ) : (
                      <div className="flex h-full w-full items-center justify-center">
                        <UserAvatar name={member.name} color={member.color} size={56} avatar={member.avatar} />
                      </div>
                    )}
                    <div className="absolute inset-x-0 bottom-0 truncate bg-gradient-to-t from-black/70 to-transparent px-3 py-2 text-xs text-white">
                      {member.name}
                    </div>
                  </div>
                )
              })}
              {session.state === 'joining' && (
                <div className="relative aspect-[3/4] max-h-full overflow-hidden rounded-3xl border border-dashed border-white/25 bg-white/5">
                  <div className="flex h-full w-full items-center justify-center text-xs text-white/60">Connecting…</div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Controls */}
        {session.state !== 'ended' && !session.error && (
          <footer className="flex items-center justify-center gap-4 px-6 pb-8 pt-2">
            <button
              onClick={session.toggleMic}
              aria-label={session.micEnabled ? 'Mute mic' : 'Unmute mic'}
              className={cn(
                'flex h-14 w-14 items-center justify-center rounded-full transition-colors',
                session.micEnabled ? 'bg-white/15 text-white hover:bg-white/25' : 'bg-white text-black',
              )}
            >
              {session.micEnabled ? <Mic className="h-5 w-5" /> : <MicOff className="h-5 w-5" />}
            </button>
            <button
              onClick={session.leaveCall}
              aria-label="Leave call"
              className="flex h-16 w-16 items-center justify-center rounded-full bg-rose-500 text-white shadow-lg shadow-rose-500/30 hover:bg-rose-600"
            >
              <PhoneOff className="h-6 w-6" />
            </button>
            <button
              onClick={session.toggleCamera}
              aria-label={session.cameraEnabled ? 'Turn camera off' : 'Turn camera on'}
              disabled={session.kind !== 'video'}
              className={cn(
                'flex h-14 w-14 items-center justify-center rounded-full transition-colors disabled:opacity-30',
                session.cameraEnabled ? 'bg-white/15 text-white hover:bg-white/25' : 'bg-white text-black',
              )}
            >
              {session.cameraEnabled ? <Video className="h-5 w-5" /> : <VideoOff className="h-5 w-5" />}
            </button>
          </footer>
        )}
      </motion.div>
    </AnimatePresence>
  )
}

// banners (incoming ring + ongoing call) 

export function GroupCallRingBanner({ session }: { session: GroupCallSessionControls }) {
  // 1. Live incoming ring.
  if (session.ring && session.state === 'idle') {
    return (
      <motion.div
        initial={prefersReducedMotion() ? { opacity: 0 } : { opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0 }}
        transition={spring()}
        className="mx-3 mb-2 flex items-center gap-3 rounded-2xl border border-amber-500/30 bg-amber-500/10 px-4 py-3"
        role="alert"
      >
        <span className="relative flex h-10 w-10 items-center justify-center rounded-full bg-amber-500/20">
          <PhoneCall className="h-4.5 w-4.5 text-amber-700" aria-hidden />
          <span className="absolute inset-0 animate-ping rounded-full bg-amber-500/20" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-amber-900">
            {session.ring.title ? `${session.ring.title} · group call` : `Group call from ${session.ring.caller.name}`}
          </p>
          <p className="truncate text-xs text-amber-700/80">
            {session.ring.kind === 'video' ? 'Video call' : 'Voice call'} · started by {session.ring.caller.name}
          </p>
        </div>
        <button
          onClick={session.dismissRing}
          className="rounded-full px-3 py-2 text-xs text-amber-900/60 hover:bg-amber-500/10"
        >
          Ignore
        </button>
        <button
          onClick={session.joinCall}
          className="rounded-full bg-amber-600 px-4 py-2 text-xs font-semibold text-white shadow hover:bg-amber-700"
        >
          Join
        </button>
      </motion.div>
    )
  }

  // 2. Ongoing call discovered via probe/state.
  if (session.ongoingElsewhere && session.state === 'idle') {
    const names = session.ongoingMembers
      .slice(0, 3)
      .map((m) => m.name.split(' ')[0])
      .join(', ')
    return (
      <motion.div
        initial={prefersReducedMotion() ? { opacity: 0 } : { opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={spring()}
        className="mx-3 mb-2 flex items-center gap-3 rounded-2xl border border-amber-500/30 bg-amber-500/10 px-4 py-3"
        role="status"
      >
        <span className="flex h-9 w-9 items-center justify-center rounded-full bg-amber-500/20">
          <Users className="h-4 w-4 text-amber-700" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-amber-900">
            Ongoing group call · {session.ongoingMembers.length} in call
          </p>
          <p className="truncate text-xs text-amber-700/80">{names}</p>
        </div>
        <button
          onClick={session.ignoreOngoing}
          className="rounded-full px-3 py-1.5 text-xs text-amber-900/60 hover:bg-amber-500/10"
        >
          Ignore
        </button>
        <button
          onClick={session.joinOngoing}
          className="rounded-full bg-amber-600 px-4 py-2 text-xs font-semibold text-white shadow hover:bg-amber-700"
        >
          Join
        </button>
      </motion.div>
    )
  }

  return null
}
