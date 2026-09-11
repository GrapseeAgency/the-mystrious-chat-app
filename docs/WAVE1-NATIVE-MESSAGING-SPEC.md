# WAVE 1 — NATIVE MESSAGING SURFACE PARITY (binding implementation spec)

Status: ACTIVE · Approves: user directive "PULSE — WAVE 1" · Baseline: Wave 0 closed (v0.2.0-native, versionCode 10)
Ground truth source: web app audit (this spec §1) — natives reproduce PRODUCT BEHAVIOUR with native UI (Compose / SwiftUI). No DOM/CSS copying, no WebView/RN/Flutter.
Forbidden: Calls, Stories, Hub, Games, Voice Rooms, WebGL effects, mock/stub implementations, Wave 2 auto-start.

---

## 1. WEB GROUND TRUTH — CONTRACT EVERY NATIVE FEATURE MUST MATCH

All endpoints exist TODAY on the backend. **Zero backend/protocol changes are authorized for Wave 1** (natives were the ones lagging).

### 1.1 Messaging wire contract
- **Send message**: `POST /api/conversations/{id}/messages` JSON
  `{senderId, content, replyToId?, parentId?, imagePath?, audioPath?, durationMs?, filePath?, fileName?, fileSize?, kind?, topicId?, viewOnce?, payload?}`
  → `201 {message: ChatMessage, streak?, xpAwarded}`. `kind` whitelist: `text|image|audio|sticker|location|file`. `content` ≤ 2000.
  - **THREAD REPLY** = ordinary send with `parentId` = thread-root id. Server rules: parent must exist, same conversation, not deleted, `parent.parentId === null` (else 400 "Threads are one level deep"). Replies relay as plain `message:new`. This is the CORRECT behaviour — the web client's known thread-send bug was its own cache-shape crash, NOT the wire. Natives send `{senderId, content, parentId}` and must never conflate `parentId` (thread) with `replyToId` (inline quote).
  - **IMAGE** = `imagePath` set (`kind` may stay `text`); **FILE** = `kind:'file'` + `filePath` + `fileName` (+`fileSize`); **VOICE** = `audioPath` + `durationMs` (0..600000).
- **Pagination**: `GET /api/conversations/{id}/messages?limit=200` (newest 200 asc, `hasMore`, `total`); older pages `?limit=40&before=<ISO of oldest loaded>`; conversation search `?limit=100&q=<substring on content+fileName>` → asc, `hasMore:false`.
- **Thread read**: `GET /api/messages/{id}/thread?userId=` → `{parent: ChatMessage, replies: ChatMessage[]}` (replies asc).
- **Read receipt (watermark model — there is NO per-message delivered field)**: `POST /api/conversations/{id}/read {userId}` bumps participant `lastReadAt`, relays `message:read {conversationId, userId, lastReadAt}`. Client posts debounced 600ms while room open/visible. Ticks on own last bubble: clock while `temp-`/queued → single ✓ sent → ✓✓ when `message.createdAt <= other members' lastReadAt`. Group "seen by" = members whose `lastReadAt >= message.createdAt` (from conversation members; summaries include `members[].lastReadAt`).
- **Reactions**: `POST /api/messages/{id}/react {userId, emoji}` → `{message}`; whitelist `👍❤️😂😮😢🎉`; toggle semantics (`@@unique(messageId,userId,emoji)`).
- **Edit**: `PATCH /api/messages/{id} {userId, content}` → `{message}` (`editedAt` set); sender-only (403 otherwise).
- **Delete**: `DELETE /api/messages/{id} {requesterId}` → `{message}` tombstone (`deletedAt`); sender-only, soft, idempotent.
- **Pin**: `POST /api/messages/{id}/pin {userId}` toggle → `{message}` (`pinnedAt/pinnedBy`); pins list `GET /api/conversations/{id}/pinned?userId=` → `{messages}` pinnedAt asc.
- **Save/star**: `POST /api/messages/{id}/save {userId}` → `{saved}` toggle.
- **Forward**: NO endpoint — client re-POSTs the same body (reusing stored `imagePath`/`filePath`/`fileName`/`fileSize`/`audioPath`) to each target conversation.
- **Drafts**: `PATCH /api/conversations/{id}/draft {userId, draft}` (`""` clears) → `{ok, draft}`; server mirrors per-participant `draft`, surfaced as `myDraft`. Local wins; server seeds cross-device.
- **Global search**: `GET /api/search?userId=&q=` → `{messages: [SearchResultMessage(+conversationName,isGroup)], total}` (≤30 hits, newest 1200 rows, soft-deleted excluded, `q` ≤ 64).
- **Media upload**: `POST /api/uploads` JSON `{dataUrl: "data:<mime>;base64,…"}` (NOT multipart) → `201 {filePath, imagePath}`. Mimes: image jpeg/png/webp (client downscales ≤1280px JPEG q0.82 first), audio webm/mpeg/ogg/wav/mp4/aac, pdf/zip/txt/csv. Media ≤4.5MB data-URL, docs ≤10MB. Serve: `GET /api/uploads/{file}` (immutable cache). Message media URL = `{gateway}/api/uploads/{imagePath|filePath}`.
- **Socket C→S emits used by messaging**: `join {userId}` (on every connect/reconnect), `typing {recipients: members except me, conversationId, userId, userName, isTyping}` (throttle ≤1/1.5s, idle-stop 1.2s, immediate stop on send/blur). Reads/reactions/edits go over REST; server relays S→C.
- **Socket S→C consumed**: `joined`, `presence:snapshot` (onlineUserIds), `typing`, `message:new|deleted|read|react|edited|pinned|viewed`, `poll:voted`, `link:preview`, `translation:added`, `conversation:updated {conversationId}`. All 27 registry events already decoded by both native socket clients (Wave 0); Wave 1 wires them into UI.

