/**
 * Pulse Chat — realtime mini service (socket.io)
 * ------------------------------------------------
 * Port: 3003 (hardcoded — reached from Next.js/preview via Caddy `/?XTransformPort=3003`).
 *
 * Responsibilities (NO database access ever):
 *  - Presence tracking   : onlineUsers Map<userId, Set<socketId>>, rooms `user:{userId}`
 *  - Client→client relay : `typing`
 *  - Live voice rooms    : in-memory rosters `voiceRooms` Map + `voice:{conversationId}`
 *                          socket.io rooms; `voice:join|leave|roster|ptt|chunk` relay;
 *                          R48 `voice:transcript` ephemeral live-caption relay
 *  - Stage rooms (R24-c) : Clubhouse hierarchy in `stageRooms` Map + `stage:{conversationId}`
 *                          socket.io rooms; `stage:join|hand|approve|mute|end|leave` → `stage:state`
 *  - Spatial presence    : Gather-style positions in `spaceRooms` Map + `space:{conversationId}`
 *                          socket.io rooms; `space:join|move|leave` → `space:state`
 *  - 1:1 call signaling  : R33-a — `call:offer|answer|ice|reject|cancel|hangup`
 *                          relayed between the two parties' `user:{userId}`
 *                          rooms; in-memory `callSessions` Map with a 30s ring
 *                          timeout (`call:cancel` reason 'timeout') and busy/
 *                          offline guards. NO media flows through here — WebRTC
 *                          peer-to-peer carries audio/video; this is signal only.
 *  - HTTP relay API      : POST /notify (message:new|message:deleted|message:read), POST /typing
 *  - Privacy gating (R47) : typing relay + presence snapshots are FILTERED by
 *                           per-user privacy flags fetched from Next's
 *                           /api/internal/privacy (shared key, 30s TTL cache,
 *                           fail-open). Still NO database access here.
 *  - Health probe        : GET / (or anything unknown) → { ok:true, service:'pulse-socket' }
 *
 * Routing note (verified against engine.io@6.6.9 source): because our socket.io
 * path is '/', engine.io's attach() interceptor matches EVERY url and it would
 * swallow our JSON endpoints (and fall back to pre-existing listeners ONLY on
 * mismatch). We therefore attach socket.io normally, capture its registered
 * 'request'/'upgrade' handlers, then reinstall ONE unified dispatcher that:
 *   • hands engine-protocol requests (query contains EIO=..&transport=..)
 *     straight back to the captured engine handlers, untouched;
 *   • serves /notify, /typing, OPTIONS and the health probe itself.
 */
import { createServer } from 'node:http'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { Server } from 'socket.io'
import type { Socket } from 'socket.io'

const PORT = 3003

// ---------------------------------------------------------------------------
// Bootstrap http server + socket.io (options copied from examples/websocket)
// ---------------------------------------------------------------------------
const httpServer = createServer()

const io = new Server(httpServer, {
  // DO NOT change the path, it is used by Caddy to forward the request to the correct port
  path: '/',
  cors: {
    origin: '*',
    methods: ['GET', 'POST'],
  },
  pingTimeout: 60000,
  pingInterval: 25000,
  serveClient: false, // client bundle is served by Next.js, keep listener list minimal
})

// Reclaim the unified request/upgrade dispatchers (see file header).
const ENGINE_REQUEST_HANDLERS = httpServer.listeners('request').slice()
const ENGINE_UPGRADE_HANDLERS = httpServer.listeners('upgrade').slice()
httpServer.removeAllListeners('request')
httpServer.removeAllListeners('upgrade')

// ---------------------------------------------------------------------------
// Presence model
// ---------------------------------------------------------------------------
/** userId -> connected socketIds of that user */
const onlineUsers = new Map<string, Set<string>>()
/** socketId -> userId (reverse index) */
const socketUser = new Map<string, string>()

const roomOf = (userId: string) => `user:${userId}`

// R47 — superseded by visibleOnlineIds() (presence-hiding); kept for the
// health probe's raw online count if ever needed.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const presenceSnapshot = (): string[] => Array.from(onlineUsers.keys()).sort()

function addPresence(userId: string, socketId: string): void {
  let sockets = onlineUsers.get(userId)
  if (!sockets) {
    sockets = new Set<string>()
    onlineUsers.set(userId, sockets)
  }
  sockets.add(socketId)
}

/**
 * Removes a socket from the presence maps.
 * @returns true when this was the LAST socket of that user (user went offline).
 */
function dropPresence(socketId: string): boolean {
  const userId = socketUser.get(socketId)
  if (!userId) return false
  socketUser.delete(socketId)
  const sockets = onlineUsers.get(userId)
  if (!sockets) return false
  sockets.delete(socketId)
  if (sockets.size === 0) {
    onlineUsers.delete(userId)
    return true // last socket closed -> user fully offline
  }
  return false
}

// ---------------------------------------------------------------------------
// R47 — privacy-flag cache (sourced from the Next.js API; NO db access here,
// same pattern as the scheduled-send dispatcher: HTTP + shared key).
//   presenceVisible ← User prefs lastSeenVisible (Telegram couples last seen
//                     AND online under one toggle)
//   typingVisible   ← User prefs typingVisible (R47)
// Consumers FAIL OPEN: a missing/stale flag means "visible", so an API hiccup
// never blinds the whole mesh. Flags refresh lazily with a 30s TTL.
// ---------------------------------------------------------------------------
const NEXT_API_BASE = process.env.PULSE_ORIGIN ?? 'http://localhost:3000'
const PRIVACY_KEY = process.env.CRON_SECRET ?? 'pulse-dispatch-key'
const PRIVACY_TTL_MS = 30_000

type PrivacyFlags = { typingVisible: boolean; presenceVisible: boolean }
const privacyCache = new Map<string, { flags: PrivacyFlags; fetchedAt: number }>()
let privacyInflight: Promise<void> | null = null

async function fetchPrivacyFlags(ids: string[]): Promise<void> {
  if (ids.length === 0) return
  const stale = (id: string): boolean => {
    const hit = privacyCache.get(id)
    return !hit || Date.now() - hit.fetchedAt > PRIVACY_TTL_MS
  }
  let need = ids.filter(stale)
  if (need.length === 0) return
  if (privacyInflight) {
    await privacyInflight.catch(() => {})
    need = ids.filter(stale)
    if (need.length === 0) return
  }
  privacyInflight = (async () => {
    try {
      const res = await fetch(
        `${NEXT_API_BASE}/api/internal/privacy?ids=${encodeURIComponent(need.slice(0, 500).join(','))}`,
        { headers: { 'x-pulse-key': PRIVACY_KEY }, signal: AbortSignal.timeout(5000) },
      )
      const body = (await res.json().catch(() => null)) as {
        flags?: Record<string, { typingVisible?: unknown; presenceVisible?: unknown }>
      } | null
      if (res.ok && body && body.flags) {
        const now = Date.now()
        for (const [id, raw] of Object.entries(body.flags)) {
          if (
            raw &&
            typeof raw.typingVisible === 'boolean' &&
            typeof raw.presenceVisible === 'boolean'
          ) {
            privacyCache.set(id, {
              flags: { typingVisible: raw.typingVisible, presenceVisible: raw.presenceVisible },
              fetchedAt: now,
            })
          }
        }
      }
    } catch {
      // app may be mid-restart — fail OPEN until the next poll
    } finally {
      privacyInflight = null
    }
  })()
  await privacyInflight
}

function privacyOf(userId: string): PrivacyFlags {
  const hit = privacyCache.get(userId)
  return hit?.flags ?? { typingVisible: true, presenceVisible: true }
}

