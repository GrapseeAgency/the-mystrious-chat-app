# PULSE — NATIVE ARCHITECTURE + WEB PARITY MASTER SPECIFICATION

**Status:** SPECIFICATION ONLY — nothing in this document has been implemented. No code was changed to produce it.
**Version:** 1.0 · **Scope:** `apps/android` (Kotlin/Compose), `apps/ios` (SwiftUI), `packages/protocol`, shared Next.js backend, `mini-services/pulse-socket`.
**Input:** source-verified inspection of the entire repo (2025). Native Android state at ship `v0.1.7-native` (versionCode 8). iOS state at commit `2589b03`.

---

## 0. PROVENANCE & METHOD (honesty note)

- The Web Forensic Audit deliverables ("Reports 1–3") were produced in-session and **do not exist as files in this repository** (verified: repo-wide search for report/audit artifacts returned nothing). The session context carrying them was consumed.
- Therefore the authoritative feature inventory used here was **rebuilt directly from source code** — the same ground truth the audit used — and is reproduced in §2 with file-path citations. Nothing in this document relies on unverified memory of the reports.
- Every claim about existing native code below was verified by reading the actual files (paths cited). Claims are classified as **VERIFIED** (read in source), **DORMANT** (coded but unreachable/unused), or **STUB/DEAD-END** (explicit placeholder).
- Per the product directive: **natives must preserve the product's behaviour and capabilities, not reproduce the web's UI.** Where the web does something that is a web-implementation artifact (hash URLs, 13 nav architectures, 420 px phone frame, sessionStorage identity), this spec defines the native-native equivalent instead of cloning.

### 0.1 Product-over-pixel rules (binding for both platforms)

| # | Rule |
|---|---|
| P1 | Every §2 feature maps to a native *capability*, rendered with platform-idiomatic components (Material 3 on Android, SwiftUI/HIG on iOS). Never port DOM layout. |
| P2 | Web-only presentation systems (13 nav architectures, 5 UI themes, phone-frame shell, hash-URL routing) are **deliberately not ported**. Natives ship ONE idiomatic navigation shell (the existing CapsuleDock) and light/dark + platform dynamic color. |
| P3 | Web sheets → native bottom sheets (M3 `ModalBottomSheet` / SwiftUI `.sheet` + `.fullScreenCover` for immersive surfaces like calls, story viewer, voice rooms). |
| P4 | Web toasts → Android `Snackbar` / iOS capsule toast (already exists). Web WebAudio ping + vibration → native notification sound + haptics APIs. |
| P5 | Motion: port the *feel* (4 spring presets, 0.94 press scale, 28 ms stagger, particle celebrations), implemented with native animation systems — never re-implement framer-motion. |
| P6 | Accessibility: natives follow OS accessibility (TalkBack/VoiceOver, system Reduce Motion, Dynamic Type) as first-class, not an in-app toggle clone. |
| P7 | Offline: natives must be **at least** as capable as web (outbox, drafts, offline cache, offline onboarding) and are expected to exceed it (persistent storage, background sync, push). |
| P8 | No feature is "done" on native without its acceptance test (§4) passing against the live gateway. |

---

## 1. GROUND TRUTH — WHAT EXISTS TODAY (source-verified)

### 1.1 Web (the parity target)

**Backend:** Next.js 16 App Router. **42 Prisma models** on SQLite (`prisma/schema.prisma`). **~95 REST route files** under `src/app/api/**` (full enumeration in the worklog, Task 2-c). Realtime: `mini-services/pulse-socket` on port 3003 — Socket.IO v4, **zero DB access**, privacy flags pulled from `GET /api/internal/privacy` (fail-open, 30 s TTL). Rooms: `user:{id}`, `voice:{conv}`, `stage:{conv}`, `space:{conv}`.

**Socket events (actual service contract — supersede the stale `SOCKET_EVENTS` subset in `packages/protocol/src/contracts.ts`):**
- C→S: `join{userId}` · `typing` · `voice:join|leave|ptt|chunk` · `voice:transcript` · `stage:join|hand|approve|mute|end|leave` · `space:join|move|leave` · `call:offer|answer|ice|reject|cancel|hangup`
- S→C: `joined{onlineUserIds}` · `presence:snapshot` · `typing` · `message:new|deleted|read|react|edited|pinned|viewed` · `poll:voted` · `link:preview` · `translation:added` · `conversation:updated` · `voice:roster|ptt|chunk|transcript` · `stage:state|ended` · `space:state` · `call:offer|answer|ice|reject|cancel|hangup`
- HTTP relay: `POST /notify {event, recipients[], payload}` (11 whitelisted events) + `POST /typing`. Health: `GET /`.
- Transport (web client): polling-first through the Caddy gateway (`/?XTransformPort=3003`), ws upgrade, reconnect 800 ms→5 s cap.

**Client state:** 7 localStorage keys (`pulse.prefs.v1`, `pulse.settings.v1`, `pulse.drafts.v1`, `pulse.outbox.v1`, `pulse.uiTheme.v2`, `pulse.navStyle.v2`, `pulse.pip.v2`) + 1 sessionStorage (`pulse.session.v1` — per-tab identity).

**Key web behaviours with no web-native push:** notifications are **in-app only** (sonner toasts, WebAudio ping, `navigator.vibrate`, quiet hours). Calls are **real WebRTC** (`RTCPeerConnection`, Google STUN, no TURN — P2P only on open NATs). Offline: SW offline shell (network-first, `/api` & socket never intercepted), outbox FIFO ≤50 with `temp-` optimistic bubbles, per-conversation drafts with 600 ms server mirror.

**Surfaces:** 16 hash routes, ~40 named sheets/overlays, 9 settings sections (one — "Real-time & Voice" — is display-only), Hub with 6 live panels + 100-app catalog.

### 1.2 Android today (verified)

Multi-module Gradle (11 modules: `:app :core :protocol :domain :data :ui :feature-chat :feature-calls :feature-stories :feature-hub :feature-settings`), Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01, **Hilt 2.53.1** DI, **Ktor 2.3.12** REST, **`io.socket:socket.io-client` 2.1.0** (Socket.IO v4-compatible), **Room 2.6.1** (v3, 2 entities, destructive migration), DataStore prefs. `applicationId app.pulse.chat`, minSdk 21 / target 35, Java 17 + desugaring, committed release keystore with v1+v2+v3 signing, `isMinifyEnabled=false`.

- Shipped: **v0.1.7-native (versionCode 8)** via GitHub CI → Release asset → CDN `download/Pulse.apk` + `update-manifest.json`; **LiveUpdater** (563 LOC): manifest check → byte-range resumable download → 4 integrity gates (ZIP magic, PM parse+versionCode, CRC sweep, sha256) → PackageInstaller session with FileProvider fallback.
- REAL surfaces: onboarding (create/reclaim/handle-check with offline fallback chain), chats inbox (poll, optimistic flags, filters, folders/stories/mentions *data*, Note to Self, archived page, multi-select, swipe actions, mute windows, export .txt, clear chat), chat room (text send/reply/reactions/typing/seen receipts), contacts (roster/DM/block/report), profile (identity, appearance, FX, motion), global server search, ambient FX (AGSL RuntimeShader aurora/mesh/stars on API 33+, Canvas fallback), particle bursts, theme system.
- STUB/DEAD-END (exact anchors): dock compose + More{Settings, Saved, Stories} toasts (`MainActivity.kt:356,663,679,687`); header calls/compose toasts + StoriesRail/Folder-manage/Mentions/Channels toasts (`ChatsScreen.kt:302,327,340,399,409`); `CallsScreen` (19 LOC) and `StoriesScreen` (18 LOC) static and unrouted; `HubScreen` static tiles; room attach `onClick={}` (`ChatRoomScreen.kt:208`) and call `onClick={}` (`:343`); IMAGE/VOICE/FILE bubbles are placeholders (no capture/playback/load); no pin in message sheet; declared-but-dead: `pulse://` deep link filter, POST_NOTIFICATIONS/RECORD_AUDIO/CAMERA (no runtime use), socket relay (`PULSE_SOCKET=""` baked), WorkManager, `ktor-client-websockets`, `me()` API.
- Offline: Room read cache + optimistic flags; **no outbox**; send failure → error surface with refetch-only retry.

### 1.3 iOS today (verified)

XcodeGen (`project.yml`): iOS 17.0, iPhone-only portrait, `CODE_SIGNING_ALLOWED=NO` (unsigned; no entitlements → no push/associated domains), SPM: **GRDB 6.29.3** + **socket.io-client-swift 16.0.1**. 30 Swift files / ~9.1 k LOC. Real Socket.IO client coded but **dormant** (`PulseEndpoints.socketURL` returns nil → session skips construction). Metal FX (3 `[[stitchable]]` kernels: aurora/mesh/stars; Canvas approximations for caustics/liquid). CapsuleDock + per-tab NavigationStack, `pendingOpenRoom` bridge, wired compose/Settings/Saved/Stories dock items.

- REAL surfaces: onboarding (create/reclaim/handle-check incl. offline `local_` identity), identity switcher, chats list (REST poll 6 s, optimistic pin/mute/archive/unread, batch bar, swipe chips, streak chips, draft preview, export/clear), room (text send/reply/reactions/read receipts, context menus, typing), New Chat sheet (DM+group), contacts (roster, block; **report dialog state exists but is unwired**), Settings probe, Profile, Hub wallet only, stories fetch with stub viewer.
- STUB/DEAD-END (exact anchors): header phone + compose toasts (`ChatsView.swift:169,173`), story-ring/folder-manage/Mentions/Channels toasts (`:197,210,300,310`), story viewer toast (`StoriesView.swift:81`), Hub tiles (`HubView.swift:81-86`), attachment no-op (`ChatRoomView.swift:176-183`), image/video/file/voice placeholder bubbles (`:327-368`), no per-message delete/edit/pin UI, GRDB **write-mostly** (read paths defined-never-called → no relaunch rehydration), inert `isActive` poller lifecycle (`ChatsView.swift:72`), stale `prefsFilter` wiring (`:2458`), no outbox/drafts.

### 1.4 Shared protocol artifacts

- `packages/protocol/src/contracts.ts`: wire types (WireUser, WireChatMessage 33 fields, WireConversationSummary 23 fields, reactions grouped-by-emoji) + `failureKindFor` status taxonomy. **Stale**: its `SOCKET_EVENTS` list is missing `message:react|edited|pinned|viewed`, `poll:voted`, `link:preview`, `translation:added`, `conversation:updated`, and the entire voice/stage/space family. `pulse-protocol-v1.schema.json` (draft-07) pins the REST DTOs only.
- Native mirrors (hand-written, test-pinned): Android `protocol/WireDtos.kt` (20 DTOs + tolerant `PulseJson`) with `LiveGatewayParityTest` (live HTTP, `assumeTrue` skip); iOS `Domain/Models/WireDtos.swift` (440 LOC) with `WireParityTests` (captured live-JSON fixtures + Android-parity status-kind test).

### 1.5 Build / CI today

- `android-ci.yml`: push/PR paths `apps/android/**`+`packages/**` → JDK 21 temurin → `:domain:test :protocol:test :core:build` → `:app:assembleRelease` (committed keystore) → artifact `pulse-release-apk` → tag-triggered GitHub Release. No instrumented tests, no lint gate, no R8.
- `ios-ci.yml`: macos-15 → xcodegen → build (simulator, unsigned) → unit tests (gated on simulator availability) → simulator artifact. No archive/IPA, no TestFlight lane (needs signing + Apple Developer Program — external dependency).
- `web-ci.yml` covers the web app. CDN = same repo (`download/`, `registry/handles.json`, `update-manifest.json`).

---

## 2. PARITY INVENTORY — EVERY WEB FEATURE → NATIVE REQUIREMENTS

Legend: `—` = no requirement of that kind. "GW" = live Next.js gateway (same API web uses — natives consume it unchanged unless marked **[BACKEND ADD]** / **[BACKEND CHANGE]**). IDs (`F-xx-nn`) are the traceability keys used in §4.

### 2.A Identity & Session

| ID | Web feature | ANDROID requirement | iOS requirement | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-ID-01 | Create identity (name ≤32 + color swatch) | Onboarding name step (exists; retain) | Onboarding name step (exists; retain) | `POST /api/users` | — | DataStore viewer / Keychain token (§3.11) | Offline `local_` identity (both already do) | — | Success haptic | — | Confetti burst on completion (exists) |
| F-ID-02 | Handle claim: debounced availability, reserved/taken, suggestion, skip | Handle step exists (350 ms debounce, staleness guard, registry fallback, 60 s backoff) — retain | Same — exists — retain | `GET /api/users/check-username` + `GET /registry/handles.json` | — | — | Registry → local reserved list fallback (both) | — | — | — | "is free!" affordance animation |
| F-ID-03 | Reclaim existing identity (409 name clash → lookup/login) | `loginInstead` flow exists — retain | Exists — retain | `GET /api/users?name=` | — | — | Honest 404/transport errors | — | — | — | — |
| F-ID-04 | Session identity model | Web uses per-tab sessionStorage. Native = **single active viewer, persistent across launches**, explicit switcher. **[BACKEND ADD]** session token (§3.11) stored in Keystore | Same; token in **Keychain** | **[BACKEND ADD]** token issue on create/reclaim; socket `join{userId,token}` | Re-`join` on reconnect + app foreground | DataStore / Keychain | Viewer survives process death (already true) | — | — | — | — |
| F-ID-05 | Multi-identity switcher | Profile switcher sheet exists — retain; add token per identity | IdentityPickerSheet exists — retain | `GET /api/users`, `POST /api/users` | `join` re-emitted per switch | List of viewers persisted | Switchable offline (cached roster) | — | — | — | Sheet spring |
| F-ID-06 | Forget viewer | Exists | Exists (identity swap = re-pick; add explicit forget) | — | disconnect socket | Clear DataStore/Keychain viewer rows | — | — | — | — | — |
| F-ID-07 | Avatar color palette (8) | Exists | Exists | SerializerPalette names emerald…cyan | — | — | — | — | — | — | — |

