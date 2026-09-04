/**
 * Pulse Chat — realtime mini service (socket.io)
 * ------------------------------------------------
 * Port: 3003 (hardcoded — reached from Next.js/preview via Caddy `/?XTransformPort=3003`).
 *
 * Responsibilities (NO database access ever):
 *  - Presence tracking   : onlineUsers Map<userId, Set<socketId>>, rooms `user:{userId}`
 *  - Client→client relay : `typing`
 *  - Live voice rooms    : in-memory rosters `voiceRooms` Map + `voice:{conversationId}`
 *                          socket.io rooms; `voice:join|leave|roster|ptt|chunk` relay
 *  - HTTP relay API      : POST /notify (message:new|message:deleted|message:read), POST /typing
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
  socket.on('join', (raw: unknown) => {
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

    socket.emit('joined', { onlineUserIds: presenceSnapshot() })
    io.emit('presence:snapshot', { onlineUserIds: presenceSnapshot() })
    console.log(`[ws] join user=${userId} sock=${socket.id} online=${onlineUsers.size}`)
  })

  /**
   * typing { recipients, conversationId, userId, userName, isTyping }
   * Direct client→client relay; sender's own ids are excluded defensively.
   */
  socket.on('typing', (raw: unknown) => {
    const data = (raw ?? {}) as TypingBody
    const conversationId = asTrimmedString(data.conversationId)
    const userId = asTrimmedString(data.userId)
    if (!conversationId || !userId) return

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

  socket.on('error', (error) => {
    console.error(`[ws] socket error (${socket.id}):`, error instanceof Error ? error.message : error)
  })

  socket.on('disconnect', (reason: string) => {
    // Voice rooms clean up FIRST: the departed mic must never keep the stage.
    leaveVoiceRoom(socket.id, `disconnect:${reason}`)
    const wentOffline = dropPresence(socket.id)
    if (wentOffline) {
      io.emit('presence:snapshot', { onlineUserIds: presenceSnapshot() })
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
  console.log(`Pulse socket service listening on port ${PORT} (path "/", presence+relay ready)`)
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