/** Presence snapshot filtered to users whose "Last seen & online" toggle allows it. */
async function visibleOnlineIds(): Promise<string[]> {
  await fetchPrivacyFlags(Array.from(onlineUsers.keys()))
  return Array.from(onlineUsers.keys())
    .filter((id) => privacyOf(id).presenceVisible)
    .sort()
}

/** Typing relay gate — drop when the sender hides typing indicators (R47). */
async function typingAllowed(userId: string): Promise<boolean> {
  await fetchPrivacyFlags([userId])
  return privacyOf(userId).typingVisible
}

// ---------------------------------------------------------------------------
// Live voice rooms (R21-b) — pure in-memory, ephemeral by design.
// ---------------------------------------------------------------------------
interface VoicePeer {
  userId: string
  name: string
  username: string | null
  color: string
  socketId: string
  joinedAt: number
}

/** conversationId -> (userId -> peer) — a socket.io room `voice:{conversationId}` backs each entry */
const voiceRooms = new Map<string, Map<string, VoicePeer>>()
/** socketId -> conversationId this socket's mic is attached to (max ONE voice room per socket) */
const socketVoiceRoom = new Map<string, string>()

const voiceRoomName = (conversationId: string) => `voice:${conversationId}`

function voiceRosterSnapshot(conversationId: string): VoicePeer[] {
  const room = voiceRooms.get(conversationId)
  if (!room) return []
  return Array.from(room.values()).sort((a, b) => a.joinedAt - b.joinedAt)
}

/** Public-safe roster payload (never leaks socketIds). */
function rosterPayload(conversationId: string) {
  return {
    conversationId,
    peers: voiceRosterSnapshot(conversationId).map((p) => ({
      id: p.userId,
      name: p.name,
      username: p.username,
      color: p.color,
    })),
  }
}

function broadcastVoiceRoster(conversationId: string): void {
  io.to(voiceRoomName(conversationId)).emit('voice:roster', rosterPayload(conversationId))
}

/**
 * Removes a socket from its voice room (explicit leave or disconnect).
 * Broadcasts the fresh roster to the remaining peers.
 */
function leaveVoiceRoom(socketId: string, reason: string): void {
  const conversationId = socketVoiceRoom.get(socketId)
  if (!conversationId) return
  socketVoiceRoom.delete(socketId)
  const room = voiceRooms.get(conversationId)
  if (!room) return

  let removedUserId: string | null = null
  for (const [userId, peer] of room) {
    if (peer.socketId === socketId) {
      room.delete(userId)
      removedUserId = userId
      break
    }
  }
  if (room.size === 0) {
    voiceRooms.delete(conversationId)
  } else {
    broadcastVoiceRoster(conversationId)
  }
  if (removedUserId) {
    // everyone still transmitting from the departed socket stops glowing
    io.to(voiceRoomName(conversationId)).emit('voice:ptt', {
      conversationId,
      userId: removedUserId,
      on: false,
    })
  }
  console.log(`[voice] leave user=${removedUserId ?? '?'} conv=${conversationId} sock=${socketId} reason=${reason} peers=${room.size}`)
}

/**
 * Stage-room enforcement hook (R24-c): force-removes ONE user's voice peer from
 * the conversation's voice roster (e.g. the host muted them from the stage).
 * The roster map stays the single source of truth, so the identity gates on
 * `voice:ptt` / `voice:chunk` immediately reject the removed user — a muted
 * speaker can never keep transmitting through a laggy/malicious client.
 */
function removeVoicePeerByUser(conversationId: string, userId: string, reason: string): void {
  const room = voiceRooms.get(conversationId)
  const peer = room?.get(userId)
  if (!room || !peer) return
  room.delete(userId)
  if (room.size === 0) {
    voiceRooms.delete(conversationId)
  } else {
    io.to(voiceRoomName(conversationId)).emit('voice:roster', rosterPayload(conversationId))
  }
  // their socket no longer holds a voice seat; also stop any glowing ring
  if (socketVoiceRoom.get(peer.socketId) === conversationId) socketVoiceRoom.delete(peer.socketId)
  io.to(voiceRoomName(conversationId)).emit('voice:ptt', { conversationId, userId, on: false })
  console.log(`[voice] force-remove user=${userId} conv=${conversationId} reason=${reason}`)
}

// ---------------------------------------------------------------------------
// Stage rooms (R24-c) — Clubhouse-style hierarchy, pure in-memory, ephemeral.
// host → speakers → listeners, plus a FIFO raised-hands queue. No DB access.
// TRUST MODEL (honest): roles are claimed by clients; the host seat is granted
// to the first joiner of a fresh room and re-claimable via `asHost` ONLY while
// the seat is empty. All rosters are identity-gated to the registering socket.
// ---------------------------------------------------------------------------
interface StagePerson {
  userId: string
  name: string
  username: string | null
  color: string
  socketId: string
  joinedAt: number
}

interface StageRoom {
  hostId: string | null
  speakers: Map<string, StagePerson>
  /** userId -> raised hand (FIFO by raisedAt) */
  hands: Map<string, { user: StagePerson; raisedAt: number }>
  listeners: Map<string, StagePerson>
}

/** conversationId -> stage state — a socket.io room `stage:{conversationId}` backs each entry */
const stageRooms = new Map<string, StageRoom>()
/** socketId -> conversationId of the stage this socket occupies (max ONE stage per socket) */
const socketStageRoom = new Map<string, string>()

const stageRoomName = (conversationId: string) => `stage:${conversationId}`

/** The host is always a speaker; if they vanished from every roster the seat is empty. */
function stageLiveHost(room: StageRoom): StagePerson | null {
  if (!room.hostId) return null
  return room.speakers.get(room.hostId) ?? null
}

/**
 * Public-safe stage payload. Contract fields: host, speakers, hands,
 * listenerCount. `listeners` is an ADDITIVE extra (names row in the sheet UI).
 */
function stageStatePayload(conversationId: string) {
  const room = stageRooms.get(conversationId)
  if (!room) return null
  const host = stageLiveHost(room)
  const speakers = Array.from(room.speakers.values()).sort((a, b) => {
    if (host && a.userId === host.userId) return -1
    if (host && b.userId === host.userId) return 1
    return a.joinedAt - b.joinedAt
  })
  const hands = Array.from(room.hands.values())
    .sort((a, b) => a.raisedAt - b.raisedAt)
    .map((h) => h.user)
  const listeners = Array.from(room.listeners.values()).sort((a, b) => a.joinedAt - b.joinedAt)
  return {
    conversationId,
    host: host ? { id: host.userId, name: host.name, color: host.color } : null,
    speakers: speakers.map((s) => ({ id: s.userId, name: s.name, color: s.color })),
    hands: hands.map((h) => ({ id: h.userId, name: h.name, color: h.color })),
    listeners: listeners.map((l) => ({ id: l.userId, name: l.name, color: l.color })),
    listenerCount: room.listeners.size,
  }
}

function broadcastStageState(conversationId: string): void {
  const payload = stageStatePayload(conversationId)
  if (!payload) return
  io.to(stageRoomName(conversationId)).emit('stage:state', payload)
}

/** Removes a user from EVERY roster of one stage room (host seat empties, no auto-promotion). */
function removeStageUser(room: StageRoom, userId: string): boolean {
  let present = false
  if (room.speakers.delete(userId)) present = true
  if (room.hands.delete(userId)) present = true
  if (room.listeners.delete(userId)) present = true
  if (room.hostId === userId) room.hostId = null
  return present
}

