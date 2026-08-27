/**
 * Pulse Chat — realtime mini service (socket.io)
 * ------------------------------------------------
 * Port: 3003 (hardcoded — reached from Next.js/preview via Caddy `/?XTransformPort=3003`).
 *
 * Responsibilities (NO database access ever):
 *  - Presence tracking   : onlineUsers Map<userId, Set<socketId>>, rooms `user:{userId}`
 *  - Client→client relay : `typing`
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
const NOTIFY_EVENTS = new Set(['message:new', 'message:deleted', 'message:read', 'message:react'])

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

  socket.on('error', (error) => {
    console.error(`[ws] socket error (${socket.id}):`, error instanceof Error ? error.message : error)
  })

  socket.on('disconnect', (reason: string) => {
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
