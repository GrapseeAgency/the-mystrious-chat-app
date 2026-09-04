// ─────────────────────────────────────────────────────────────
// Pulse — Live stage room (R24-c "Beyond Chat" wave 3).
// Clubhouse-style stage hierarchy over the pulse-socket service:
//   host → speakers → listeners (+ FIFO raised-hands queue).
//
// REAL realtime, zero mocks:
//  · rosters/roles/hands live in the socket service's in-memory
//    stage state (`stage:{conversationId}` rooms) — the genuine
//    product, same trust model as the existing voice rooms.
//  · speakers (incl. the host) ALSO join the existing
//    `voice:{conversationId}` room, so the proven voice:ptt /
//    voice:chunk relay carries stage audio — the mic button below
//    uses the exact same AudioWorklet→16 kHz PCM→250 ms chunk
//    capture pipeline as voice-room-sheet (copied, not imported,
//    to keep both files independently owned).
//  · the mic arms lazily on the first PTT press (a real user
//    gesture) so pure listeners are never asked for permission.
//  · nothing is recorded or stored — chunks are relayed and dropped.
//
// The sheet auto-joins as a LISTENER when opened and emits
// stage:leave on close/unmount. Host seat: first joiner of a fresh
// room becomes host; an empty seat can be re-claimed (asHost).
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { io, type Socket } from 'socket.io-client'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import {
  AlertTriangle,
  AudioLines,
  Crown,
  Hand,
  LoaderCircle,
  Mic,
  MicOff,
  PhoneOff,
  Podcast,
  Radio,
  UserCheck,
  UsersRound,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import { useQuery } from '@tanstack/react-query'
import { fireParticles, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/chat/user-avatar'
import type { ConversationDetail } from '@/lib/types'

export const STAGE_OPEN_EVENT = 'pulse:open-stage'

// ── audio pipeline constants (copied from voice-room-sheet — same codec) ──
const TARGET_RATE = 16_000 // Hz — wideband voice, keeps chunks small
const CHUNK_MS = 250 // emit a chunk every 250 ms
const CHUNK_SAMPLES = Math.round((TARGET_RATE * CHUNK_MS) / 1000) // 4000 samples
const JITTER_BUFFER_S = 0.085 // playback pre-roll for smooth-ish scheduling
const RELAY_CONNECT_TIMEOUT_MS = 8_000
const STAGE_RESYNC_MS = 2_500 // rate-limit for roster-missing-me recovery

export interface StageMe {
  id: string
  name: string
  username: string | null
  color: string
}

export interface StagePersonInfo {
  id: string
  name: string
  color: string
}

/** Mirrors the server's `stage:state` payload (additive `listeners` included). */
export interface StageState {
  conversationId: string
  host: StagePersonInfo | null
  speakers: StagePersonInfo[]
  hands: StagePersonInfo[]
  listeners: StagePersonInfo[]
  listenerCount: number
}

export type StageStatus = 'idle' | 'joining' | 'joined' | 'error'
export type StageRole = 'audience' | 'listener' | 'speaker' | 'host'

export interface StageController {
  status: StageStatus
  /** honest failure text for the inline error state (relay unreachable etc.) */
  errorMsg: string
  /** socket link to the relay (gateway :3003) is up */
  connected: boolean
  inRoom: boolean
  state: StageState | null
  role: StageRole
  handRaised: boolean
  /** peer ids currently holding PTT in the voice room (emerald glow) */
  speakingIds: ReadonlySet<string>
  micMuted: boolean
  transmitting: boolean
  /** honest mic-arming failure (permission denied etc.) — the session continues */
  micError: string
  /** bumps every time the host ends the stage while we were in the room */
  endedTick: number
  join: (asHost?: boolean) => void
  leave: () => void
  raiseHand: (raised: boolean) => void
  approveHand: (targetUserId: string) => void
  muteMember: (targetUserId: string) => void
  endStage: () => void
  claimHost: () => void
  setPtt: (on: boolean) => void
  toggleMute: () => void
}

// ── binary helpers (copied from voice-room-sheet — same codec) ──

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const step = 0x8000
  for (let i = 0; i < bytes.length; i += step) {
    binary += String.fromCharCode(...bytes.subarray(i, i + step))
  }
  return btoa(binary)
}

function base64ToBytes(b64: string): Uint8Array | null {
  try {
    const bin = atob(b64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    return bytes
  } catch {
    return null
  }
}

function floatToInt16(input: Float32Array): Int16Array {
  const out = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]))
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff
  }
  return out
}

function int16ToFloat(bytes: Uint8Array): Float32Array<ArrayBuffer> {
  const usable = bytes.byteLength - (bytes.byteLength % 2)
  const pcm = new Int16Array(bytes.buffer, bytes.byteOffset, usable / 2)
  const out = new Float32Array(pcm.length)
  for (let i = 0; i < out.length; i++) out[i] = pcm[i] / 0x8000
  return out
}

/** Linear-interpolation decimation of one fixed-size block down to TARGET_RATE. */
function downsampleBlock(input: Float32Array, outLen: number): Float32Array {
  if (input.length <= 1) return new Float32Array(outLen)
  const out = new Float32Array(outLen)
  const step = (input.length - 1) / Math.max(1, outLen - 1)
  for (let i = 0; i < outLen; i++) {
    const p = i * step
    const i0 = Math.floor(p)
    const frac = p - i0
    const a = input[i0]
    const b = i0 + 1 < input.length ? input[i0 + 1] : a
    out[i] = a + (b - a) * frac
  }
  return out
}