/**
 * Removes a socket from its stage room (explicit leave or disconnect).
 * Mirrors `leaveVoiceRoom`: the server-side map is authoritative.
 */
function leaveStageRoom(socketId: string, reason: string): void {
  const conversationId = socketStageRoom.get(socketId)
  if (!conversationId) return
  socketStageRoom.delete(socketId)
  const room = stageRooms.get(conversationId)
  if (!room) return

  // find the roster entry owned by THIS socket (rosters are keyed by userId)
  let removedUserId: string | null = null
  for (const map of [room.speakers, room.listeners]) {
    for (const [userId, person] of map) {
      if (person.socketId === socketId) {
        removedUserId = userId
        break
      }
    }
    if (removedUserId) break
  }
  if (!removedUserId) {
    for (const [userId, hand] of room.hands) {
      if (hand.user.socketId === socketId) {
        removedUserId = userId
        break
      }
    }
  }
  if (removedUserId) removeStageUser(room, removedUserId)

  const empty = room.speakers.size === 0 && room.hands.size === 0 && room.listeners.size === 0
  if (empty) {
    stageRooms.delete(conversationId)
  } else {
    broadcastStageState(conversationId)
  }
  console.log(`[stage] leave user=${removedUserId ?? '?'} conv=${conversationId} sock=${socketId} reason=${reason}`)
}

// ---------------------------------------------------------------------------
// Spatial presence (R24-c) — Gather.town-style office, pure in-memory.
// Positions are normalized 0..1; movement is throttled + clamped server-side.
// ---------------------------------------------------------------------------
interface SpacePlayer {
  userId: string
  name: string
  username: string | null
  color: string
  socketId: string
  x: number // normalized 0..1
  y: number // normalized 0..1
  lastMoveAt: number
}

/** conversationId -> (userId -> player) — a socket.io room `space:{conversationId}` backs each entry */
const spaceRooms = new Map<string, Map<string, SpacePlayer>>()
/** socketId -> conversationId of the space this socket occupies (max ONE space per socket) */
const socketSpaceRoom = new Map<string, string>()

const spaceRoomName = (conversationId: string) => `space:${conversationId}`
const SPACE_MOVE_THROTTLE_MS = 80
const SPACE_IDLE_PRUNE_MS = 5 * 60_000

const clamp01 = (value: number): number => Math.max(0, Math.min(1, value))

/** Drops players idle for over 5 minutes (no accepted move) and empty rooms. */
function pruneSpaceRoom(conversationId: string): void {
  const room = spaceRooms.get(conversationId)
  if (!room) return
  const now = Date.now()
  for (const [userId, player] of room) {
    if (now - player.lastMoveAt > SPACE_IDLE_PRUNE_MS) room.delete(userId)
  }
  if (room.size === 0) spaceRooms.delete(conversationId)
}

function spaceStatePayload(conversationId: string): { conversationId: string; players: Array<{ id: string; name: string; color: string; x: number; y: number }> } {
  const room = spaceRooms.get(conversationId)
  const round4 = (value: number): number => Math.round(value * 10_000) / 10_000
  const players = room
    ? Array.from(room.values())
        .sort((a, b) => a.userId.localeCompare(b.userId))
        .map((p) => ({ id: p.userId, name: p.name, color: p.color, x: round4(p.x), y: round4(p.y) }))
    : []
  return { conversationId, players }
}

function broadcastSpaceState(conversationId: string): void {
  pruneSpaceRoom(conversationId)
  io.to(spaceRoomName(conversationId)).emit('space:state', spaceStatePayload(conversationId))
}

/** Removes a socket from its space room (explicit leave or disconnect). */
function leaveSpaceRoom(socketId: string, reason: string): void {
  const conversationId = socketSpaceRoom.get(socketId)
  if (!conversationId) return
  socketSpaceRoom.delete(socketId)
  const room = spaceRooms.get(conversationId)
  if (!room) return

  let removedUserId: string | null = null
  for (const [userId, player] of room) {
    if (player.socketId === socketId) {
      room.delete(userId)
      removedUserId = userId
      break
    }
  }
  if (room.size === 0) spaceRooms.delete(conversationId)
  if (removedUserId) broadcastSpaceState(conversationId)
  console.log(`[space] leave user=${removedUserId ?? '?'} conv=${conversationId} sock=${socketId} reason=${reason}`)
}

// ---------------------------------------------------------------------------
// 1:1 call signaling (R33-a) — pure in-memory, signal ONLY (no media, no DB).
// Wire contract lives in src/lib/call-types.ts (client) — mirrored here.
// A call session is born on `call:offer` and dies on answer+hangup, reject,
// cancel, the 30s ring timeout, or either party disconnecting.
// ---------------------------------------------------------------------------
interface CallSession {
  callId: string
  conversationId: string
  callerId: string
  calleeId: string
  kind: 'voice' | 'video'
  /** Ring phase (offer sent, no answer yet) when false. */
  answered: boolean
  createdAt: number
  timer: ReturnType<typeof setTimeout>
}

const CALL_RING_TIMEOUT_MS = 30_000
const CALL_SDP_MAX_CHARS = 64 * 1024
const CALL_ICE_MAX_CHARS = 4 * 1024

/** callId -> live session */
const callSessions = new Map<string, CallSession>()
/** userId -> callId the user is currently ringing in / talking on (one per user) */
const callByUser = new Map<string, string>()

function callKindOf(value: unknown): 'voice' | 'video' | null {
  return value === 'voice' || value === 'video' ? value : null
}

/** Session torn down; survivor(s) notified. reason picks the wire event. */
function dropCallSession(
  callId: string,
  reason: 'timeout' | 'cancel' | 'hangup' | 'error',
  endedByUserId: string | null,
): void {
  const session = callSessions.get(callId)
  if (!session) return
  clearTimeout(session.timer)
  callSessions.delete(callId)
  if (callByUser.get(session.callerId) === callId) callByUser.delete(session.callerId)
  if (callByUser.get(session.calleeId) === callId) callByUser.delete(session.calleeId)

  const base = {
    callId,
    conversationId: session.conversationId,
    kind: session.kind,
  }
  if (session.answered && (reason === 'hangup' || reason === 'error')) {
    // Active call torn down — the surviving party stops the media UI.
    const survivorId = endedByUserId === session.callerId ? session.calleeId : session.callerId
    io.to(roomOf(survivorId)).emit('call:hangup', {
      ...base,
      from: endedByUserId ?? '',
      to: survivorId,
      durationSec: Math.round((Date.now() - session.createdAt) / 1000),
    })
  } else if (!session.answered) {
    // Ring torn down — BOTH sides drop the ringer (terminal before media).
    io.to(roomOf(session.callerId)).emit('call:cancel', {
      ...base,
      from: endedByUserId ?? session.calleeId,
      to: session.callerId,
      reason: reason === 'timeout' ? 'timeout' : 'cancel',
    })
    io.to(roomOf(session.calleeId)).emit('call:cancel', {
      ...base,
      from: endedByUserId ?? session.callerId,
      to: session.calleeId,
      reason: reason === 'timeout' ? 'timeout' : 'cancel',
    })
  }
  console.log(
    `[call] drop call=${callId} caller=${session.callerId} callee=${session.calleeId} answered=${session.answered} reason=${reason}`,
  )
}

// ---------------------------------------------------------------------------
// Payload helpers
// ---------------------------------------------------------------------------
function asTrimmedString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * recipients: unique non-empty strings; defensively excludes the sender id.
 */
