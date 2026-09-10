// ─────────────────────────────────────────────────────────────
// Pulse Wave-0 relay subset — the exact behaviors the Android JVM
// round-trip test (SocketRoundTripTest) verifies against the REAL
// Socket.IO wire, mirroring mini-services/pulse-socket semantics:
//   join          → ack `joined {onlineUserIds}` + broadcast `presence:snapshot`
//   typing        → relayed to every OTHER joined socket
//   POST /notify  → emit {event, payload} to joined sockets of recipientIds
//   GET /kick     → test-only: abort a user's raw transport (network blip)
//   GET /         → health probe
// ─────────────────────────────────────────────────────────────
'use strict';

const http = require('http');
const { Server } = require('socket.io');

const port = Number(process.env.PORT || 3990);

const httpServer = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'pulse-wave0-relay' }));
    return;
  }
  // Test-only: ABORT the transport under one joined user — a REAL network
  // blip. `socket.disconnect(true)` would send a graceful engine.io CLOSE
  // (reason "io server disconnect"), which socket.io clients deliberately
  // do NOT auto-reconnect from. Destroying the raw transport never tells
  // the client why — reconnection=true must restore it.
  if (req.method === 'GET' && req.url.startsWith('/kick')) {
    const userId = new URL(req.url, 'http://localhost').searchParams.get('userId');
    let kicked = 0;
    for (const [socketId, uid] of joined) {
      if (userId && uid === userId) {
        const target = io.sockets.sockets.get(socketId);
        if (target) {
          if (target.conn && target.conn.transport && target.conn.transport.name === 'websocket' && target.conn.transport.socket) {
            target.conn.transport.socket.terminate(); // no close frame, pure drop
          } else if (target.conn && target.conn.request && target.conn.request.socket) {
            target.conn.request.socket.destroy();
          }
          joined.delete(socketId);
          kicked += 1;
        }
      }
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, kicked }));
    return;
  }
  if (req.method === 'POST' && req.url === '/notify') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try {
        const parsed = JSON.parse(body || '{}');
        const event = parsed.event;
        const payload = parsed.payload === undefined ? null : parsed.payload;
        const recipients = Array.isArray(parsed.recipientIds) ? parsed.recipientIds : null;
        if (!event) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'missing event' }));
          return;
        }
        let delivered = 0;
        for (const [socketId, userId] of joined) {
          if (recipients && !recipients.includes(userId)) continue;
          const target = io.sockets.sockets.get(socketId);
          if (target) {
            target.emit(event, payload);
            delivered += 1;
          }
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, delivered }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'bad json' }));
      }
    });
    return;
  }
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'not found' }));
});

const io = new Server(httpServer, { cors: { origin: true } });

/** socketId → userId (in-memory presence map, room resolution simplified). */
const joined = new Map();

function onlineUserIds() {
  return [...new Set(joined.values())].filter(Boolean);
}

io.on('connection', (socket) => {
  socket.on('join', (raw) => {
    const userId = raw && typeof raw === 'object' ? String(raw.userId || '') : '';
    joined.set(socket.id, userId);
    const online = onlineUserIds();
    socket.emit('joined', { onlineUserIds: online });
    io.emit('presence:snapshot', { onlineUserIds: online });
  });

  socket.on('typing', (raw) => {
    for (const [socketId] of joined) {
      if (socketId === socket.id) continue;
      const target = io.sockets.sockets.get(socketId);
      if (target) target.emit('typing', raw === undefined ? null : raw);
    }
  });

  socket.on('disconnect', () => {
    joined.delete(socket.id);
    io.emit('presence:snapshot', { onlineUserIds: onlineUserIds() });
  });
});

// Bind 0.0.0.0 explicitly: a bare listen(port) binds :: (dual-stack), and in
// sandboxed environments IPv4-mapped connections to 127.0.0.1 can stall in the
// accept queue. An explicit IPv4 wildcard makes every loopback client (Java
// socket.io-client, okhttp, HttpClient) connect natively; CI runners are fine.
httpServer.listen(port, '0.0.0.0', () => {
  console.log('pulse-wave0-relay listening on ' + port);
});