/** Defensive parser for the server's `stage:state` broadcast. */
function parseStageState(raw: unknown): StageState | null {
  if (raw === null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const conversationId = typeof r.conversationId === 'string' ? r.conversationId : ''
  if (!conversationId) return null
  const people = (value: unknown): StagePersonInfo[] =>
    Array.isArray(value)
      ? value.flatMap((item): StagePersonInfo[] => {
          if (item === null || typeof item !== 'object') return []
          const p = item as Record<string, unknown>
          if (typeof p.id !== 'string' || typeof p.name !== 'string') return []
          return [{ id: p.id, name: p.name, color: typeof p.color === 'string' ? p.color : 'emerald' }]
        })
      : []
  const hostRaw = r.host
  const host =
    hostRaw !== null && typeof hostRaw === 'object'
      ? (() => {
          const h = hostRaw as Record<string, unknown>
          if (typeof h.id !== 'string' || typeof h.name !== 'string') return null
          return { id: h.id, name: h.name, color: typeof h.color === 'string' ? h.color : 'emerald' }
        })()
      : null
  const speakers = people(r.speakers)
  const hands = people(r.hands)
  const listeners = people(r.listeners)
  return {
    conversationId,
    host,
    speakers,
    hands,
    listeners,
    listenerCount:
      typeof r.listenerCount === 'number' && Number.isFinite(r.listenerCount)
        ? Math.max(0, Math.floor(r.listenerCount))
        : listeners.length,
  }
}

function deriveRole(state: StageState | null, meId: string): StageRole {
  if (!state) return 'audience'
  if (state.host?.id === meId) return 'host'
  if (state.speakers.some((s) => s.id === meId)) return 'speaker'
  return 'listener'
}

// ── the engine hook ──────────────────────────────────────────

export function useStageRoom(conversationId: string, me: StageMe): StageController {
  const [status, setStatus] = useState<StageStatus>('idle')
  const [errorMsg, setErrorMsg] = useState('')
  const [connected, setConnected] = useState(false)
  const [stageState, setStageState] = useState<StageState | null>(null)
  const [speakingIds, setSpeakingIds] = useState<ReadonlySet<string>>(new Set())
  const [micMuted, setMicMuted] = useState(false)
  const [transmitting, setTransmitting] = useState(false)
  const [micError, setMicError] = useState('')
  const [endedTick, setEndedTick] = useState(0)

  const statusRef = useRef<StageStatus>('idle')
  const applyStatus = useCallback((next: StageStatus) => {
    statusRef.current = next
    setStatus(next)
  }, [])

  const socketRef = useRef<Socket | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const ctxRef = useRef<AudioContext | null>(null)
  const nodesRef = useRef<{ source: AudioNode; node: AudioNode; sink: AudioNode } | null>(null)
  /** capture accumulator: one native-rate block ≈ CHUNK_MS of audio */
  const blockRef = useRef<{ buf: Float32Array; count: number }>({ buf: new Float32Array(0), count: 0 })
  const seqRef = useRef(1)
  const peerPlayRef = useRef<Map<string, { lastSeq: number; nextAt: number }>>(new Map())
  const joinedConvRef = useRef<string | null>(null)
  const transmittingRef = useRef(false)
  const mutedRef = useRef(false)
  const micArmedRef = useRef(false)
  const voiceJoinedRef = useRef(false)
  const wasHostRef = useRef(false)
  const mountedRef = useRef(true)
  const lastResyncRef = useRef(0)
  const roleRef = useRef<StageRole>('audience')

  const role = useMemo(() => deriveRole(stageState, me.id), [stageState, me.id])
  roleRef.current = role
  const handRaised = stageState?.hands.some((h) => h.id === me.id) ?? false

  const stopTransmitRef = useRef<() => void>(() => undefined)

  // ── socket plumbing ────────────────────────────────────────

  const emitStageJoin = useCallback(
    (sock: Socket, convId: string, asHost: boolean) => {
      sock.emit('stage:join', {
        conversationId: convId,
        user: { id: me.id, name: me.name, username: me.username, color: me.color },
        asHost,
      })
    },
    [me.id, me.name, me.username, me.color],
  )

  const ensureSocket = useCallback(() => {
    if (socketRef.current) return socketRef.current

    const sock = io('/?XTransformPort=3003', {
      path: '/',
      transports: ['polling', 'websocket'],
      upgrade: true,
      reconnection: true,
      reconnectionDelay: 800,
      reconnectionDelayMax: 5_000,
      timeout: RELAY_CONNECT_TIMEOUT_MS,
    })
    socketRef.current = sock

    sock.on('connect', () => {
      if (!mountedRef.current) return
      setConnected(true)
      // the relay wipes rosters on reconnect/restart — re-register the seat
      const convId = joinedConvRef.current
      if (convId) {
        emitStageJoin(sock, convId, wasHostRef.current)
        // a speaker's voice seat rides the same socket
        if (voiceJoinedRef.current) {
          sock.emit('voice:join', {
            conversationId: convId,
            user: { id: me.id, name: me.name, username: me.username, color: me.color },
          })
        }
      }
    })
    sock.on('disconnect', () => {
      if (mountedRef.current) setConnected(false)
    })

    sock.on('stage:state', (raw: unknown) => {
      if (!mountedRef.current) return
      const next = parseStageState(raw)
      if (!next || next.conversationId !== joinedConvRef.current) return
      setStageState(next)
      wasHostRef.current = next.host?.id === me.id
      // prune transmit rings to peers that are actually on stage
      const liveSpeakers = new Set(next.speakers.map((s) => s.id))
      setSpeakingIds((prev) => {
        const nxt = new Set<string>()
        for (const id of prev) if (liveSpeakers.has(id)) nxt.add(id)
        return nxt.size === prev.size ? prev : nxt
      })
      // relay restart wiped us mid-session → re-register (rate-limited)
      const present =
        next.host?.id === me.id ||
        next.speakers.some((s) => s.id === me.id) ||
        next.hands.some((h) => h.id === me.id) ||
        next.listeners.some((l) => l.id === me.id)
      const now = Date.now()
      if (!present && now - lastResyncRef.current > STAGE_RESYNC_MS) {
        lastResyncRef.current = now
        emitStageJoin(sock, next.conversationId, wasHostRef.current)
      }
    })

    sock.on('stage:ended', (raw: unknown) => {
      if (!mountedRef.current) return
      const r = (raw ?? {}) as { conversationId?: unknown }
      const convId = typeof r.conversationId === 'string' ? r.conversationId : ''
      if (!convId || convId !== joinedConvRef.current) return
      setEndedTick((t) => t + 1)
      // leaving from inside a handler is unsafe — defer the teardown one tick
      queueMicrotask(() => leaveRef.current())
    })

    // ── voice-room relay (only while my stage seat is a speaker seat) ──
    sock.on('voice:ptt', (raw: unknown) => {
      if (!mountedRef.current) return
      if (raw === null || typeof raw !== 'object') return
      const r = raw as { conversationId?: unknown; userId?: unknown; on?: unknown }
      const convId = typeof r.conversationId === 'string' ? r.conversationId : ''
      const userId = typeof r.userId === 'string' ? r.userId : ''
      if (!convId || convId !== joinedConvRef.current || !userId) return
      setSpeakingIds((prev) => {
        const nxt = new Set(prev)
        if (r.on === true) nxt.add(userId)
        else nxt.delete(userId)
        return nxt
      })
    })

    sock.on('voice:chunk', (raw: unknown) => {
      if (!mountedRef.current) return
      if (raw === null || typeof raw !== 'object') return
      const r = raw as { conversationId?: unknown; userId?: unknown; seq?: unknown; data?: unknown }
      const convId = typeof r.conversationId === 'string' ? r.conversationId : ''
      const userId = typeof r.userId === 'string' ? r.userId : ''
      const seq = typeof r.seq === 'number' && Number.isFinite(r.seq) ? Math.floor(r.seq) : -1
      if (!convId || convId !== joinedConvRef.current || !userId || seq < 0) return
      if (typeof r.data !== 'string' || r.data.length === 0) return
      const bytes = base64ToBytes(r.data)
      if (!bytes || bytes.length === 0) return

      const state = peerPlayRef.current.get(userId) ?? { lastSeq: 0, nextAt: 0 }
      if (seq <= state.lastSeq) return // stale/duplicate packet
      state.lastSeq = seq
      peerPlayRef.current.set(userId, state)

      try {
        const ctx = ctxRef.current
        if (!ctx) return
        if (ctx.state === 'suspended') void ctx.resume()
        const samples = int16ToFloat(bytes)
        if (samples.length === 0) return
        const buffer = ctx.createBuffer(1, samples.length, TARGET_RATE)
        buffer.copyToChannel(samples, 0)
        const src = ctx.createBufferSource()
        src.buffer = buffer
        // per-peer playhead: chain chunks back-to-back, ≥ one jitter buffer ahead
        const at = Math.max(ctx.currentTime + JITTER_BUFFER_S, state.nextAt)
        src.start(at)
        state.nextAt = at + buffer.duration
        src.connect(ctx.destination)
        src.onended = () => {
          try {
            src.disconnect()
          } catch {
            /* already gone */
          }
        }
      } catch {
        // a corrupt chunk must never take the room down — drop it
      }
    })

    return sock
  }, [emitStageJoin, me.id, me.name, me.username, me.color])

  // ── capture pipeline (copied from voice-room-sheet) ────────

  const sendChunk = useCallback(
    (samples: Float32Array) => {
      const sock = socketRef.current
      const convId = joinedConvRef.current
      if (!sock || !convId || !sock.connected) return
      try {
        const pcm = floatToInt16(samples)
        const data = bytesToBase64(new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength))
        seqRef.current += 1
        sock.emit('voice:chunk', {
          conversationId: convId,
          userId: me.id,
          seq: seqRef.current - 1,
          data,
        })
      } catch {
        // encode failure — drop this slice, the stream keeps flowing
      }
    },
    [me.id],
  )

  const pushSamples = useCallback(
    (input: Float32Array) => {
      if (!transmittingRef.current || mutedRef.current) return
      const block = blockRef.current
      if (block.buf.length === 0) return // capture not armed
      let srcOff = 0
      while (srcOff < input.length) {
        const take = Math.min(block.buf.length - block.count, input.length - srcOff)
        block.buf.set(input.subarray(srcOff, srcOff + take), block.count)
        block.count += take
        srcOff += take
        if (block.count === block.buf.length) {
          sendChunk(downsampleBlock(block.buf, CHUNK_SAMPLES))
          block.count = 0
        }
      }
    },
    [sendChunk],
  )

  /** AudioWorklet source inlined as a Blob URL — no extra public file needed. */
  const attachCapture = useCallback(async (ctx: AudioContext, stream: MediaStream) => {
    const source = ctx.createMediaStreamSource(stream)
    const sink = ctx.createGain()
    sink.gain.value = 0 // capture only — never monitor locally (no echo loop)
    sink.connect(ctx.destination)

    const workletSrc = `class PulseStageCaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0]
    if (input && input[0] && input[0].length > 0) this.port.postMessage(input[0])
    return true
  }
}
registerProcessor('pulse-stage-capture-processor', PulseStageCaptureProcessor)`
    let node: AudioNode | null = null
    try {
      const blobUrl = URL.createObjectURL(new Blob([workletSrc], { type: 'application/javascript' }))
      try {
        await ctx.audioWorklet.addModule(blobUrl)
        const worklet = new AudioWorkletNode(ctx, 'pulse-stage-capture-processor')
        worklet.port.onmessage = (event: MessageEvent) => {
          const data = event.data
          if (data instanceof Float32Array) pushSamples(data)
        }
        node = worklet
      } finally {
        URL.revokeObjectURL(blobUrl)
      }
    } catch {
      node = null // fall through to the ScriptProcessor path
    }
    if (!node) {
      // ScriptProcessor fallback (deprecated but universal — incl. older Safari)
      const processor = ctx.createScriptProcessor(4096, 1, 1)
      processor.onaudioprocess = (event) => {
        const channel = event.inputBuffer.getChannelData(0)
        pushSamples(new Float32Array(channel))
      }
      node = processor
    }
    source.connect(node)
    node.connect(sink)
    nodesRef.current = { source, node, sink }
  }, [pushSamples])

  const teardownAudio = useCallback(() => {
    const nodes = nodesRef.current
    nodesRef.current = null
    if (nodes) {
      for (const n of [nodes.source, nodes.node, nodes.sink]) {
        try {
          n.disconnect()
        } catch {
          /* already gone */
        }
      }
    }
    const stream = streamRef.current
    streamRef.current = null
    if (stream) {
      for (const track of stream.getTracks()) {
        try {
          track.stop()
        } catch {
          /* already stopped */
        }
      }
    }
    const ctx = ctxRef.current
    ctxRef.current = null
    if (ctx) {
      void ctx.close().catch(() => undefined)
    }
    blockRef.current = { buf: new Float32Array(0), count: 0 }
  }, [])

  const teardownSocket = useCallback(() => {
    const sock = socketRef.current
    socketRef.current = null
    if (sock) {
      sock.removeAllListeners()
      sock.disconnect()
    }
    setConnected(false)
  }, [])

  /**
   * Lazily arms the mic on the FIRST PTT press. The AudioContext is constructed
   * synchronously inside the caller's gesture (iOS unlocks only those), then the
   * permission prompt + graph build happen after the first await.
   */
  const armMic = useCallback(async (): Promise<boolean> => {
    if (micArmedRef.current) return true
    const Ctx =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!Ctx) {
      setMicError('This browser cannot process live audio.')
      return false
    }
    const ctx = new Ctx()
    ctxRef.current = ctx
    void ctx.resume().catch(() => undefined)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      })
      streamRef.current = stream
      await attachCapture(ctx, stream)
      micArmedRef.current = true
      setMicError('')
      return true
    } catch (error) {
      teardownAudio()
      const name = error instanceof DOMException ? error.name : ''
      let message = 'Could not arm the microphone — try again.'
      if (name === 'NotAllowedError' || name === 'SecurityError') {
        message = 'Microphone access was denied — allow it in your browser settings to speak on stage.'
      } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
        message = 'No usable microphone was found on this device.'
      } else if (name === 'NotReadableError') {
        message = 'Your microphone is busy in another app — close it and try again.'
      }
      setMicError(message)
      toast.error(message)
      return false
    }
  }, [attachCapture, teardownAudio])

  const beginTransmit = useCallback(() => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (!convId || !sock || !sock.connected) return
    const r = roleRef.current
    if (transmittingRef.current || mutedRef.current || (r !== 'host' && r !== 'speaker')) return
    transmittingRef.current = true
    blockRef.current.count = 0 // fresh capture window per transmission
    setTransmitting(true)
    try {
      sock.emit('voice:ptt', { conversationId: convId, userId: me.id, on: true })
    } catch {
      /* best effort — rings self-heal on the next toggle */
    }
  }, [me.id])

  const stopTransmit = useCallback(() => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (!transmittingRef.current) return
    transmittingRef.current = false
    setTransmitting(false)
    if (convId && sock) {
      // flush the partial capture window so the tail of the sentence ships too
      const block = blockRef.current
      if (block.count > 0 && block.buf.length > 0) {
        const outLen = Math.max(1, Math.round((block.count / block.buf.length) * CHUNK_SAMPLES))
        sendChunk(downsampleBlock(block.buf.subarray(0, block.count), outLen))
        block.count = 0
      }
      try {
        sock.emit('voice:ptt', { conversationId: convId, userId: me.id, on: false })
      } catch {
        /* best effort */
      }
    }
  }, [me.id, sendChunk])

  stopTransmitRef.current = stopTransmit

  const waitConnect = (sock: Socket): Promise<void> =>
    new Promise((resolve, reject) => {
      if (sock.connected) return resolve()
      const timer = setTimeout(() => {
        cleanup()
        reject(new Error('timeout'))
      }, RELAY_CONNECT_TIMEOUT_MS)
      const cleanup = () => {
        clearTimeout(timer)
        sock.off('connect', onConnect)
        sock.off('connect_error', onError)
      }
      const onConnect = () => {
        cleanup()
        resolve()
      }
      const onError = () => {
        cleanup()
        reject(new Error('unreachable'))
      }
      sock.once('connect', onConnect)
      sock.once('connect_error', onError)
    })

  // ── public controls ────────────────────────────────────────

  const leave = useCallback(() => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (convId && sock) {
      try {
        sock.emit('stage:leave', { conversationId: convId })
      } catch {
        /* socket already gone — disconnect cleanup covers it */
      }
      if (voiceJoinedRef.current) {
        voiceJoinedRef.current = false
        try {
          sock.emit('voice:leave', { conversationId: convId })
        } catch {
          /* best effort */
        }
      }
    }
    joinedConvRef.current = null
    wasHostRef.current = false
    micArmedRef.current = false
    transmittingRef.current = false
    mutedRef.current = false
    seqRef.current = 1
    peerPlayRef.current.clear()
    if (mountedRef.current) {
      setStageState(null)
      setSpeakingIds(new Set())
      setTransmitting(false)
      setMicMuted(false)
      setMicError('')
      applyStatus('idle')
      setErrorMsg('')
    }
    stopTransmitRef.current = () => undefined
    teardownAudio()
    teardownSocket()
  }, [applyStatus, teardownAudio, teardownSocket])

  const leaveRef = useRef(leave)
  leaveRef.current = leave

  const join = useCallback(
    (asHost = false) => {
      if (joinedConvRef.current === conversationId && (statusRef.current === 'joined' || statusRef.current === 'joining')) {
        return
      }
      if (joinedConvRef.current && joinedConvRef.current !== conversationId) {
        leave() // conversation switch mid-session — the seat belongs to ONE room
      }
      if (statusRef.current === 'joining') return
      applyStatus('joining')
      setErrorMsg('')

      void (async () => {
        try {
          const sock = ensureSocket()
          if (!sock.connected) await waitConnect(sock)
          if (!mountedRef.current) return
          joinedConvRef.current = conversationId
          wasHostRef.current = asHost
          emitStageJoin(sock, conversationId, asHost)
          applyStatus('joined')
        } catch {
          if (!mountedRef.current) return
          const message = 'Could not reach the stage relay — check your connection and retry.'
          applyStatus('error')
          setErrorMsg(message)
          toast.error(message)
        }
      })()
    },
    [applyStatus, conversationId, emitStageJoin, ensureSocket, leave],
  )

  const setPtt = useCallback(
    (on: boolean) => {
      if (on) {
        const r = roleRef.current
        if (r !== 'host' && r !== 'speaker') return
        if (mutedRef.current || transmittingRef.current) return
        if (micArmedRef.current) {
          beginTransmit()
        } else {
          // arm on the gesture, then transmit once the graph is live
          void armMic().then((ok) => {
            if (ok && !mutedRef.current && !transmittingRef.current) beginTransmit()
          })
        }
      } else {
        stopTransmit()
      }
    },
    [armMic, beginTransmit, stopTransmit],
  )

  const toggleMute = useCallback(() => {
    const next = !mutedRef.current
    mutedRef.current = next
    setMicMuted(next)
    if (next && transmittingRef.current) stopTransmit() // a muted mic never transmits
    const stream = streamRef.current
    if (stream) {
      for (const track of stream.getAudioTracks()) {
        try {
          track.enabled = !next
        } catch {
          /* keep the software gate as the source of truth */
        }
      }
    }
  }, [stopTransmit])

  const raiseHand = useCallback(
    (raised: boolean) => {
      const convId = joinedConvRef.current
      const sock = socketRef.current
      if (!convId || !sock || !sock.connected) return
      try {
        sock.emit('stage:hand', { conversationId: convId, user: { id: me.id }, raised })
      } catch {
        /* best effort — state resync repairs the view */
      }
    },
    [me.id],
  )

  const approveHand = useCallback(
    (targetUserId: string) => {
      const convId = joinedConvRef.current
      const sock = socketRef.current
      if (!convId || !sock || !sock.connected) return
      if (roleRef.current !== 'host') return // host-only moderation
      try {
        sock.emit('stage:approve', { conversationId: convId, byUserId: me.id, targetUserId })
      } catch {
        /* best effort */
      }
    },
    [me.id],
  )

  const muteMember = useCallback(
    (targetUserId: string) => {
      const convId = joinedConvRef.current
      const sock = socketRef.current
      if (!convId || !sock || !sock.connected) return
      if (roleRef.current !== 'host') return // host-only moderation
      try {
        sock.emit('stage:mute', { conversationId: convId, byUserId: me.id, targetUserId })
      } catch {
        /* best effort */
      }
    },
    [me.id],
  )

  const endStage = useCallback(() => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (!convId || !sock || !sock.connected) return
    if (roleRef.current !== 'host') return // host-only moderation
    try {
      sock.emit('stage:end', { conversationId: convId, byUserId: me.id })
    } catch {
      /* best effort — stage:ended repair covers it */
    }
  }, [me.id])

  const claimHost = useCallback(() => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (!convId || !sock || !sock.connected) return
    if (statusRef.current !== 'joined') return
    // the server honors asHost claims ONLY while the host seat is empty
    emitStageJoin(sock, convId, true)
  }, [emitStageJoin])

  // speakers (incl. the host) ride the proven voice-room relay for live audio
  useEffect(() => {
    const sock = socketRef.current
    const convId = joinedConvRef.current
    if (!sock || !convId || !sock.connected || statusRef.current !== 'joined') return
    const speaker = role === 'host' || role === 'speaker'
    if (speaker && !voiceJoinedRef.current) {
      voiceJoinedRef.current = true
      try {
        sock.emit('voice:join', {
          conversationId: convId,
          user: { id: me.id, name: me.name, username: me.username, color: me.color },
        })
      } catch {
        /* reconnect resync re-registers it */
      }
    } else if (!speaker && voiceJoinedRef.current) {
      voiceJoinedRef.current = false
      stopTransmitRef.current()
      try {
        sock.emit('voice:leave', { conversationId: convId })
      } catch {
        /* best effort */
      }
      setSpeakingIds(new Set())
      teardownAudio() // demoted → the mic graph is released
    }
  }, [role, stageState, me.id, me.name, me.username, me.color, teardownAudio])

  // unmount → full teardown (stop tracks, emit stage:leave, drop socket)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      leaveRef.current()
    }
  }, [])

  return {
    status,
    errorMsg,
    connected,
    inRoom: status === 'joined',
    state: stageState,
    role,
    handRaised,
    speakingIds,
    micMuted,
    transmitting,
    micError,
    endedTick,
    join,
    leave,
    raiseHand,
    approveHand,
    muteMember,
    endStage,
    claimHost,
    setPtt,
    toggleMute,
  }
}

