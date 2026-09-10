/**
 * Pulse iOS test fixture — a minimal socket.io relay exercising the exact
 * contract SocketRoundTripTests verifies against PulseSocketClient:
 *
 *   join { userId }        → ack `joined` { onlineUserIds } to the socket
 *                            + `presence:snapshot` broadcast to everyone
 *   typing { recipients, conversationId, userId, userName, isTyping }
 *                          → relayed to `user:<recipient>` rooms
 *   POST /notify           → { event, recipients, payload } relayed to
 *                            `user:<recipient>` rooms (whitelist parity)
 *   GET  /health           → 200 { ok: true } (spawn-readiness probe)
 *
 * Deliberately a subset of mini-services/pulse-socket: no privacy gates,
 * no voice/stage rooms — the Wave 0 round-trip only pins join/presence/
 * typing/notify.
 *
 * Usage: node server.js [port]   (default 3991)
 */
'use strict';

const http = require('http');
const { Server } = require('socket.io');

const port = Number(process.argv[2] || 3991);
const online = new Map(); // socketId -> userId

function snapshot() {
  return Array.from(new Set(online.values()));
}

const httpServer = http.createServer((req, res) => {
  const url = req.url || '/';

  if (req.method === 'GET' && url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, online: snapshot().length }));
    return;
  }

  if (req.method === 'POST' && url === '/notify') {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
    });
    req.on('end', () => {
      let parsed;
      try {
        parsed = JSON.parse(body || '{}');
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, error: 'invalid JSON body' }));
        return;
      }
      const { event, recipients, payload } = parsed;
      if (typeof event !== 'string' || !event || !Array.isArray(recipients)) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, error: 'event and recipients required' }));
        return;
      }
      let delivered = 0;
      for (const recipient of recipients) {
        io.to('user:' + String(recipient)).emit(event, payload === undefined ? null : payload);
        delivered += 1;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, delivered }));
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: false, error: 'not found' }));
});

const io = new Server(httpServer, {
  cors: { origin: true, credentials: false },
});

io.on('connection', (socket) => {
  socket.on('join', (raw) => {
    const userId = raw && typeof raw.userId === 'string' ? raw.userId.trim() : '';
    if (!userId) return;
    online.set(socket.id, userId);
    socket.join('user:' + userId);
    socket.emit('joined', { onlineUserIds: snapshot() });
    io.emit('presence:snapshot', { onlineUserIds: snapshot() });
  });

  socket.on('typing', (raw) => {
    const data = raw || {};
    const recipients = Array.isArray(data.recipients) ? data.recipients : [];
    const payload = {
      conversationId: typeof data.conversationId === 'string' ? data.conversationId : '',
      userId: typeof data.userId === 'string' ? data.userId : '',
      userName: typeof data.userName === 'string' ? data.userName : '',
      isTyping: data.isTyping === true,
    };
    for (const recipient of recipients) {
      io.to('user:' + String(recipient)).emit('typing', payload);
    }
  });

  socket.on('disconnect', () => {
    online.delete(socket.id);
    io.emit('presence:snapshot', { onlineUserIds: snapshot() });
  });
});

httpServer.listen(port, '127.0.0.1', () => {
  console.log('pulse-socket-fixture listening on 127.0.0.1:' + port);
});