function sanitizeRecipients(raw: unknown, excludeUserId?: string): string[] {
  if (!Array.isArray(raw)) return []
  const out = new Set<string>()
  for (const item of raw) {
    const id = asTrimmedString(item)
    if (id && id !== excludeUserId) out.add(id)
  }
  return Array.from(out)
}

function readJsonBody(req: IncomingMessage, maxBytes = 64 * 1024): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let size = 0
    req.on('data', (chunk: Buffer) => {
      size += chunk.length
      if (size > maxBytes) {
        reject(new Error('payload too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8')
        resolve(text ? JSON.parse(text) : null)
      } catch (err) {
        reject(err instanceof Error ? err : new Error('invalid JSON'))
      }
    })
    req.on('error', reject)
  })
}

// ---------------------------------------------------------------------------
// Internal HTTP relay endpoints (called by Next.js API routes)
// ---------------------------------------------------------------------------
const NOTIFY_EVENTS = new Set(['message:new', 'message:deleted', 'message:read', 'message:react', 'message:edited', 'message:pinned', 'message:viewed', 'poll:voted', 'link:preview', 'translation:added', 'conversation:updated'])

type NotifyBody = { event?: unknown; recipients?: unknown; payload?: unknown }

async function handleNotify(req: IncomingMessage, res: ServerResponse): Promise<void> {
  let body: NotifyBody
  try {
    body = (await readJsonBody(req)) as NotifyBody
  } catch {
    sendJson(res, 400, { ok: false, error: 'invalid JSON body' })
    return
  }

  const event = asTrimmedString(body?.event)
  if (!NOTIFY_EVENTS.has(event)) {
    sendJson(res, 400, { ok: false, error: `event must be one of ${Array.from(NOTIFY_EVENTS).join(', ')}` })
    return
  }
  if (!Array.isArray(body?.recipients)) {
    sendJson(res, 400, { ok: false, error: 'recipients must be an array of userIds' })
    return
  }

  const recipients = sanitizeRecipients(body.recipients)
  let delivered = 0
  for (const recipient of recipients) {
    io.to(roomOf(recipient)).emit(event, body.payload ?? null)
    delivered++
  }
  console.log(`[http] notify event=${event} delivered=${delivered}/${recipients.length}`)
  sendJson(res, 200, { ok: true, delivered })
}

type TypingBody = {
  recipients?: unknown
  conversationId?: unknown
  userId?: unknown
  userName?: unknown
  isTyping?: unknown
}

async function handleTypingHttp(req: IncomingMessage, res: ServerResponse): Promise<void> {
  let body: TypingBody
  try {
    body = (await readJsonBody(req)) as TypingBody
  } catch {
    sendJson(res, 400, { ok: false, error: 'invalid JSON body' })
    return
  }
  if (typeof body?.recipients === 'undefined') {
    sendJson(res, 400, { ok: false, error: 'recipients must be an array of userIds' })
    return
  }

  const conversationId = asTrimmedString(body.conversationId)
  const userId = asTrimmedString(body.userId)
  if (!conversationId || !userId) {
    sendJson(res, 400, { ok: false, error: 'conversationId and userId are required' })
    return
  }

  // R47 — typing-indicator hiding (same gate as the ws relay path).
  if (!(await typingAllowed(userId))) {
    sendJson(res, 200, { ok: true, delivered: 0, hidden: true })
    return
  }

  const payload = {
    conversationId,
    userId,
    userName: typeof body.userName === 'string' ? body.userName.slice(0, 32) : '',
    isTyping: body.isTyping === true,
  }
  const recipients = sanitizeRecipients(body.recipients, userId)

  let delivered = 0
  for (const recipient of recipients) {
    io.to(roomOf(recipient)).emit('typing', payload)
    delivered++
  }
  console.log(`[http] typing user=${userId} conv=${conversationId} isTyping=${payload.isTyping} delivered=${delivered}`)
  sendJson(res, 200, { ok: true, delivered })
}

// ---------------------------------------------------------------------------
// Unified HTTP router (single 'request' listener — see file header)
// ---------------------------------------------------------------------------
const BASE_URL = `http://localhost:${PORT}`

function isEngineIoRequest(searchParams: URLSearchParams): boolean {
  // Engine.IO v4 handshake/poll requests always carry both params.
  return searchParams.has('EIO') && searchParams.has('transport')
}

function applyCorsHeaders(res: ServerResponse): void {
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'POST, GET, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  if (res.writableEnded || res.headersSent) return
  applyCorsHeaders(res)
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(body))
}

async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
  let pathname = '/'
  let searchParams = new URLSearchParams()
  try {
    const url = new URL(req.url ?? '/', BASE_URL)
    pathname = url.pathname
    searchParams = url.searchParams
  } catch {
    // unparsable url -> treat as root below
  }

  // 1. socket.io engine traffic: give it back to engine.io untouched.
  if (isEngineIoRequest(searchParams)) {
    for (const handler of ENGINE_REQUEST_HANDLERS) {
      handler.call(httpServer, req, res)
    }
    return
  }

  // 2. CORS preflight for our JSON endpoints.
  if (req.method === 'OPTIONS') {
    applyCorsHeaders(res)
    res.writeHead(204)
    res.end()
    return
  }

  // 3. relay endpoints (Next.js API routes call these internally)
  if (req.method === 'POST' && pathname === '/notify') {
    await handleNotify(req, res)
    return
  }
  if (req.method === 'POST' && pathname === '/typing') {
    await handleTypingHttp(req, res)
    return
  }

  // 4. everything else (incl GET /) -> health probe friendly identity JSON
  sendJson(res, 200, {
    ok: true,
    service: 'pulse-socket',
    port: PORT,
    online: onlineUsers.size,
    voiceRooms: voiceRooms.size,
    stageRooms: stageRooms.size,
    spaceRooms: spaceRooms.size,
    callSessions: callSessions.size,
    uptimeSec: Math.round(process.uptime()),
  })
}

httpServer.on('request', (req, res) => {
  route(req, res).catch((err) => {
    console.error('[http] handler error:', err instanceof Error ? err.message : err)
    sendJson(res, 500, { ok: false, error: 'internal error' })
  })
})

httpServer.on('upgrade', (req, socket, head) => {
  let engineRequest = false
  try {
    engineRequest = isEngineIoRequest(new URL(req.url ?? '/', BASE_URL).searchParams)
  } catch {
    engineRequest = false
  }
  if (!engineRequest) {
    socket.destroy()
    return
  }
  for (const handler of ENGINE_UPGRADE_HANDLERS) {
    handler.call(httpServer, req, socket, head)
  }
})