// ── the sheet UI ─────────────────────────────────────────────

function SpeakingBars({ reduced }: { reduced: boolean }) {
  if (reduced) {
    return (
      <span className="flex items-end gap-[2.5px]" aria-hidden>
        <span className="h-[13px] w-[3px] rounded-full bg-emerald-300" />
        <span className="h-[6px] w-[3px] rounded-full bg-emerald-300" />
        <span className="h-[15px] w-[3px] rounded-full bg-emerald-300" />
      </span>
    )
  }
  return (
    <span className="flex items-end gap-[2.5px]" aria-hidden>
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          className="w-[3px] rounded-full bg-emerald-300"
          animate={{ height: [4, 13, 6, 15, 5] }}
          transition={{ duration: 0.9, repeat: Infinity, delay: i * 0.14, ease: 'easeInOut' }}
          style={{ height: 5 }}
        />
      ))}
    </span>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="flex items-center gap-2 pb-2 text-[10px] font-bold uppercase tracking-[0.14em] text-zinc-500">
      {children}
    </p>
  )
}

/** Avatar with the voice-room glowing-ring treatment for active transmitters. */
function StageAvatar({
  person,
  speaking,
  size = 40,
}: {
  person: StagePersonInfo
  speaking: boolean
  size?: number
}) {
  return (
    <motion.span
      animate={
        speaking
          ? { boxShadow: '0 0 0 2px rgba(52,211,153,0.9), 0 0 18px rgba(16,185,129,0.55)' }
          : { boxShadow: '0 0 0 1px rgba(255,255,255,0.1)' }
      }
      transition={{ duration: 0.18 }}
      className="rounded-full"
      style={{ willChange: 'transform' }}
    >
      <UserAvatar name={person.name} color={person.color} size={size} />
    </motion.span>
  )
}

