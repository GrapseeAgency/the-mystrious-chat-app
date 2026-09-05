// ─────────────────────────────────────────────────────────────
// Pulse — 1:1 voice + video calls (R33-a, WhatsApp/Signal-style).
//
// Two exports wire the whole feature into chat-room:
//   1. useCallSession({...}) — the call state machine: WebRTC
//      (getUserMedia + RTCPeerConnection, Google STUN), signaling over the
//      shared socket channel (call:offer/answer/ice/reject/cancel/hangup),
//      ring timeout handling and the SINGLE-WRITER call log rule
//      (the CALLER's client POSTs every terminal row to /api/calls).
//   2. <CallOverlay session={...} /> — the full-screen glass call
//      experience: outgoing ring, incoming ring, active call (remote video
//      full-bleed or big avatar, live duration, mic/camera toggles) and a
//      brief ended summary card. Honest permission-error card when the mic
//      is denied; graceful voice fallback when no camera exists.
//
// Mounted once per DM room — incoming calls surface while that room is open
// (this wave: overlay lives at chat-room level, not app level).
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { Mic, MicOff, Phone, PhoneMissed, PhoneOff, Video, VideoOff, PhoneCall } from 'lucide-react'
import { toast } from 'sonner'
import type { CallCancelReason, CallKind } from '@/lib/call-types'
import { apiJson } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { spring, prefersReducedMotion } from '@/lib/motion'
import { usePulseCallChannel } from '@/components/chat/pulse-realtime-provider'
import { UserAvatar } from '@/components/chat/user-avatar'
import { cn } from '@/lib/utils'

// ── contracts ────────────────────────────────────────────────

export type CallUiState = 'idle' | 'calling' | 'incoming' | 'active' | 'ended'

export interface CallPeer {
  id: string
  name: string
  color: string
  avatar: string | null
}

export interface CallSessionControls {
  state: CallUiState
  kind: CallKind
  direction: 'outgoing' | 'incoming' | null
  /** Who the overlay renders (DM peer for outgoing, caller for incoming). */
  peer: CallPeer | null
  /** Non-null = honest glass error card replaces the call UI. */
  error: string | null
  /** Ended-card text ('No answer', 'Declined', 'Call ended · 0:42', …). */
  summary: string | null
  durationSec: number
  micEnabled: boolean
  cameraEnabled: boolean
  remoteStream: MediaStream | null
  localStream: MediaStream | null
  startCall: (kind: CallKind) => void
  acceptCall: () => void
  declineCall: () => void
  endCall: () => void
  /** Close the permission/error card — logs the attempt as 'missed'. */
  dismissError: () => void
  toggleMic: () => void
  toggleCamera: () => void
}

export interface CallSessionOptions {
  meId: string
  meName: string
  meColor?: string
  meAvatar?: string | null
  conversationId: string
  /** DM peer (null = group/no peer → outgoing calls are a no-op). */
  peer: CallPeer | null
}

interface IncomingOfferMeta {
  callId: string
  conversationId: string
  from: string
  kind: CallKind
  sdp: string
  peer: CallPeer
}

// ── helpers ──────────────────────────────────────────────────

const STUN_SERVERS: RTCConfiguration = {
  iceServers: [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
  ],
}