### 2.B Conversations list (home)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-CL-01 | Summary list sorted by `updatedAt`, preview builders (text/photo/voice/file/reply/deleted/draft) | Room-backed list exists — extend preview builders to real media thumbnails | Exists — same | `GET /api/conversations?userId=` | `message:new` → 500 ms debounced refresh (both coded) | Room `ConversationEntity` / GRDB `conversation` | Full offline read (Android yes; **iOS: wire GRDB read paths**) | — | Dock unread badge (exists both) | Thumbnail decode | Entrance stagger 28 ms (exists both) |
| F-CL-02 | Unread counts, dock badge 99+ | Exists | Exists | unreadCount in summary | `message:new`/`message:read` | — | — | — | Badge increments live | — | Badge pop |
| F-CL-03 | Pin to top (per-user toggle) | Exists (optimistic + PATCH) | Exists | `PATCH /api/conversations/{id}/pin` (server toggles) | — | pinnedAt cached | Optimistic + rollback (both coded) | — | — | — | — |
| F-CL-04 | Mute 8 h / 1 w / always (+ "Muted until stamp" copy) | Exists | Exists | `PATCH …/mute {userId,until}` | — | mutedUntilEpoch | Optimistic (both) | — | Muted rows suppress badge/toast on native too | — | — |
| F-CL-05 | Archive + archived sub-page | Exists (real page) | Exists (`fullScreenCover`) | `PATCH …/archive` | — | archivedAt | Optimistic | — | Archived excluded from badge | — | Swipe-reveal chip |
| F-CL-06 | Mark unread (manual) | Exists | Exists | `PATCH …/mark-unread {on}` | — | manualUnread | Optimistic | — | Dot on row | — | — |
| F-CL-07 | Filter chips all/unread/groups (persisted) | Exists (DataStore) | Exists (UserDefaults; **fix stale re-filter wiring `prefsFilter`**) | — | — | Pref key | Applies offline | — | — | — | Chip spring |
| F-CL-08 | Note to Self (get-or-create) | Exists | Exists | `POST /api/conversations/self` | — | — | Cached | — | — | — | — |
| F-CL-09 | Streaks live/at-risk/lost + heat ring | Exists (heat ring 2-4/5-9/10+) | Exists (StreakHeatRing) | `myStreak/deadStreak/lostStreak` in summary | — | — | — | — | — | — | Conic heat ring (exists) |
| F-CL-10 | Drafts: per-conv, list preview "Draft:", server mirror | **Build**: Room draft column already exists in entity (`myDraft`); add local composer-draft persistence | **Build**: add draft store (GRDB) + preview | `GET/PATCH /api/conversations/{id}/draft` | — | Local draft key + server mirror | Restore draft offline (web does; native must too) | — | — | — | — |
| F-CL-11 | Presence dots + "N online" | Exists (socket coded, dormant) | Exists (dormant) | — | `join`→`joined`/`presence:snapshot` (privacy-filtered) | — | Hide dots when offline | — | — | — | Presence glow |
| F-CL-12 | Typing preview in rows (4 s expiry) | Coded (dormant) | Coded (dormant) | `POST /typing` relay | `typing` | — | — | — | — | — | TypingDots (exists both) |
| F-CL-13 | Swipe-left reveal Pin/Archive | Exists (Compose swipe, threshold) | Exists (DragGesture) | — | — | — | — | — | — | — | Glass chip reveal (exists) |
| F-CL-14 | Long-press multi-select + batch archive/mute-8h/mark-read | Exists | Exists | Batch = sequential single calls | — | — | Partial-failure toasts | — | — | — | MultiSelectBar (exists both) |
| F-CL-15 | Export chat `.txt` (full history ≤24 pages) | Exists (cacheDir file + share) | Exists (UIActivityViewController) | `GET …/messages?limit=500&before=` ×N | — | Temp file | Requires network (honest error) | — | — | — | Share sheet |
| F-CL-16 | Clear my messages (sequential soft-delete) | Exists | Exists | `DELETE /api/messages/{id}` ×N | `message:deleted` fan-out | Room/GRDB delete | — | — | Confirm dialog | — | — |
| F-CL-17 | Pull-to-refresh, skeletons, empty/error cards | Exists (all four) | Exists (pull) / skeleton / empty / error | — | — | — | Retry button | — | — | — | Shimmer skeleton |
| F-CL-18 | Foreground 6 s poll | Exists (ViewModel poller) | Exists but **fix inert `isActive` lifecycle** — start/stop on tab visibility | — | Poll is the no-socket fallback | — | Silent-fail while offline | — | — | Battery: poll only when foregrounded | — |
| F-CL-19 | Conversation action sheet (pin/archive/unread/mute/export/clear) | Exists | Exists (ChatActionSheet) | as above | — | — | — | — | — | — | M3 bottom sheet / iOS sheet |

### 2.C Messaging core (room)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-MS-01 | Send text ≤2000 (server MESSAGE_MAX) | Exists (SendMessageUseCase, 4000 client cap — **align to 2000**) | Exists | `POST …/messages {senderId,content}` → `{message}` | `message:new` to recipients | Room/GRDB message | **Build outbox** (F-MS-25) | — | — | — | Send burst 22-26 particles (exists both) |
| F-MS-02 | Formatting: ```pre```, `code`, **bold**, __underline__, *italic*, ~~strike~~, \|\|spoiler\|\| | **Build**: parser + styled spans; spoiler = tap-to-reveal | **Build**: AttributedString parser + blur spoiler | — (content is raw markdown-ish text) | — | — | — | — | — | — | Spoiler reveal animation |
| F-MS-03 | Jumbo-emoji detection (emoji-only → large bubble) | **Build** | **Build** | — | — | — | — | — | — | — | Scale pop |
| F-MS-04 | Edit own text message | **Build** (wire `PATCH /api/messages/{id}` + composer edit mode) | **Build** | `PATCH /api/messages/{id}` (sender-only) | `message:edited` | updatedAt/editedAt column (Room has) | Update cache on event | — | — | — | Inline edit transition |
| F-MS-05 | Delete for everyone → tombstone | **Add menu row to action sheet** (API + UI) | **Build** (no per-message delete UI today) | `DELETE /api/messages/{id}` | `message:deleted` (tombstone payload) | soft-delete column | Tombstone replace in cache | — | — | — | Tombstone fade |
| F-MS-06 | Reply quote (incl. deleted variant) | Exists (reply above composer + quote bubble) | Exists | `replyToId` on send; replySnippet wire shape | — | replyTo columns | Render from cache | — | — | — | Quote scroll-jump flash (web parity) |
| F-MS-07 | Threads ("Reply in thread", thread view) | **Build** (room thread sheet; `GET /api/messages/{id}/thread`) | **Build** | `parentId` self-rel; `GET …/thread` | `message:new` within thread | threadRootId column (GRDB has) | Thread history cached | — | Thread unread surface (follow web) | — | Sheet |
| F-MS-08 | Reactions: 6 quick + 24 picker, grouped chips, who-reacted, toggle | Exists (6 quick; **add 24-picker + who-reacted**) | Exists (6 quick; same additions) | `POST /api/messages/{id}/react` (toggle) | `message:react` (fresh message) | reactionsJson column | Cache update | — | — | — | Bouncy pop (exists); ❤️ hearts burst (exists) |
| F-MS-09 | Copy text | Exists (sheet) | Exists (contextMenu) | — | — | — | — | — | — | — | Clipboard toast |
| F-MS-10 | Forward to chat… (multi-target) | **Build** (ForwardSheet parity: pick chats → send copy) | **Build** | Re-`POST …/messages` per target | `message:new` | — | Queued if offline | — | — | — | Sheet |
| F-MS-11 | Save/star → Saved library | **Build** (`POST /api/messages/{id}/save` + `GET /api/users/{id}/saved` page) | **Build** | save toggle + library GET | — | — | Library cached | — | — | — | — |
| F-MS-12 | Pin message + room pins sheet | **Build** (Android sheet accepts pin param but offers no row; iOS has none) | **Build** | `POST /api/messages/{id}/pin`; `GET …/pinned` | `message:pinned` | pinnedAt column | — | — | — | — | Sheet |
| F-MS-13 | Convert message → task | **Build** (Kanban card from message; `POST …/kanban {sourceMessageId}`) | **Build** | kanban POST | — | — | — | — | Toast confirm | — | — |
| F-MS-14 | Remind me (picker → reminder) | **Build** (RemindersSheet + due loop) | **Build** | `POST /api/reminders` | — | Reminders cache | Local scheduling (see notifications W-PS-05) | Notifications | **Local notification at remindAt** | — | Time picker |
| F-MS-15 | Message info (owner: delivered/viewed, timestamps) | **Build** | **Build** | Compose from message fields | — | — | — | — | — | — | Sheet |
| F-MS-16 | View-once (burn after view) | **Build**: render sealed bubble → tap → `POST /api/messages/{id}/viewed` → reveal → burned state | **Build** | `viewOnce/viewedAt/viewedBy`; `POST …/viewed` | `message:viewed` | viewedAt cached | Sealed state persists | — | — | — | Burn/shatter effect (web has; port as particle burst) |
| F-MS-17 | Incognito send (anon + stable alias FNV-1a) | **Build** (composer toggle + alias preview) | **Build** | `anon/anonAlias` fields on send | — | — | — | — | — | — | Toggle reveal |
| F-MS-18 | Scheduled messages + scheduled manager | **Build** (ScheduleSheet + manager; list/cancel via API) | **Build** | `GET/POST …/scheduled`, `DELETE /api/scheduled/{id}`; dispatch cron `POST /api/maintenance/dispatch` | `message:new` when dispatched | Scheduled cache | Cancel offline queued | Notifications | Local echo optional; real message arrives via socket/push | — | DateTime picker |
| F-MS-19 | Disappearing TTL (conversation) | **Build** (info-page picker; render expiry state; hide expired) | **Build** | `PATCH …/disappearing {ttlSeconds}`; server stamps `expiresAt` | `message:new` carries TTL | expiresAt column | Skip expired on cache load | — | — | — | TTL chip in header |
| F-MS-20 | Slow mode (429 + retryAfter countdown on composer) | **Build** (ApiError.retryAfter → composer lockout timer) | **Build** | `PATCH …/slow-mode`; 429 in send | — | — | — | — | Honest 429 toast | — | Countdown ring |
| F-MS-21 | Broadcast composer lock (channels/stage non-admin) | **Build** (locked composer state both platforms) | **Build** | `broadcastMode` + role from summary/members | — | — | — | — | "Only admins can post" notice | — | Locked composer visual |
| F-MS-22 | Slash commands (15) + SlashPalette | **Build** (palette + parsing; `/help` local, others server-embedded in send) | **Build** | Commands are text; bots react (T-AI-02) | `message:new` | — | — | — | — | — | Palette spring |
| F-MS-23 | Message effects (confetti/lasers/echo/sparkles full-screen) | **Build** (particle host exists; add 4 effect kinds on receive of effect-flagged messages) | **Build** (ParticleBus exists; add kinds) | Effect encoded in message payload | `message:new` | — | — | — | — | GPU particle layer | Full-screen FX (web message-effects parity) |
| F-MS-24 | Sticker picker (24) | **Build** (sticker grid → kind=sticker message) | **Build** | `kind:"sticker"` + payload | `message:new` | — | — | — | — | — | Sticker bounce |
| F-MS-25 | Offline send → outbox FIFO ≤50, `temp-` bubble, flush on online/visible/20 s, stop-at-first-failure | **Build**: Room outbox table + WorkManager expedited flush + connectivity trigger | **Build**: GRDB outbox + BGTask/foreground flush | Same send API on flush; temp id swap | `message:new` echoes back | Outbox table (persistent) | **Core offline capability** | — | Delivered toast on flush | — | Temp bubble styling |
| F-MS-26 | Delivery ticks: sent → delivered → read ("Seen") | Exists for read (dormant socket) — keep; delivered = ack of POST success | Exists (dormant) | read via `POST …/read` | `message:read` | lastReadAt | — | — | — | — | Tick transition |
| F-MS-27 | Typing emit (start/1.2 s idle stop) | Exists | Exists | — | `typing` | — | — | — | — | — | — |
| F-MS-28 | History paging (`before` cursor) + day separators + reverse layout | Exists (Room paging) | Exists (limit 200 + before) | `GET …/messages?limit&before` | — | Paged cache | Reads from cache | — | — | — | Day separators (iOS has; Android has dayChip) |
| F-MS-29 | Quick phrases rail (CRUD) | **Build** | **Build** | `GET/POST/DELETE /api/users/{id}/phrases` | — | Phrases cache | Cached | — | — | — | Rail chips |
| F-MS-30 | Topic rail (per-room topics; scoped message view) | **Build** (TopicBar + topic filter) | **Build** | `GET/POST …/topics`, `DELETE /api/topics/{id}` | — | topicId column | — | — | — | — | Chip rail |

### 2.D Media (photos, documents, voice notes, links)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-MD-01 | Photo send: client square-crop ≤512 / compress ≤1280 JPEG 0.82 → data-URL POST → bubble | **Build**: CameraX/Photo Picker → Bitmap pipeline (reuse web ≤512 crop + 0.82 quality) → Ktor multipart-equivalent base64 POST → Coil display | **Build**: PhotosPicker/AVFoundation capture → UIImage pipeline → upload → AsyncImage | `POST /api/uploads` (base64 data-URL; media 4.5 MB cap) + `GET /api/uploads/{file}` | `message:new` (kind=image, imagePath) | imagePath cached; disk cache for bytes | Cache served images locally for offline read | Camera (capture), Photos (picker) | — | Camera, GPU decode | Lightbox pinch-zoom (web has lightbox) |
| F-MD-02 | Document send ≤10 MB (file card: name/size icon) | **Build**: system document picker (ACTION_OPEN_DOCUMENT) → base64 → upload → file card | **Build**: `fileImporter` → upload → card | `POST /api/uploads` (docs 10 MB cap) | `message:new` (kind=file) | filePath/fileName/fileSize cached | Re-download on demand | — | — | File system access via picker | — |
| F-MD-03 | Voice notes: hold-to-record ≥600 ms, waveform, playback 1×/1.5×/2×, duration | **Build**: MediaRecorder (AAC) → upload → waveform render (Android already draws deterministic bars; switch to real amplitude) → MediaPlayer with rate | **Build**: AVAudioRecorder → upload → waveform → AVAudioPlayer rate | `POST /api/uploads` (audio) ; `durationMs` on send | `message:new` (kind=voice) | audioPath cached | Cached playback | **RECORD_AUDIO (Android runtime) / mic usage (iOS plist already set)** | — | Microphone | Press-and-hold gesture with cancel-up-slide (native pattern) |
| F-MD-04 | Voice-note transcription (ASR strip under bubble) | **Build**: call transcribe on demand; render strip | **Build** | `POST /api/messages/{id}/transcribe` (cached on row) | — | transcript cached | Cached | Mic (already required for recording) | — | — | — |
| F-MD-05 | Link unfurl (OG card, live fetch, relay) | **Build**: render linkPreview card; client triggers unfurl for unseen links | **Build** | `POST /api/messages/{id}/unfurl` → LinkPreview cache | `link:preview` relay | linkUrl/linkPreview columns | Cache card | — | — | — | — |
| F-MD-06 | Translation (LLM, per lang, persisted) | **Build**: translate action → show per-lang text | **Build** | `POST /api/messages/{id}/translate` | `translation:added` | translations cache | Cached | — | — | — | — |
| F-MD-07 | Location share (pin card) | **Build**: picker (coarse location or map point) → kind=location payload → render card; optional deep-link to maps | **Build**: CoreLocation / MapKit card | `kind:"location"` + payload (lat/lng/label) | `message:new` | — | Render from payload | Location (optional — only if live location chosen; static pin needs none) | — | GPS (optional) | Map card tap → platform maps |
| F-MD-08 | Photo lightbox / viewer (tap → full-screen, captions) | **Build** (full-screen dialog + gestures) | **Build** (fullScreenCover) | Serves from `GET /api/uploads/{file}` | — | — | Cached images viewable offline | — | — | — | Pinch-zoom, swipe-dismiss |
| F-MD-09 | Upload durability (UploadedFile BLOB fallback survives disk wipe) | No native work (server behaviour) | No native work | Already implemented server-side | — | — | — | — | — | — | — |