function SpeakerTile({
  person,
  speaking,
  isMe,
  isHost,
  canModerate,
  onMute,
  reduced,
}: {
  person: StagePersonInfo
  speaking: boolean
  isMe: boolean
  isHost: boolean
  canModerate: boolean
  onMute: () => void
  reduced: boolean
}) {
  return (
    <li
      className={cn(
        'flex min-w-0 flex-1 basis-[calc(50%-0.25rem)] items-center gap-2.5 rounded-2xl border px-2.5 py-2',
        speaking ? 'border-emerald-500/30 bg-emerald-500/[0.06]' : 'border-white/5 bg-white/[0.03]',
      )}
    >
      <StageAvatar person={person} speaking={speaking} size={38} />
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 truncate text-[13px] font-semibold text-zinc-100">
          <span className="truncate">{person.name}</span>
          {isMe ? (
            <span className="shrink-0 rounded-full bg-emerald-500/15 px-1.5 py-px text-[9px] font-bold uppercase tracking-wider text-emerald-300">
              you
            </span>
          ) : null}
        </p>
        <p className="flex items-center gap-1 truncate text-[10.5px] text-zinc-500">
          {speaking ? (
            <>
              <SpeakingBars reduced={reduced} />
              <span className="font-bold uppercase tracking-wider text-emerald-300">live</span>
            </>
          ) : (
            <span className="uppercase tracking-wider">{isHost ? 'hosting' : 'on stage'}</span>
          )}
        </p>
      </div>
      {canModerate && !isHost ? (
        <Button
          variant="outline"
          size="icon"
          aria-label={`Mute ${person.name}`}
          onClick={() => {
            haptic(10)
            onMute()
          }}
          className="size-11 shrink-0 rounded-xl border-white/10 bg-white/5 text-zinc-400 hover:bg-rose-500/15 hover:text-rose-300"
        >
          <MicOff className="size-4" aria-hidden />
        </Button>
      ) : null}
    </li>
  )
}