### 1.2 Behaviour rules
- Main river EXCLUDES thread replies (`parentId != null`); "N replies ↳" chip counts replies; unread counts INCLUDE thread replies; typing/ping rules unchanged.
- Optimistic temp id: `local_<clientId>` (native convention; web uses `temp-`), reconciliation swaps temp→real by clientId and dedupes same-sender+content `temp`/`local_` rows.
- Outbox: FIFO ≤50, drop-oldest, enqueue dedupe by clientId, flush stop-at-first-NETWORK-failure, 4xx → drop entry + keep draining, attempts bump, flush triggers: app start, socket reconnect, foreground, periodic self-heal (Android 20s / iOS 60s+BGTask), WorkManager/BGTask. Text-only (media sends require online — failure surfaces an error, never queued).
- Deleted → tombstone "🚫 Message deleted"; edited → row replaced; pinned banner shows newest pin; pin glyph on bubble.
- Day separators in timeline (web/iOS have them; Android adds in Wave 1).

---

## 2. FEATURE → PLATFORM MATRIX (acceptance per row: FEATURE → Android impl → iOS impl → endpoint/event → persistence → offline → test)

| # | Feature | Android | iOS | Backend | Persistence | Offline | Test |
|---|---|---|---|---|---|---|---|
| 1 | Conversation list (DM/group/channel, unread, timestamps, drafts indicator, archive/mute/mark-unread/pin) | EXISTS (rows, badges, filters, batch bar, archived page). ADD: list draft preview merges local DraftDao ("local wins"), OutboxDropped snackbar | EXISTS. ADD: server draft write-back from room (PATCH), outbox drop toast EXISTS — verify | `GET /api/conversations` + PATCH sub-resources (existing) | Room conversations + GRDB conversations (existing) | GRDB/Room rehydrate (existing) | existing JVM/instrumented + new unit where touched |
| 2 | Timeline + pagination + optimistic + ticks | ADD: load-older on scroll-top (`before=` cursor, 40/page, `hasMore` stop), day separators, tick states clock→✓→✓✓ (DM watermark from summaries members), keep queued clock | ADD: load-older same cursor, day chips EXIST, add ✓/✓✓ ticks + failed state | `GET .../messages?limit=&before=` | Room messages / GRDB messages + in-memory page merge | cached seed then network (existing) | JVM pagination-merge test; CI compile; manual QA script |
| 3 | Realtime (join/reconnect/typing/presence/read/unread) | EXISTS (signals→Room→reactive UI). ADD: read receipt posting debounce 600ms on room visible; unread badge on `message:new` without full refetch (keep 500ms debounce) | EXISTS. ADD: `messageRead` parses real `lastReadAt` payload (not Date()); read-post debounce EXISTS | socket join/typing + REST read | — (signals) | reconnect flush (existing) | SocketRoundTrip tests (existing, both platforms) |
| 4 | Message actions: reply/react/copy (EXISTS) + edit/delete/pin/save/forward/info | ADD: action sheet w/ Edit (own text, prefilled composer, PATCH), Delete (own, confirm, DELETE), Pin/Unpin (POST toggle + pinned banner from `/pinned` + glyphs), Save (POST /save + toast), Forward (multi-target sheet re-POST), Info sheet (read-by from members lastReadAt ≥ createdAt + reactions list) | SAME via contextMenu + sheets | endpoints §1.1 | Room/GRDB row upserts (react/edit/delete/pin swap rows) | server truth; optimistic row swap w/ revert on failure | JVM FakeRepo tests for new repo methods; WireParity decode additions |
| 5 | Threads | ADD: decode `parentId`→`threadRootId` (fix conflation with replyToId), "Reply in thread" on top-level msgs, ThreadScreen (parent + replies asc + composer), send `parentId`, realtime reply append (Room filter query), "N replies" chip on parent bubble, main river filters `threadRootId != null` | ADD: same — ThreadSheet from parent bubble, send `parentId` in body (new), realtime append, "N replies" chip, river filter | POST messages w/ `parentId` + GET `/api/messages/{id}/thread` | Room threadRootId col (exists) / GRDB ADD parentId col | thread replies NOT queued (error if offline — web parity) | JVM thread-payload test; iOS WireParity parentId decode test |
| 6 | Drafts (local + restore + server mirror) | ADD: 600ms debounced PATCH `/draft` mirror after local save (blank → `""`); restore EXISTS | ADD: same PATCH from room draftChanged | PATCH `/draft` | DraftDao/GRDB draft (existing) | local-first (existing) | JVM/iOS unit: mirror debounce + blank-clear |
| 7 | Offline outbox | EXISTS engine. ADD: dropped-entry snackbar, offline state honesty already queued-banner | EXISTS engine+toasts. ADD: room offline banner when disconnected | — | outbox tables (existing) | full chain (existing) | FlushOutboxUseCase tests (existing); NEW JVM live-gateway offline→flush integration test (unreachable endpoint → enqueue → repoint live → flush → socket delivery) |
| 8 | Search (global/conversation/jump) | ADD: room search entry (server `q=` + local filter of loaded window), tap → scroll+flash; global hit row passes `jumpMessageId` → room scrolls+flashes (≤14 bounded `before=` rounds if out of window) | ADD: room search sheet same; `RoomRoute` carries jumpMessageId; ScrollViewReader scrollTo + flash | `GET /api/search`, `GET messages?q=` | none (query-time) | loaded-window local filter works offline | JVM jump-window test; iOS decode test |
| 9 | Media messaging (image/file/captions/preview/upload/persistence/download) | ADD: attach sheet (photo picker via PickVisualMedia / document via OpenDocument), image downscale ≤1280 JPEG q0.82 → dataURL → POST /uploads → send `imagePath` + caption; doc ≤10MB → upload → `kind:'file'`; bubbles: Coil AsyncImage (add coil-compose), tap → fullscreen lightbox dialog, file bubble → download to cache + FileProvider ACTION_VIEW open/share; caption field in attach flow; upload progress spinner; upload failures show inline error (never queued) | ADD: PhotosPicker + fileImporter, same upload JSON, AsyncImage + fullScreenCover lightbox, QuickLook preview for docs, share/download via ShareLink/FileManager | POST /api/uploads + GET /api/uploads/{file} | Room v5 media cols / GRDB v3 media cols | media NOT queued; online-only with error | JVM upload-flow test w/ FakeApi; iOS unit decode; CI compile |