### 2.E Rich objects & social play

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-RO-01 | Polls: builder, vote/move, close, live tally | **Build**: PollBuilderSheet + poll bubble + vote API | **Build** | `POST …/poll`; `POST /api/polls/{id}/vote|close` | `poll:voted` → tally update | Poll columns (Room has poll field) | Last tally cached | — | — | — | Tally bars animate |
| F-RO-02 | Red packets: send (atomic debit + precomputed slices), grab, refund, bubble art | **Build**: RedPacketSheet + bubble + grab flow | **Build** | `POST /api/redpackets`, `GET …/{id}`, `POST …/grab` (atomic) | `message:new` (kind=red_packet) | packet state cache | Grab requires network (honest fail) | — | — | — | Open-packet animation (web has; port as native spring + particles) |
| F-RO-03 | Whiteboard: live multi-user strokes, undo, clear | **Build**: Canvas sheet; debounce point-batches | **Build**: PencilKit-style Canvas (custom, same wire format) | `GET/POST/DELETE …/whiteboard` (points JSON 0..1) | Strokes arrive via `message:new`? — **no dedicated socket event today; web uses polling/notify relay — keep same transport** | Stroke cache | Local draft strokes | — | — | Touch stylus support (Android stylus / Apple Pencil) free | Stroke smoothing |
| F-RO-04 | Kanban: board CRUD, message→card | **Build**: KanbanSheet (3 columns, drag reorder) | **Build** | `GET/POST …/kanban`, `PATCH/DELETE /api/kanban/{cardId}` | — | Card cache | Cached board | — | — | — | Drag & drop (native DnD) |
| F-RO-05 | Events: create, RSVP going/maybe/no, check-in | **Build**: EventsSheet | **Build** | `GET/POST …/events`, `POST /api/events/{id}/rsvp|checkin`, `DELETE /api/events/{id}` | — | Event cache | — | Calendar (optional: add-to-calendar intent) | Optional local reminder | — | RSVP chips |
| F-RO-06 | Reminders: create, due list, fire | **Build**: RemindersSheet + due badge | **Build** | `GET /api/reminders[?due=1]`, `POST`, `PATCH/DELETE /api/reminders/{id}` | — | Reminder cache | Local schedule fires offline | Notifications | **Local notification at remindAt** (due loop polls API when online) | — | — |
| F-RO-07 | Tic-tac-toe: invite (open O seat), moves, win line, abandoned | **Build**: GameCard in-room + move API | **Build** | `POST /api/games` (match+invite msg), `GET /api/games?conversationId=`, `POST …/{id}/move|join` | Board refresh via `message:new` (invite msg) + poll on card visible | Board state cache | Move requires network | — | Your-turn surface (in-app; push later) | — | Win-line strike animation |
| F-RO-08 | Tournaments: open season, join, standings, finish | **Build**: TournamentSheet/card | **Build** | `POST/GET /api/tournaments`, `GET/PATCH /api/tournaments/{id}`, `POST …/join` | — | Standings cache | Cached standings | — | — | — | — |
| F-RO-09 | Leaderboard (XP + msgs + game wins + tournament pts; scoped/global) | **Build**: LeaderboardSheet | **Build** | `GET /api/leaderboard?…` | — | — | Cached last board | — | — | — | Rank rows stagger |
| F-RO-10 | XP economy (2/msg, 250/day cap) + streak buckets | No native logic — display only (profile/leaderboard) | Same | Server-side only (`src/lib/xp.ts`, serializers) | — | — | — | — | — | — | — |

### 2.F Voice rooms (PTT)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-VR-01 | Join/leave voice room, live roster | **Build**: VoiceRoomSheet parity → M3 sheet; roster from socket | **Build**: `.fullScreenCover` sheet | — (no REST) | `voice:join|leave` → `voice:roster` | — | Requires live socket (honest offline state) | Mic | In-call audio mode | Speaker routing | Speaker tiles animate on join |
| F-VR-02 | Push-to-talk: hold → unmute, PCM chunk relay, mute toggle | **Build**: AudioRecord 16 kHz PCM → base64 chunks → `voice:chunk` | **Build**: AVAudioEngine tap → chunks | — | `voice:ptt|chunk` (server relays to peers) | — | — | **RECORD_AUDIO runtime / mic** | — | Microphone, AudioTrack/AudioUnit playback | Hold-to-talk gesture + speaking glow |
| F-VR-03 | Live captions (~4 s PCM → ASR → ephemeral `voice:transcript`) | **Build**: reuse chunk pipeline; render caption strip | **Build** | `POST /api/voice/transcribe` (WAV 16 kHz) | `voice:transcript` | Ephemeral — nothing stored | — | Mic | — | — | Caption fade |
| F-VR-04 | Speaking indicators | **Build** (from `voice:ptt` state) | **Build** | — | `voice:ptt` | — | — | — | — | — | Ring pulse on active speaker |

### 2.G Stage (Clubhouse-style)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-ST-01 | Stage rooms: speakers/audience hierarchy, join/leave | **Build**: StageRoomSheet parity (native sheet; speaker row vs audience list) | **Build** | — | `stage:join|leave` → `stage:state` | — | Requires socket | Mic | — | Mic/speaker | Stage/audience layout transitions |
| F-ST-02 | Hand raise + host approve (audience → speaker) | **Build** | **Build** | — | `stage:hand` → `stage:state` | — | — | — | — | — | Hand icon bounce |
| F-ST-03 | Host controls: mute speaker, end room | **Build** (role-gated controls) | **Build** | — | `stage:mute|end` → `stage:ended` | — | — | — | — | — | — |
| F-ST-04 | Stage audio = same PTT chunk pipeline as F-VR-02 | Shared media pipeline | Shared | — | `voice:chunk` within stage room | — | — | Mic | — | Mic | — |

### 2.H Space (Gather-style spatial map)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-SP-01 | Spatial map: join, move avatar between positions | **Build**: SpaceSheet parity — Canvas/Compose map, drag avatar tile | **Build**: SwiftUI map with drag | — | `space:join|move|leave` → `space:state` | Last position cache | Requires socket | — | — | — | Smooth position lerp |
| F-SP-02 | Peer positions rendered live | **Build** | **Build** | — | `space:state` | — | — | — | — | — | Peer tile pop-in |
| F-SP-03 | Audio behaviour follows web contract (position relay only; no positional-audio DSP server-side) | **Match web exactly — do not invent** | Same | — | as above | — | — | Mic | — | — | — |

