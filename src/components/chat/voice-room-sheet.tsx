// ─────────────────────────────────────────────────────────────
// Pulse — Live Voice room (R21-b "Beyond Chat" wave).
// Voxer/Zello/Telegram voice-chat style walkie-talkie rooms
// relayed through the pulse-socket service (port 3003).
//
// REAL audio, zero mocks:
//  · join → getUserMedia({audio}) — permission-denied surfaces an
//    honest inline error (no fake audio ever plays or sends).
//  · PTT (hold-to-talk, tap-to-latch) → raw mic PCM is captured
//    via AudioWorklet (ScriptProcessor fallback), downsampled to
//    16 kHz mono, split into ~250 ms Int16 chunks, base64-packed
//    and emitted as `voice:chunk` over the socket relay.
//  · receivers decode chunks into an AudioBuffer queue with a
//    small jitter buffer for gapless-ish playback.
//  · nothing is recorded, stored, or uploaded — chunks are relayed
//    peer-to-peer through the socket service and dropped.
//
// The session ENGINE (mic + socket + roster) lives in the
// `useVoiceRoom` hook so the room keeps running while the sheet
// is closed (the chat shows a "Voice · N live" pill). The sheet
// below is pure UI on top of that controller.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { io, type Socket } from 'socket.io-client'
import { motion } from 'framer-motion'
import {
  AlertTriangle,
  AudioLines,
  LoaderCircle,
  Mic,
  MicOff,
  PhoneOff,
  Radio,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/chat/user-avatar'

// ── audio pipeline constants ─────────────────────────────────
const TARGET_RATE = 16_000 // Hz — wideband voice, keeps chunks small
const CHUNK_MS = 250 // emit a chunk every 250 ms
const CHUNK_SAMPLES = Math.round((TARGET_RATE * CHUNK_MS) / 1000) // 4000 samples
const JITTER_BUFFER_S = 0.085 // playback pre-roll for smooth-ish scheduling
const RELAY_CONNECT_TIMEOUT_MS = 8_000

export interface VoicePeerInfo {
  id: string
  name: string
  username: string | null
  color: string
}

export type VoiceRoomStatus = 'idle' | 'joining' | 'joined' | 'error'

export interface VoiceRoomController {
  status: VoiceRoomStatus
  /** honest failure text for the inline error state (permission denied etc.) */
  errorMsg: string
  /** socket link to the relay (gateway :3003) is up */
  connected: boolean
  inRoom: boolean
  roster: VoicePeerInfo[]
  /** peer ids currently holding PTT (emerald glow) */
  speakingIds: ReadonlySet<string>
  micMuted: boolean
  /** local user is actively transmitting */
  transmitting: boolean
  join: () => void
  leave: () => void
  setPtt: (on: boolean) => void
  toggleMute: () => void
}

// ── binary helpers ───────────────────────────────────────────

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
  for (let i = 0; i < pcm.length; i++) out[i] = pcm[i] / 0x8000
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

// ── the engine hook ──────────────────────────────────────────

export function useVoiceRoom(conversationId: string, me: AppUser): VoiceRoomController {
  const [status, setStatus] = useState<VoiceRoomStatus>('idle')
  const [errorMsg, setErrorMsg] = useState('')
  const [connected, setConnected] = useState(false)
  const [roster, setRoster] = useState<VoicePeerInfo[]>([])
  const [speakingIds, setSpeakingIds] = useState<ReadonlySet<string>>(new Set())
  const [micMuted, setMicMuted] = useState(false)
  const [transmitting, setTransmitting] = useState(false)

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
  const mountedRef = useRef(true)

  // ── socket plumbing ────────────────────────────────────────

  const lastResyncRef = useRef(0)

  const emitVoiceJoin = useCallback((sock: Socket, convId: string) => {
    sock.emit('voice:join', {
      conversationId: convId,
      user: { id: me.id, name: me.name, username: me.username, color: me.color },
    })
  }, [me.id, me.name, me.username, me.color])

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
      // the relay wipes rosters on reconnect/restart — re-register the mic
      if (joinedConvRef.current) emitVoiceJoin(sock, joinedConvRef.current)
    })
    sock.on('disconnect', () => {
      if (mountedRef.current) setConnected(false)
    })

    sock.on('voice:roster', (raw: unknown) => {
      if (!mountedRef.current) return
      if (raw === null || typeof raw !== 'object') return
      const r = raw as { conversationId?: unknown; peers?: unknown }
      const convId = typeof r.conversationId === 'string' ? r.conversationId : ''
      if (!convId || convId !== joinedConvRef.current || !Array.isArray(r.peers)) return
      const peers = r.peers.flatMap((p): VoicePeerInfo[] => {
        if (p === null || typeof p !== 'object') return []
        const peer = p as Record<string, unknown>
        if (typeof peer.id !== 'string' || typeof peer.name !== 'string') return []
        return [
          {
            id: peer.id,
            name: peer.name,
            username: typeof peer.username === 'string' ? peer.username : null,
            color: typeof peer.color === 'string' ? peer.color : 'emerald',
          },
        ]
      })
      setRoster(peers)
      // prune transmit rings to peers that are actually on stage
      const liveIds = new Set(peers.map((p) => p.id))
      setSpeakingIds((prev) => {
        const next = new Set<string>()
        for (const id of prev) if (liveIds.has(id)) next.add(id)
        return next.size === prev.size ? prev : next
      })
      // relay restart wiped us mid-session → re-register (rate-limited)
      const now = Date.now()
      if (!liveIds.has(me.id) && joinedConvRef.current === convId && now - lastResyncRef.current > 2_000) {
        lastResyncRef.current = now
        emitVoiceJoin(sock, convId)
      }
    })

    sock.on('voice:ptt', (raw: unknown) => {
      if (!mountedRef.current) return
      if (raw === null || typeof raw !== 'object') return
      const r = raw as { conversationId?: unknown; userId?: unknown; on?: unknown }
      const convId = typeof r.conversationId === 'string' ? r.conversationId : ''
      const userId = typeof r.userId === 'string' ? r.userId : ''
      if (!convId || convId !== joinedConvRef.current || !userId) return
      setSpeakingIds((prev) => {
        const next = new Set(prev)
        if (r.on === true) next.add(userId)
        else next.delete(userId)
        return next
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
  }, [emitVoiceJoin, me.id])

  // ── capture pipeline ───────────────────────────────────────

  const sendChunk = useCallback((samples: Float32Array) => {
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
  }, [me.id])

  const pushSamples = useCallback((input: Float32Array) => {
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
  }, [sendChunk])

  /** AudioWorklet source inlined as a Blob URL — no extra public file needed. */
  const attachCapture = useCallback(async (ctx: AudioContext, stream: MediaStream) => {
    const source = ctx.createMediaStreamSource(stream)
    const sink = ctx.createGain()
    sink.gain.value = 0 // capture only — never monitor locally (no echo loop)
    sink.connect(ctx.destination)

    const workletSrc = `class PulseCaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0]
    if (input && input[0] && input[0].length > 0) this.port.postMessage(input[0])
    return true
  }
}
registerProcessor('pulse-capture-processor', PulseCaptureProcessor)`
    let node: AudioNode | null = null
    try {
      const blobUrl = URL.createObjectURL(new Blob([workletSrc], { type: 'application/javascript' }))
      try {
        await ctx.audioWorklet.addModule(blobUrl)
        const worklet = new AudioWorkletNode(ctx, 'pulse-capture-processor')
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
    peerPlayRef.current.clear()
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

  // ── public controls ────────────────────────────────────────

  const join = useCallback(() => {
    if (status === 'joining' || status === 'joined') return
    setStatus('joining')
    setErrorMsg('')

    const fail = (message: string) => {
      if (!mountedRef.current) return
      teardownAudio()
      setStatus('error')
      setErrorMsg(message)
      toast.error(message)
    }

    void (async () => {
      try {
        // 1. AudioContext FIRST (iOS only unlocks contexts created in a gesture)
        const Ctx = window.AudioContext ??
          (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
        if (!Ctx) {
          fail('This browser cannot process live audio.')
          return
        }
        const ctx = new Ctx()
        ctxRef.current = ctx
        void ctx.resume().catch(() => undefined)

        // 2. mic — the honest gate; permission denial lands in the inline error
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        })
        streamRef.current = stream

        // 3. relay link
        const sock = ensureSocket()
        if (!sock.connected) {
          await new Promise<void>((resolve, reject) => {
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
        }

        // 4. arm capture graph (worklet → ScriptProcessor fallback)
        await attachCapture(ctx, stream)

        // 5. take the stage
        joinedConvRef.current = conversationId
        emitVoiceJoin(sock, conversationId)
        if (mountedRef.current) {
          setStatus('joined')
          setMicMuted(false)
          mutedRef.current = false
          for (const track of stream.getAudioTracks()) track.enabled = true
        }
      } catch (error) {
        const name = error instanceof DOMException ? error.name : ''
        if (name === 'NotAllowedError' || name === 'SecurityError') {
          fail('Microphone access was denied — allow it in your browser settings to go live.')
        } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
          fail('No usable microphone was found on this device.')
        } else if (name === 'NotReadableError') {
          fail('Your microphone is busy in another app — close it and try again.')
        } else {
          fail('Could not reach the voice relay — check your connection and retry.')
        }
      }
    })()
  }, [status, attachCapture, conversationId, emitVoiceJoin, ensureSocket, teardownAudio])

  const leave = useCallback(() => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (convId && sock) {
      try {
        sock.emit('voice:leave', { conversationId: convId })
      } catch {
        /* socket already gone — disconnect cleanup covers it */
      }
    }
    joinedConvRef.current = null
    transmittingRef.current = false
    mutedRef.current = false
    seqRef.current = 1
    if (mountedRef.current) {
      setTransmitting(false)
      setMicMuted(false)
      setRoster([])
      setSpeakingIds(new Set())
      setStatus('idle')
      setErrorMsg('')
    }
    teardownAudio()
    teardownSocket()
  }, [teardownAudio, teardownSocket])

  const setPtt = useCallback((on: boolean) => {
    const convId = joinedConvRef.current
    const sock = socketRef.current
    if (!convId || !sock) return
    if (on) {
      if (transmittingRef.current || mutedRef.current || !sock.connected) return
      transmittingRef.current = true
      blockRef.current.count = 0 // fresh capture window per transmission
      setTransmitting(true)
      try {
        sock.emit('voice:ptt', { conversationId: convId, userId: me.id, on: true })
      } catch {
        /* best effort — rings self-heal on the next toggle */
      }
    } else {
      if (!transmittingRef.current) return
      transmittingRef.current = false
      setTransmitting(false)
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

  const toggleMute = useCallback(() => {
    const next = !mutedRef.current
    mutedRef.current = next
    setMicMuted(next)
    if (next && transmittingRef.current) setPtt(false) // a muted mic never transmits
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
  }, [setPtt])

  // conversation switch mid-session → the mic belongs to ONE room
  useEffect(() => {
    if (joinedConvRef.current && joinedConvRef.current !== conversationId) {
      const wasInRoom = status === 'joined'
      leave()
      if (wasInRoom) toast.info('Left the live voice room')
    }
  }, [conversationId])

  // unmount → full teardown (stop tracks, emit leave, drop socket)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      leave()
    }
  }, [])

  return {
    status,
    errorMsg,
    connected,
    inRoom: status === 'joined',
    roster,
    speakingIds,
    micMuted,
    transmitting,
    join,
    leave,
    setPtt,
    toggleMute,
  }
}

// ── the sheet UI ─────────────────────────────────────────────

function SpeakingBars() {
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

function PeerRow({
  peer,
  speaking,
  isMe,
}: {
  peer: VoicePeerInfo
  speaking: boolean
  isMe: boolean
}) {
  return (
    <li className="flex items-center gap-3 rounded-2xl border border-white/5 bg-white/[0.03] px-3 py-2.5">
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
        <UserAvatar name={peer.name} color={peer.color} size={40} />
      </motion.span>
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 truncate text-sm font-semibold text-zinc-100">
          <span className="truncate">{peer.name}</span>
          {isMe ? (
            <span className="shrink-0 rounded-full bg-emerald-500/15 px-1.5 py-px text-[9px] font-bold uppercase tracking-wider text-emerald-300">
              you
            </span>
          ) : null}
        </p>
        <p className="truncate text-[11px] text-zinc-500">
          {peer.username ? `@${peer.username}` : 'on stage'}
        </p>
      </div>
      {speaking ? (
        <span className="flex shrink-0 items-center gap-1.5 rounded-full bg-emerald-500/10 px-2 py-1">
          <SpeakingBars />
          <span className="text-[10px] font-bold uppercase tracking-wider text-emerald-300">live</span>
        </span>
      ) : (
        <span className="shrink-0 text-[10px] font-bold uppercase tracking-wider text-zinc-600">listening</span>
      )}
    </li>
  )
}

export function VoiceRoomSheet({
  title,
  myId,
  voice,
  onClose,
}: {
  title: string
  /** the local user's id — marks "you" in the roster */
  myId: string
  voice: VoiceRoomController
  onClose: () => void
}) {
  const voiceRef = useRef(voice)
  const holdStartRef = useRef(0)
  const pointerActiveRef = useRef(false)

  // keep the latest controller reachable from effects/handlers (never during render)
  useEffect(() => {
    voiceRef.current = voice
  })

  // closing the sheet must never leave a ghost transmission hot
  useEffect(() => {
    return () => voiceRef.current.setPtt(false)
  }, [])

  // Esc dismisses
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const onPttDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!voice.inRoom || voice.micMuted) return
    e.preventDefault()
    e.currentTarget.setPointerCapture?.(e.pointerId)
    haptic(8)
    pointerActiveRef.current = true
    holdStartRef.current = Date.now()
    if (voice.transmitting) {
      voice.setPtt(false) // tap while latched → unlatch
      return
    }
    voice.setPtt(true)
  }

  const onPttUp = () => {
    pointerActiveRef.current = false
    if (!voiceRef.current.transmitting) return
    // a quick tap latches the mic on; a real hold stops on release
    if (Date.now() - holdStartRef.current >= 260) voiceRef.current.setPtt(false)
  }

  /** keyboard parity: Space/Enter toggles the latch like a tap */
  const onPttKey = () => {
    if (!voice.inRoom || voice.micMuted) return
    haptic(8)
    holdStartRef.current = Date.now()
    voice.setPtt(!voice.transmitting)
  }

  const connectionLine = (() => {
    if (voice.inRoom && voice.connected) {
      return { dot: 'bg-emerald-400', text: 'Connected via gateway :3003', pulse: false }
    }
    if (voice.status === 'joining') {
      return { dot: 'bg-amber-400', text: 'Connecting to the voice relay…', pulse: true }
    }
    if (voice.inRoom && !voice.connected) {
      return { dot: 'bg-rose-400', text: 'Reconnecting to the relay…', pulse: true }
    }
    return { dot: 'bg-zinc-500', text: 'Standby — not connected', pulse: false }
  })()

  const canTalk = voice.inRoom && !voice.micMuted

  return (
    <>
      {/* backdrop */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.2 }}
        onClick={onClose}
        aria-hidden
        className="fixed inset-0 z-[60] bg-black/60 backdrop-blur-sm"
      />

      {/* sheet */}
      <motion.div
        role="dialog"
        aria-modal="true"
        aria-label={`Live voice room — ${title}`}
        initial={{ y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={{ type: 'spring', stiffness: 380, damping: 38 }}
        drag="y"
        dragConstraints={{ top: 0, bottom: 0 }}
        dragElastic={{ top: 0, bottom: 0.55 }}
        onDragEnd={(_, info) => {
          if (info.offset.y > 110 || info.velocity.y > 620) onClose()
        }}
        style={{ willChange: 'transform' }}
        className="fixed inset-x-0 bottom-0 z-[70] mx-auto flex max-h-[82dvh] w-full max-w-[420px] flex-col overflow-hidden rounded-t-3xl border-t border-white/10 bg-zinc-950/95 text-zinc-100 shadow-[0_-18px_60px_rgba(0,0,0,0.55)] backdrop-blur-xl"
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
            <AudioLines className="size-5" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-[15px] font-bold tracking-tight">Live Voice</h2>
            <p className="truncate text-[11.5px] text-zinc-400">{title}</p>
          </div>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Close voice room"
            onClick={onClose}
            className="size-8 shrink-0 rounded-full text-zinc-400 hover:bg-white/10 hover:text-white"
          >
            <X className="size-4.5" aria-hidden />
          </Button>
        </div>

        {/* scrollable body */}
        <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto px-4 pb-2">
          {/* connection state */}
          <p className="flex items-center gap-2 pb-3 text-[11px] font-medium text-zinc-400">
            <span className={cn('size-1.5 rounded-full', connectionLine.dot, connectionLine.pulse && 'animate-pulse')} aria-hidden />
            {connectionLine.text}
          </p>

          {/* roster */}
          {voice.inRoom ? (
            voice.roster.length > 0 ? (
              <ul className="space-y-2" aria-label="Voice participants">
                {voice.roster.map((peer) => (
                  <PeerRow
                    key={peer.id}
                    peer={peer}
                    speaking={voice.speakingIds.has(peer.id)}
                    isMe={peer.id === myId}
                  />
                ))}
              </ul>
            ) : (
              <p className="py-4 text-center text-xs text-zinc-500">Syncing roster…</p>
            )
          ) : (
            <div className="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-white/10 bg-white/[0.02] px-4 py-7 text-center">
              <span className="flex size-11 items-center justify-center rounded-2xl bg-emerald-500/10 text-emerald-300" aria-hidden>
                <Radio className="size-5" />
              </span>
              <p className="text-[13px] font-semibold text-zinc-200">Nobody is on stage yet</p>
              <p className="max-w-[240px] text-[11.5px] leading-relaxed text-zinc-500">
                Join the room, hold the talk button and speak — everyone inside hears you instantly.
              </p>
            </div>
          )}

          {/* honest failure state */}
          {voice.status === 'error' && voice.errorMsg ? (
            <div
              role="alert"
              className="mt-3 flex items-start gap-2.5 rounded-2xl border border-rose-500/25 bg-rose-500/10 px-3 py-3"
            >
              <AlertTriangle className="mt-0.5 size-4 shrink-0 text-rose-400" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-[12.5px] font-medium leading-relaxed text-rose-200">{voice.errorMsg}</p>
                <Button
                  onClick={() => voice.join()}
                  className="mt-2 h-8 rounded-xl bg-rose-500/90 px-3 text-xs font-bold text-white hover:bg-rose-500"
                >
                  Try again
                </Button>
              </div>
            </div>
          ) : null}

          {voice.inRoom && voice.micMuted ? (
            <p className="mt-3 rounded-xl bg-amber-500/10 px-3 py-2 text-[11.5px] font-medium text-amber-300" role="status">
              Mic is muted — unmute to talk.
            </p>
          ) : null}
        </div>

        {/* PTT + controls */}
        <div className="shrink-0 border-t border-white/10 bg-zinc-950/80 px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <div className="flex items-center justify-center gap-5 pb-3">
            {/* mute toggle */}
            <Button
              variant="outline"
              size="icon"
              aria-label={voice.micMuted ? 'Unmute microphone' : 'Mute microphone'}
              aria-pressed={voice.micMuted}
              disabled={!voice.inRoom}
              onClick={() => {
                haptic(8)
                voice.toggleMute()
              }}
              className={cn(
                'size-12 rounded-full border-white/10 bg-white/5 text-zinc-300 hover:bg-white/10 hover:text-white disabled:opacity-40',
                voice.micMuted && 'border-amber-400/40 bg-amber-500/15 text-amber-300',
              )}
            >
              {voice.micMuted ? <MicOff className="size-5" aria-hidden /> : <Mic className="size-5" aria-hidden />}
            </Button>

            {/* push-to-talk */}
            <button
              type="button"
              aria-label={voice.transmitting ? 'Stop transmitting' : 'Push to talk — hold or tap to latch'}
              aria-pressed={voice.transmitting}
              disabled={!canTalk}
              onPointerDown={onPttDown}
              onPointerUp={onPttUp}
              onPointerCancel={onPttUp}
              onPointerLeave={() => {
                // only a genuinely-held pointer drag-off stops the mic —
                // never a mouse merely drifting across a latched button
                if (pointerActiveRef.current) onPttUp()
              }}
              onKeyDown={(e) => {
                if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) {
                  e.preventDefault()
                  onPttKey()
                }
              }}
              onContextMenu={(e) => e.preventDefault()}
              className={cn(
                'relative flex size-[108px] touch-none flex-col items-center justify-center gap-0.5 rounded-full text-white select-none outline-none transition-colors duration-150',
                canTalk
                  ? voice.transmitting
                    ? 'bg-gradient-to-br from-emerald-400 to-emerald-600 shadow-[0_0_34px_rgba(16,185,129,0.55)]'
                    : 'bg-gradient-to-br from-zinc-700 to-zinc-800 shadow-inner'
                  : 'cursor-not-allowed bg-zinc-800/70 text-zinc-500',
              )}
              style={{ willChange: 'transform' }}
            >
              {voice.transmitting ? (
                <>
                  <span className="absolute inset-0 animate-ping rounded-full bg-emerald-400/35" aria-hidden style={{ animationDuration: '1.3s' }} />
                  <span className="absolute -inset-2 animate-ping rounded-full border border-emerald-400/30" aria-hidden style={{ animationDuration: '1.9s' }} />
                </>
              ) : null}
              {voice.transmitting ? (
                <AudioLines className="size-7" aria-hidden />
              ) : (
                <Mic className="size-7" aria-hidden />
              )}
              <span className="text-[9.5px] font-bold uppercase tracking-widest">
                {voice.transmitting ? 'Live' : canTalk ? 'Hold' : 'Off'}
              </span>
            </button>

            {/* leave / join */}
            {voice.inRoom ? (
              <Button
                variant="outline"
                size="icon"
                aria-label="Leave voice room"
                onClick={() => {
                  haptic(10)
                  voice.leave()
                }}
                className="size-12 rounded-full border-rose-500/30 bg-rose-500/10 text-rose-300 hover:bg-rose-500/20 hover:text-rose-200"
              >
                <PhoneOff className="size-5" aria-hidden />
              </Button>
            ) : (
              <Button
                size="icon"
                aria-label="Join live voice"
                onClick={() => {
                  haptic(10)
                  voice.join()
                }}
                disabled={voice.status === 'joining'}
                className="size-12 rounded-full bg-emerald-500 text-white shadow-md shadow-emerald-600/30 hover:bg-emerald-500/90 disabled:opacity-60"
              >
                {voice.status === 'joining' ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <AudioLines className="size-5" aria-hidden />
                )}
              </Button>
            )}
          </div>

          <p className="text-center text-[10px] leading-relaxed text-zinc-500">
            Hold to talk · tap to latch · {voice.inRoom ? `${voice.roster.length} on stage` : 'join to open the mic'}
            <span className="mt-0.5 block text-zinc-600">
              Voice is streamed live in 250 ms chunks and never recorded or stored.
            </span>
          </p>
        </div>
      </motion.div>
    </>
  )
}