function HandRow({
  person,
  index,
  isMe,
  canModerate,
  onApprove,
  onDecline,
}: {
  person: StagePersonInfo
  index: number
  isMe: boolean
  canModerate: boolean
  onApprove: () => void
  onDecline: () => void
}) {
  return (
    <li className="flex items-center gap-2.5 rounded-2xl border border-amber-400/20 bg-amber-500/[0.06] px-2.5 py-2">
      <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-amber-400/15 text-[10px] font-bold text-amber-300" aria-hidden>
        {index + 1}
      </span>
      <UserAvatar name={person.name} color={person.color} size={34} />
      <p className="flex min-w-0 flex-1 items-center gap-1.5 truncate text-[13px] font-semibold text-zinc-100">
        <span className="truncate">{person.name}</span>
        <Hand className="size-3.5 shrink-0 text-amber-300" aria-hidden />
        {isMe ? (
          <span className="shrink-0 rounded-full bg-emerald-500/15 px-1.5 py-px text-[9px] font-bold uppercase tracking-wider text-emerald-300">
            you
          </span>
        ) : null}
      </p>
      {canModerate ? (
        <span className="flex shrink-0 items-center gap-1.5">
          <Button
            size="icon"
            aria-label={`Approve ${person.name} to speak`}
            onClick={() => {
              haptic(12)
              onApprove()
            }}
            className="size-11 rounded-xl bg-emerald-500 text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90"
          >
            <UserCheck className="size-4" aria-hidden />
          </Button>
          <Button
            variant="outline"
            size="icon"
            aria-label={`Decline ${person.name}`}
            onClick={() => {
              haptic(8)
              onDecline()
            }}
            className="size-11 rounded-xl border-white/10 bg-white/5 text-zinc-400 hover:bg-rose-500/15 hover:text-rose-300"
          >
            <MicOff className="size-4" aria-hidden />
          </Button>
        </span>
      ) : null}
    </li>
  )
}