**Version bumps**: Android versionCode 11 / `0.3.0-native`. iOS: bump marketing version to 0.3.0 if a version key exists (else skip — xcodegen).

---

## 3. SCHEMA CHANGES (natives only)

### Room v4 → v5 (Android, `PulseDatabase`, additive ALTERs, `MIGRATION_4_5`, exportSchema, schemas/5.json committed)
`message` ADD: `imagePath TEXT`, `audioPath TEXT`, `filePath TEXT`, `fileName TEXT`, `fileSize INTEGER`, `viewOnce INTEGER NOT NULL DEFAULT 0` (render gate only).
`conversation` ADD: `membersJson TEXT NOT NULL DEFAULT '[]'` (id/name/color/lastReadAt/role per member — powers ticks + info sheet).
Mapper: `ChatMessageDto.toDomain()` maps `parentId→threadRootId` (STOP conflating with replyToId), maps media fields; `ConversationSummaryDto.toDomain()` fills membersJson.

### GRDB v2 → v3 (iOS, `PulseStore`, additive ALTERs, migration v3)
`message` ADD: `parentId TEXT`, `imagePath TEXT`, `audioPath TEXT`, `durationMs REAL`, `filePath TEXT`, `fileName TEXT`, `fileSize INTEGER`, `editedAt TEXT`, `deletedAt TEXT`, `reactionsJson TEXT NOT NULL DEFAULT '[]'`, `senderColor TEXT`.
`upsert(messages:)` persists ALL of the above (ends the lossy cache); `WireChatMessage(cached:)` round-trips them.

---

## 4. GATE (user directive)
Install → open conversation → send/receive realtime → core actions → offline send → online flush reconcile. Verified by: Android CI (build+instrumented) + JVM live-gateway integration test + local E2E; iOS CI (build+tests+archive+launch smoke) + in-sim socket round-trip; honest reporting for anything unverifiable (no local macOS/Android SDK limits documented).

## 5. NON-GOALS (Wave 1)
Voice recording/playback, view-once consume, polls, link previews, translations, topics, push notifications, saved-messages library page, new-chat composers beyond existing Contacts/NewChatSheet, calls/stories UI.