// ---------------------------------------------------------------------------
// Socket lifecycle & realtime events
// ---------------------------------------------------------------------------
io.on('connection', (socket: Socket) => {
  console.log(`[ws] connect sock=${socket.id} clients=${io.engine.clientsCount}`)

  /** join { userId } — register presence, enter personal room, ack + snapshot broadcast */
  socket.on('join', async (raw: unknown) => {
    const userId = asTrimmedString((raw as { userId?: unknown })?.userId ?? null)
    if (!userId || userId.length > 64) {
      console.warn(`[ws] join rejected sock=${socket.id} (bad userId)`)
      return
    }

    // Defensive re-join: forget previous mapping of THIS socket first.
    if (socketUser.has(socket.id)) dropPresence(socket.id)

    socketUser.set(socket.id, userId)
    addPresence(userId, socket.id)
    void socket.join(roomOf(userId))

    // R47 — presence-hiding: the snapshot excludes users whose "Last seen &
    // online" toggle is off (their own client sees a filtered list too).
    const snapshot = await visibleOnlineIds()
    socket.emit('joined', { onlineUserIds: snapshot })
    io.emit('presence:snapshot', { onlineUserIds: snapshot })
    console.log(`[ws] join user=${userId} sock=${socket.id} online=${onlineUsers.size} visible=${snapshot.length}`)
  })

  /**
   * typing { recipients, conversationId, userId, userName, isTyping }
   * Direct client→client relay; sender's own ids are excluded defensively.
   */
  socket.on('typing', async (raw: unknown) => {
    const data = (raw ?? {}) as TypingBody
    const conversationId = asTrimmedString(data.conversationId)
    const userId = asTrimmedString(data.userId)
    if (!conversationId || !userId) return

    // R47 — typing-indicator hiding: the sender's own privacy toggle ends the
    // relay here (server-side, not a client courtesy).
    if (!(await typingAllowed(userId))) return

    const payload = {
      conversationId,
      userId,
      userName: typeof data.userName === 'string' ? data.userName.slice(0, 32) : '',
      isTyping: data.isTyping === true,
    }
    const recipients = sanitizeRecipients(data.recipients, userId)
    for (const recipient of recipients) {
      io.to(roomOf(recipient)).emit('typing', payload)
    }
    if (recipients.length > 0) {
      console.log(`[ws] typing relay user=${userId} conv=${conversationId} isTyping=${payload.isTyping} targets=${recipients.length}`)
    }
  })

  // ── Live voice room events (R21-b) ─────────────────────────

  /**
   * voice:join { conversationId, user: { id, name, username, color } }
   * Joins the socket to room `voice:{conversationId}`, upserts the in-memory
   * roster and broadcasts `voice:roster` to every peer in that voice room.
   */
  socket.on('voice:join', (raw: unknown) => {
    const data = (raw ?? {}) as {
      conversationId?: unknown
      user?: { id?: unknown; name?: unknown; username?: unknown; color?: unknown }
    }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.user?.id).slice(0, 64)
    if (!conversationId || !userId) {
      console.warn(`[voice] join rejected sock=${socket.id} (bad conversationId/userId)`)
      return
    }

    // One voice room per socket: silently drop any previous membership first.
    leaveVoiceRoom(socket.id, 'switch')

    const peer: VoicePeer = {
      userId,
      name: asTrimmedString(data.user?.name).slice(0, 64) || 'Someone',
      username:
        typeof data.user?.username === 'string' && data.user.username.trim().length > 0
          ? data.user.username.trim().slice(0, 32)
          : null,
      color: asTrimmedString(data.user?.color).slice(0, 24) || 'emerald',
      socketId: socket.id,
      joinedAt: Date.now(),
    }

    let room = voiceRooms.get(conversationId)
    if (!room) {
      room = new Map<string, VoicePeer>()
      voiceRooms.set(conversationId, room)
    }
    room.set(userId, peer) // last session wins on duplicate userId
    socketVoiceRoom.set(socket.id, conversationId)
    void socket.join(voiceRoomName(conversationId))

    broadcastVoiceRoster(conversationId)
    console.log(`[voice] join user=${userId} conv=${conversationId} sock=${socket.id} peers=${room.size}`)
  })

  /** voice:leave { conversationId? } — explicit exit (conversationId optional; map is the truth). */
  socket.on('voice:leave', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown }
    const claimed = asTrimmedString(data.conversationId).slice(0, 128)
    const actual = socketVoiceRoom.get(socket.id)
    // Defensively ignore mismatched claims — the server-side map is authoritative.
    if (actual && claimed && claimed !== actual) return
    leaveVoiceRoom(socket.id, 'explicit')
  })

  /**
   * voice:ptt { conversationId, userId, on } — push-to-talk state relay.
   * Broadcast to the WHOLE voice room (sender included) so self rings glow too.
   */
  socket.on('voice:ptt', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; userId?: unknown; on?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.userId).slice(0, 64)
    if (!conversationId || !userId) return
    // Only peers actually in this voice room may flip its transmit state.
    const room = voiceRooms.get(conversationId)
    const peer = room?.get(userId)
    if (!peer || peer.socketId !== socket.id) return
    io.to(voiceRoomName(conversationId)).emit('voice:ptt', {
      conversationId,
      userId,
      on: data.on === true,
    })
  })

  /**
   * voice:chunk { conversationId, userId, seq, data(base64 PCM) } — audio relay.
   * Broadcast to the voice room EXCEPT the sender. Size-capped, identity-checked.
   */
  const MAX_VOICE_CHUNK_CHARS = 96 * 1024
  socket.on('voice:chunk', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; userId?: unknown; seq?: unknown; data?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.userId).slice(0, 64)
    const seq = typeof data.seq === 'number' && Number.isFinite(data.seq) ? Math.floor(data.seq) : -1
    if (!conversationId || !userId || seq < 0) return
    if (typeof data.data !== 'string' || data.data.length === 0 || data.data.length > MAX_VOICE_CHUNK_CHARS) return
    // Identity gate: a socket may only stream chunks AS the peer it registered.
    const room = voiceRooms.get(conversationId)
    const peer = room?.get(userId)
    if (!peer || peer.socketId !== socket.id) return
    socket.to(voiceRoomName(conversationId)).emit('voice:chunk', {
      conversationId,
      userId,
      seq,
      data: data.data,
    })
  })

  /**
   * voice:transcript (R48) { conversationId, userId, name, color, text } —
   * live-caption relay. The SPEAKER's client runs its own mic through the
   * real ASR service (/api/voice/transcribe, participant-gated) and emits
   * the resulting text here; the relay broadcasts it to the WHOLE voice
   * room (sender included) so every viewer sees the same caption strip.
   * Identity-gated to the registered peer + rate-limited (min 700 ms
   * between captions per socket) + length-capped. Ephemeral: never stored.
   */
  const MAX_TRANSCRIPT_CHARS = 280
  const TRANSCRIPT_MIN_INTERVAL_MS = 700
  const lastTranscriptAt = new Map<string, number>()
  socket.on('voice:transcript', (raw: unknown) => {
    const data = (raw ?? {}) as {
      conversationId?: unknown
      userId?: unknown
      text?: unknown
    }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.userId).slice(0, 64)
    const text = asTrimmedString(data.text).slice(0, MAX_TRANSCRIPT_CHARS)
    if (!conversationId || !userId || text.length === 0) return
    // Identity gate: a socket may only caption AS the peer it registered.
    const room = voiceRooms.get(conversationId)
    const peer = room?.get(userId)
    if (!peer || peer.socketId !== socket.id) return
    const now = Date.now()
    const last = lastTranscriptAt.get(socket.id) ?? 0
    if (now - last < TRANSCRIPT_MIN_INTERVAL_MS) return
    lastTranscriptAt.set(socket.id, now)
    io.to(voiceRoomName(conversationId)).emit('voice:transcript', {
      conversationId,
      userId,
      name: peer.name,
      color: peer.color,
      text,
      at: now,
    })
  })

  // ── Stage room events (R24-c) ─────────────────────────────

  /**
   * stage:join { conversationId, user: { id, name, username, color }, asHost? }
   * Joins the socket to room `stage:{conversationId}` and upserts the roster.
   * First joiner of a FRESH room becomes host; `asHost` claims are honored ONLY
   * while the host seat is empty (trust model documented in the header above).
   * Re-joins (resync) keep the role the user already had.
   */
  socket.on('stage:join', (raw: unknown) => {
    const data = (raw ?? {}) as {
      conversationId?: unknown
      asHost?: unknown
      user?: { id?: unknown; name?: unknown; username?: unknown; color?: unknown }
    }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.user?.id).slice(0, 64)
    if (!conversationId || !userId) {
      console.warn(`[stage] join rejected sock=${socket.id} (bad conversationId/userId)`)
      return
    }

    // One stage per socket: silently drop any previous stage membership first.
    leaveStageRoom(socket.id, 'switch')

    let room = stageRooms.get(conversationId)
    if (!room) {
      room = { hostId: null, speakers: new Map(), hands: new Map(), listeners: new Map() }
      stageRooms.set(conversationId, room)
    }

    // Defensive: if the recorded host vanished from every roster, free the seat.
    if (
      room.hostId &&
      !room.speakers.has(room.hostId) &&
      !room.hands.has(room.hostId) &&
      !room.listeners.has(room.hostId)
    ) {
      room.hostId = null
    }

    const wasHost = room.hostId === userId
    const prevRole = room.speakers.has(userId)
      ? 'speaker'
      : room.listeners.has(userId)
        ? 'listener'
        : room.hands.has(userId)
          ? 'hand'
          : null
    const prevRaisedAt = prevRole === 'hand' ? (room.hands.get(userId)?.raisedAt ?? Date.now()) : 0
    room.speakers.delete(userId)
    room.hands.delete(userId)
    room.listeners.delete(userId)

    const person: StagePerson = {
      userId,
      name: asTrimmedString(data.user?.name).slice(0, 64) || 'Someone',
      username:
        typeof data.user?.username === 'string' && data.user.username.trim().length > 0
          ? data.user.username.trim().slice(0, 32)
          : null,
      color: asTrimmedString(data.user?.color).slice(0, 24) || 'emerald',
      socketId: socket.id,
      joinedAt: Date.now(),
    }

    const freshRoom = room.speakers.size === 0 && room.hands.size === 0 && room.listeners.size === 0
    let role: 'host' | 'speaker' | 'hand' | 'listener'
    if (wasHost || (freshRoom && room.hostId === null) || (data.asHost === true && room.hostId === null)) {
      room.hostId = userId
      room.speakers.set(userId, person)
      role = 'host'
    } else if (prevRole === 'speaker') {
      room.speakers.set(userId, person) // resync keeps the speaker seat
      role = 'speaker'
    } else if (prevRole === 'hand') {
      room.hands.set(userId, { user: person, raisedAt: prevRaisedAt }) // resync keeps the raised hand
      role = 'hand'
    } else {
      room.listeners.set(userId, person)
      role = 'listener'
    }

    socketStageRoom.set(socket.id, conversationId)
    void socket.join(stageRoomName(conversationId))

    broadcastStageState(conversationId)
    console.log(`[stage] join user=${userId} conv=${conversationId} sock=${socket.id} role=${role}`)
  })

  /**
   * stage:hand { conversationId, user: { id }, raised } — ONLY listeners may
   * toggle their own hand, and only from the socket that registered them.
   */
  socket.on('stage:hand', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; user?: { id?: unknown }; raised?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.user?.id).slice(0, 64)
    if (!conversationId || !userId) return
    const room = stageRooms.get(conversationId)
    if (!room) return
    const listener = room.listeners.get(userId)
    if (!listener || listener.socketId !== socket.id) return
    if (data.raised === true) {
      if (!room.hands.has(userId)) room.hands.set(userId, { user: listener, raisedAt: Date.now() })
    } else {
      room.hands.delete(userId)
    }
    broadcastStageState(conversationId)
    console.log(`[stage] hand user=${userId} conv=${conversationId} raised=${data.raised === true}`)
  })

  /**
   * stage:approve { conversationId, byUserId, targetUserId } — host-only:
   * moves a raised hand into the speakers roster (hand + listener entries cleared).
   */
  socket.on('stage:approve', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; byUserId?: unknown; targetUserId?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const byUserId = asTrimmedString(data.byUserId).slice(0, 64)
    const targetUserId = asTrimmedString(data.targetUserId).slice(0, 64)
    if (!conversationId || !byUserId || !targetUserId) return
    const room = stageRooms.get(conversationId)
    if (!room) return
    const host = stageLiveHost(room)
    // host-only, identity-gated to the socket the host registered from
    if (!host || host.userId !== byUserId || host.socketId !== socket.id) return
    const hand = room.hands.get(targetUserId)
    if (!hand) return
    room.hands.delete(targetUserId)
    room.listeners.delete(targetUserId)
    room.speakers.set(targetUserId, hand.user)
    broadcastStageState(conversationId)
    console.log(`[stage] approve target=${targetUserId} conv=${conversationId} by=${byUserId}`)
  })

  /**
   * stage:mute { conversationId, byUserId, targetUserId } — host-only.
   * Speaker → listener (contract). The host seat itself is immune.
   * Also dismisses a raised hand (the sheet's hand-queue "Mute" affordance)
   * and force-removes the target's voice seat so a muted speaker cannot keep
   * transmitting through a laggy client (real enforcement, not client-trust).
   */
  socket.on('stage:mute', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; byUserId?: unknown; targetUserId?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const byUserId = asTrimmedString(data.byUserId).slice(0, 64)
    const targetUserId = asTrimmedString(data.targetUserId).slice(0, 64)
    if (!conversationId || !byUserId || !targetUserId) return
    const room = stageRooms.get(conversationId)
    if (!room) return
    const host = stageLiveHost(room)
    if (!host || host.userId !== byUserId || host.socketId !== socket.id) return
    if (targetUserId === room.hostId) return // the host cannot mute themselves via this event

    let acted: 'speaker' | 'hand' | null = null
    if (room.speakers.has(targetUserId)) {
      const person = room.speakers.get(targetUserId)
      if (person) {
        room.speakers.delete(targetUserId)
        room.hands.delete(targetUserId)
        room.listeners.set(targetUserId, person)
        acted = 'speaker'
        // real enforcement: strip their mic seat from the voice roster immediately
        removeVoicePeerByUser(conversationId, targetUserId, 'stage-mute')
      }
    } else if (room.hands.has(targetUserId)) {
      room.hands.delete(targetUserId)
      acted = 'hand'
    }
    if (!acted) return
    broadcastStageState(conversationId)
    console.log(`[stage] mute target=${targetUserId} conv=${conversationId} by=${byUserId} acted=${acted}`)
  })

  /**
   * stage:end { conversationId, byUserId } — host-only: deletes the stage and
   * tells everyone (`stage:ended`), then force-cleans the io room + map indices.
   */
  socket.on('stage:end', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; byUserId?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const byUserId = asTrimmedString(data.byUserId).slice(0, 64)
    if (!conversationId || !byUserId) return
    const room = stageRooms.get(conversationId)
    if (!room) return
    const host = stageLiveHost(room)
    if (!host || host.userId !== byUserId || host.socketId !== socket.id) return

    stageRooms.delete(conversationId)
    io.to(stageRoomName(conversationId)).emit('stage:ended', { conversationId })
    io.in(stageRoomName(conversationId)).socketsLeave(stageRoomName(conversationId))
    for (const [sockId, conv] of socketStageRoom) {
      if (conv === conversationId) socketStageRoom.delete(sockId)
    }
    console.log(`[stage] end conv=${conversationId} by=${byUserId}`)
  })

  /** stage:leave { conversationId? } — explicit exit; the server map is authoritative. */
  socket.on('stage:leave', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown }
    const claimed = asTrimmedString(data.conversationId).slice(0, 128)
    const actual = socketStageRoom.get(socket.id)
    if (actual && claimed && claimed !== actual) return
    leaveStageRoom(socket.id, 'explicit')
  })

  // ── Spatial presence events (R24-c) ───────────────────────

  /**
   * space:join { conversationId, user: { id, name, username, color } }
   * Upserts the player (x=0.5, y=0.5 default; previous position kept on re-join)
   * and broadcasts `space:state` to the whole `space:{conversationId}` room.
   */
  socket.on('space:join', (raw: unknown) => {
    const data = (raw ?? {}) as {
      conversationId?: unknown
      user?: { id?: unknown; name?: unknown; username?: unknown; color?: unknown }
    }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const userId = asTrimmedString(data.user?.id).slice(0, 64)
    if (!conversationId || !userId) {
      console.warn(`[space] join rejected sock=${socket.id} (bad conversationId/userId)`)
      return
    }

    // One space per socket: silently drop any previous space membership first.
    leaveSpaceRoom(socket.id, 'switch')

    let room = spaceRooms.get(conversationId)
    if (!room) {
      room = new Map<string, SpacePlayer>()
      spaceRooms.set(conversationId, room)
    }
    const existing = room.get(userId)
    const player: SpacePlayer = {
      userId,
      name: asTrimmedString(data.user?.name).slice(0, 64) || 'Someone',
      username:
        typeof data.user?.username === 'string' && data.user.username.trim().length > 0
          ? data.user.username.trim().slice(0, 32)
          : null,
      color: asTrimmedString(data.user?.color).slice(0, 24) || 'emerald',
      socketId: socket.id,
      x: existing ? existing.x : 0.5,
      y: existing ? existing.y : 0.5,
      lastMoveAt: Date.now(),
    }
    room.set(userId, player) // last session wins on duplicate userId
    socketSpaceRoom.set(socket.id, conversationId)
    void socket.join(spaceRoomName(conversationId))

    broadcastSpaceState(conversationId)
    console.log(`[space] join user=${userId} conv=${conversationId} sock=${socket.id} players=${room.size}`)
  })

  /**
   * space:move { conversationId, x, y } — identity-gated, clamped to 0..1 and
   * throttled to one accepted move per 80 ms per player (non-finite → ignored).
   */
  socket.on('space:move', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown; x?: unknown; y?: unknown }
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    if (!conversationId) return
    const room = spaceRooms.get(conversationId)
    if (!room) return
    let player: SpacePlayer | null = null
    for (const candidate of room.values()) {
      if (candidate.socketId === socket.id) {
        player = candidate
        break
      }
    }
    if (!player) return
    if (typeof data.x !== 'number' || !Number.isFinite(data.x)) return
    if (typeof data.y !== 'number' || !Number.isFinite(data.y)) return
    const now = Date.now()
    if (now - player.lastMoveAt < SPACE_MOVE_THROTTLE_MS) return // throttle: ignore rapid-fire moves
    player.x = clamp01(data.x)
    player.y = clamp01(data.y)
    player.lastMoveAt = now
    broadcastSpaceState(conversationId)
  })

  /** space:leave { conversationId? } — explicit exit; the server map is authoritative. */
  socket.on('space:leave', (raw: unknown) => {
    const data = (raw ?? {}) as { conversationId?: unknown }
    const claimed = asTrimmedString(data.conversationId).slice(0, 128)
    const actual = socketSpaceRoom.get(socket.id)
    if (actual && claimed && claimed !== actual) return
    leaveSpaceRoom(socket.id, 'explicit')
  })

  // ── 1:1 call signaling events (R33-a) ─────────────────────

  /**
   * call:offer { callId, conversationId, to, kind, sdp, callerName, callerColor, callerAvatar }
   * Identity-gated: the socket must be registered as `from`. Guards: callee
   * offline ('offline') or busy — already ringing/in a call ('busy'). A live
   * session arms the 30s ring timeout that cancels for BOTH parties.
   */
  socket.on('call:offer', (raw: unknown) => {
    const data = (raw ?? {}) as Record<string, unknown>
    const callId = asTrimmedString(data.callId).slice(0, 64)
    const conversationId = asTrimmedString(data.conversationId).slice(0, 128)
    const from = asTrimmedString(data.from).slice(0, 64)
    const to = asTrimmedString(data.to).slice(0, 64)
    const kind = callKindOf(data.kind)
    const sdp =
      typeof data.sdp === 'string' && data.sdp.length > 0 && data.sdp.length <= CALL_SDP_MAX_CHARS
        ? data.sdp
        : null
    if (!callId || !conversationId || !from || !to || from === to || !kind || !sdp) return
    // Identity gate: you may only ring people AS the user your socket joined as.
    if (socketUser.get(socket.id) !== from) return
    if (!onlineUsers.has(to)) {
      socket.emit('call:cancel', { callId, conversationId, from, to, kind, reason: 'offline' })
      console.log(`[call] offer rejected (callee offline) call=${callId} from=${from} to=${to}`)
      return
    }
    const calleeBusy = callByUser.get(to)
    if (calleeBusy) {
      socket.emit('call:cancel', { callId, conversationId, from, to, kind, reason: 'busy' })
      console.log(`[call] offer rejected (busy) call=${callId} from=${from} to=${to} against=${calleeBusy}`)
      return
    }
    // Defensive: a stale session of the caller's own never blocks a new ring.
    const callerBusy = callByUser.get(from)
    if (callerBusy) dropCallSession(callerBusy, 'cancel', from)

    const session: CallSession = {
      callId,
      conversationId,
      callerId: from,
      calleeId: to,
      kind,
      answered: false,
      createdAt: Date.now(),
      timer: setTimeout(() => dropCallSession(callId, 'timeout', null), CALL_RING_TIMEOUT_MS),
    }
    callSessions.set(callId, session)
    callByUser.set(from, callId)
    callByUser.set(to, callId)

    io.to(roomOf(to)).emit('call:offer', {
      callId,
      conversationId,
      from,
      to,
      kind,
      sdp,
      callerName: asTrimmedString(data.callerName).slice(0, 64) || 'Someone',
      callerColor: asTrimmedString(data.callerColor).slice(0, 24) || 'emerald',
      callerAvatar:
        typeof data.callerAvatar === 'string' && data.callerAvatar.length > 0 && data.callerAvatar.length <= 256
          ? data.callerAvatar
          : null,
    })
    console.log(`[call] offer call=${callId} from=${from} to=${to} kind=${kind}`)
  })

  /**
   * call:answer { callId, sdp } — callee only (identity-gated). Disarms the
   * ring timeout and relays the WebRTC answer to the caller's room.
   */
  socket.on('call:answer', (raw: unknown) => {
    const data = (raw ?? {}) as Record<string, unknown>
    const callId = asTrimmedString(data.callId).slice(0, 64)
    const from = asTrimmedString(data.from).slice(0, 64)
    const sdp =
      typeof data.sdp === 'string' && data.sdp.length > 0 && data.sdp.length <= CALL_SDP_MAX_CHARS
        ? data.sdp
        : null
    const session = callSessions.get(callId)
    if (!session || !sdp) return
    if (socketUser.get(socket.id) !== from || from !== session.calleeId) return
    session.answered = true
    clearTimeout(session.timer)
    io.to(roomOf(session.callerId)).emit('call:answer', {
      callId,
      conversationId: session.conversationId,
      from,
      to: session.callerId,
      kind: session.kind,
      sdp,
    })
    console.log(`[call] answer call=${callId} from=${from} → caller=${session.callerId}`)
  })

  /**
   * call:ice { callId, candidate, sdpMid, sdpMLineIndex } — either party;
   * relayed to the OTHER side (works while ringing too — clients queue early
   * candidates until their peer connection exists).
   */
  socket.on('call:ice', (raw: unknown) => {
    const data = (raw ?? {}) as Record<string, unknown>
    const callId = asTrimmedString(data.callId).slice(0, 64)
    const from = asTrimmedString(data.from).slice(0, 64)
    const candidate =
      typeof data.candidate === 'string' && data.candidate.length > 0 && data.candidate.length <= CALL_ICE_MAX_CHARS
        ? data.candidate
        : null
    const session = callSessions.get(callId)
    if (!session || !candidate) return
    if (socketUser.get(socket.id) !== from) return
    if (from !== session.callerId && from !== session.calleeId) return
    const to = from === session.callerId ? session.calleeId : session.callerId
    io.to(roomOf(to)).emit('call:ice', {
      callId,
      conversationId: session.conversationId,
      from,
      to,
      kind: session.kind,
      candidate,
      sdpMid: typeof data.sdpMid === 'string' ? data.sdpMid : null,
      sdpMLineIndex:
        typeof data.sdpMLineIndex === 'number' && Number.isFinite(data.sdpMLineIndex)
          ? Math.floor(data.sdpMLineIndex)
          : null,
    })
  })

  /**
   * call:reject { callId } — callee only (identity-gated): the caller receives
   * `call:reject` (their client logs 'declined'), the callee gets `call:cancel`.
   */
  socket.on('call:reject', (raw: unknown) => {
    const data = (raw ?? {}) as Record<string, unknown>
    const callId = asTrimmedString(data.callId).slice(0, 64)
    const from = asTrimmedString(data.from).slice(0, 64)
    const session = callSessions.get(callId)
    if (!session) return
    if (socketUser.get(socket.id) !== from || from !== session.calleeId) return
    clearTimeout(session.timer)
    callSessions.delete(callId)
    if (callByUser.get(session.callerId) === callId) callByUser.delete(session.callerId)
    if (callByUser.get(session.calleeId) === callId) callByUser.delete(session.calleeId)
    io.to(roomOf(session.callerId)).emit('call:reject', {
      callId,
      conversationId: session.conversationId,
      from,
      to: session.callerId,
      kind: session.kind,
    })
    io.to(roomOf(session.calleeId)).emit('call:cancel', {
      callId,
      conversationId: session.conversationId,
      from,
      to: session.calleeId,
      kind: session.kind,
      reason: 'cancel',
    })
    console.log(`[call] reject call=${callId} by=${from} → caller=${session.callerId}`)
  })

  /**
   * call:cancel { callId } — caller aborts while ringing (identity-gated):
   * BOTH parties are told (the caller's client logs 'missed').
   */
  socket.on('call:cancel', (raw: unknown) => {
    const data = (raw ?? {}) as Record<string, unknown>
    const callId = asTrimmedString(data.callId).slice(0, 64)
    const from = asTrimmedString(data.from).slice(0, 64)
    const session = callSessions.get(callId)
    if (!session) return
    if (socketUser.get(socket.id) !== from || from !== session.callerId) return
    dropCallSession(callId, 'cancel', from)
  })

  /**
   * call:hangup { callId } — either party ends an ACTIVE call (identity-gated);
   * the surviving party receives `call:hangup` with the elapsed duration.
   */
  socket.on('call:hangup', (raw: unknown) => {
    const data = (raw ?? {}) as Record<string, unknown>
    const callId = asTrimmedString(data.callId).slice(0, 64)
    const from = asTrimmedString(data.from).slice(0, 64)
    const session = callSessions.get(callId)
    if (!session) return
    if (socketUser.get(socket.id) !== from || (from !== session.callerId && from !== session.calleeId)) return
    dropCallSession(callId, 'hangup', from)
  })

  socket.on('error', (error) => {
    console.error(`[ws] socket error (${socket.id}):`, error instanceof Error ? error.message : error)
  })

  socket.on('disconnect', (reason: string) => {
    // Voice rooms clean up FIRST: the departed mic must never keep the stage.
    leaveVoiceRoom(socket.id, `disconnect:${reason}`)
    // Stage + spatial presence rosters follow (mirror cleanup).
    leaveStageRoom(socket.id, `disconnect:${reason}`)
    leaveSpaceRoom(socket.id, `disconnect:${reason}`)
    // Calls: the departed party tears the session down for both sides
    // (unanswered ring → 'timeout' cancel; active call → hangup to survivor).
    const departedUserId = socketUser.get(socket.id)
    if (departedUserId) {
      const callId = callByUser.get(departedUserId)
      if (callId) dropCallSession(callId, 'timeout', departedUserId)
    }
    const wentOffline = dropPresence(socket.id)
    if (wentOffline) {
      void visibleOnlineIds().then((snapshot) => {
        io.emit('presence:snapshot', { onlineUserIds: snapshot })
      })
      console.log(`[ws] disconnect sock=${socket.id} reason=${reason} — user went OFFLINE, online=${onlineUsers.size}`)
    } else {
      console.log(`[ws] disconnect sock=${socket.id} reason=${reason} (other sessions remain, online=${onlineUsers.size})`)
    }
  })
})