function StageRoomSheetUI({
  conversationId,
  myId,
  stage,
  onClose,
}: {
  conversationId: string
  myId: string
  stage: StageController
  onClose: () => void
}) {
  const reduced = useReducedMotion() ?? false
  const stageRef = useRef(stage)
  const holdStartRef = useRef(0)
  const pointerActiveRef = useRef(false)
  const confirmEndRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [confirmEnd, setConfirmEnd] = useState(false)

  useEffect(() => {
    stageRef.current = stage
  })

  // closing the sheet must never leave a ghost transmission hot
  useEffect(() => {
    return () => {
      stageRef.current.setPtt(false)
      if (confirmEndRef.current) clearTimeout(confirmEndRef.current)
    }
  }, [])

  // Esc dismisses
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // real conversation title (cached; same endpoint the room header uses)
  const detail = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: async (): Promise<ConversationDetail> => {
      const res = await apiJson<{ conversation: ConversationDetail }>(
        `/api/conversations/${encodeURIComponent(conversationId)}?userId=${encodeURIComponent(myId)}`,
      )
      return res.conversation
    },
    staleTime: 60_000,
    retry: 1,
    enabled: stage.inRoom,
  })
  const title = useMemo(() => {
    const c = detail.data
    if (!c) return 'Live stage'
    if (c.name && c.name.trim().length > 0) return c.name
    const other = c.members.find((m) => m.id !== myId)
    return other ? other.name : 'Live stage'
  }, [detail.data, myId])

  const state = stage.state
  const isHost = stage.role === 'host'
  const isSpeaker = stage.role === 'speaker'
  const isListener = stage.role === 'listener'
  const canTalk = stage.inRoom && (isHost || isSpeaker) && !stage.micMuted

  const onPttDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!canTalk) return
    e.preventDefault()
    e.currentTarget.setPointerCapture?.(e.pointerId)
    haptic(8)
    pointerActiveRef.current = true
    holdStartRef.current = Date.now()
    if (stage.transmitting) {
      stage.setPtt(false) // tap while latched → unlatch
      return
    }
    stage.setPtt(true)
  }

  const onPttUp = () => {
    pointerActiveRef.current = false
    if (!stageRef.current.transmitting) return
    // a quick tap latches the mic on; a real hold stops on release
    if (Date.now() - holdStartRef.current >= 260) stageRef.current.setPtt(false)
  }

  const onPttKey = () => {
    if (!canTalk) return
    haptic(8)
    holdStartRef.current = Date.now()
    stage.setPtt(!stage.transmitting)
  }

  const onEndStage = () => {
    // two-tap confirm — ending the stage is destructive for the whole room
    if (!confirmEnd) {
      setConfirmEnd(true)
      haptic(12)
      if (confirmEndRef.current) clearTimeout(confirmEndRef.current)
      confirmEndRef.current = setTimeout(() => setConfirmEnd(false), 2600)
      return
    }
    if (confirmEndRef.current) {
      clearTimeout(confirmEndRef.current)
      confirmEndRef.current = null
    }
    setConfirmEnd(false)
    haptic(18)
    stage.endStage()
  }

  const connectionLine = (() => {
    if (stage.inRoom && stage.connected) {
      return { dot: 'bg-emerald-400', text: 'Connected via gateway :3003', pulse: false }
    }
    if (stage.status === 'joining') {
      return { dot: 'bg-amber-400', text: 'Connecting to the stage relay…', pulse: true }
    }
    if (stage.inRoom && !stage.connected) {
      return { dot: 'bg-rose-400', text: 'Reconnecting to the relay…', pulse: true }
    }
    return { dot: 'bg-zinc-500', text: 'Standby — not connected', pulse: false }
  })()

  const speakersWithoutHost = state ? state.speakers.filter((s) => s.id !== state.host?.id) : []
  const listenerNames = state ? state.listeners.slice(0, 4).map((l) => l.name) : []

  return (
    <>
      {/* backdrop */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: reduced ? 0 : 0.2 }}
        onClick={onClose}
        aria-hidden
        className="fixed inset-0 z-[60] bg-black/60 backdrop-blur-sm"
      />

      {/* sheet */}
      <motion.div
        role="dialog"
        aria-modal="true"
        aria-label="Stage room"
        initial={{ y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={reduced ? { duration: 0 } : { type: 'spring', stiffness: 380, damping: 38 }}
        drag="y"
        dragConstraints={{ top: 0, bottom: 0 }}
        dragElastic={{ top: 0, bottom: 0.55 }}
        onDragEnd={(_, info) => {
          if (info.offset.y > 110 || info.velocity.y > 620) onClose()
        }}
        style={{ willChange: 'transform' }}
        className="fixed inset-x-0 bottom-0 z-[70] mx-auto flex max-h-[88dvh] w-full max-w-[420px] flex-col overflow-hidden rounded-t-3xl border-t border-white/10 bg-zinc-950/95 text-zinc-100 shadow-[0_-18px_60px_rgba(0,0,0,0.55)] backdrop-blur-xl"
      >
        {/* grab handle */}
        <button
          type="button"
          aria-label="Drag down to close"
          onClick={onClose}
          className="mx-auto flex w-full cursor-grab justify-center pt-2.5 pb-1 outline-none active:cursor-grabbing"
        >
          <span className="h-1.5 w-11 rounded-full bg-white/15" aria-hidden />
        </button>

        {/* header */}
        <div className="flex items-start gap-2 px-4 pt-1.5 pb-3">
          <span
            aria-hidden
            className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-2xl bg-emerald-500/15 text-emerald-300"
          >
            <Podcast className="size-5" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="flex items-center gap-2 truncate text-[15px] font-bold tracking-tight">
              <span className="relative flex size-2 shrink-0" aria-hidden>
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-rose-500 opacity-70" style={{ animationDuration: '1.4s' }} />
                <span className="relative inline-flex size-2 rounded-full bg-rose-500" />
              </span>
              Live stage
            </h2>
            <p className="truncate text-[11.5px] text-zinc-400">{title}</p>
          </div>
          {state ? (
            <span
              className="flex shrink-0 items-center gap-1 rounded-full bg-white/5 px-2 py-1 text-[10.5px] font-bold text-zinc-300"
              aria-label={`${state.listenerCount} listening`}
            >
              <UsersRound className="size-3.5 text-zinc-400" aria-hidden />
              {state.listenerCount}
            </span>
          ) : null}
          <Button
            variant="ghost"
            size="icon"
            aria-label="Close stage room"
            onClick={onClose}
            className="size-11 shrink-0 rounded-full text-zinc-400 hover:bg-white/10 hover:text-white"
          >
            <X className="size-4.5" aria-hidden />
          </Button>
        </div>

        {/* scrollable body */}
        <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto px-4 pb-2">
          <p className="flex items-center gap-2 pb-3 text-[11px] font-medium text-zinc-400">
            <span className={cn('size-1.5 rounded-full', connectionLine.dot, connectionLine.pulse && 'animate-pulse')} aria-hidden />
            {connectionLine.text}
          </p>

          {/* honest failure state */}
          {stage.status === 'error' && stage.errorMsg ? (
            <div
              role="alert"
              className="flex items-start gap-2.5 rounded-2xl border border-rose-500/25 bg-rose-500/10 px-3 py-3"
            >
              <AlertTriangle className="mt-0.5 size-4 shrink-0 text-rose-400" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-[12.5px] font-medium leading-relaxed text-rose-200">{stage.errorMsg}</p>
                <Button
                  onClick={() => {
                    haptic(8)
                    stage.join(false)
                  }}
                  className="mt-2 h-8 rounded-xl bg-rose-500/90 px-3 text-xs font-bold text-white hover:bg-rose-500"
                >
                  Try again
                </Button>
              </div>
            </div>
          ) : null}

          {stage.inRoom && !state ? (
            <p className="flex items-center justify-center gap-2 py-6 text-xs text-zinc-500">
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
              Syncing stage…
            </p>
          ) : null}

          {state ? (
            <div className="space-y-4 pb-1">
              {/* HOST */}
              <section aria-label="Host">
                <SectionLabel>Host</SectionLabel>
                {state.host ? (
                  <div className="flex items-center gap-3 rounded-2xl border border-amber-400/20 bg-amber-500/[0.05] px-3 py-2.5">
                    <Crown className="size-4 shrink-0 text-amber-300" aria-hidden />
                    <StageAvatar person={state.host} speaking={stage.speakingIds.has(state.host.id)} size={40} />
                    <div className="min-w-0 flex-1">
                      <p className="flex items-center gap-1.5 truncate text-sm font-semibold text-zinc-100">
                        <span className="truncate">{state.host.name}</span>
                        {state.host.id === myId ? (
                          <span className="shrink-0 rounded-full bg-emerald-500/15 px-1.5 py-px text-[9px] font-bold uppercase tracking-wider text-emerald-300">
                            you
                          </span>
                        ) : null}
                      </p>
                      <p className="truncate text-[11px] text-zinc-500">Runs the room · approves hands</p>
                    </div>
                  </div>
                ) : (
                  <div className="flex flex-col items-start gap-2 rounded-2xl border border-dashed border-white/10 bg-white/[0.02] px-3 py-3">
                    <p className="text-[12.5px] font-medium text-zinc-300">No host on stage right now.</p>
                    {stage.inRoom && !isHost ? (
                      <Button
                        onClick={() => {
                          haptic(10)
                          stage.claimHost()
                        }}
                        className="h-8 rounded-xl bg-amber-400/90 px-3 text-xs font-bold text-zinc-950 hover:bg-amber-400"
                      >
                        <Crown className="size-3.5" aria-hidden />
                        Claim host
                      </Button>
                    ) : null}
                  </div>
                )}
              </section>

              {/* SPEAKERS */}
              <section aria-label="Speakers">
                <SectionLabel>
                  Speakers
                  <span className="text-zinc-600">· {speakersWithoutHost.length + (state.host ? 0 : 0)}</span>
                </SectionLabel>
                {speakersWithoutHost.length > 0 ? (
                  <ul className="flex flex-wrap gap-2">
                    {speakersWithoutHost.map((person) => (
                      <SpeakerTile
                        key={person.id}
                        person={person}
                        speaking={stage.speakingIds.has(person.id)}
                        isMe={person.id === myId}
                        isHost={false}
                        canModerate={isHost}
                        onMute={() => stage.muteMember(person.id)}
                        reduced={reduced}
                      />
                    ))}
                  </ul>
                ) : (
                  <p className="rounded-2xl border border-dashed border-white/10 bg-white/[0.02] px-3 py-3 text-center text-[11.5px] text-zinc-500">
                    Only the host is on stage — approve a raised hand to add speakers.
                  </p>
                )}
              </section>

              {/* RAISED HANDS (FIFO queue) */}
              {state.hands.length > 0 ? (
                <section aria-label="Raised hands">
                  <SectionLabel>Raised hands · {state.hands.length}</SectionLabel>
                  <ul className="space-y-2">
                    {state.hands.map((person, i) => (
                      <HandRow
                        key={person.id}
                        person={person}
                        index={i}
                        isMe={person.id === myId}
                        canModerate={isHost}
                        onApprove={() => stage.approveHand(person.id)}
                        onDecline={() => stage.muteMember(person.id)}
                      />
                    ))}
                  </ul>
                </section>
              ) : null}

              {/* LISTENERS (collapsed) */}
              <section aria-label="Listeners">
                <SectionLabel>Listeners · {state.listenerCount}</SectionLabel>
                {state.listenerCount > 0 ? (
                  <div className="flex items-center gap-2.5 rounded-2xl border border-white/5 bg-white/[0.03] px-3 py-2.5">
                    <span className="flex -space-x-2" aria-hidden>
                      {state.listeners.slice(0, 4).map((l) => (
                        <span key={l.id} className="rounded-full ring-2 ring-zinc-950">
                          <UserAvatar name={l.name} color={l.color} size={26} />
                        </span>
                      ))}
                    </span>
                    <p className="min-w-0 flex-1 truncate text-[11.5px] text-zinc-400">
                      {listenerNames.join(', ')}
                      {state.listenerCount > listenerNames.length ? ` +${state.listenerCount - listenerNames.length} more` : ''}
                    </p>
                  </div>
                ) : (
                  <p className="rounded-2xl border border-dashed border-white/10 bg-white/[0.02] px-3 py-3 text-center text-[11.5px] text-zinc-500">
                    Nobody is listening yet — share the room.
                  </p>
                )}
              </section>

              {/* honest mic-arming failure (the session continues) */}
              {stage.micError ? (
                <div role="alert" className="flex items-start gap-2.5 rounded-2xl border border-rose-500/25 bg-rose-500/10 px-3 py-3">
                  <AlertTriangle className="mt-0.5 size-4 shrink-0 text-rose-400" aria-hidden />
                  <p className="min-w-0 flex-1 text-[12.5px] font-medium leading-relaxed text-rose-200">{stage.micError}</p>
                </div>
              ) : null}

              {stage.inRoom && stage.micMuted && (isHost || isSpeaker) ? (
                <p className="rounded-xl bg-amber-500/10 px-3 py-2 text-[11.5px] font-medium text-amber-300" role="status">
                  Mic is muted — unmute to talk.
                </p>
              ) : null}
            </div>
          ) : null}
        </div>

        {/* controls */}
        <div className="shrink-0 border-t border-white/10 bg-zinc-950/80 px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
          {/* host moderation: end stage (two-tap confirm) */}
          {isHost ? (
            <Button
              variant="outline"
              aria-label="End stage"
              onClick={onEndStage}
              className={cn(
                'mb-3 h-10 w-full rounded-xl text-xs font-bold',
                confirmEnd
                  ? 'border-rose-500/50 bg-rose-500/20 text-rose-200 hover:bg-rose-500/30'
                  : 'border-rose-500/25 bg-rose-500/10 text-rose-300 hover:bg-rose-500/20',
              )}
            >
              <Radio className="size-3.5" aria-hidden />
              {confirmEnd ? 'Tap again to end the stage for everyone' : 'End stage'}
            </Button>
          ) : null}

          {stage.inRoom && (isHost || isSpeaker) ? (
            <>
              <div className="flex items-center justify-center gap-5 pb-3">
                {/* mute toggle */}
                <Button
                  variant="outline"
                  size="icon"
                  aria-label={stage.micMuted ? 'Unmute microphone' : 'Mute microphone'}
                  aria-pressed={stage.micMuted}
                  onClick={() => {
                    haptic(8)
                    stage.toggleMute()
                  }}
                  className={cn(
                    'size-12 rounded-full border-white/10 bg-white/5 text-zinc-300 hover:bg-white/10 hover:text-white',
                    stage.micMuted && 'border-amber-400/40 bg-amber-500/15 text-amber-300',
                  )}
                >
                  {stage.micMuted ? <MicOff className="size-5" aria-hidden /> : <Mic className="size-5" aria-hidden />}
                </Button>

                {/* push-to-talk */}
                <motion.button
                  type="button"
                  aria-label={stage.transmitting ? 'Stop transmitting' : 'Push to talk — hold or tap to latch'}
                  aria-pressed={stage.transmitting}
                  disabled={!canTalk}
                  onPointerDown={onPttDown}
                  onPointerUp={onPttUp}
                  onPointerCancel={onPttUp}
                  onPointerLeave={() => {
                    // only a genuinely-held pointer drag-off stops the mic
                    if (pointerActiveRef.current) onPttUp()
                  }}
                  onKeyDown={(e) => {
                    if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) {
                      e.preventDefault()
                      onPttKey()
                    }
                  }}
                  onContextMenu={(e) => e.preventDefault()}
                  whileTap={canTalk ? { scale: 0.94 } : undefined}
                  transition={spring.snappy}
                  className={cn(
                    'relative flex size-[96px] touch-none flex-col items-center justify-center gap-0.5 rounded-full text-white select-none outline-none transition-colors duration-150',
                    canTalk
                      ? stage.transmitting
                        ? 'bg-gradient-to-br from-emerald-400 to-emerald-600 shadow-[0_0_34px_rgba(16,185,129,0.55)]'
                        : 'bg-gradient-to-br from-zinc-700 to-zinc-800 shadow-inner'
                      : 'cursor-not-allowed bg-zinc-800/70 text-zinc-500',
                  )}
                  style={{ willChange: 'transform' }}
                >
                  {stage.transmitting && !reduced ? (
                    <>
                      <span className="absolute inset-0 animate-ping rounded-full bg-emerald-400/35" aria-hidden style={{ animationDuration: '1.3s' }} />
                      <span className="absolute -inset-2 animate-ping rounded-full border border-emerald-400/30" aria-hidden style={{ animationDuration: '1.9s' }} />
                    </>
                  ) : null}
                  {stage.transmitting ? <AudioLines className="size-6" aria-hidden /> : <Mic className="size-6" aria-hidden />}
                  <span className="text-[9.5px] font-bold uppercase tracking-widest">
                    {stage.transmitting ? 'Live' : canTalk ? 'Hold' : 'Off'}
                  </span>
                </motion.button>

                {/* leave */}
                <Button
                  variant="outline"
                  size="icon"
                  aria-label="Leave stage"
                  onClick={() => {
                    haptic(10)
                    stage.leave()
                    onClose()
                  }}
                  className="size-12 rounded-full border-rose-500/30 bg-rose-500/10 text-rose-300 hover:bg-rose-500/20 hover:text-rose-200"
                >
                  <PhoneOff className="size-5" aria-hidden />
                </Button>
              </div>
              <p className="text-center text-[10px] leading-relaxed text-zinc-500">
                Hold to talk · tap to latch · stage audio rides the live voice relay
                <span className="mt-0.5 block text-zinc-600">Streamed in 250 ms chunks — never recorded or stored.</span>
              </p>
            </>
          ) : stage.inRoom && isListener ? (
            <>
              <motion.button
                type="button"
                aria-label={stage.handRaised ? 'Lower hand' : 'Raise hand'}
                aria-pressed={stage.handRaised}
                onClick={() => {
                  haptic(12)
                  stage.raiseHand(!stage.handRaised)
                }}
                whileTap={{ scale: 0.94 }}
                transition={spring.snappy}
                className={cn(
                  'flex h-12 w-full items-center justify-center gap-2 rounded-2xl text-sm font-bold outline-none',
                  stage.handRaised
                    ? 'bg-amber-400/90 text-zinc-950 shadow-md shadow-amber-500/25 hover:bg-amber-400'
                    : 'bg-emerald-500 text-white shadow-md shadow-emerald-600/30 hover:bg-emerald-500/90',
                )}
                style={{ willChange: 'transform' }}
              >
                <Hand className="size-4.5" aria-hidden />
                {stage.handRaised ? 'Hand raised — waiting for the host' : 'Raise hand'}
              </motion.button>
              <div className="flex items-center justify-between pt-2.5">
                <p className="text-[10px] leading-relaxed text-zinc-500">
                  {stage.handRaised ? 'The host will approve you to speak.' : 'You are listening — raise a hand to speak.'}
                </p>
                <Button
                  variant="outline"
                  aria-label="Leave stage"
                  onClick={() => {
                    haptic(10)
                    stage.leave()
                    onClose()
                  }}
                  className="h-9 rounded-xl border-rose-500/30 bg-rose-500/10 px-3 text-xs font-bold text-rose-300 hover:bg-rose-500/20 hover:text-rose-200"
                >
                  <PhoneOff className="size-3.5" aria-hidden />
                  Leave
                </Button>
              </div>
            </>
          ) : null}
        </div>
      </motion.div>
    </>
  )
}