/** 65 → "1:05", 3675 → "1:01:15". */
export function formatCallDuration(totalSec: number): string {
  const sec = Math.max(0, Math.floor(totalSec))
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const pad = (v: number) => v.toString().padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

const CANCEL_SUMMARY: Record<CallCancelReason, string> = {
  timeout: 'No answer',
  cancel: 'Call canceled',
  busy: 'Peer is busy',
  offline: 'Peer is offline',
}

// ── the hook ─────────────────────────────────────────────────

export function useCallSession(options: CallSessionOptions): CallSessionControls {
  const { meId, meName, meColor = 'emerald', meAvatar = null, conversationId, peer } = options
  const { subscribeCallEvents, emitCallEvent, callChannelConnected } = usePulseCallChannel()

  const [state, setState] = useState<CallUiState>('idle')
  const [kind, setKind] = useState<CallKind>('voice')
  const [direction, setDirection] = useState<'outgoing' | 'incoming' | null>(null)
  const [activePeer, setActivePeer] = useState<CallPeer | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [summary, setSummary] = useState<string | null>(null)
  const [durationSec, setDurationSec] = useState(0)
  const [micEnabled, setMicEnabled] = useState(true)
  const [cameraEnabled, setCameraEnabled] = useState(true)
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null)
  const [localStream, setLocalStream] = useState<MediaStream | null>(null)

  // Mutable mirrors so the (stable) socket listener always sees fresh values.
  // (Written inside callbacks/effects only — never during render.)
  const stateRef = useRef<CallUiState>('idle')
  const callIdRef = useRef<string | null>(null)
  const kindRef = useRef<CallKind>('voice')
  const directionRef = useRef<'outgoing' | 'incoming' | null>(null)
  const answeredRef = useRef(false)
  const connectedAtRef = useRef(0)
  const pcRef = useRef<RTCPeerConnection | null>(null)
  const localStreamRef = useRef<MediaStream | null>(null)
  const pendingIceRef = useRef<RTCIceCandidateInit[]>([])
  const offerRef = useRef<IncomingOfferMeta | null>(null)
  const peerRef = useRef<CallPeer | null>(peer)
  const optionsRef = useRef({ meId, meName, meColor, meAvatar, conversationId })

  // Keep option/peer mirrors in sync after each commit.
  useEffect(() => {
    peerRef.current = peer
  }, [peer])
  useEffect(() => {
    optionsRef.current = { meId, meName, meColor, meAvatar, conversationId }
  }, [meId, meName, meColor, meAvatar, conversationId])
  useEffect(() => {
    stateRef.current = state
  }, [state])

  /** Fire-and-forget CallLog row — the CALLER is the single writer. */
  const writeLog = useCallback(async (status: 'completed' | 'missed' | 'declined', duration = 0, callKind?: CallKind) => {
    const opts = optionsRef.current
    const target = peerRef.current
    if (!target || target.id === opts.meId) return
    try {
      await apiJson('/api/calls', {
        method: 'POST',
        body: JSON.stringify({
          userId: opts.meId,
          conversationId: opts.conversationId,
          peerId: target.id,
          kind: callKind ?? kindRef.current,
          status,
          durationSec: Math.max(0, Math.round(duration)),
        }),
      })
    } catch {
      toast.error('Could not save this call to history')
    }
  }, [])

  /** Stop every track, close the peer connection, clear queued ICE. */
  const teardownMedia = useCallback(() => {
    if (pcRef.current) {
      pcRef.current.onicecandidate = null
      pcRef.current.ontrack = null
      pcRef.current.onconnectionstatechange = null
      try {
        pcRef.current.close()
      } catch {
        // already closed
      }
      pcRef.current = null
    }
    for (const track of localStreamRef.current?.getTracks() ?? []) track.stop()
    localStreamRef.current = null
    pendingIceRef.current = []
    offerRef.current = null
    answeredRef.current = false
    connectedAtRef.current = 0
    setLocalStream(null)
    setRemoteStream(null)
    setDurationSec(0)
    setMicEnabled(true)
    setCameraEnabled(true)
  }, [])

  const showEnded = useCallback((text: string) => {
    setSummary(text)
    setState('ended')
    stateRef.current = 'ended'
  }, [])

  const resetIdle = useCallback(() => {
    teardownMedia()
    callIdRef.current = null
    directionRef.current = null
    setState('idle')
    stateRef.current = 'idle'
    setSummary(null)
    setError(null)
    setActivePeer(null)
    setDirection(null)
  }, [teardownMedia])

  const createPeerConnection = useCallback((): RTCPeerConnection => {
    const pc = new RTCPeerConnection(STUN_SERVERS)
    pc.onicecandidate = (event) => {
      if (!event.candidate || !callIdRef.current) return
      const me = optionsRef.current.meId
      const target = directionRef.current === 'outgoing' ? peerRef.current?.id : offerRef.current?.from
      if (!target) return
      emitCallEvent('call:ice', {
        callId: callIdRef.current,
        conversationId: optionsRef.current.conversationId,
        from: me,
        to: target,
        kind: kindRef.current,
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
      })
    }
    pc.ontrack = (event) => {
      const [stream] = event.streams
      if (stream) setRemoteStream(stream)
    }
    pc.onconnectionstatechange = () => {
      // Quality hint chip derives from connectionState in the overlay.
    }
    // Keep the mic/camera toggles honest after renegotiations.
    const stream = localStreamRef.current
    if (stream) for (const track of stream.getTracks()) pc.addTrack(track, stream)
    pcRef.current = pc
    return pc
  }, [emitCallEvent])

  /**
   * getUserMedia with honest fallbacks: no camera (or camera constraint
   * rejected) → retry as a voice call with a toast; mic denied → throw so the
   * caller can show the glass error card.
   */
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
        // No usable camera → fall back to voice, never fake it.
        toast('Camera unavailable — starting a voice call')
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

  const startCall = useCallback(
    (wanted: CallKind) => {
      if (stateRef.current !== 'idle') return
      const target = peerRef.current
      if (!target || target.id === optionsRef.current.meId) return
      if (!callChannelConnected) {
        toast.error('You are offline — calls need a connection')
        return
      }

      const callId = `call-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
      callIdRef.current = callId
      directionRef.current = 'outgoing'
      kindRef.current = wanted
      setKind(wanted)
      setDirection('outgoing')
      setActivePeer(target)
      setSummary(null)
      setError(null)
      setState('calling')
      stateRef.current = 'calling'

      void (async () => {
        try {
          const actualKind = await acquireMedia(wanted)
          if (callIdRef.current !== callId) return // canceled while prompting
          const pc = createPeerConnection()
          const offer = await pc.createOffer({
            offerToReceiveAudio: true,
            offerToReceiveVideo: actualKind === 'video',
          })
          await pc.setLocalDescription(offer)
          emitCallEvent('call:offer', {
            callId,
            conversationId: optionsRef.current.conversationId,
            from: optionsRef.current.meId,
            to: target.id,
            kind: actualKind,
            sdp: offer.sdp ?? '',
            callerName: optionsRef.current.meName,
            callerColor: optionsRef.current.meColor,
            callerAvatar: optionsRef.current.meAvatar,
          })
        } catch (err) {
          // Mic denied (or unavailable) — honest error card, no fake UI.
          console.error('[call] getUserMedia failed:', err instanceof Error ? err.message : err)
          setError('Microphone access is needed for calls. Check the browser permissions and try again.')
        }
      })()
    },
    [acquireMedia, callChannelConnected, createPeerConnection, emitCallEvent],
  )

  const acceptCall = useCallback(() => {
    const offer = offerRef.current
    if (!offer || stateRef.current !== 'incoming') return
    haptic(16)

    void (async () => {
      try {
        const actualKind = await acquireMedia(offer.kind)
        const pc = createPeerConnection()
        await pc.setRemoteDescription({ type: 'offer', sdp: offer.sdp })
        const answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        emitCallEvent('call:answer', {
          callId: offer.callId,
          conversationId: offer.conversationId,
          from: optionsRef.current.meId,
          to: offer.from,
          kind: actualKind,
          sdp: answer.sdp ?? '',
        })
        // Flush candidates that arrived while we were ringing.
        for (const candidate of pendingIceRef.current.splice(0)) {
          try {
            await pc.addIceCandidate(candidate)
          } catch {
            // stale candidate — ICE will recover
          }
        }
        answeredRef.current = true
        connectedAtRef.current = Date.now()
        setDurationSec(0)
        setState('active')
        stateRef.current = 'active'
        haptic(10)
      } catch (err) {
        console.error('[call] accept failed:', err instanceof Error ? err.message : err)
        // Callee could not join → reject politely; the caller logs the row.
        emitCallEvent('call:reject', {
          callId: offer.callId,
          conversationId: offer.conversationId,
          from: optionsRef.current.meId,
          to: offer.from,
          kind: offer.kind,
        })
        setError('Microphone access is needed for calls. Check the browser permissions and try again.')
        offerRef.current = offer
        callIdRef.current = offer.callId
        directionRef.current = 'incoming'
      }
    })()
  }, [acquireMedia, createPeerConnection, emitCallEvent])

  const declineCall = useCallback(() => {
    const offer = offerRef.current
    if (!offer || stateRef.current !== 'incoming') return
    haptic(12)
    // Callee NEVER writes the row — the caller (who got call:reject) logs 'declined'.
    emitCallEvent('call:reject', {
      callId: offer.callId,
      conversationId: offer.conversationId,
      from: optionsRef.current.meId,
      to: offer.from,
      kind: offer.kind,
    })
    teardownMedia()
    callIdRef.current = null
    showEnded('Declined')
  }, [emitCallEvent, showEnded, teardownMedia])

  const endCall = useCallback(() => {
    const callId = callIdRef.current
    const current = stateRef.current
    if (current === 'idle') return
    haptic(14)

    if (current === 'incoming') {
      declineCall()
      return
    }

    if (current === 'calling' && directionRef.current === 'outgoing') {
      if (callId) {
        emitCallEvent('call:cancel', {
          callId,
          conversationId: optionsRef.current.conversationId,
          from: optionsRef.current.meId,
          to: peerRef.current?.id ?? '',
          kind: kindRef.current,
        })
      }
      // Caller cancels an unanswered ring → 'missed' (single-writer rule).
      void writeLog('missed', 0)
      showEnded('Call canceled')
      return
    }

    if (current === 'active') {
      const elapsed = connectedAtRef.current > 0 ? (Date.now() - connectedAtRef.current) / 1000 : 0
      if (callId) {
        emitCallEvent('call:hangup', {
          callId,
          conversationId: optionsRef.current.conversationId,
          from: optionsRef.current.meId,
          to:
            directionRef.current === 'outgoing'
              ? peerRef.current?.id ?? ''
              : offerRef.current?.from ?? '',
          kind: kindRef.current,
          durationSec: Math.round(elapsed),
        })
      }
      if (directionRef.current === 'outgoing') {
        void writeLog('completed', elapsed)
      }
      showEnded(elapsed >= 1 ? `Call ended · ${formatCallDuration(elapsed)}` : 'Call ended')
      return
    }
  }, [declineCall, emitCallEvent, showEnded, writeLog])

  const dismissError = useCallback(() => {
    // A failed attempt (e.g. mic denied) still counts as an unanswered call.
    if (directionRef.current === 'outgoing') void writeLog('missed', 0)
    resetIdle()
  }, [resetIdle, writeLog])

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

  // ── signaling listener (stable; everything via refs) ────────
  const handleCallEvent = useCallback(
    (event: string, raw: unknown) => {
      if (raw === null || typeof raw !== 'object') return
      const payload = raw as Record<string, unknown>
      const callId = typeof payload.callId === 'string' ? payload.callId : ''
      const me = optionsRef.current.meId

      if (event === 'call:offer') {
        const from = typeof payload.from === 'string' ? payload.from : ''
        if (!callId || from === me) return
        // Already busy → reject immediately (the caller logs 'declined').
        if (stateRef.current !== 'idle') {
          emitCallEvent('call:reject', {
            callId,
            conversationId: optionsRef.current.conversationId,
            from: me,
            to: from,
            kind: payload.kind === 'video' ? 'video' : 'voice',
          })
          return
        }
        const kind = payload.kind === 'video' ? 'video' : 'voice'
        offerRef.current = {
          callId,
          conversationId: typeof payload.conversationId === 'string' ? payload.conversationId : '',
          from,
          kind,
          sdp: typeof payload.sdp === 'string' ? payload.sdp : '',
          peer: {
            id: from,
            name: typeof payload.callerName === 'string' && payload.callerName ? payload.callerName : 'Someone',
            color: typeof payload.callerColor === 'string' && payload.callerColor ? payload.callerColor : 'emerald',
            avatar: typeof payload.callerAvatar === 'string' ? payload.callerAvatar : null,
          },
        }
        callIdRef.current = callId
        directionRef.current = 'incoming'
        kindRef.current = kind
        setActivePeer(offerRef.current.peer)
        setKind(kind)
        setDirection('incoming')
        setSummary(null)
        setError(null)
        setState('incoming')
        stateRef.current = 'incoming'
        haptic(30)
        return
      }

      // Everything below targets the call we are part of.
      if (!callId || callId !== callIdRef.current) return

      if (event === 'call:answer' && directionRef.current === 'outgoing') {
        const sdp = typeof payload.sdp === 'string' ? payload.sdp : ''
        if (!sdp || !pcRef.current) return
        void (async () => {
          try {
            await pcRef.current?.setRemoteDescription({ type: 'answer', sdp })
            for (const candidate of pendingIceRef.current.splice(0)) {
              try {
                await pcRef.current?.addIceCandidate(candidate)
              } catch {
                // stale candidate — ICE recovers on its own
              }
            }
            answeredRef.current = true
            connectedAtRef.current = Date.now()
            setDurationSec(0)
            setState('active')
            stateRef.current = 'active'
            haptic(10)
          } catch (err) {
            console.error('[call] answer apply failed:', err instanceof Error ? err.message : err)
            showEnded('Call failed')
          }
        })()
        return
      }

      if (event === 'call:ice') {
        const candidateString = typeof payload.candidate === 'string' ? payload.candidate : ''
        if (!candidateString) return
        const candidate: RTCIceCandidateInit = {
          candidate: candidateString,
          sdpMid: typeof payload.sdpMid === 'string' ? payload.sdpMid : null,
          sdpMLineIndex:
            typeof payload.sdpMLineIndex === 'number' && Number.isFinite(payload.sdpMLineIndex)
              ? Math.floor(payload.sdpMLineIndex)
              : null,
        }
        if (pcRef.current && pcRef.current.remoteDescription) {
          void pcRef.current.addIceCandidate(candidate).catch(() => {
            // stale candidate — ignore
          })
        } else {
          pendingIceRef.current.push(candidate)
        }
        return
      }

      if (event === 'call:reject' && directionRef.current === 'outgoing') {
        // Caller got declined → logs 'declined' (single-writer rule).
        teardownMedia()
        callIdRef.current = null
        void writeLog('declined', 0)
        showEnded('Declined')
        return
      }

      if (event === 'call:cancel') {
        const reason: CallCancelReason =
          payload.reason === 'timeout' || payload.reason === 'busy' || payload.reason === 'offline'
            ? payload.reason
            : 'cancel'
        const wasOutgoing = directionRef.current === 'outgoing'
        const wasAnswered = answeredRef.current
        teardownMedia()
        callIdRef.current = null
        if (wasOutgoing && !wasAnswered) {
          // Caller's ring died (timeout / self-cancel) → 'missed'.
          void writeLog('missed', 0)
          showEnded(CANCEL_SUMMARY[reason])
        } else if (!wasOutgoing && !wasAnswered) {
          showEnded('Missed call')
        } else if (wasAnswered) {
          showEnded('Call ended')
        } else {
          showEnded(CANCEL_SUMMARY[reason])
        }
        return
      }

      if (event === 'call:hangup') {
        const elapsed = connectedAtRef.current > 0 ? (Date.now() - connectedAtRef.current) / 1000 : 0
        const wasOutgoing = directionRef.current === 'outgoing'
        teardownMedia()
        callIdRef.current = null
        if (wasOutgoing) void writeLog('completed', elapsed)
        showEnded(elapsed >= 1 ? `Call ended · ${formatCallDuration(elapsed)}` : 'Call ended')
        return
      }
    },
    [emitCallEvent, showEnded, teardownMedia, writeLog],
  )

  useEffect(() => subscribeCallEvents(handleCallEvent), [subscribeCallEvents, handleCallEvent])

  // Live duration ticker while connected.
  useEffect(() => {
    if (state !== 'active') return undefined
    const interval = setInterval(() => {
      if (connectedAtRef.current > 0) {
        setDurationSec(Math.floor((Date.now() - connectedAtRef.current) / 1000))
      }
    }, 1000)
    return () => clearInterval(interval)
  }, [state])

  // Ended card auto-dismisses after ~2.5s.
  useEffect(() => {
    if (state !== 'ended') return undefined
    const timer = setTimeout(() => resetIdle(), 2500)
    return () => clearTimeout(timer)
  }, [state, resetIdle])

  return {
    state,
    kind,
    direction,
    peer: activePeer,
    error,
    summary,
    durationSec,
    micEnabled,
    cameraEnabled,
    remoteStream,
    localStream,
    startCall,
    acceptCall,
    declineCall,
    endCall,
    dismissError,
    toggleMic,
    toggleCamera,
  }
}

// ── the overlay ──────────────────────────────────────────────

export interface CallOverlayProps {
  session: CallSessionControls
}

/** Pulsing halo rings around the avatar (disabled under reduced motion). */
function AvatarHalo({ reduced, children }: { reduced: boolean; children: React.ReactNode }) {
  return (
    <span className="relative inline-flex size-32 items-center justify-center">
      {reduced ? null : (
        <>
          <motion.span
            aria-hidden
            className="absolute inset-0 rounded-full border border-emerald-300/40"
            animate={{ scale: [1, 1.35], opacity: [0.7, 0] }}
            transition={{ duration: 1.8, repeat: Infinity, ease: 'easeOut' }}
          />
          <motion.span
            aria-hidden
            className="absolute inset-0 rounded-full border border-emerald-300/30"
            animate={{ scale: [1, 1.55], opacity: [0.5, 0] }}
            transition={{ duration: 1.8, repeat: Infinity, ease: 'easeOut', delay: 0.6 }}
          />
        </>
      )}
      {children}
    </span>
  )
}

/** Round glass action button used across all call states. */
function CallButton({
  label,
  onClick,
  tone,
  size = 'md',
  children,
}: {
  label: string
  onClick: () => void
  tone: 'neutral' | 'danger' | 'accept'
  size?: 'md' | 'lg'
  children: React.ReactNode
}) {
  return (
    <motion.button
      type="button"
      aria-label={label}
      onClick={onClick}
      whileTap={{ scale: 0.9 }}
      transition={spring.snappy}
      className={cn(
        'flex items-center justify-center rounded-full border outline-none',
        size === 'lg' ? 'size-[4.5rem]' : 'size-14',
        tone === 'danger' &&
          'border-rose-300/30 bg-rose-500/90 text-white shadow-[0_10px_30px_-10px_rgba(244,63,94,0.8)] hover:bg-rose-500',
        tone === 'accept' &&
          'border-emerald-300/30 bg-emerald-500/90 text-white shadow-[0_10px_30px_-10px_rgba(16,185,129,0.8)] hover:bg-emerald-500',
        tone === 'neutral' &&
          'glass-pill text-zinc-100 hover:bg-white/10',
      )}
    >
      {children}
    </motion.button>
  )
}

export function CallOverlay({ session }: CallOverlayProps) {
  const {
    state,
    kind,
    direction,
    peer,
    error,
    summary,
    durationSec,
    micEnabled,
    cameraEnabled,
    remoteStream,
    localStream,
    acceptCall,
    declineCall,
    endCall,
    dismissError,
    toggleMic,
    toggleCamera,
  } = session

  const [reducedMotion] = useState(() => prefersReducedMotion())
  const remoteVideoRef = useRef<HTMLVideoElement | null>(null)
  const localVideoRef = useRef<HTMLVideoElement | null>(null)
  const remoteAudioRef = useRef<HTMLAudioElement | null>(null)

  // Attach streams as they arrive (elements mount when state changes).
  useEffect(() => {
    if (remoteVideoRef.current && remoteStream) remoteVideoRef.current.srcObject = remoteStream
    if (remoteAudioRef.current && remoteStream) remoteAudioRef.current.srcObject = remoteStream
  }, [remoteStream, state, kind])
  useEffect(() => {
    if (localVideoRef.current && localStream) localVideoRef.current.srcObject = localStream
  }, [localStream, state, kind])

  const visible = state !== 'idle' && peer !== null
  const isVideo = kind === 'video'
  const controlsDisabled = error !== null

  return (
    <AnimatePresence>
      {visible ? (
        <motion.div
          key="call-overlay"
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 24 }}
          transition={spring.soft}
          className="fixed inset-0 z-[80] flex flex-col overflow-hidden bg-zinc-950/95 text-white backdrop-blur-2xl"
          role="dialog"
          aria-label={
            state === 'incoming'
              ? `Incoming ${kind} call from ${peer.name}`
              : state === 'active'
                ? `Active ${kind} call with ${peer.name}`
                : `${kind} call with ${peer.name}`
          }
        >
          {/* ambient emerald wash — matches the app's aurora language */}
          <div
            aria-hidden
            className="pointer-events-none absolute inset-0"
            style={{
              background:
                'radial-gradient(120% 70% at 50% -10%, rgba(16,185,129,0.22) 0%, rgba(16,185,129,0.06) 38%, rgba(0,0,0,0) 65%), radial-gradient(90% 55% at 50% 110%, rgba(20,184,166,0.16) 0%, rgba(0,0,0,0) 60%)',
            }}
          />

          {/* remote media (active video calls: full-bleed) */}
          {state === 'active' && isVideo ? (
            <video
              ref={remoteVideoRef}
              autoPlay
              playsInline
              className="absolute inset-0 size-full object-cover"
            />
          ) : null}
          {/* remote audio for voice calls (hidden element, autoplay) */}
          <audio ref={remoteAudioRef} autoPlay playsInline className="hidden" />

          {/* local camera PiP (active video calls) */}
          {state === 'active' && isVideo && localStream ? (
            <motion.video
              ref={localVideoRef}
              autoPlay
              playsInline
              muted
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={spring.soft}
              className="absolute bottom-28 right-4 z-10 h-36 w-24 rounded-2xl border border-white/15 object-cover shadow-2xl"
              style={{ transform: 'scaleX(-1)' }}
            />
          ) : null}

          {/* ── INCOMING / CALLING / ACTIVE content ── */}
          <div className="relative z-20 flex min-h-0 flex-1 flex-col items-center justify-center px-6 pb-40 pt-[max(2rem,env(safe-area-inset-top))]">
            {state === 'active' && isVideo ? (
              <div className="absolute left-0 right-0 top-[max(1rem,env(safe-area-inset-top))] flex flex-col items-center gap-0.5 px-4 text-center">
                <p className="text-base font-semibold tracking-tight text-white drop-shadow">{peer.name}</p>
                <p className="text-xs font-medium tabular-nums text-emerald-300">
                  {formatCallDuration(durationSec)}
                </p>
              </div>
            ) : (
              <>
                <AvatarHalo reduced={reducedMotion}>
                  <UserAvatar
                    name={peer.name}
                    color={peer.color}
                    avatar={peer.avatar}
                    size={112}
                  />
                </AvatarHalo>
                <motion.p
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={spring.soft}
                  className="mt-6 text-2xl font-bold tracking-tight text-white"
                >
                  {peer.name}
                </motion.p>
                <motion.p
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  transition={{ ...spring.soft, delay: 0.05 }}
                  className={cn(
                    'mt-1.5 text-sm font-medium',
                    state === 'incoming' ? 'text-emerald-300' : 'text-zinc-300',
                  )}
                >
                  {state === 'incoming'
                    ? `Incoming ${kind} call`
                    : state === 'calling'
                      ? isVideo
                        ? 'Video call…'
                        : 'Calling…'
                      : formatCallDuration(durationSec)}
                </motion.p>
              </>
            )}

            {/* honest permission/error card */}
            {error ? (
              <motion.div
                initial={{ opacity: 0, scale: 0.96 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={spring.soft}
                className="glass-deep glass-sheen mt-8 w-full max-w-xs rounded-3xl p-5 text-center"
                role="alert"
              >
                <span className="mx-auto flex size-10 items-center justify-center rounded-full bg-rose-500/15">
                  <MicOff className="size-5 text-rose-400" aria-hidden />
                </span>
                <p className="mt-3 text-sm font-semibold leading-snug text-zinc-800 dark:text-zinc-100">
                  {error}
                </p>
                <button
                  type="button"
                  onClick={dismissError}
                  className="mt-4 h-10 w-full rounded-full bg-emerald-500 text-sm font-bold text-white outline-none transition-transform hover:bg-emerald-500/90 active:scale-[0.98]"
                >
                  Close
                </button>
              </motion.div>
            ) : null}

            {/* ended summary card */}
            {state === 'ended' && summary ? (
              <motion.div
                initial={{ opacity: 0, scale: 0.94, y: 10 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.96 }}
                transition={spring.soft}
                className="glass-deep glass-sheen mt-8 flex items-center gap-3 rounded-2xl px-5 py-3.5"
                role="status"
              >
                <span
                  className={cn(
                    'flex size-8 shrink-0 items-center justify-center rounded-full',
                    summary.startsWith('Call ended') ? 'bg-emerald-500/15' : 'bg-rose-500/15',
                  )}
                >
                  {summary.startsWith('Call ended') ? (
                    <PhoneCall className="size-4 text-emerald-500 dark:text-emerald-400" aria-hidden />
                  ) : (
                    <PhoneMissed className="size-4 text-rose-500 dark:text-rose-400" aria-hidden />
                  )}
                </span>
                <p className="text-sm font-semibold text-zinc-800 dark:text-zinc-100">{summary}</p>
              </motion.div>
            ) : null}
          </div>

          {/* ── controls ── */}
          <div className="relative z-20 px-6 pb-[max(1.75rem,env(safe-area-inset-bottom))]">
            {state === 'incoming' && !controlsDisabled ? (
              <div className="mx-auto flex w-full max-w-xs items-center justify-around">
                <div className="flex flex-col items-center gap-2">
                  <CallButton label="Decline call" onClick={declineCall} tone="danger" size="lg">
                    <PhoneOff className="size-7" aria-hidden />
                  </CallButton>
                  <span className="text-xs font-medium text-zinc-400">Decline</span>
                </div>
                <div className="flex flex-col items-center gap-2">
                  <CallButton
                    label={`Accept ${kind} call`}
                    onClick={acceptCall}
                    tone="accept"
                    size="lg"
                  >
                    {isVideo ? (
                      <Video className="size-7" aria-hidden />
                    ) : (
                      <Phone className="size-7" aria-hidden />
                    )}
                  </CallButton>
                  <span className="text-xs font-medium text-zinc-400">Accept</span>
                </div>
              </div>
            ) : null}

            {state === 'calling' && !controlsDisabled ? (
              <div className="mx-auto flex w-full max-w-xs items-center justify-around">
                <div className="flex flex-col items-center gap-2">
                  <CallButton label="Cancel call" onClick={endCall} tone="danger" size="lg">
                    <PhoneOff className="size-7" aria-hidden />
                  </CallButton>
                  <span className="text-xs font-medium text-zinc-400">Cancel</span>
                </div>
              </div>
            ) : null}

            {state === 'active' && !controlsDisabled ? (
              <div className="mx-auto flex w-full max-w-sm items-center justify-around">
                <div className="flex flex-col items-center gap-2">
                  <CallButton
                    label={micEnabled ? 'Mute microphone' : 'Unmute microphone'}
                    onClick={toggleMic}
                    tone="neutral"
                  >
                    {micEnabled ? (
                      <Mic className="size-6" aria-hidden />
                    ) : (
                      <MicOff className="size-6" aria-hidden />
                    )}
                  </CallButton>
                  <span className="text-xs font-medium text-zinc-400">{micEnabled ? 'Mute' : 'Muted'}</span>
                </div>
                {isVideo ? (
                  <div className="flex flex-col items-center gap-2">
                    <CallButton
                      label={cameraEnabled ? 'Turn camera off' : 'Turn camera on'}
                      onClick={toggleCamera}
                      tone="neutral"
                    >
                      {cameraEnabled ? (
                        <Video className="size-6" aria-hidden />
                      ) : (
                        <VideoOff className="size-6" aria-hidden />
                      )}
                    </CallButton>
                    <span className="text-xs font-medium text-zinc-400">
                      {cameraEnabled ? 'Camera' : 'Camera off'}
                    </span>
                  </div>
                ) : null}
                <div className="flex flex-col items-center gap-2">
                  <CallButton label="End call" onClick={endCall} tone="danger" size="lg">
                    <PhoneOff className="size-7" aria-hidden />
                  </CallButton>
                  <span className="text-xs font-medium text-zinc-400">End</span>
                </div>
              </div>
            ) : null}
          </div>

          {/* sr-only live region for the timer */}
          <span className="sr-only" role="timer" aria-live="off">
            {state === 'active' ? formatCallDuration(durationSec) : ''}
          </span>
          {/* keep direction/kind referenced for a11y labeling completeness */}
          <span hidden aria-hidden>{direction ?? ''}</span>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

// ── overlay end ──
