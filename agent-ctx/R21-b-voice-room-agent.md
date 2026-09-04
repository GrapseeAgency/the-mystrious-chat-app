# Task R21-b — Pulse "Beyond Chat" wave: Live Voice Rooms

Agent: Z.ai Code
Status: COMPLETE
Date: session R21 (wave b)

## Files changed (ownership respected)
1. `mini-services/pulse-socket/index.ts` (EXCLUSIVE — extended)
2. `src/components/chat/voice-room-sheet.tsx` (NEW — engine hook `useVoiceRoom` + `VoiceRoomSheet` UI)
3. `src/components/chat/chat-room.tsx` (EXCLUSIVE — 6 surgical edits only)
4. `/home/z/my-project/worklog.md` (appended `Task ID: R21-b` section)

## Event contracts (socket.io, port 3003, path `/`)
Client→Server:
- `voice:join` `{ conversationId, user: { id, name, username, color } }` → joins room `voice:{conversationId}`, upserts in-memory roster, broadcasts `voice:roster`
- `voice:leave` `{ conversationId? }` → prunes + broadcasts (server-side map is authoritative; mismatched claims ignored)
- `voice:ptt` `{ conversationId, userId, on: boolean }` → relayed to whole voice room INCLUDING sender (identity-gated)
- `voice:chunk` `{ conversationId, userId, seq: number, data: base64(Int16 PCM 16kHz mono) }` → relayed to room EXCEPT sender (96KB cap, identity-gated)

Server→Client:
- `voice:roster` `{ conversationId, peers: [{ id, name, username, color }] }`
- `voice:ptt` `{ conversationId, userId, on }`
- `voice:chunk` `{ conversationId, userId, seq, data }`

In-memory state: `voiceRooms Map<convId, Map<userId, VoicePeer>>`, `socketVoiceRoom Map<socketId, convId>`. Disconnect → leave + roster broadcast + forced `voice:ptt off`.

## Verification proof
- Service restarted: killed stale pid on :3003, `bun run dev` in mini-services/pulse-socket (bg, log /tmp/pulse-socket-dev.log). Health: `{"ok":true,"service":"pulse-socket","port":3003,"online":0,"voiceRooms":0,...}`
- Handshake: `curl "http://localhost:3003/socket.io/?EIO=4&transport=polling"` → `0{"sid":"DYyz…","upgrades":["websocket"],…}` ✓
- Node socket.io-client roundtrip (temp script, deleted after): **7/7 PASS**
  1. PASS — voice:join → voice:roster (A sees self)
  2. PASS — second join broadcasts 2-peer roster to room
  3. PASS — voice:ptt relayed to sender + peers
  4. PASS — voice:chunk reaches peers EXCEPT sender
  5. PASS — identity gate blocks spoofed voice:chunk
  6. PASS — voice:leave broadcasts pruned roster
  7. PASS — disconnect cleans roster + force-stops PTT
- `npx tsc --noEmit | grep` my files → 0 errors (repo's remaining 6 src errors are pre-existing in other crews' files: whiteboard route ×2, group-info-sheet, bot-engine ×3, skills)
- `bun run lint` → 0 errors (2 pre-existing warnings in stories files, not this task's files)
- `GET /` → 200 after edits; dev.log healthy (no compile errors; historical EADDRINUSE + stories-route 500 entries predate this task)

## Audio pipeline (real, no mocks)
- join → AudioContext created synchronously in the click gesture (iOS unlock) → getUserMedia({echoCancellation, noiseSuppression, autoGainControl}) → AudioWorklet (Blob URL `pulse-capture-processor`) with ScriptProcessor(4096) fallback → 250ms native blocks → linear-interp downsample 16kHz mono → Int16 → base64 → `voice:chunk` (seq increments, partial window flushed on release)
- receive → validate → stale-seq drop → AudioBuffer(16kHz) scheduled via per-peer playhead `max(now+85ms, nextAt)` for gapless-ish playback
- mute = `track.enabled=false` + software gate + forced PTT-off; leave/unmount/conv-switch = full teardown (tracks stopped, nodes disconnected, ctx closed, socket killed)
- permission denied → honest inline error panel + toast (NotAllowed/NotFound/NotReadable mapped)

## Notes for next crews
- The voice engine lives in chat-room scope (`useVoiceRoom`), so the room survives sheet close — the "Voice · N live" pill reopens it. Closing the sheet auto-stops transmission but keeps membership.
- Relay restarts (bun --hot) self-heal: socket reconnect re-emits voice:join; roster-missing-me triggers a rate-limited resync.
- Do NOT send presence `join` from the voice socket — presence counting stays on the main provider socket only.
- :3000 Next dev server was never restarted; no `bun run build` was run.