// ---------------------------------------------------------------------------
// Scheduled-send dispatcher ticker.
// This service intentionally has NO database access; it simply pokes the
// Next.js maintenance endpoint on an interval, which flushes due Telegram-style
// delayed messages through the exact same pipeline as a live send.
// ---------------------------------------------------------------------------
const NEXT_APP_URL = process.env.PULSE_ORIGIN ?? 'http://localhost:3000'
const DISPATCH_INTERVAL_MS = 20_000
const DISPATCH_KEY = process.env.CRON_SECRET ?? 'pulse-dispatch-key'

async function tickDispatch(): Promise<void> {
  try {
    const res = await fetch(`${NEXT_APP_URL}/api/maintenance/dispatch`, {
      method: 'POST',
      headers: { 'x-pulse-key': DISPATCH_KEY },
      signal: AbortSignal.timeout(8000),
    })
    const body = (await res.json().catch(() => null)) as { dispatched?: number } | null
    if (body && typeof body.dispatched === 'number' && body.dispatched > 0) {
      console.log(`[cron] dispatched ${body.dispatched} scheduled message(s)`)
    }
  } catch (error) {
    // app may be mid-restart — the next tick retries silently
    if (process.env.PULSE_DISPATCH_DEBUG === '1') {
      console.log('[cron] dispatch probe failed:', error instanceof Error ? error.message : error)
    }
  }
}
setInterval(() => void tickDispatch(), DISPATCH_INTERVAL_MS)

// ---------------------------------------------------------------------------
// Safety nets + start + graceful shutdown
// ---------------------------------------------------------------------------
process.on('uncaughtException', (err) => {
  console.error('[fatal] uncaughtException:', err instanceof Error ? err.stack ?? err.message : err)
})
process.on('unhandledRejection', (err) => {
  console.error('[fatal] unhandledRejection:', err instanceof Error ? err.stack ?? String(err) : String(err))
})

httpServer.listen(PORT, () => {
  console.log(`Pulse socket service listening on port ${PORT} (path "/", presence+relay+voice+stage+space ready)`)
})

let shuttingDown = false
function shutdown(signal: string): void {
  if (shuttingDown) return
  shuttingDown = true
  console.log(`Received ${signal} signal, shutting down server...`)
  io.disconnectSockets(true)
  httpServer.close(() => {
    console.log('WebSocket server closed')
    process.exit(0)
  })
  // Never hang the process on lingering keep-alive connections.
  setTimeout(() => process.exit(0), 2000).unref()
}
process.on('SIGTERM', () => shutdown('SIGTERM'))
process.on('SIGINT', () => shutdown('SIGINT'))
