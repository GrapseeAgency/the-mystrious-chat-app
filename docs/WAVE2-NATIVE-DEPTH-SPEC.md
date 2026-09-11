# WAVE 2 — NATIVE MESSAGING DEPTH II — BINDING SPEC

Status: ACTIVE. Source of truth for Wave 2 implementation on Android (Kotlin+Compose+Room) and iOS (Swift+SwiftUI+GRDB).
Web app = behavioural specification. Backend is FROZEN (all endpoints exist — verified 2024 Wave 2 ground-truth audit). Do not modify `src/app/api/**`, `mini-services/**`, or `prisma/schema.prisma`.

---

## 0. CONTRACT FACTS (verified against web source + routes)

- Auth: NO Authorization header anywhere. Caller identity rides body keys (`senderId`, `userId`, `requesterId`).
- Socket: `join {userId}`; envelope events `message:viewed`, `poll:voted`, `link:preview` carry `{type, message, recipientIds, conversationId}` — **the `message` row is authoritative and must be upserted whole**.
- Message send: `POST /api/conversations/{id}/messages` — accepted keys: `senderId, content(≤2000), kind(text|image|audio|sticker|location|file), payload, imagePath, audioPath(webm|mp3|ogg|wav|m4a|aac on disk), durationMs(0..600000, only with audioPath), filePath+fileName+fileSize(kind=file), replyToId, parentId, topicId(must belong to conv), viewOnce(===true REQUIRES imagePath), anon`.
- Upload: `POST /api/uploads {dataUrl}` → 201 `{filePath, imagePath}`. dataUrl regex allows `audio/(webm|mpeg|ogg|wav|mp4|aac)`. Media GET: `/api/uploads/{file}` (public, immutable cache).
- Poll wire shape inside `message.poll`:
  `{id, question, closed, options:[{id, text, position, voteCount, votedBy:[userId]}], totalVotes, myOptionId}` — **NO `multiple`, NO `closesAt`** (server is single-choice + manual close only).
- LinkPreview shape inside `message.linkPreview`: `{url, title, description, imageUrl, siteName}` (all string|null).
- Saved list: `GET /api/users/{id}/saved` → `{items:[{savedAt, conversation:{id,isGroup,name}, message}]}` — newest-first, cap 100, NO server pagination/search.
- Topics: `GET /api/conversations/{id}/topics?userId=` → `{topics:[{id,name,emoji,lastMessageAt,messageCount}]}` (General is NOT a row); `POST …/topics {userId,name(1..32),emoji?}` → 200 existing / 201 `{topic}` (case-insensitive dedupe); `DELETE /api/topics/{id}?userId=` → `{ok:true}` (creator/admin only); messages GET accepts `&topicId=`; send body accepts `topicId` (bumps Topic.lastMessageAt).
- Transcribe: `POST /api/messages/{id}/transcribe {requesterId}` → 200 `{transcript, transcribedAt, cached}` — **requires `kind==="audio"`**; 422 empty ASR; 502 service down. Cache-hit path returns `cached:true`.
- View-once: `POST /api/messages/{id}/viewed {userId}` → 200 `{message}` — idempotent, first non-sender open stamps `viewedAt/viewedBy`; sender 400. Relay `message:viewed` → all members except consumer.

## 1. WEB BEHAVIOUR → NATIVE DECISION → REASON (mandatory table for the report)