// ── the contract hook (mounted by chat-room; opens via CustomEvent) ──

export function useStageSheet(conversationId: string, me: StageMe) {
  const [open, setOpen] = useState(false)
  const stage = useStageRoom(conversationId, me)
  const prevRoleRef = useRef<StageRole>('audience')

  // palette/tray entries open the sheet with `pulse:open-stage`
  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(STAGE_OPEN_EVENT, handler)
    return () => window.removeEventListener(STAGE_OPEN_EVENT, handler)
  }, [])

  // sheet open ⇄ stage session lifecycle (join defaults to listener)
  useEffect(() => {
    if (open) {
      stage.join(false)
    } else {
      stage.leave()
    }
  }, [open, conversationId, stage.join, stage.leave])

  // the host ended the stage → close the sheet + toast (engine self-leaves)
  // (deferred via microtask so the effect body never sets state synchronously)
  useEffect(() => {
    if (stage.endedTick <= 0) return
    const t = setTimeout(() => {
      setOpen(false)
      toast.info('The host ended the stage.', { description: 'Everyone was returned to the chat.' })
    }, 0)
    return () => clearTimeout(t)
  }, [stage.endedTick])

  // celebrate role promotions (real state transitions, not fake timing)
  useEffect(() => {
    const prev = prevRoleRef.current
    prevRoleRef.current = stage.role
    if (!stage.inRoom) return
    if ((prev === 'audience' || prev === 'listener') && stage.role === 'host') {
      toast.success('You are hosting the stage — your mic is live.')
      fireParticles({ kind: 'stars', x: 0.5, y: 0.5, count: 70 })
    } else if (prev === 'listener' && stage.role === 'speaker') {
      toast.success('Approved! You are live on stage — your mic is unlocked.')
      fireParticles({ kind: 'stars', x: 0.5, y: 0.5, count: 70 })
    }
  }, [stage.role, stage.inRoom])

  const node = (
    <AnimatePresence>
      {open ? (
        <StageRoomSheetUI key="stage-room-sheet" conversationId={conversationId} myId={me.id} stage={stage} onClose={() => setOpen(false)} />
      ) : null}
    </AnimatePresence>
  )

  return { open, setOpen, node }
}
