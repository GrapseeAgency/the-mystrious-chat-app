// ─────────────────────────────────────────────────────────────
// Pulse — 1:1 call wire contract (R33-a).
//
// Single source of truth for EVERYTHING that crosses the wire for
// WhatsApp/Signal-style voice + video calls:
//   • call:* socket.io payloads relayed by mini-services/pulse-socket
//   • /api/calls REST shapes (CallLog history)
//
// Deliberately NOT in src/lib/types.ts (file ownership: R33-a owns this
// module). Client-only DOM types (MediaStream) are kept OUT of here so the
// socket service can adopt the payload interfaces without lib.dom.
// ─────────────────────────────────────────────────────────────

/** Audio-only vs camera call. */
export type CallKind = 'voice' | 'video'

/** Terminal CallLog states — the CALLER's client writes all rows. */
export type CallStatus = 'completed' | 'missed' | 'declined'

/**
 * Why a ringing call ended without an answer:
 *  • 'timeout' — 30s ring elapsed (server timer) or caller vanished
 *  • 'cancel'  — caller hung up before the callee answered
 *  • 'busy'    — callee was already ringing/in another call
 *  • 'offline' — callee had no live socket when the offer arrived
 */
export type CallCancelReason = 'timeout' | 'cancel' | 'busy' | 'offline'

/** Fields every call:* signaling payload carries. */
export interface CallSignalBase {
  /** Shared correlation id (caller generates, both sides + server key on it). */
  callId: string
  conversationId: string
  /** Sender userId. */
  from: string
  /** Recipient userId. */
  to: string
  kind: CallKind
}

/**
 * caller → callee. Opens the ring. `sdp` is the caller's WebRTC offer
 * (RTCSessionDescription.sdp). Caller profile fields let the incoming UI
 * render before any REST fetch.
 */
export interface CallOfferPayload extends CallSignalBase {
  sdp: string
  callerName: string
  callerColor: string
  callerAvatar: string | null
}

/** callee → caller. `sdp` is the callee's WebRTC answer. */
export interface CallAnswerPayload extends CallSignalBase {
  sdp: string
}

/**
 * Either direction, any time after the offer. Thin candidate triple so the
 * payload stays JSON-safe everywhere (no lib.dom dependency).
 */
export interface CallIcePayload extends CallSignalBase {
  candidate: string
  sdpMid: string | null
  sdpMLineIndex: number | null
}

/** callee → caller: call declined (ringer stops, caller logs 'declined'). */
export type CallRejectPayload = CallSignalBase

/**
 * caller → callee while still ringing (ringer stops, caller logs 'missed'),
 * or server → both parties on the 30s timeout / party disconnect.
 */
export interface CallCancelPayload extends CallSignalBase {
  reason: CallCancelReason
}

/** Either party ends an ACTIVE call (caller logs 'completed' + duration). */
export interface CallHangupPayload extends CallSignalBase {
  /** Seconds the call was connected, reported by whoever hangs up (advisory). */
  durationSec?: number
}

/** Names of every call:* client→server event the socket service accepts. */
export type CallSignalEvent =
  | 'call:offer'
  | 'call:answer'
  | 'call:ice'
  | 'call:reject'
  | 'call:cancel'
  | 'call:hangup'

// ── REST (/api/calls) ────────────────────────────────────────

/** Peer info resolved server-side for history rows. */
export interface CallPeerInfo {
  id: string
  name: string
  username: string | null
  color: string
  avatar: string | null
}

/** One call-history row as returned by GET /api/calls. */
export interface CallLogItem {
  id: string
  conversationId: string
  callerId: string
  calleeId: string
  kind: CallKind
  status: CallStatus
  durationSec: number
  startedAt: string
  /** true when the listing viewer was the caller of this row. */
  outgoing: boolean
  /** The OTHER party relative to the viewer. */
  peer: CallPeerInfo
}

/** POST /api/calls body — viewer is always the caller (single-writer rule). */
export interface CallLogCreateInput {
  userId: string
  conversationId: string
  peerId: string
  kind: CallKind
  status: CallStatus
  durationSec?: number
}

/** Runtime guard for CallKind (server + client both validate wire input). */
export function isCallKind(value: unknown): value is CallKind {
  return value === 'voice' || value === 'video'
}

/** Runtime guard for CallStatus. */
export function isCallStatus(value: unknown): value is CallStatus {
  return value === 'completed' || value === 'missed' || value === 'declined'
}