| # | WEB BEHAVIOUR | NATIVE DECISION | REASON |
|---|---|---|---|
| 1 | Voice notes sent with `kind` omitted → DB row `kind='text'` → `/transcribe` always 400 | Send `kind:"audio"` | Web bug (worklog W1): transcription unusable on web-recorded notes. Server whitelist accepts `audio`. |
| 2 | Poll card derives pick from `votedBy` because server `myOptionId` is null on history GET and **actor-relative on relayed rows** | Derive own pick ONLY from `options[].votedBy.includes(myId)`; never render `myOptionId` | Vote/close relay maps the row with the ACTOR as viewer → every recipient would see the voter's pick as "mine". Proven bug. |
| 3 | Poll UI blocks revote after picking (API allows move-the-vote) | Match Web: no revote once picked | Web = behavioural specification for this wave. |
| 4 | Polls: single-choice only, manual creator close, no deadlines, no multi-select | Match Web capability envelope | Backend has no `multiple`/`closesAt`. Documented as product capability, not a bug. |
| 5 | View-once send UI ABSENT on Web (no tray tile, no photo-drawer switch) | ADD send toggle in the photo caption drawer (image-only) | Wave 2 mandate: "native send UI". Server accepts `viewOnce:true`+imagePath. |
| 6 | View-once burn is render-only; server never wipes `imagePath`; blurred real image stays in DOM; image URL fetchable forever | Once `viewedAt != null` (local or wire), native removes ALL render paths of the image (no blurred original, no lightbox entry, no preview cache of that URL for the gated row) | Anti-replay hardening mandated by Wave 2 ("prevent replay after consumption"). Server cannot be changed (frozen). |
| 7 | Group view-once: first viewer burns for everyone (single `viewedAt`) | Match Web | Server truth; document as known behaviour. |
| 8 | Link unfurl triggered only by the original sender's client, fire-and-forget after send | Same: after OWN successful send whose content matches `https?://` or bare `www.`, call `POST /api/messages/{id}/unfurl {userId}` once, ignore result; incoming `link:preview` envelope upserts row | Parity + natives must self-trigger (master-spec note). |
| 9 | Topics: "General" chip = WHOLE room (no `topicId` filter); topic views filter `topicId=`; no topic socket events; web patches topic cache via 3.5 s poll | Same semantics, BUT live: incoming `message:new` rows already carry `topicId`; native store filtering (Room Flow / GRDB observation) updates the open topic view instantly. Counts refresh on open + after own send + 15 s poll | Web 3.5 s lag is a client-cache artifact; native persistence layer gives live topic views for free. Improvement, not a bug copy. |
| 10 | Thread replies never filed to a topic (web `!parentId` guard) | Same: omit `topicId` on thread replies | Parity. |
| 11 | Voice: tap-to-start (not hold), 600 ms minimum discard, composer replaced by record bar | Match | UX parity. |
| 12 | Voice speed chip global pref 1x→1.5x→2x | Persist platform-side (Android DataStore prefs `voiceRate`; iOS UserDefaults `pulse.voiceRate`), applied live + on (re)start | Parity (`pulse.settings.v1.voiceRate`). |
| 13 | Voice waveform is decorative (id-hashed bars), not amplitude | Match (deterministic bars from message id) | Server sends no waveform data; parity honest. |
| 14 | Saved library: 100-item cap, no server pagination/search | Local filter/search over fetched items; "open original" = navigate room + jump-to-message + flash | Server frozen; Wave 2 asks for local capability. |
| 15 | Transcription strip: show `transcript` if present else "Transcribe" pill (not on optimistic rows) | Same | Parity. |

## 2. PLATFORM WORK BREAKDOWN

### 2.1 ANDROID (apps/android)
- **Room v5 → v6** (additive; follow MIGRATION_4_5 template; export `6.json`; extend RoomMigrationTest):
  - `messages` ADD: `viewedAt TEXT, transcript TEXT, transcribedAt TEXT, pollJson TEXT, linkPreviewJson TEXT, topicId TEXT`
  - NEW `topics(id TEXT PK, conversationId TEXT NOT NULL, name TEXT NOT NULL, emoji TEXT NOT NULL, lastMessageAt TEXT, messageCount INTEGER NOT NULL DEFAULT 0)` + index on `conversationId`
  - NEW `savedMessages(messageId TEXT PK, conversationId TEXT NOT NULL, savedAt TEXT NOT NULL)`