### 2.I Calls (WebRTC 1:1)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-CA-01 | Audio call: offer/answer/ICE over socket, P2P (Google STUN; **no TURN — same limitation as web**) | **Build**: `org.webrtc` (maintained fork e.g. `io.getstream:stream-webrtc-android`) PeerConnectionFactory; signaling via existing socket client | **Build**: WebRTC SPM binary (e.g. `stasel/WebRTC`) + RTCPeerConnection | **No new backend routes** — signaling is socket-only today | `call:offer|answer|ice|reject|cancel|hangup` (identity-gated at service; 30 s server ring timeout, busy/offline guards) | CallLog cache | Calls impossible offline — honest disabled state | **RECORD_AUDIO / mic** | Ringtone + vibrating incoming surface | Mic + speaker | Call sheet spring; connecting pulse |
| F-CA-02 | Video call (video→audio fallback when no camera) | **Build**: Camera2 capture via webrtc | **Build**: AVFoundation capture | — | same | — | — | **CAMERA runtime / camera usage** | — | Camera | Remote video view layout |
| F-CA-03 | Ring / accept / reject / 30 s timeout / busy / offline guards | **Build**: full-screen incoming overlay (native call-style), reject/accept | **Build**: fullScreenCover incoming (CallKit is a later decision — see §3.10) | — | same | — | — | — | Ringtone loop until timeout | Speaker | Accept-slide gesture |
| F-CA-04 | Call overlay UI: mute, speaker toggle, hangup, live duration | **Build** | **Build** | — | — | — | — | — | — | — | — |
| F-CA-05 | Call log (single-writer: caller logs completed/missed/declined) + Calls page | **Build**: replace `CallsScreen` stub with real history page + dock/entry wiring | **Build**: Calls page (parity with web #/calls) | `GET /api/calls`, `POST /api/calls` | — | CallLog cache | Cached log | — | Missed-call surface in log | — | — |
| F-CA-06 | In-call state survives navigation (web overlay is shell-level) | **Build**: call state hoisted above NavHost (Activity-scoped service later) | **Build**: call state hoisted above tab shell | — | — | — | — | — | — | — | Picture-in-picture mini call (native capability — improvement over web) |

### 2.J Stories (24 h status)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-SR-01 | Story rail: rings (unseen spin), mine/seen chips | Exists (Android animated sweep rings; iOS StoryRingCell) — retain | Same | `GET /api/stories?requesterId=` (self + shared-contact users, 24 h active) | — | Story cache | Cached rail | — | — | — | Ring spin (exists) |
| F-SR-02 | Composer: text/image story + background color | **Build**: replace toast with StoryComposerSheet | **Build**: replace toast | `POST /api/stories` (text/image) | — | — | — | Photos/Camera for image story | — | Camera/gallery | Progress bar |
| F-SR-03 | Viewer: tap-through, progress segments, 24 h expiry | **Build**: fullScreenCover viewer | **Build**: fullScreenCover (replace stub toast `StoriesView.swift:81`) | Same GET | — | Viewed-id cache | Cached stories viewable | — | — | — | Segment progress animation |
| F-SR-04 | Story views: mark viewed (dedup), owner sees viewer list | **Build** | **Build** | `POST /api/stories/{id}/view`, `GET …/view` | — | — | — | — | — | — | — |
| F-SR-05 | Delete own story | **Build** | **Build** | `DELETE /api/stories/{id}` | — | — | — | — | — | — | — |

### 2.K Channels (broadcast)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-CH-01 | Channel directory (+ mine) | **Build**: ChannelsPage (replace toast `ChatsScreen.kt:399/409` family) | **Build**: ChannelsPage (replace toasts `:300,310`) | `GET /api/channels` | — | Channel cache | Cached | — | — | — | — |
| F-CH-02 | Create broadcast channel + first post | **Build** | **Build** | `POST /api/channels` | `conversation:updated` | — | — | — | — | — | — |
| F-CH-03 | Subscribe / unsubscribe (admin succession rules) | **Build** | **Build** | `POST/DELETE /api/channels/{id}/subscribe` | — | — | — | — | — | — | — |
| F-CH-04 | Composer lock for non-admins (see F-MS-21) | Shared with messaging core | Shared | `broadcastMode` + role | — | — | — | — | — | — | Locked composer |

### 2.L Contacts, Profile & Safety

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-CP-01 | Contacts roster A–Z + presence | Exists (both) — retain | Same | `GET /api/users` | `presence:snapshot` | Roster cache | Cached roster | — | — | — | Section headers sticky |
| F-CP-02 | Add-contact page (name lookup → DM) | Exists (ContactsScreen DM) — add explicit add page if web's #/contacts/add flow requires it | Same | `GET /api/users?name=` | — | — | — | — | — | — | — |
| F-CP-03 | Full user page (#/user/<id>): profile, stats, actions | **Build**: UserPage (route from avatar taps) | **Build**: UserPage | `GET /api/users/{id}`, `GET /api/users/{id}/stats` | — | — | Cached | — | — | — | — |
| F-CP-04 | Edit profile: name/about/avatar upload/status emoji+text | **Build**: Profile edit form + avatar upload pipeline (F-MD-01 compressor) | **Build** | `PATCH /api/users/{id}`; avatar via `POST /api/uploads` | — | — | — | Photos (avatar pick) | — | — | — |
| F-CP-05 | Block/unblock + server-enforced DM boundary | Exists (block POST; ContactsScreen sheet) — add blocked-list UI in settings (P-SE-05) | Same | `POST/DELETE/GET /api/users/{id}/block`, `GET …/blocks`; server enforces at conversation create/send | — | Block list cache | Cached | — | Blocked send → honest error | — | — |
| F-CP-06 | Report account (reason + details; private) | Exists (reason dialog in ContactsViewModel) | **Finish unwired dialog** (`ContactsView.swift:10-11`) | `POST /api/users/{id}/report` | — | — | — | — | Confirmation toast | — | — |
| F-CP-07 | Safety numbers (12×5 digits, deterministic sha256) + verify | **Build**: SafetySheet parity | **Build** | `GET/POST/DELETE /api/users/{id}/safety`; logic in `src/lib/safety.ts` (server-computed) | — | Verified map cache | Cached | — | — | — | — |
| F-CP-08 | Peer verification badge | **Build** (UserVerification) | **Build** | Same safety endpoint family | — | — | — | — | — | — | Badge sparkle |
| F-CP-09 | Status emoji + text | **Build** (edit + display in rows/header) | **Build** | `PATCH /api/users/{id}` (statusEmoji/statusText) | — | — | — | — | — | — | — |

### 2.M Search & Mentions

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-SM-01 | Spotlight global search (⌘K / search pill): conversations + server message hits | Exists (ChatsScreen search: local filter + debounced 250 ms server ≥2 chars) — promote to shell-level search entry | Exists (search mode in ChatsView) — same promotion | `GET /api/search?userId=&q=` (scan 1200, cap 30) | — | — | Local filter works offline; server hits need network | — | — | — | Highlighted snippet (web parity ≤64 window) |
| F-SM-02 | Room search page (#/room/<id>/search) | **Build**: in-room search surface | **Build** | `GET /api/search` filtered client-side or re-use history scan | — | — | Search local cache | — | — | — | — |
| F-SM-03 | Mentions feed + unread badge (@FullName boundary regex, 14 d) | Exists (mention count in ViewModel; **feed page is a toast — build MentionsPage**) | Same (toast `:300`) | `GET /api/mentions?userId=&limit=50` | — | Mentions cache | Cached | — | Badge on EntryPill | — | Row highlight for own name |
| F-SM-04 | @mention composition (trigger in composer) | **Build**: type `@` → member suggester | **Build** | Roster of conversation members | — | — | — | — | — | — | Suggester popover |

### 2.N Folders

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-FD-01 | Folder rail with data + filter | Exists (rail data real; **tap-to-filter + manage are toasts — build**) | Same (`:210`) | `GET /api/folders?userId=` | — | Folder cache | Filter offline over cached list | — | — | — | — |
| F-FD-02 | Create / rename / emoji / reorder | **Build**: FoldersSheet parity | **Build** | `POST /api/folders`, `PATCH/DELETE /api/folders/{id}` | — | — | — | — | — | — | Reorder DnD |
| F-FD-03 | Membership full-replace (txn) + assignment from list | **Build** | **Build** | `PUT /api/folders/{id}/conversations` | — | conversationIds cached | — | — | — | — | — |

### 2.O Picture-in-Picture panes

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-PI-01 | ≤3 live panes; 1 expanded draggable glass window; open-new demotes focused + evicts oldest | **Build**: overlay layer above NavHost (Compose popup) with pane model — *not* Android system PiP (that's for media; here it's an in-app window stack) | **Build**: overlay layer above tab shell (same reasoning) | Pane content = existing conversation streams | `message:new` per pane unread | Pane geometry persisted (`pulse.pip.v2` web) → native key-value | Geometry persists | — | Per-pane unread badge | — | Drag with rubber-band frame clamp (top 64 / bottom 92 / margin 10) |
| F-PI-02 | Collapsed pills: 48 px avatar, unread 9+, close X, tap → expand/open conversation | **Build** | **Build** | — | — | — | — | — | — | — | Pill collapse morph |
| F-PI-03 | Header tap dispatches open-conversation | **Build** (pendingOpenRoom bridge exists on iOS; Android nav route) | **Build** | — | — | — | — | — | — | — | — |

### 2.P Settings (9 sections; cosmetic vs functional preserved honestly)

| ID | Web section | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-SE-01 | Account (profile card, copy User ID, edit-profile) | Exists in ProfileScreen — add "copy user ID" | Exists — same | `GET /api/users/{id}` | — | — | — | — | — | — | — |
| F-SE-02 | Appearance (color mode, UI themes, nav styles, FX mode, wallpaper) | Color mode + FX picker exist. UI-themes & 13-nav-styles: **NOT PORTED** (rule P2). Wallpaper: port as chat background picker | Same (ambient picker exists) | `GET/PATCH /api/settings` (prefs blob) | — | DataStore/UserDefaults prefs | Works offline | — | — | GPU for FX | Live preview strips (iOS has; port to Android) |
| F-SE-03 | Chat (bubbleRadius, density, drafts & outbox management) | **Build**: bubble radius + density tokens; **Drafts/Outbox manager surface** (needs F-MS-25/10) | Same | prefs blob | — | Local + server mirror | Editable offline | — | — | — | — |
| F-SE-04 | Notifications (previews/sound/vibrate + quiet hours) | **Build**: native in-app notification prefs + quiet hours logic; maps to F-W-PS-05 when push lands | Same | prefs blob (notifPreviews/Sound/Vibrate) | — | prefs | Quiet hours computed locally | POST_NOTIFICATIONS when push lands | Drives alert behaviour | — | — |
| F-SE-05 | Privacy & Security (lastSeen / readReceipts / typing + blocked list) | **Build**: toggles via prefs (server-enforced since R46 — real) + **blocked accounts list** (F-CP-05) | Same | `PATCH /api/settings`; enforcement in serializers + socket privacy flags | Socket honors privacy (fail-open) | — | — | — | — | — | — |
| F-SE-06 | Real-time & Voice (socket badge, online count, network, voice-room info — **display-only on web too**) | **Build**: diagnostic card (gateway host, live probe ms, realtime mode — iOS SettingsView already has the probe; port to Android) | Exists in SettingsView — retain | `GET /api/users` probe / socket health | Live status | — | Honest "offline" | — | — | — | — |
| F-SE-07 | Accessibility (reduced motion, haptics) | Motion toggle exists — **also honor system "Remove animations"**; haptics toggle | Same + **honor system Reduce Motion** (already gates FX) | prefs (reducedMotion) | — | prefs | — | — | — | — | — |
| F-SE-08 | Data & Storage (footprint stats, clear drafts/outbox, PWA install) | **Build**: cache footprint + clear actions; "install" row = update channel (LiveUpdater is the native equivalent) | Same; updates row = App Store note (exists) | — | — | — | — | — | — | — | — |
| F-SE-09 | About (version, GitHub) | Exists (App updates card + About) | Exists | — | — | — | — | — | — | — | — |

### 2.Q Hub (mini-app economy)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-HB-01 | Wallet (PC/GEM balance + ledger) | **Replace static HubScreen tile** with real wallet panel | Exists (HubView wallet) — retain/extend | `GET /api/hub/wallet?userId=` | — | Wallet cache | Cached balance (stale-marked) | — | — | — | Count-up animation |
| F-HB-02 | Daily check-in (+25 PC streak bonus) | **Build** | **Build** | `POST /api/hub/wallet/checkin` | — | lastCheckIn cache | Honest offline fail | — | Success haptic + particles | — | — |
| F-HB-03 | @handle transfer (atomic) | **Build** | **Build** | `POST /api/hub/wallet/transfer` | — | — | — | — | — | — | — |
| F-HB-04 | Swap PC⇄GEM (rates + stats) | **Build** | **Build** | `GET/POST /api/hub/swap` | — | — | — | — | — | — | — |
| F-HB-05 | Tasks (personal kanban CRUD) | **Build** | **Build** | `GET/POST /api/hub/tasks`, `PATCH/DELETE /api/hub/tasks/{id}` | — | Task cache | Cached | — | — | — | — |
| F-HB-06 | Market (post listing, buy with escrow) | **Build** | **Build** | `GET/POST /api/hub/market`, `POST …/market/{id}/buy` | — | — | — | — | — | — | — |
| F-HB-07 | Logs stream (limit 80) | **Build** | **Build** | `GET /api/hub/logs` | — | — | — | — | — | — | Live append |
| F-HB-08 | Apps matrix: 100-app catalog, 10 categories, search, install/connect | **Build**: Hub apps panel with category chips + tiles (static catalog ships in binary from `src/lib/hub-catalog.ts` — port to shared JSON) | Same | `GET/POST/DELETE /api/hub/apps/{appId}/install` | — | Install state cache | Catalog browsable offline; install needs network | — | — | — | Tile grid stagger |
| F-HB-09 | App communities (appKey group, founder admin) | **Build** | **Build** | `GET/POST /api/hub/apps/{appId}/community` → real conversation | `conversation:updated` | — | — | — | — | — | — |
| F-HB-10 | My apps (installed list) | **Build** | **Build** | installs GET | — | — | Cached | — | — | — | — |

### 2.R Groups & invites

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-GR-01 | Create DM/group (server dedupe) | Exists | Exists | `POST /api/conversations {creatorId, memberIds, isGroup, name?}` | `conversation:updated` | — | — | — | — | — | — |
| F-GR-02 | Group info: rename, photo, description, flags | **Build**: RoomInfoPage (web #/room/<id>/info) | **Build** | `PATCH /api/conversations/{id}`; `GET …/{id}` detail | `conversation:updated` | — | — | Photos | — | — | — |
| F-GR-03 | Members: add, remove, promote/demote (admin) | **Build** (RoomMemberAdd parity) | **Build** | `POST/DELETE …/members`, `PATCH/DELETE …/members/{userId}` | `conversation:updated` | members cached | — | — | — | — | — |
| F-GR-04 | Leave group | **Build** | **Build** | `DELETE …/members` (self) | — | — | — | — | Confirm dialog | — | — |
| F-GR-05 | Invite codes: create/regenerate, public preview, redeem (`?join=` web) | **Build**: invite sheet + **deep link `pulse://invite/<code>`** (manifest filter exists; implement handling) | **Build**: invite sheet + `pulse://invite/<code>` custom scheme (universal links later — needs domain/AASA, external dep) | `POST /api/conversations/{id}/invite`, `GET /api/invite/{code}`, `POST /api/invite/{code}/join` | `conversation:updated` | — | — | — | — | — | — |
| F-GR-06 | conversation:updated handling (list refresh trigger) | **Build** (event exists in service; client handlers must add) | **Build** | — | `conversation:updated` | — | — | — | — | — | — |

### 2.S Offline / PWA (web) → Persistence & background (native)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-OF-01 | Offline shell (SW precache, offline 503 page) | Native equivalent: Room cache IS the shell (already true for lists/rooms) | GRDB cache (must be wired for reads first) | — | — | Local DB | **Full offline read of last-synced state** | — | — | — | Offline banner/toast (web parity) |
| F-OF-02 | PWA install prompt | Not applicable — native install = Play/CDN APK via LiveUpdater | Not applicable — App/TestFlight | — | — | — | — | — | — | — | — |
| F-OF-03 | Outbox FIFO ≤50 (F-MS-25) | **Build** | **Build** | flush = normal send API | Echo on flush | Persistent queue | Core | — | Delivered toast | — | — |
| F-OF-04 | Flush triggers: mount-with-pending / online / visibilitychange / 20 s self-heal | **Build**: connectivity callback + lifecycle (ON_START) + WorkManager expedited | **Build**: NWPathMonitor + scenePhase + BGAppRefreshTask | — | — | — | — | — | — | — | — |
| F-OF-05 | Delta sync (web re-fetches full lists; **natives should add cursor sync**) | **[BACKEND ADD]** `GET /api/conversations?since=<ISO>` (optional; v1 can keep full refresh) | Same | **[BACKEND ADD]** (optional) | — | — | Faster reconnect | — | — | — | — |

### 2.T AI & automation

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-AI-01 | LLM companion bot "Pulse AI" (DMs always; groups via @Pulse AI; typing relay; 16-msg context) | Zero native logic — it is a real User row replying via normal pipeline; render `viaAutomation` tag (Android already does) | Same | `src/lib/ai-bot.ts` (server, z-ai SDK) | `message:new` + `typing` relay | — | — | — | — | — | Bot tag chip |
| F-AI-02 | Command bot (10 deterministic commands; trigger via `/cmd`, `@pulseai`, "pulseai,") | **Build**: SlashPalette parity covers triggers; bot replies land as messages | Same | `src/lib/bot-engine.ts` (server) | `message:new` | — | — | — | — | — | — |
| F-AI-03 | Keyword automations (admin-authored trigger/reply, word-boundary, `viaAutomation`) | **Build**: AutomationsSheet CRUD (admin-gated) | **Build** | `GET/POST …/automations`, `PATCH/DELETE /api/automations/{id}` | — | Rules cache | — | — | — | — | — |
| F-AI-04 | AI recap (last 30 msgs → ≤5 bullets; 5-min cache; 409 <5) | **Build**: recap card in room info/menu | **Build** | `POST /api/ai/recap` | — | Cached recap | Cached last recap | — | — | — | — |
| F-AI-05 | Webhooks: manage (participant-gated) + public ingest `POST /api/webhooks/{token}` | **Build**: webhook management card (Android ChatsActions already links; iOS add) | **Build** | `GET/POST /api/webhooks`, `POST/DELETE /api/webhooks/{token}` | `message:new` | — | — | — | — | — | Token copy |

### 2.U Realtime infrastructure (cross-cutting)

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-RT-01 | join → joined{onlineUserIds} → presence:snapshot (privacy-filtered) | Coded, dormant — power when socket URL baked | Coded, dormant — same | — | Socket.IO v4 | — | Presence absent offline (honest) | — | — | — | — |
| F-RT-02 | typing (privacy-gated R47) | Coded | Coded | `/typing` relay | `typing` | — | — | — | — | — | — |
| F-RT-03 | message fan-out: new/deleted/read/react/edited/pinned/viewed | **Build**: add handlers for react/edited/pinned/viewed (Android subscribes subset today) | Same (iOS subscribes more already) | `POST /notify` whitelist (11 events) | socket | Query-cache/Room update | — | — | In-app toast on new message (muted convs suppressed) | — | — |
| F-RT-04 | poll:voted, link:preview, translation:added, conversation:updated | **Build**: subscribe + route to caches | **Build** | — | socket | — | — | — | — | — | — |
| F-RT-05 | voice/stage/space room events | **Build** (§2.F/G/H) | **Build** | — | socket | — | — | Mic | — | Mic | — |
| F-RT-06 | call signaling | **Build** (§2.I) | **Build** | — | socket | — | — | Mic/Camera | — | — | — |
| F-RT-07 | Reconnect: 800 ms→5 s, polling-first upgrade (web) | Engine.io defaults today — **add explicit backoff cap + foreground re-join** | `.reconnects(true)` — same additions | — | socket | — | Silent retry; status surfaced in settings diagnostic | — | — | — | — |
| F-RT-08 | Privacy flags service-side (typing hidden, presence filtered; fail-open 30 s TTL) | No client work (server behaviour); clients must tolerate missing presence | Same | `GET /api/internal/privacy` (x-pulse-key) | — | — | — | — | — | — | — |

### 2.V Theme, FX & motion

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-FX-01 | Ambient modes ×6 (off/aurora/caustics/mesh/stars/liquid; 3 GLSL shaders + 2 Canvas approximations; epoch t=14.2) | **Exists** (AGSL ports + Canvas fallback) — retain | **Exists** (Metal kernels + Canvas) — retain | prefs blob (`fx.webglMode`) | — | Pref | Persisted | — | — | GPU (30 fps TimelineView cap) | Static frame under reduced motion (both already) |
| F-FX-02 | Particle bursts: confetti/hearts/stars/burst (send/react/onboarding triggers) | **Exists** (ParticleBurstHost) — retain; add effect kinds (F-MS-23) | **Exists** (ParticleBus) — same | — | — | — | — | — | — | GPU | Physics loop pauses when no live particles (both already) |
| F-FX-03 | 5 UI themes (glass/kinetic/minimal/dynamic/aero) | **NOT PORTED** (rule P2) — natives get light/dark + Material You direction later | Same — HIG materials | — | — | — | — | — | — | — | — |
| F-FX-04 | 13 nav architectures | **NOT PORTED** (rule P2) — CapsuleDock is the native nav | Same | — | — | — | — | — | — | — | — |
| F-FX-05 | Conversation themes (wallpaper + tint, ≤48 entries in prefs) | **Build**: conv-theme picker + background layer behind room | **Build** | prefs (`chat.convThemes`) | — | prefs | — | — | — | — | Theme transition |
| F-FX-06 | Dark mode (system/light/dark cycle) | **Exists** (in-app override + system-bar sync) | **Exists** | prefs | — | prefs | — | — | — | — | — |
| F-FX-07 | Motion system: 4 springs (snappy/soft/bouncy/gentle), press 0.94, stagger 28 ms, reduced-motion | **Exists** (`ui/Motion.kt`) — retain | **Exists** (`PulseFormatting.swift`) — retain | — | — | — | — | — | — | — | — |

### 2.W Platform services & cross-cutting

| ID | Web feature | ANDROID | iOS | BACKEND/API | REALTIME | STORAGE | OFFLINE | PERMS | NOTIF | MEDIA/HW | MOTION/GESTURE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F-PS-01 | In-app update system (manifest → APK, 4 integrity gates) — **Android-only today by design** | **Exists** (LiveUpdater) — retain; keep versioned chain (v0.1.8+ …) | App Store/TestFlight channel (needs Apple Developer Program — **external dependency**) | `download/update-manifest.json` (add per-platform entries when iOS ships) | — | — | Update check offline-tolerant | REQUEST_INSTALL_PACKAGES (existing) | Update banner (exists) | — | — |
| F-PS-02 | Scheduled-message dispatch cron (server flushes ≤25 due) | No client work | No client work | `POST /api/maintenance/dispatch` (x-pulse-key; keepers service exists) | `message:new` on dispatch | — | — | — | — | — | — |
| F-PS-03 | OS push notifications — **does not exist on web at all** (in-app only) | **Native addition (decided)**: Tier 1 = local notifications (reminders F-RO-06, scheduled echo F-MS-18). Tier 2 = FCM push — **[BACKEND ADD]** token registry + sender + Firebase project (external dep). POST_NOTIFICATIONS runtime on 13+ | Same decisioning: APNs (needs Apple Developer + entitlements + **[BACKEND ADD]** APNs sender); local notifications for Tier 1 | **[BACKEND ADD]** for Tier 2 | Push arrives even when socket down | Token storage | Local notifications fire offline | POST_NOTIFICATIONS / APNs auth | **The native superpower web lacks** | — | — |
| F-PS-04 | Deep links: web hash routes + `?join=CODE`; Android manifest declares `pulse://` (dead); iOS none | **Build**: handle `pulse://room/<id>`, `pulse://invite/<code>`, `pulse://user/<id>` via onNewIntent → nav routes | **Build**: `pulse://` scheme in project.yml infoProperties + `.onOpenURL` | Invite preview/join APIs | — | — | — | — | — | — | Cold-start routing animation |
| F-PS-05 | Health/observability (socket `GET /` health; web debug surfaces) | Keep honest failure surfaces; add crash reporting later (**external dep decision** — do not silently add) | Same | — | — | — | — | — | — | — | — |

---

## 3. THE 16 ARCHITECTURE DEFINITIONS

### 3.1 Android architecture

**Layering (retain, do not restructure):** `:app → :feature-* → :data → :domain ← :core`, `:protocol` pure Kotlin shared by data+domain+tests; Hilt injection at app/data boundaries. Domain stays framework-free.

```
app        MainActivity · PulseShell(NavHost) · CapsuleDock · SessionViewModel · Onboarding · LiveUpdater · FX hosts
feature-*  feature-chat (inbox+room) · feature-contacts · feature-settings(profile) · feature-hub (REBUILD real) ·
           feature-calls (REBUILD real) · feature-stories (REBUILD real)
data       PulseApi(Ktor) · PulseSocketClient(io.socket) · Room(PulseDatabase) · DataStore(PulsePrefsStore) · PulseRepositoryImpl
domain     Models · PulseRepository interface · UseCases (SendMessageUseCase + new: FlushOutbox, ObserveOutbox…)
protocol   WireDtos.kt · SocketContracts.kt (expand to full contract)
core       PulseEndpoints · PulseResult · PulseTime · PulseFx
```

**Module evolution (no new modules until a feature demands it):** media capture/playback lives in `:data` (port layer) + `:ui` (renderers); WebRTC engine in new `:feature-calls` internals; outbox in `:data` + WorkManager worker in `:app` (needs Android runtime) or `:data` with androidx-work dependency (already declared).

**State:** Room + DataStore as single sources of truth exposed as Flows; ViewModels own UI state; repository = the only network/DB orchestrator (today's pattern — keep). Socket signals → `SharedFlow<Signal>` → repository pump (exists) → Room upsert → UI recompose. Optimistic mutations keep the existing mutate/reconcile helper.

**Key decisions:** (a) keep minSdk 21 + desugaring until users demand otherwise; (b) keep committed keystore + v1/v2/v3 signing for the sideload/CDN channel; (c) enable R8 **only after** Room/Ktor/serialization keep-rules are proven in CI (new `proguard-rules.pro` must be added — it is missing); (d) single-Activity, no fragments, Compose-only.

### 3.2 iOS architecture

**Layering (retain):** SwiftUI app, no storyboards, no UIKitVC except wrappers (`ActivityShareSheet`). Keep: `Domain/Models` (pure), `Core/Networking` (PulseAPIClient, PulseSocketClient), `Core/Storage` (GRDB PulseStore), `Core/Prefs`, `Core/Support` (Session, Endpoints, Toast, Haptics, Formatting, Components, Theme tokens), `Core/FX` (Metal + ParticleBus), `Features/*`.

**Required structural fixes before scaling features:** (a) **make PulseStore the source of truth** — wire `observeConversations()`/`messages(conversationId:)` read paths so relaunch rehydrates offline (today write-only); (b) fix `ChatsViewModel.isActive` lifecycle (start/stop poll on tab visibility); (c) fix `prefsFilter` stale wiring (bind to `PulsePrefs` object, not a fresh copy); (d) finish the report dialog (`ContactsView`); (e) route all image URLs through `PulseTheme.photoURL` (NewChatSheet bypasses it).

**Feature architecture:** each new surface (calls, stories viewer, voice rooms, hub panels) = `Features/<Name>/<Name>View.swift` + `ViewModel` (ObservableObject) subscribing to `session.signals`; sheets declared at the owning tab; immersive surfaces (calls, stories viewer, stage) use `.fullScreenCover`.

**Key decisions:** (a) stay iOS 17.0 (Metal stitchable + TimelineView need it; no back-deploys); (b) stay iPhone portrait for parity waves; iPad/landscape later; (c) unsigned CI artifact until Apple Developer Program exists — then TestFlight lane; (d) no third-party HTTP stack (URLSession is enough); GRDB and socket.io-client-swift stay the only SPM deps until WebRTC (`stasel/WebRTC`) joins.

### 3.3 Shared protocol / data-contract strategy

**Source of truth:** `packages/protocol/src/contracts.ts`. **Problem today:** it lags the socket service (missing 10+ events) and the JSON Schema pins only REST DTOs.

**Contract plan (backend-first, then mirrors):**
1. **[BACKEND CHANGE]** Expand `contracts.ts` `SOCKET_EVENTS` + add typed payload interfaces for: `message:react|edited|pinned|viewed`, `poll:voted`, `link:preview`, `translation:added`, `conversation:updated`, `voice:roster|chunk`, `stage:*`, `space:*`, full `call:*` (already typed in `src/lib/call-types.ts` — move/duplicate into protocol).
2. Regenerate/extend `pulse-protocol-v1.schema.json` (REST DTOs stay; socket payloads added as definitions).
3. **Native mirrors stay hand-written** (deliberate: no codegen infra). Android `WireDtos.kt` + iOS `WireDtos.swift` extend in lockstep; the existing parity tests (Android `LiveGatewayParityTest` against live HTTP; iOS `WireParityTests` against captured live JSON) are the enforcement mechanism — **extend both to cover every new DTO** (acceptance AT-PT-01..03).
4. **Rules:** tolerant decoding everywhere (`ignoreUnknownKeys` / unknown keys ignored); lowercase wire kinds (`text|image|voice|video|file|poll|system|sticker|location|red_packet`) mapped to native enums at the DTO boundary only; dates ISO-8601; reactions grouped `{emoji, userIds, count}`; streaks decode both `{"count":n}` and int.
5. **Versioning:** bump schema title to v2 when socket payloads land; natives accept unknown fields so old clients survive server additions (already true).

### 3.4 Networking architecture

- **One gateway client per platform** (`PulseApi` Ktor / `PulseAPIClient` URLSession) against `PulseEndpoints.gatewayHttpUrl` (baked BuildConfig/constant, overridable by manifest on Android — keep; iOS gains a manifest-driven override path in the same wave as its live gateway).
- **Error taxonomy is already shared** (401 AUTH / 403 FORBIDDEN / 404 NOT_FOUND / 422 VALIDATION / 429 RATE_LIMITED / 5xx SERVER / network) — both sides preserve server `error/code/suggestion` bodies (username_taken, slow-mode retryAfter). Keep.
- **Timeouts:** Android 3 s connect / 6 s request (keep); iOS caps at 6 s (keep). No retries on writes except outbox flush; idempotent GETs may retry once with jitter.
- **429 handling:** parse `retryAfter` → composer lockout (F-MS-20).
- **Auth:** none today (§3.11 defines the token upgrade; transport change = one header, both clients).
- **Base-URL policy:** natives talk to the SAME Next.js deployment as web. The static-CDN "gateway" (raw.githubusercontent) baked today is an offline-first artifact — the live-gateway baking decision (worklog F2 open item) resolves as: **manifest-driven `gateway`/`socket` override (Android already implements via update-manifest.json; iOS adds the same)** so one binary works offline-first and live when the manifest points at a real host.

### 3.5 Socket.IO architecture

- **Server:** unchanged (`mini-services/pulse-socket`, port 3003, Socket.IO v4, rooms `user:`/`voice:`/`stage:`/`space:`, `/notify` + `/typing` relays). Natives connect **directly to the socket host** (no Caddy `XTransformPort` — that is a sandbox-web artifact; on real deployments socket and gateway share the origin).
- **Client parity targets (both platforms):**
  1. Emit `join {userId[,token §3.11]}` on connect **and on every reconnect/foreground** (Android does connect-only today).
  2. Subscribe the FULL event set (§1.1 list) — Android is missing `message:react|edited|pinned|viewed`, `poll:voted`, `link:preview`, `translation:added`, `conversation:updated`, `voice:roster|chunk`, `stage:*`, `space:*`, `call:reject`.
  3. Typed `Signal` enum → single repository/session pump → caches (pattern exists both sides).
  4. Reconnect: exponential 800 ms → 5 s cap (web parity), infinite with backoff, **no reconnect spam when URL empty** (Android's null-socket guard — keep on both).
  5. Heartbeat: engine.io defaults (25 s ping / 60 s timeout) — do not customize until a measured need exists.
- **Realtime fan-out rule:** UI never subscribes to sockets directly; only the pump does (prevents the multi-listener duplication the web audit flagged).

### 3.6 Local database architecture

**Android (Room):** expand schema v3 → v4+ with real migrations (delete `fallbackToDestructiveMigration` before the first user-facing release after this spec): add tables `outbox(id, conversationId, clientId, content, kind, payloadJson, createdAt, attempts)`, `draft(conversationId PK, text, updatedAt)`, `folder`, `story`, `mention`, `call_log`, `wallet_cache`, and extend `MessageEntity` with `editedAt, expiresAt, viewedOnce, viewedAt, anonAlias, linkUrl, linkPreviewJson, translationsJson, transcript, pinnedAt, topicId, parentId, payloadJson` (columns already exist partially — align to wire DTO 1:1). Indices: `MessageEntity(conversationId, createdAt)`, `outbox(createdAt)`.

**iOS (GRDB):** migration `"v2"`: add same tables/columns; **enable FTS5** on message body for offline search (`PulseStore.swift:45` already reserves this); add outbox + draft tables; then wire the read paths (§3.2 fix (a)).

**Shared rules:** DB is a *cache + queue*, never the source of identity; server is truth; every REST write updates DB optimistically and reconciles from response; every socket event upserts DB; TTL columns (`expiresAt`) filtered at query time; media bytes cached outside DB (disk cache) — mirroring web's disk+BLOB duality.

### 3.7 Offline / sync architecture

**Principles:** (1) launch offline → render last DB state instantly, badge surfaces that are stale; (2) writes queue when offline (outbox ≤50 FIFO, temp-id bubbles); (3) flush in order, stop at first failure, swap temp→real id in DB + UI, toast on delivery; (4) triggers: app start with pending / connectivity regained / app foreground / 20 s self-heal; (5) flags (pin/mute/archive/read) stay optimistic-with-rollback (exists); (6) handle-check fallback chain stays (server → handles.json → reserved list).

**Background:** Android — WorkManager expedited worker for flush + periodic (6 h) light sync when battery-ok (activates the already-declared dependency). iOS — BGAppRefreshTask for outbox flush + light sync (requires `Info.plist` `BGTaskSchedulerPermittedIdentifiers` — add with the wave). Push (§3.9) eventually replaces most background sync needs.

**Conflict policy:** last-writer-wins per field (server semantics already); outbox replays sends, never edits; drafts are last-writer with 600 ms debounce mirror.

### 3.8 Media architecture

- **Upload:** single path — client compresses (image ≤512 square crop for avatars/stories, ≤1280 JPEG q0.82 for photos; docs ≤10 MB; audio AAC ~32-64 kbps) → base64 data-URL → `POST /api/uploads` → wire stores `imagePath/audioPath/filePath` → served by `GET /api/uploads/{file}` (strict whitelist; BLOB fallback). **Native change request (later, optional): [BACKEND CHANGE]** accept multipart to cut base64 overhead (+33 %) — not required for parity.
- **Voice notes:** hold-to-record ≥600 ms (both platforms' native recorders); waveform from real amplitude taps (web uses pseudo-waveform — natives may exceed); playback rates 1×/1.5×/2×.
- **Live voice (rooms/stage):** 16 kHz PCM chunks base64 over `voice:chunk` (~4 s frames), captions via `POST /api/voice/transcribe` → ephemeral `voice:transcript`. Native capture: Android `AudioRecord`, iOS `AVAudioEngine` input tap. Playback: `AudioTrack` / `AVAudioPlayer` queue.
- **Images:** disk cache (Coil on Android; URLCache/AsyncImage on iOS) keyed by upload path; lightbox with pinch-zoom.
- **Camera:** Android CameraX (photo) / iOS AVFoundation; **no video recording in v1 parity** (web has none — video calls only).

### 3.9 Notifications architecture

Web has **none** (in-app toasts + WebAudio + vibration only). Natives ship the real thing, tiered:

- **Tier 1 — local notifications (no backend):** reminders (`remindAt`), scheduled-message echo, due-reminder sweeps, (optional) missed-self-actions. Android: `NotificationManager` + `POST_NOTIFICATIONS` runtime (13+); iOS: `UNUserNotificationCenter`. Quiet-hours + mute + notifPreviews prefs (F-SE-04) gate all tiers.
- **Tier 2 — push (needs external deps, flagged):** FCM (Android: Firebase project + `google-services.json`) and APNs (iOS: Apple Developer Program, entitlements, key) + **[BACKEND ADD]** `PushToken` table + sender invoked from the message-send pipeline (respecting mute/privacy/quiet rules server-side) and call-ring events. This is the single biggest new backend surface; it is *specified*, not approved for implementation here.
- **Tier 3 (later):** iOS CallKit + PushKit VoIP push for native incoming-call UX; Android telecom `Connection`/`InCallService` integration.
- **In-app notification surfaces stay** (dock badge, row badge, toasts, sounds, haptics) exactly as web — they are the product's feedback layer, push is the delivery layer.

### 3.10 Calls / WebRTC architecture

- **Stack:** Android `io.getstream:stream-webrtc-android` (maintained prebuilt of org.webrtc); iOS `stasel/WebRTC` SPM binary. Both wrap the same libwebrtc the web browser ships — wire-level compatible with web peers.
- **Signaling:** identical `call:*` events via the existing socket clients; identity-gating and 30 s server timeout already server-side. No TURN (web has none) → document identical NAT limitation; add coturn **[BACKEND ADD]** only if field testing shows failure rates that matter.
- **Peer logic (per platform):** PeerConnectionFactory singleton; `getUserMedia` equivalents (Camera2/AVFoundation + AudioRecord/AudioUnit); offer/answer/ICE relay; audio output routing (speaker/earpiece) via AudioManager/AVAudioSession; call state machine (idle→ringing→connecting→active→ended) mirrors `src/lib/call-types.ts`.
- **CallLog:** caller single-writer `POST /api/calls` exactly as web.
- **iOS CallKit:** defer to Tier 3 — an unsanctioned CallKit integration is App Store risk; first ship in-app overlay (matches web), upgrade later.
- **Permissions:** RECORD_AUDIO (+CAMERA for video) runtime both platforms; graceful denial → audio-only / honest error card (web parity).

### 3.11 Security / authentication architecture

**Today (honest):** identity = self-declared `userId` in query/body and `join{userId}`. Anyone with the gateway URL can act as any user; socket joins are unauthenticated; `x-pulse-key` internal endpoints use a default constant. This is acceptable for the current dev deployment and must be **stated in the product**, not hidden.

**Target model (spec):**
1. **[BACKEND ADD]** `SessionToken`: on `POST /api/users` (create) and reclaim flow, server returns `{user, token}` (random 32 B, stored hashed on `User`); `POST /api/users/login` (new) for reclaim returns same.
2. **Natives** store token in **Keystore-encrypted DataStore** (Android) / **Keychain** (iOS); attach `Authorization: Bearer` on every API call; socket `join{userId, token}`; server verifies (middleware, optional-verify during migration so web keeps working).
3. **Web** adopts the same token in localStorage (phase 2, separate decision — web keeps working unauthenticated during migration via optional-verify).
4. **Socket service** verifies token via `GET /api/internal/verify` or shares the DB read path; identity-gated `call:*` becomes token-gated.
5. **Keychain/Keystore also** store the gateway override (manifest value) to prevent trivial endpoint spoofing on sideloaded builds (best-effort; sideload trust is inherently limited).
6. **Report/block** surfaces exist server-side; natives render them (F-CP-05/06). Safety numbers remain deterministic server-computed (F-CP-07).

### 3.12 Navigation architecture

**Android:** single-Activity Compose Navigation. Routes (mirror web hash routes 1:1 where they are product surfaces): `chats, hub, contacts, profile, room/{id}, archived, channels, mentions, calls, folders, settings, settings/{section}, user/{id}, room/{id}/info, room/{id}/search, hub/apps, hub/apps/{id}, hub/c/{slug}`. Deep links `pulse://room/{id}`, `pulse://invite/{code}`, `pulse://user/{id}` bound to the same routes (manifest filter already exists — wire `onNewIntent`). Sheets = M3 `ModalBottomSheet` at owning screen; dock hides in room (exists); back = predictive back (`enableOnBackInvokedCallback` already set).

**iOS:** per-tab `NavigationStack` (exists) + typed route payloads (`RoomRoute` pattern — extend to `UserRoute`, `SettingsRoute`). Sheets: `.sheet` default; `.fullScreenCover` for calls, story viewer, stage/voice rooms, onboarding-level surfaces. Deep links via `.onOpenURL` + `pendingOpenRoom`-style bridges (pattern exists).

**Mapping rule:** every §2 surface must appear in the platform's route table; no feature reachable only by dead toast (all current toast dead-ends are scheduled for replacement in §2).

### 3.13 Animation / motion strategy

- **Shared feel, native systems:** Android Compose `animate*AsTransition` + GraphicsLayer; iOS SwiftUI `.animation`/`withAnimation` + TimelineView/Canvas. Presets locked to web values: springs snappy(560,0.85)/soft(300,0.82)/bouncy(730,0.44)/gentle(120,0.90) — **already ported on both** (`ui/Motion.kt`, `PulseFormatting.swift`); keep them the only spring sources (no ad-hoc springs).
- **Press:** uniform 0.94 scale (`pulsePress` / `PulseButtonStyle`) on every tappable.
- **Lists:** 28 ms stagger ×≤13 rows on first entrance (exists both).
- **Particles:** 4 kinds (confetti/hearts/stars/burst) via existing GPU hosts; triggers: send, ❤️ react, identity create, onboarding complete, view-once burn, red-packet open, effects messages (F-MS-23).
- **Ambient:** 6 modes, 30 fps cap, epoch t=14.2, static frame when reduced motion (exists both).
- **Reduced motion:** honor OS setting first (Android "Remove animations" / iOS `accessibilityReduceMotion`), in-app toggle overrides on top; both FX stacks already gate — extend to new surfaces.
- **Transitions:** tab switch = 24 pt directional slide + fade 220 ms (exists); room push = platform default (Android Compose nav transitions tuned to the same easing `CubicBezier(0.16,1,0.3,1)`; iOS default push).

### 3.14 Performance strategy

- **Budgets:** cold start ≤2 s to first frame on mid-tier 2020 hardware; list scroll 60 fps with 500 cached conversations; room open ≤400 ms from DB (network-independent); socket event → UI ≤150 ms when foregrounded.
- **Android:** Room paging (LIMIT/OFFSET windows), LazyColumn keys, Coil disk+memory cache, baseline profiles later, R8 after keep-rules proven; ambient shader only when visible + 30 fps; particle host zero-idle-cost (exists).
- **iOS:** GRDB prepared statements + `DatabasePublishers` (exists), LazyVStack for chats, `drawingGroup()` only for FX layers; avoid `TimelineView` when ambient off (exists); JS-bundle-free = no webview anywhere.
- **Network:** poll ONLY when tab visible (fixes iOS inert lifecycle); socket replaces poll when live (poll becomes fallback); debounced refresh after socket events (500 ms — exists); delta sync [BACKEND ADD optional] to cut payload.
- **Battery:** mic/camera sessions released on leave; background work bounded (WorkManager constraints / BGTask budget).

### 3.15 Testing strategy

- **Contract tests (the backbone):** Android `LiveGatewayParityTest` pattern (live HTTP, env-gated skip) + iOS `WireParityTests` (captured fixtures) — extend to EVERY DTO family in §2: conversations, messages (all kinds), folders, stories, mentions, search, wallet, calls, tournaments, invite. Target: any wire drift fails CI on both platforms.
- **Domain/use-case tests:** JVM (Android JUnit5, exists) — add `FlushOutboxUseCaseTest`, streak/deadStreak decode tests, retryAfter mapping. iOS XCTest — add outbox order tests, GRDB migration v1→v2 test, session token storage test.
- **Repository tests:** Android with in-memory Room; iOS with in-memory GRDB (already supported by `PulseStore` init).
- **UI tests:** not in parity waves; add Compose screenshot smoke + SwiftUI preview snapshots only after features stabilize (honest scope).
- **E2E:** one scripted gateway scenario per wave executed against the live deployment (send→receive→react→read across web+native), recorded in worklog.
- **Manual hardware matrix:** mic/camera/push/PackageInstaller verdicts require devices — tracked as HARDWARE-DEPENDENT items (never auto-marked PASS).

### 3.16 Build / CI / CD strategy

- **Android (retain + extend):** android-ci gates = JVM tests → assembleRelease → artifact → tag-release. Add: `:data:test` when repository tests land; R8 build job (separate, non-blocking until proven); upload APK sha256 to release notes (forensic gate continues: zip testzip, v1/v2/v3, AXML versionCode walk, CDN byte-equality).
- **CD (Android, exists):** tag `v*` → GitHub Release asset → CDN commit (`download/Pulse.apk` + `update-manifest.json` versionCode bump) → in-app LiveUpdater offers it. Keep the four-source hash rule (repo artifact, release asset, raw mirror, manifest target).
- **iOS:** ios-ci gates = xcodegen → build → unit tests → artifact. Add when Apple Developer exists: archive + signed IPA + TestFlight upload lane (`xcodebuild -exportArchive`); until then the simulator artifact + WireParityTests are the quality bar (honest: no device validation in CI).
- **Shared:** `packages/**` changes trigger BOTH native CI paths (already configured) — contract edits can never land without proving both mirrors build.
- **Versioning:** Android `versionCode` monotonic (next = 9), `versionName` wave-tagged; iOS marketing version in project.yml.

---

## 4. TRACEABILITY MATRIX — FEATURE → ANDROID → iOS → BACKEND → ACCEPTANCE TEST

Rule: an acceptance test (AT) is **done** only when executed against the live gateway (or device, where noted). One AT per feature ID. "Dev" = device/emulator-dependent step.

### 4.A Identity & Session (F-ID-01…07)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-ID-01 | `OnboardingScreen` name step | `OnboardingView` name step | `POST /api/users` | AT-ID-01: create identity online → user row appears in `GET /api/users`; confetti fires; viewer persisted across relaunch |
| F-ID-02 | handle step + registry chain | same | `check-username` + `handles.json` | AT-ID-02: type taken handle → "is taken"+suggestion; airplane-mode → registry/`local` fallback verdict |
| F-ID-03 | `loginInstead` reclaim | same | `GET /api/users?name=` | AT-ID-03: 409 name clash → login branch → lands in chats of the reclaimed identity |
| F-ID-04 | token in Keystore-enc DataStore | token in Keychain | **[ADD]** token issue | AT-ID-04: kill+relaunch → still signed in; token present in secure store; API calls carry `Authorization` |
| F-ID-05 | switcher sheet + per-identity token | IdentityPickerSheet | roster + create | AT-ID-05: switch identity → socket re-joins as new user; inbox swaps; both viewers persist |
| F-ID-06 | forget viewer | forget viewer | — | AT-ID-06: forget → onboarding gate shows; secure store cleared (verifiable via fresh launch) |
| F-ID-07 | swatch palette | same | palette names | AT-ID-07: chosen color renders as avatar gradient on web AND both natives (cross-check web) |

### 4.B Conversations list (F-CL-01…19)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-CL-01 | `ChatsViewModel` + Room | `ChatsViewModel` + GRDB reads (fix) | list GET | AT-CL-01: send from web → native row preview updates ≤6 s (poll) and instantly when socket live |
| F-CL-02 | dock badge | dock badge | unreadCount | AT-CL-02: 3 unread → badge "3"; read all → badge clears on both natives |
| F-CL-03 | pin PATCH optimistic | same | `…/pin` | AT-CL-03: pin offline → row stays pinned; online reconcile keeps pin; unpin server reflects |
| F-CL-04 | mute strip 8h/1w/always | same | `…/mute` | AT-CL-04: mute 8 h → "Muted until <stamp>"; badge suppressed for that row |
| F-CL-05 | archived page | fullScreenCover | `…/archive` | AT-CL-05: archive → vanishes from list, appears in archived; unarchive restores |
| F-CL-06 | mark unread | same | `…/mark-unread` | AT-CL-06: mark unread → badge returns; opening room clears it |
| F-CL-07 | DataStore filter | fix prefsFilter binding | — | AT-CL-07: pick "Groups" → kill app → filter still applied on relaunch |
| F-CL-08 | self-chat card | same | `conversations/self` | AT-CL-08: tap Note to Self twice → same conversation id (no dup) |
| F-CL-09 | heat ring | StreakHeatRing | streak fields | AT-CL-09: at-risk convo shows amber ring; lost shows gray (verify against server streak bucket) |
| F-CL-10 | draft col + composer restore | GRDB draft store | draft GET/PATCH | AT-CL-10: type draft → kill → relaunch → draft restored; second device sees "Draft:" row |
| F-CL-11 | presence pump | same | — | AT-CL-11 (live socket): web user online → native dot green ≤5 s; offline → gray |
| F-CL-12 | typing map 4 s | same | `/typing` | AT-CL-12: web types → native row shows "typing…" ≤2 s and clears ≤6 s after stop |
| F-CL-13 | swipe reveal | DragGesture chips | — | AT-CL-13 (dev): swipe-left reveals Pin+Archive chips; tap pins without opening room |
| F-CL-14 | MultiSelectBar | same | batch = loop | AT-CL-14: select 3 → archive all → 3 archived, partial-failure toast if one 4xx |
| F-CL-15 | export to cacheDir+share | share sheet | paged history | AT-CL-15: export 250-msg chat → .txt contains all, share sheet opens |
| F-CL-16 | clear loop | same | DELETE ×N | AT-CL-16: clear my messages → my bubbles become tombstones on web too |
| F-CL-17 | skeleton/empty/error | same | — | AT-CL-17: airplane-mode first launch → error card w/ Retry; empty state with art |
| F-CL-18 | poll lifecycle | fix isActive | — | AT-CL-18 (dev): background app → poller stops (no network ops in profiler); foreground → resumes |
| F-CL-19 | action sheet | ChatActionSheet | as above | AT-CL-19: every sheet action performs its server call + toast (matrix run) |

### 4.C Messaging core (F-MS-01…30)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-MS-01 | SendMessageUseCase (cap 2000) | composer | messages POST | AT-MS-01: send 2001 chars → client blocks; 2000 chars → delivered; web renders |
| F-MS-02 | span parser | AttributedString parser | — | AT-MS-02: send `**b** _i_ ~~s~~ \|\|sp\|\| `code`` → all styles render on both natives + web |
| F-MS-03 | jumbo detect | same | — | AT-MS-03: emoji-only message renders ≥2× size |
| F-MS-04 | edit UI + PATCH | same | messages PATCH | AT-MS-04: edit → text changes on web + other native live (`message:edited`) |
| F-MS-05 | delete row in sheet | contextMenu delete | DELETE | AT-MS-05: delete → tombstone everywhere incl. sender's other device |
| F-MS-06 | reply UI (exists) | same | replyToId | AT-MS-06: reply → quote shows author+snippet; tapping quote scrolls to original (web parity) |
| F-MS-07 | thread sheet | same | thread GET | AT-MS-07: reply-in-thread → thread sheet lists replies; main flow not polluted |
| F-MS-08 | 24-picker + who-reacted | same | react POST | AT-MS-08: react with 🎉 → web sees grouped chip; long-press chip → reactor list |
| F-MS-09 | copy | copy | — | AT-MS-09: copy → clipboard contains raw text |
| F-MS-10 | ForwardSheet | same | re-POST | AT-MS-10: forward to 2 chats → both get copy w/ Forwarded semantics |
| F-MS-11 | saved toggle + library page | same | save POST / saved GET | AT-MS-11: star message → appears in Saved library; unstar removes |
| F-MS-12 | pins sheet | same | pin POST / pinned GET | AT-MS-12: pin → appears in RoomPins sheet; pin glyph on bubble |
| F-MS-13 | convert-to-task | same | kanban POST | AT-MS-13: convert → Kanban board gains card w/ sourceMessageId |
| F-MS-14 | reminders sheet | same | reminders POST | AT-MS-14 (dev): reminder in 1 min → local notification fires at time (quiet hours off) |
| F-MS-15 | info sheet | same | composed fields | AT-MS-15: info shows sent/delivered/read timestamps matching server data |
| F-MS-16 | sealed→view→burn | same | viewed POST | AT-MS-16: view-once → first tap reveals + marks; second device shows burned; retry shows burned |
| F-MS-17 | incognito toggle | same | anon fields | AT-MS-17: incognito send → web shows alias, not identity; alias stable across messages |
| F-MS-18 | schedule+manager | same | scheduled APIs + cron | AT-MS-18: schedule +2 min → cancel → never sends; schedule without cancel → sends at time (watch `maintenance/dispatch`) |
| F-MS-19 | TTL picker+expiry | same | disappearing PATCH | AT-MS-19: set TTL 1 h → new messages carry expiresAt; expired message invisible after window |
| F-MS-20 | 429 lockout timer | same | slow-mode PATCH | AT-MS-20: slow 30 s → 2nd send → 429 → composer shows countdown; send works after expiry |
| F-MS-21 | locked composer | same | broadcastMode | AT-MS-21: non-admin in channel → composer locked w/ notice; admin unaffected |
| F-MS-22 | slash palette | same | bot triggers | AT-MS-22: `/roll 2d6` → bot reply lands with payload `{bot:true}` |
| F-MS-23 | 4 FX kinds on receive | same | payload flag | AT-MS-23 (dev): send "confetti" effect from web → native fires full-screen confetti |
| F-MS-24 | sticker grid | same | kind=sticker | AT-MS-24: send sticker → renders on both natives + web |
| F-MS-25 | outbox+WorkManager | outbox+flush | same POST | AT-MS-25: airplane-mode send → temp bubble; go online → delivered ≤10 s, temp id swapped; order preserved for 3 queued |
| F-MS-26 | seen via message:read | same | read POST | AT-MS-26 (live socket): open room on native B → native A shows "Seen" ≤2 s |
| F-MS-27 | typing emit | same | typing | AT-MS-27: type on native → web shows typing bubble; stops 1.2 s after idle |
| F-MS-28 | paging+day sep | same | messages GET | AT-MS-28: scroll top → older page loads; day separators correct across midnight |
| F-MS-29 | phrases rail | same | phrases CRUD | AT-MS-29: add phrase → rail chip; delete removes |
| F-MS-30 | topic bar | same | topics APIs | AT-MS-30: create topic → filter shows only topic messages; delete topic → messages fall to General |

### 4.D Media (F-MD-01…09)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-MD-01 | Photo Picker+compress+upload+Coil | PhotosPicker+pipeline+AsyncImage | uploads POST/GET | AT-MD-01 (dev): send 4 MB photo → compressed ≤512/1280 → bubble renders image; web sees same |
| F-MD-02 | doc picker+upload | fileImporter | uploads POST | AT-MD-02 (dev): send 9.9 MB PDF → file card; 10.1 MB → honest size error |
| F-MD-03 | MediaRecorder+waveform+rate | AVAudioRecorder+player | uploads POST | AT-MD-03 (dev): record 5 s voice → waveform plays at 2×; web plays same file |
| F-MD-04 | transcribe strip | same | transcribe POST | AT-MD-04: tap transcribe → text strip appears; cached on second open |
| F-MD-05 | OG card render | same | unfurl POST | AT-MD-05: send URL → card (title/desc/img) appears; second client gets it via relay |
| F-MD-06 | translate action | same | translate POST | AT-MD-06: translate to ES → Spanish text shown; persists for other client |
| F-MD-07 | location card | MapKit card | kind=location | AT-MD-07: share pin → card renders; tap opens platform maps |
| F-MD-08 | lightbox | fullScreenCover viewer | uploads GET | AT-MD-08: tap photo → full-screen pinch-zoom; swipe dismiss |
| F-MD-09 | — | — | server | AT-MD-09: delete disk file server-side → GET still serves from BLOB (web test, natives unaffected) |

### 4.E Rich objects (F-RO-01…10)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-RO-01 | poll builder+bubble | same | poll APIs | AT-RO-01: create 3-option poll → vote → tally moves live on web; close locks |
| F-RO-02 | redpacket flow | same | redpacket APIs | AT-RO-02: send 100 PC/4 grabs → 4 users grab distinct slices; 5th → expired/honest fail; refund after 24 h (server check) |
| F-RO-03 | canvas sheet | canvas sheet | whiteboard APIs | AT-RO-03: draw stroke → appears on other client ≤5 s; undo removes last; clear empties both |
| F-RO-04 | kanban sheet | same | kanban APIs | AT-RO-04: add/move/delete card persists across relaunch |
| F-RO-05 | events sheet | same | events APIs | AT-RO-05: create event → RSVP "going" → check-in stamped once (second → guarded) |
| F-RO-06 | reminders sheet | same | reminders APIs | AT-RO-06: due reminder shows in due list; PATCH fired → badge clears |
| F-RO-07 | game card | same | games APIs | AT-RO-07: start match → join as O → winning 3 moves → win line rendered; board state identical on web |
| F-RO-08 | tournament sheet | same | tournaments APIs | AT-RO-08: create season → 2 join → standings show points; finish locks |
| F-RO-09 | leaderboard sheet | same | leaderboard GET | AT-RO-09: board shows XP+msgs+wins; scoped == room scope when in room |
| F-RO-10 | display only | display only | server-side | AT-RO-10: XP visible in profile after messaging (server behaviour; no native math) |

### 4.F Voice rooms (F-VR-01…04)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-VR-01 | voice sheet+roster | same | — | AT-VR-01 (dev, live socket): two devices join → roster shows both; leave removes |
| F-VR-02 | AudioRecord→chunk→play | AVAudioEngine | — | AT-VR-02 (dev): PTT hold on A → audio heard on B ≤1 s; mute blocks |
| F-VR-03 | captions strip | same | voice/transcribe | AT-VR-03 (dev): speak 4 s → caption text appears on peer |
| F-VR-04 | ptt glow | same | voice:ptt | AT-VR-04 (dev): B holds → A sees speaking ring on B's tile |

### 4.G Stage / 4.H Space (F-ST, F-SP)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-ST-01 | stage sheet | fullScreenCover | — | AT-ST-01 (dev): 3 join (1 host, 2 audience) → hierarchy correct on all clients |
| F-ST-02 | hand+approve | same | — | AT-ST-02 (dev): audience raises hand → host approves → speaker tile moves up |
| F-ST-03 | host controls | same | — | AT-ST-03 (dev): host mutes speaker (audio stops) and ends room (all bounce) |
| F-ST-04 | shared PTT | shared | — | AT-ST-04 (dev): approved speaker's PTT audible to audience |
| F-SP-01 | map+move | map+move | — | AT-SP-01 (dev): drag my tile → position updates on peer ≤2 s |
| F-SP-02 | peer render | same | — | AT-SP-02 (dev): peer joins → tile pops at their position |
| F-SP-03 | contract match | same | — | AT-SP-03: wire capture matches web space:state shape (fixture test) |

### 4.I Calls (F-CA-01…06)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-CA-01 | webrtc audio | webrtc audio | — (socket only) | AT-CA-01 (dev ×2): native↔web audio call connects P2P; audio both ways |
| F-CA-02 | video capture | video capture | — | AT-CA-02 (dev ×2): video call shows remote video; camera-denied → audio fallback |
| F-CA-03 | incoming overlay | fullScreenCover | — | AT-CA-03 (dev): ring 30 s no answer → server `call:cancel` timeout → overlay dismisses w/ reason |
| F-CA-04 | overlay controls | same | — | AT-CA-04 (dev): mute/speaker/hangup each take effect; duration ticks |
| F-CA-05 | calls page real | calls page | calls APIs | AT-CA-05: completed call → log row (completed, durationSec); rejected → declined on both clients once |
| F-CA-06 | call above nav | call above shell | — | AT-CA-06 (dev): navigate tabs during call → call continues; hangup returns cleanly |

### 4.J Stories (F-SR-01…05)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-SR-01 | rail (exists) | rail (exists) | stories GET | AT-SR-01: post story on web → native rail ring appears ≤ refresh |
| F-SR-02 | composer sheet | composer sheet | stories POST | AT-SR-02 (dev): post text story + image story → both visible on web |
| F-SR-03 | viewer cover | viewer cover (fix stub) | same GET | AT-SR-03 (dev): tap ring → full-screen viewer, tap-through, auto-advance |
| F-SR-04 | views mark+list | same | view APIs | AT-SR-04: view on native → web owner sees viewer listed; re-view deduped |
| F-SR-05 | delete own | same | stories DELETE | AT-SR-05: delete → gone from all rails |

### 4.K Channels (F-CH-01…04)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-CH-01 | directory page | directory page | channels GET | AT-CH-01: directory lists channels + mine separated; entry from pill (no toast) |
| F-CH-02 | create+first post | same | channels POST | AT-CH-02: create → channel appears in directory and my list with welcome post |
| F-CH-03 | subscribe/unsub | same | subscribe APIs | AT-CH-03: subscribe → appears in mine; unsubscribe → leaves; creator leaves → admin succession (web cross-check) |
| F-CH-04 | composer lock | same | broadcastMode | AT-CH-04: subscriber sees locked composer (cross-ref AT-MS-21) |

### 4.L Contacts/Profile/Safety (F-CP-01…09)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-CP-01 | roster (exists) | roster (exists) | users GET | AT-CP-01: roster sorted A–Z, presence dots when live |
| F-CP-02 | add page | add page | users?name= | AT-CP-02: lookup by exact name → profile + DM action |
| F-CP-03 | user page | user page | users/[id] + stats | AT-CP-03: open user → stats match server (msg count, member-since) |
| F-CP-04 | edit+avatar upload | same | users PATCH + uploads | AT-CP-04: change about+avatar → visible on web after refresh |
| F-CP-05 | block+blocked list | same | block APIs | AT-CP-05: block → DM attempt from blocked side fails honestly (server-enforced); unblock restores |
| F-CP-06 | report dialog | finish dialog | report POST | AT-CP-06: report w/ reason → confirmation; re-report same reason → idempotent |
| F-CP-07 | safety sheet | same | safety APIs | AT-CP-07: safety number identical on both clients (deterministic); verify stamps |
| F-CP-08 | verified badge | same | safety verify | AT-CP-08: verified peer shows badge in roster + room header |
| F-CP-09 | status edit | same | users PATCH | AT-CP-09: set status emoji+text → renders in rows/header |

### 4.M Search & Mentions (F-SM-01…04)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-SM-01 | shell search entry | same | search GET | AT-SM-01: search "contract" → message hits w/ highlighted snippet + local chat matches |
| F-SM-02 | room search | same | search GET | AT-SM-02: room-scoped search returns only that room's hits |
| F-SM-03 | mentions page+badge | same | mentions GET | AT-SM-03: mention Bob in group → Bob's badge increments; feed lists it |
| F-SM-04 | @ suggester | same | members roster | AT-SM-04: type "@" → member list; select → @Full Name inserted |

### 4.N Folders (F-FD-01…03)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-FD-01 | rail filter real | same | folders GET | AT-FD-01: tap folder → list filters to its conversations |
| F-FD-02 | manage sheet | same | folders POST/PATCH/DELETE | AT-FD-02: create "Work 📂" → rename → reorder → persists |
| F-FD-03 | membership assign | same | conversations PUT | AT-FD-03: assign 3 chats → folder shows exactly 3; remove 1 → 2 |

### 4.O PiP (F-PI-01…03)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-PI-01 | overlay panes | overlay panes | — | AT-PI-01 (dev): open 3 PiP panes → 4th demotes oldest; drag clamps inside frame |
| F-PI-02 | pills | pills | — | AT-PI-02 (dev): collapse → pill w/ unread badge; new message bumps badge |
| F-PI-03 | open bridge | pendingOpenRoom | — | AT-PI-03: tap expanded header → room opens; geometry persists relaunch |

### 4.P Settings (F-SE-01…09)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-SE-01 | account card | account card | users/[id] | AT-SE-01: copy User ID → clipboard matches viewer id |
| F-SE-02 | appearance (FX+mode) | same | settings PATCH | AT-SE-02: change FX mode → background changes; prefs survives relaunch; blob on server matches |
| F-SE-03 | chat prefs + outbox mgr | same | settings PATCH | AT-SE-03: bubble radius pill → bubbles re-shape; outbox manager lists queued sends |
| F-SE-04 | notif prefs + quiet hours | same | settings PATCH | AT-SE-04 (dev): quiet hours on → reminder inside window suppressed |
| F-SE-05 | privacy toggles+blocked list | same | settings PATCH + blocks GET | AT-SE-05: readReceipts off → other client never sees "read" (server-enforced) |
| F-SE-06 | diagnostic card | exists | probe | AT-SE-06: probe shows gateway host + ms or honest failure offline |
| F-SE-07 | accessibility | same | settings PATCH | AT-SE-07 (dev): OS reduce-motion on → FX static + transitions fade |
| F-SE-08 | data mgmt | same | — | AT-SE-08: clear drafts/outbox → counts zero; storage row reflects |
| F-SE-09 | about | exists | — | AT-SE-09: version matches build (versionName / CFBundleVersion) |

### 4.Q Hub (F-HB-01…10)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-HB-01 | wallet panel | wallet (exists) | wallet GET | AT-HB-01: balance + last 10 ledger rows match server |
| F-HB-02 | check-in | same | checkin POST | AT-HB-02: check-in → +25 PC; second same-day → honest already-checked-in |
| F-HB-03 | transfer | same | transfer POST | AT-HB-03: transfer 10 PC to @bob → bob's balance +10, ledger both sides |
| F-HB-04 | swap | same | swap GET/POST | AT-HB-04: swap 50 PC→GEM at shown rate → balances update atomically |
| F-HB-05 | tasks | same | tasks APIs | AT-HB-05: add/move/complete/delete task persists |
| F-HB-06 | market | same | market APIs | AT-HB-06: list for 30 PC → other user buys → escrow moves funds, listing sold |
| F-HB-07 | logs | same | logs GET | AT-HB-07: stream shows latest events incl. the transfer from AT-HB-03 |
| F-HB-08 | apps matrix | same | install APIs | AT-HB-08: browse 100 apps offline (bundled catalog); install online → state connected |
| F-HB-09 | app community | same | community APIs | AT-HB-09: join app community → real conversation opens; founder is admin |
| F-HB-10 | my apps | same | installs GET | AT-HB-10: my apps lists exactly installed set |

### 4.R Groups & invites (F-GR-01…06)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-GR-01 | new chat sheet (exists) | NewChatSheet (exists) | conversations POST | AT-GR-01: create group w/ 2 members → members include creator (distinctIds) |
| F-GR-02 | room info page | room info page | conversations PATCH/GET | AT-GR-02: rename + photo + description → all render for other members |
| F-GR-03 | member mgmt | same | members APIs | AT-GR-03: add member → appears; promote → admin chip; remove → gone (event received) |
| F-GR-04 | leave | same | members DELETE | AT-GR-04: leave → conversation leaves my list; others see member count drop |
| F-GR-05 | invite deep link | same | invite APIs | AT-GR-05: create code → open `pulse://invite/<code>` cold → preview → join → member |
| F-GR-06 | conversation:updated handler | same | — | AT-GR-06: rename group on web → native list title updates live |

### 4.S Offline (F-OF-01…05)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-OF-01 | Room cache shell | GRDB reads wired | — | AT-OF-01: airplane-mode cold start → last conversations + room render from DB with stale badge |
| F-OF-02 | — | — | — | AT-OF-02: n/a on natives (install channels differ) — documented |
| F-OF-03 | outbox table | outbox table | — | AT-OF-03: queue 5 offline → relaunch app offline → still queued (persistent) |
| F-OF-04 | triggers | triggers | — | AT-OF-04: toggle airplane off → flush auto-starts ≤2 s without touching UI |
| F-OF-05 | delta cursor (optional) | same | **[ADD]** optional | AT-OF-05: if implemented — sync after 1 h gap fetches only changed convs (verify via server log); else full-refresh test |

### 4.T AI & automation (F-AI-01…05)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-AI-01 | render only | render only | ai-bot.ts | AT-AI-01: DM the bot from native → reply ≤15 s with typing indicator first; viaAutomation-free but bot-tagged |
| F-AI-02 | slash triggers | same | bot-engine.ts | AT-AI-02: `/8ball` from native → canned reply |
| F-AI-03 | automations CRUD | same | automations APIs | AT-AI-03: admin adds rule "deploy"→reply → member typing "deploy" triggers reply once |
| F-AI-04 | recap card | same | ai/recap POST | AT-AI-04: recap in 10-msg room → ≤5 bullets; <5 msgs → honest 409 message |
| F-AI-05 | webhook mgmt | same | webhooks APIs | AT-AI-05: create webhook → curl token POST → message lands from webhook identity |

### 4.U Realtime (F-RT-01…08)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-RT-01 | join/presence | same | — | AT-RT-01 (live): native join → web presence snapshot includes it |
| F-RT-02 | typing | same | /typing | AT-RT-02: covered in AT-CL-12/AT-MS-27 |
| F-RT-03 | full message events | same | /notify whitelist | AT-RT-03: react+edit+pin+view-once from web → each reflects on native without poll (≤2 s) |
| F-RT-04 | aux events | same | — | AT-RT-04: poll vote + link preview + rename → native caches update live |
| F-RT-05 | voice/stage/space | same | — | AT-RT-05: covered by AT-VR/ST/SP |
| F-RT-06 | call signals | same | — | AT-RT-06: covered by AT-CA-01 |
| F-RT-07 | reconnect policy | same | — | AT-RT-07 (dev): kill Wi-Fi 30 s → back → socket re-joins, missed messages fetched, no duplicate listeners |
| F-RT-08 | tolerate missing presence | same | privacy GET | AT-RT-08: peer hides lastSeen → native shows no dot (web cross-check) |

### 4.V Theme/FX (F-FX-01…07)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-FX-01 | AGSL/Canvas (exists) | Metal/Canvas (exists) | settings blob | AT-FX-01: all 6 modes render; off = clear; preview strips match live |
| F-FX-02 | particles (exists) | particles (exists) | — | AT-FX-02 (dev): send/react/onboarding each fire correct kind |
| F-FX-03 | not ported (P2) | not ported | — | AT-FX-03: documented decision — no test |
| F-FX-04 | not ported (P2) | not ported | — | AT-FX-04: documented decision — no test |
| F-FX-05 | conv theme picker | same | prefs blob | AT-FX-05: set room wallpaper → room background changes, persists |
| F-FX-06 | dark cycle (exists) | exists | prefs | AT-FX-06: cycle system→light→dark → system bars/status icons follow (Android F3 fix) |
| F-FX-07 | motion presets (exists) | exists | — | AT-FX-07: press any row/button → uniform 0.94 scale; no ad-hoc springs (code review gate) |

### 4.W Platform (F-PS-01…05)

| FEATURE | ANDROID | iOS | BACKEND | ACCEPTANCE TEST |
|---|---|---|---|---|
| F-PS-01 | LiveUpdater (exists) | App Store lane (external dep) | update-manifest | AT-PS-01 (dev): publish v0.1.8 → in-app banner → download → integrity gates → PackageInstaller verdict shown |
| F-PS-02 | — | — | dispatch cron | AT-PS-02: scheduled message due → dispatched by keeper → arrives on natives |
| F-PS-03 | local notifs; FCM spec'd | local notifs; APNs spec'd | **[ADD]** push backend | AT-PS-03 (dev): local reminder notification fires; push marked BLOCKED-EXTERNAL until Firebase/Apple accounts exist |
| F-PS-04 | pulse:// handling | onOpenURL | invite APIs | AT-PS-04: cold-start `pulse://room/<id>` → opens room; `pulse://invite/<code>` → join sheet |
| F-PS-05 | honest failures | same | — | AT-PS-05: dead gateway → settings probe honest; no silent crash (logcat/oslog clean) |

---

## 5. BACKEND DELTA — EXISTS / CHANGE / ADD

The decisive architectural fact: **the natives consume the same API web does.** The overwhelming majority of the backend is ALREADY native-ready. The delta:

### 5.1 EXISTS — usable unchanged (verified by web + native parity tests)
Conversations CRUD + all sub-resources (messages, read, pin, mute, archive, mark-unread, draft, disappearing, slow-mode, screen-privacy, invite, members, topics, poll, scheduled, whiteboard, kanban, events, automations) · messages (edit/delete/react/save/pin/thread/viewed/transcribe/unfurl/translate) · users (create/list/lookup/check-username/PATCH/stats/phrases/saved/block/blocks/safety/report) · channels · stories · calls · games · tournaments · events/rsvp/checkin · folders · redpackets · webhooks · polls · reminders · uploads (+BLOB fallback) · voice/transcribe · ai/recap · hub (wallet/checkin/transfer/swap/tasks/market/logs/apps/community) · search · mentions · leaderboard · invite · settings · maintenance/dispatch · internal/privacy · socket service (all rooms/events).

### 5.2 CHANGE (small, contract-level)
| # | Change | Why |
|---|---|---|
| C-1 | `packages/protocol/src/contracts.ts`: complete `SOCKET_EVENTS` + typed socket payloads; bump schema to v2 | Contract is stale vs service (missing 10+ events) — §3.3 |
| C-2 | `POST /api/uploads` accept multipart (optional, additive) | Cut base64 overhead for native media (§3.8) |
| C-3 | `update-manifest.json`: per-platform entries (`android`, `ios`) | One manifest drives both update channels (§3.16) |
| C-4 | Error bodies: guarantee `retryAfter` on all 429s (verify slow-mode + any future rate limits) | Native lockout UX (F-MS-20) |

### 5.3 ADD (new backend surface — each is a separate approval)
| # | Addition | Blocking |
|---|---|---|
| A-1 | Session tokens: issue on create/reclaim, `Authorization` verify middleware (optional-verify for migration), `POST /api/users/login` | Nothing — natives can ship without; required for §3.11 target model |
| A-2 | Socket token verification (`join{userId,token}`) | A-1 |
| A-3 | Push: token registry + send pipeline hooks + FCM/APNs credentials | **External deps**: Firebase project, Apple Developer Program |
| A-4 | Delta sync `GET /api/conversations?since=` (optional perf) | Nothing |
| A-5 | coturn TURN server (optional) | Field-testing evidence first |

---

## 6. SHARE / REUSE / REWRITE / DISCARD LEDGER (existing native code)

### 6.1 Shared by design (contract, not code)
- Wire DTO shapes + failure taxonomy + event names (mirrored, test-pinned) · REST surface · socket service · CDN artifacts (handles.json, update-manifest.json, APK) · business rules living server-side (XP, streaks, safety numbers, bot logic, automations, blocks enforcement, privacy enforcement).

### 6.2 Reuse as-is (verified healthy)
**Android:** module structure · Hilt graph · Ktor client + error mapping · socket client + null-guard · Room cache + optimistic reconcile · PulseResult/PulseTime/PulseFx · onboarding (both steps + offline chain) · chats/room text core · contacts · profile · AGSL/Canvas FX · particle host · theme · **LiveUpdater end-to-end** · CI+CD chain.
**iOS:** shell/dock/pendingOpenRoom · PulseAPIClient + failure kinds · socket client (dormant but correct) · WireDtos + parity tests · Metal/Canvas FX · ParticleBus · toast/haptics/formatting/components/theme tokens · onboarding + offline identity · chats/room text core · NewChatSheet · Settings probe · Profile · Hub wallet.

### 6.3 Rewrite or extend in place (the "Build" cells of §2)
All ~90 **Build** rows: media stack, outbox/drafts, messaging-core gaps (edit/delete/pin/threads/save/forward/view-once/incognito/schedule/TTL/slow-mode/commands/effects/stickers), voice/stage/space, calls, stories viewer/composer, channels, hub panels, folders, mentions/search/room-info surfaces, PiP, settings completions, deep links, notifications, contract expansion, DB migrations, session tokens.

### 6.4 Discard / delete
| Item | Action | Reason |
|---|---|---|
| Android `CallsScreen` (19 LOC stub) + `StoriesScreen` (18 LOC stub) | Delete, rebuild as real features | Dead code, never routed |
| Android static `HubScreen` tiles | Replace with real panels | Showcase-only |
| Android dead deps: `androidx.work` (until activated), `ktor-client-websockets` | Remove or activate in the same wave that uses them | Zero usages today (hygiene) |
| Android `proguard-rules.pro` absence | Add file when R8 lands | Referenced but missing |
| iOS GRDB write-only usage pattern | Not discard — complete (wire read paths) | Structural fix §3.2 |
| Web-only systems: 13 nav styles, 5 UI themes, phone frame, sessionStorage identity, PWA/SW, `XTransformPort` transport | Do not port | Rule P2 / platform artifacts |

---

## 7. IMPLEMENTATION WAVES (sequenced proposal — NOT started; each wave = separate user approval)

**Wave 0 — Contract & foundation (backend + both natives)**
C-1 contract completion → extend WireDtos.kt / WireDtos.swift + parity tests → bake decision: live gateway + socket via manifest override (Android done; iOS same) → power sockets on both (full event set, reconnect policy, re-join) → iOS structural fixes (GRDB reads, poller lifecycle, prefsFilter, report dialog, photoURL).

**Wave 1 — Messaging core completion (the daily-driver gap)**
Formatting/spans · jumbo emoji · edit · delete tombstone · forward · save+library · pins sheet · threads · who-reacted+24 picker · message info · view-once · incognito · scheduled+manager · TTL · slow-mode 429 timer · composer lock · quick phrases · topics · @mentions suggester · slash palette · stickers · message effects · **outbox + drafts (offline core)** · DB migrations (Room v4 / GRDB v2).

**Wave 2 — Media**
Photos (capture/compress/upload/render/lightbox) · documents · voice notes (record/waveform/rates) + transcribe strip · link unfurl cards · translation · location cards · avatar/profile uploads.

**Wave 3 — Social graph & discovery**
User pages + stats · edit profile · blocked list · reports finish · safety numbers + verification · status emoji · contacts add page · mentions feed + badge · folders (full) · room search · spotlight promotion · channels directory · stories composer+viewer+views · deep links (`pulse://room|invite|user`).

**Wave 4 — Realtime rooms**
Voice rooms (PTT+captions) · Stage (hierarchy/hands/host) · Space (map) · conversation:updated + aux event handlers · PiP panes.

**Wave 5 — Calls**
WebRTC engines both platforms · audio → video · incoming overlay + 30 s ring · call log page · state-above-navigation.

**Wave 6 — Collaboration & Hub**
Kanban · events+RSVP+checkin · reminders (+local notifications Tier 1) · whiteboard · red packets · games+tournaments+leaderboard · Hub wallet/swap/transfer/tasks/market/logs/apps/communities.

**Wave 7 — Platform & hardening**
Session tokens (A-1/A-2) · settings completions (chat prefs, data mgmt, quiet hours) · accessibility honor-OS pass · performance pass (budgets §3.14) · R8/proguard · push Tier 2 when external deps exist (A-3) · iOS signing/TestFlight when Apple account exists.

Sequencing rationale: Waves 0–1 close the "text messaging is fully usable offline" bar (the product's core), Waves 2–3 make it feel complete, 4–6 are the differentiators, 7 is hardening. Every wave ends with its AT matrix executed and a worklog entry.

---

## 8. RISKS & OPEN DECISIONS (stated, not resolved)

1. **No TURN** (web+natives): calls fail on symmetric NATs. Mitigation: A-5 after field data.
2. **Auth gap** (self-declared identity) is the honest current state; A-1 is specified but requires a product decision on migration timing.
3. **Push needs external accounts** (Firebase, Apple Developer) — delivery blocked on credentials, not code.
4. **iOS distribution** is unsigned today; TestFlight lane blocked on Apple Developer Program.
5. **Socket.IO client compatibility** (java 2.1.0 / swift 16.0.1 vs server v4): protocol-compatible on paper; first live connection is the proof (Wave 0 AT).
6. **Whiteboard transport** has no dedicated socket event today — web parity means REST/notify transport; a `whiteboard:stroke` event would be a C-1 candidate if latency disappoints.
7. **DB migrations**: Room currently destructive — first migration wave must establish non-destructive discipline BEFORE user data accumulates.
8. **HARDWARE-DEPENDENT verification**: mic, camera, speaker routing, PackageInstaller verdicts, push, ringtone — cannot be claimed complete without devices; all marked "(dev)" in §4 and must never be auto-passed.
9. **`x-pulse-key` default constant** in internal endpoints — rotate when the deployment leaves dev.

---

## 9. DEFINITION OF DONE (native parity program)

1. Every F-ID in §2 has an implementation on both platforms (or a documented P2 "not ported" decision).
2. Every AT in §4 executed once against the live gateway; device-dependent ATs executed on one physical device per platform; results recorded in `worklog.md`.
3. Contract parity tests green on both CIs for every DTO family.
4. Offline core proven: airplane-mode cold start renders cached state; queued sends flush automatically (AT-OF-01/03/04, AT-MS-25).
5. Zero dead-end toasts remaining in either dock or room (grep-gate: "isn't available in this native build" returns nothing).
6. Android ships via the existing CDN chain (four-source hash rule); iOS ships via TestFlight or documented unsigned status.
7. Honest status table per wave: VERIFIED / DORMANT / STUB — no category invented to look complete.

---

*End of specification. Nothing herein has been implemented; every "Build" cell awaits explicit user approval, wave by wave.*