- **Protocol**: typed `PollDto`/`PollOptionDto`/`LinkPreviewDto` decode from `ChatMessageDto.poll/linkPreview` JsonElements (tolerant); `SavedItemDto{savedAt, conversation{id,isGroup,name}, message}`; `TopicDto`; `TranscribeResultDto{transcript, transcribedAt, cached}`; envelope events already subscribed (message:viewed/poll:voted/link:preview auto-upsert).
- **Domain Message** adds: `viewedAt, transcript, transcribedAt, poll: PollInfo?, linkPreview: LinkPreviewInfo?, topicId` + `fun pollPick(viewerId): String?` (votedBy-derived ONLY).
- **PulseApi** new: `transcribe(messageId)`, `markViewed(messageId)`, `createPoll(convId, question, options)`, `votePoll(pollId, optionId)`, `closePoll(pollId)`, `unfurl(messageId)`, `savedList(userId)`, `topics(convId, userId)`, `createTopic(convId, userId, name, emoji)`, `deleteTopic(topicId, userId)`, `messages(convId, limit, before, topicId?)` (+topicId param).
- **Repository**: methods wrapping the above with cache upserts (poll/vote/close/unfurl/viewed → upsert returned message; savedList → upsert messages + savedMessages rows; topics CRUD → TopicDao; transcribe → patch cached row).
- **UI (feature-chat)**: mic button (draft empty) → tap-to-start record bar (cancel X + timer + send) → MediaRecorder AAC/MPEG_4 → dataURL upload → `sendMediaMessage(kind="audio", audioPath, durationMs rounded to 100ms, min 600 ms)`; `VoiceBubble` interactive (play/pause MediaPlayer, deterministic bars recolored by progress, duration, speed chip 1x/1.5x/2x persisted, API 23+ `setPlaybackParams` guard); transcript strip; runtime `RECORD_AUDIO` flow (manifest already declares it) with denial notice.
- **View-once**: photo staged card gets view-once switch (image-only); `MessageRow` gate: `viewOnce && !mine && viewedAt==null` → blurred render + "Tap to view once" overlay → tap: repo.markViewed + lightbox; `viewedAt != null && !mine` → dashed burn tombstone "Photo opened · gone forever" (no image render at all); sender always normal.
- **Polls**: AttachSheet "Poll" tile → PollBuilderSheet (question ≤140, 2–6 options, add/remove rows) → createPoll; `PollCard` in bubble (kind POLL or pollJson != null): bars + % + vote tap (disabled when closed/picked), footer votes, creator End button; realtime via envelope auto-upsert.
- **Link previews**: post-send unfurl trigger; `LinkPreviewCard` under bubble text (suppressed on poll rows): thumbnail (Coil) max-h, title, 2-line description, siteName/host + Link2, tap → ACTION_VIEW browser intent; absent-then-arrives flow via envelope.
- **Saved library**: dock "Saved" menu item → route `saved` → `SavedLibraryScreen` (fetch → Room cache → rows "You/{sender} in {conv}", savedAt date, snippet with 🖼/🎤 prefixes, local search field, tap → navigate room + jump + flash, row action Unsave, honest empty/loading/error states).
- **Topics**: `TopicBar` under header in GROUP rooms (General chip + topic chips + counts + "+" create panel: name ≤32 + emoji row ['💬','🎨','🚀','🧠','🎉','🛠️','📌','☕']); VM `activeTopicId` state (null = General = unfiltered); sends attach `topicId` when active && no replyTo/parent; topic view fetch uses `messages(convId, topicId=)`; counts: load on open + after own send + 15 s poll; live topic view via Room flow filter (improvement #9).

### 2.2 iOS (apps/ios)
- **GRDB v3 → v4** (additive, same pattern as v3): `message` ADD `viewedAt TEXT, transcript TEXT, transcribedAt TEXT, pollJson TEXT, linkPreviewJson TEXT, topicId TEXT`; NEW `topics`, `savedMessages` tables; `upsert(messages:)` persists new columns; rehydration codec rebuilds them.
- **WireDtos**: `WireChatMessage` + `viewedAt, viewedBy, transcript, transcribedAt, topicId, linkUrl, linkPreview: WireLinkPreview?, poll: WirePoll?`; `WirePoll{id,question,closed,options:[WirePollOption{id,text,position,voteCount,votedBy:[String]}],totalVotes,myOptionId}`; `WireLinkPreview{url,title,description,imageUrl,siteName}`; `WireSavedItem{savedAt, conversation{id,isGroup,name}, message}`; `WireTopic{id,name,emoji,lastMessageAt,messageCount}`; `WireTranscribeResult{transcript,transcribedAt,cached}`; `WireTopicsPage{topics}`, `WireSavedPage{items}`.
- **PulseAPIClient** new methods mirroring §2.1 API list (+ `messages(conversationId:limit:before:query:topicId:)`).
- **UI**: record bar (AVAudioRecorder .aac → dataURL upload, tap-to-start, min 600 ms, cancel); `AVAudioPlayer` playback with rate chip + progress (mic permission via `AVAudioSession.requestRecordPermission`, NSMicrophoneUsageDescription already present); view-once gate/burn rows (send toggle in staged photo bar; burn = no image render); `PollBuilderSheet` + poll bubbles + vote/close; `LinkPreviewCard` + unfurl trigger; `SavedLibraryView` (list + search + jump-open + unsave; wire dock "Saved" — currently routes to Note-to-Self — to the real screen); `TopicBar` + create/switch/filtered fetch + live topic updates via store observation.
- Keep `PulseOutboxEngine` semantics UNCHANGED (media/voice never queue — documented Web parity).

## 3. TESTING GATE
- Android JVM: poll decode + votedBy-pick derivation, view-once state machine, voice duration rounding (`Math.max(1, round(ms/100)*100)`), DTO parity incl. saved/topic/transcribe shapes.
- Android instrumented: RoomMigrationTest v5→v6 (raw-DDL v5 → v6 open), topics/savedMessages/viewedAt DAO round-trips.
- iOS: WirePoll/WireLinkPreview/WireSavedItem/WireTopic decode; PulseStoreMigrationTests v3→v4 round-trip incl. pollJson; store topic/saved helpers. Mic-dependent behaviour: code-reviewed only; honest-skip pattern for anything needing TCC (CI cannot grant mic).
- Live E2E gate `apps/qa/wave2-depth-gate.js` (local stack): voice upload+send kind:audio+duration, transcribe contract (200→cache-hit second call, or 422/502 = ENV-LIMITED note), view-once send→view→relay→burn idempotency, poll create→vote(myOptionId actor-relative proof)→close→frozen, unfurl example.com + link:preview relay, save→list→unsave, topics create→dedupe 200→file→filter→General-includes-topic.
- Chain ONLINE→REALTIME→PERSISTENCE→RECONNECT→OFFLINE→FLUSH still holds from Wave 1; Wave 2 features ride the same envelope/outbox rules.

## 4. NON-GOALS
No backend/prisma/pulse-socket changes. No multi-select polls, deadlines, revote UI, voice-note outbox queueing, server-side view-once media wipe (frozen backend), topic socket events. No Wave 3 families (calls/stories/hub/games/voice rooms).
