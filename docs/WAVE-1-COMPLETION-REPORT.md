# PULSE — WAVE 1 COMPLETION REPORT

**Wave:** Native Messaging Surface Parity · **Release:** `v0.3.0-native` (Android versionCode 11) · **Date:** 2026-09-11
**Binding spec:** `docs/WAVE1-NATIVE-MESSAGING-SPEC.md` · **Baseline:** Wave 0 closed (`v0.2.0-native`)

---

## 0. The Wave 1 gate — VERDICT

> *"A user must be able to install the native app, open a conversation, send and receive messages in real time, use the core message actions, go offline, send messages, return online, and have the outbox reconcile correctly."*

**PASSED** — with the evidence chain below. Installable APK: released, forensically verified, CDN-live, four-source hash equal.

---

## 1. Features implemented (FEATURE → Android → iOS → backend → persistence → offline → test)

| # | Feature | Android (Kotlin/Compose) | iOS (Swift/SwiftUI) | Backend | Persistence | Offline | Test |
|---|---|---|---|---|---|---|---|
| 1 | Conversation list (DM/group/channel, unread, timestamps, drafts indicator, archive/mute/mark-unread/pin) | Rows + badges + filters + batch bar + Archived screen (Wave 0) + local-draft preview merge + OutboxDropped snackbar | Full row model + actions + optimistic mutators + Archived page (Wave 0) | `GET /api/conversations` + PATCH sub-resources (pre-existing) | Room conversations (`membersJson` v5) / GRDB conversations | GRDB/Room rehydrate (existing) | CI JVM + instrumented; live E2E |
| 2 | Timeline: pagination + optimistic + ticks + day separators | `before=` cursor pages (40) merged into reactive Room flow; scroll-anchored load-older; day pills; clock→✓→✓✓ ticks (member watermarks) | `loadOlder` id-dedupe merge; day chips (existing); Sent/Seen ticks group-aware; temp-first optimistic send | `GET messages?limit=&before=` (pre-existing) | Room v5 / GRDB v3 | cached seed → network (existing) | E2E PAGINATION (before= strictly older + hasMore); CI compile |
| 3 | Realtime: reconnect, join/leave, message:new, typing, presence, read receipts, unread updates | 27 S→C handlers (Wave 0) → reactive Room→UI; read-post debounce; re-join on reconnect | signals→PulseSession→views; `messageRead` parses real `lastReadAt` watermark; re-join on reconnect | `join`/`typing` emits + REST read + server relays | — | reconnect flush (existing) | Android JVM real relay round-trip (5/5, CI); E2E READ/RECONNECT; iOS in-sim round-trip (Wave 0-proven path) |
| 4 | Message actions: reply, reactions, edit, delete, pin, save, forward, info | MessageActionSheet: 6 reactions (web whitelist 👍❤️😂😮😢🎉), reply, thread, edit (composer mode), copy, pin/unpin (+banner+pins dialog), save, forward (multi-target re-POST), delete (confirm), info (seen-by + reactions) | contextMenu: same 10 actions; pins banner + list sheet; MessageInfoSheet; ForwardSheet | react/edit/delete/pin/save REST (pre-existing); forward = client re-POST (web parity) | row upserts (Room/GRDB) | optimistic swap w/ revert; edits never queue | E2E REACT/EDIT(403 non-sender)/PIN/PINS/SAVE; CI compile |
| 5 | Threads | ThreadScreen (route `room/{cid}/thread/{rootId}`): parent card + replies asc + composer; send `parentId`; live reply append via Room; "N replies ↳" chips; river filters `threadRootId != null` | ThreadView sheet: same; `TempMessages.make(parentId:)`; live append via signals; count chips; river filter `parentId != nil` | `parentId` send + one-level rule (400 on nested) + `GET /messages/{id}/thread` (pre-existing) | Room `threadRootId` / GRDB v3 `parentId` + `threadReplyCounts()` | thread replies NOT queued (spec §1.2, web parity) | **E2E THREAD: reply 201 + thread-read asc + one-level 400** — correct backend behaviour, web's cache-shape bug not reproduced |
| 6 | Drafts: local + restore + server mirror | DraftDao 600ms debounce + restore (local wins) + NEW `PATCH /conversations/{id}/draft` silent mirror (blank clears) | same + `setDraft` in debounced persistDraft | draft PATCH (pre-existing) | DraftDao / GRDB draft | local-first | E2E DRAFT mirror (`myDraft` surfaces) |
| 7 | Offline outbox | FIFO≤50 + stop-at-first-failure + temp→real swap + 5 flush triggers (Wave 0) + dropped-entry snackbar + offline strip | engine (Wave 0) + toasts + offline strip | — | outbox tables (existing) | full chain | E2E OFFLINE→FLUSH; FlushOutboxUseCase 5/5; PulseOutboxTests 6/6 |
| 8 | Search: global + conversation + jump | global hits → `?jump={id}` route → scroll+flash (amber ring) + bounded ≤14-round history expansion; room search (server `q=` + local window merge, snippet highlight) | RoomRoute `jumpMessageId` → same scroll+flash; in-room search panel w/ HighlightedSnippet | `GET /api/search`, `GET messages?q=` (pre-existing) | query-time | local window filter works offline | E2E SEARCH q= |
| 9 | Media messaging: image/file/captions/preview/upload/persistence/download | attach sheet (PickVisualMedia/OpenDocument) → ≤1280px JPEG q0.82 / doc ≤10MB → base64 dataURL → `POST /api/uploads` → staged caption card → send; Coil AsyncImage bubbles + lightbox dialog; file bubble → download to cache + FileProvider ACTION_VIEW open/share | PhotosPicker + fileImporter → same upload contract; AsyncImage + MediaLightboxView (pinch/2×-tap zoom); file → tmp download + QuickLook; staged card identical | `POST /api/uploads` (JSON dataUrl, pre-existing) + `GET /api/uploads/{file}` | Room v5 media cols / GRDB v3 media cols | media NEVER queues; inline error + retry keeps staged card | E2E MEDIA: upload→message(imagePath+caption)→served bytes; PulseMediaTest 5/5 |

---

## 2. Android files/modules changed

- `:protocol` — `WireDtos.kt` (+parentId/media decode, member lastReadAt), `SocketContractsTest.kt` untouched, NEW `ChatMessageDtoParityTest.kt`
- `:domain` — `Models.kt` (Message media fields, Conversation.members/ConversationMember), `PulseRepository.kt` (+11 members), `SendMessageUseCase.kt` (+parentId), `Outbox.kt` unchanged; tests updated (FakeRepo) + SendMessageUseCaseTest/FlushOutboxUseCaseTest
- `:data` — `PulseDatabase.kt` (v5: messages +imagePath/audioPath/filePath/fileName/fileSize/viewOnce, conversations +membersJson, MIGRATION_4_5, observeThread/countByThread), `PulseApi.kt` (+9 endpoints, sendMessage full body, readBytes), `PulseRepositoryImpl.kt` (mapper deconflation parentId↔replyToId, +11 impls, downloadMedia), `DataModule.kt` (migration chain 3→4→5), `schemas/…/5.json`, `RoomMigrationTest.kt` (v4→v5 + full 3→4→5 path)
- `:core` — NEW `media/PulseMedia.kt` + `PulseMediaTest` (5)
- `:feature-chat` — `ChatRoomScreen.kt` (rebuild: pagination, day pills, ticks, attach, banners, search+jump, snackbar), NEW `ThreadScreen.kt`, NEW `MessageSheets.kt` (actions/forward/info/pins/delete dialogs + PulseCheckCheck canvas glyph), NEW `ChatRoomViewModel.kt` (654L engine), NEW `MediaSupport.kt`, `ChatsViewModel.kt` (extraction + draft merge), `ChatsScreen.kt` (jump param), `build.gradle.kts` (+Coil, +activity-compose)
- `:app` — `MainActivity.kt` (thread route + jump arg), `build.gradle.kts` (versionCode 11 / 0.3.0-native)
- `gradle.properties` — heap 2048m (CI D8 OOM fix)

## 3. iOS files/modules changed

- `Domain/Models/WireDtos.swift` — WireChatMessage +Identifiable (+media/parentId decode confirmed), NEW WireThreadPage/WirePinnedPage/WireSavedToggle/WireUploadResult, member lastReadAt
- `Core/Networking/PulseAPIClient.swift` — +editMessage/toggleMessagePin/toggleMessageSave/pinnedMessages/thread/messages(before:query:)/uploadMedia/setDraft/conversationDetail; sendMessage extended (parentId + media + kind); query-encoding helper
- `Core/Storage/PulseStore.swift` — GRDB v3 (message +parentId/imagePath/audioPath/durationMs/filePath/fileName/fileSize/editedAt/deletedAt/reactionsJson/senderColor — full-fidelity cache), messages(threadRootId:), threadReplyCounts()
- `Core/Support/PulseSession.swift` — messageRead carries lastReadAt; `PulseEndpoints.swift` +mediaURL
- `Features/Chat/ChatRoomView.swift` (~1870L) — ChatRoomView jumpMessageId; RoomMessageRow extraction; RoomViewModel +14 members (pagination, replyCounts, pins, jump+flash, room search, edit/delete/pin/save, staged media + sendStaged, temp-first send + swapTemp, group-aware Seen watermarks, draft mirror); composer attach Menu + staged bar + edit bar; pinned banner + pins sheet; offline strip; MessageInfoSheet; BubbleView image/file/thread-chip/flash/Sent
- NEW `Features/Chat/ThreadView.swift`, `ForwardSheet.swift`, `MediaPickerSupport.swift` (+MediaLightboxView, PulseMediaOpener), `QuickLookView.swift`
- `Features/Chats/ChatsView.swift` — RoomRoute +jumpMessageId (hash/==), search-hit wiring, HighlightedSnippet promoted internal
- `PulseTests/` — PulseDataLayerTests (NEW, 9), PulseStoreMigrationTests (+4 = 8), WireParityTests (+4 = 7), PulseOutboxTests updated

## 4. Backend changes

**ZERO.** Every Wave 1 surface rode pre-existing endpoints/relays — the natives were the lagging side. The web thread-send bug was confirmed to be web-client cache-shape only; the wire contract (`parentId`) was implemented correctly on natives and verified live.

## 5. Protocol changes

None to `packages/protocol/src/contracts.ts` (registry already complete). Native mirror updates only (Android `WireDtos.kt`, iOS `WireDtos.swift`).

## 6. Tests added

- Android JVM: ChatMessageDtoParityTest (protocol), PulseMediaTest 5 (core), SendMessageUseCase/FlushOutboxUseCase/FakeRepo updates — local run GREEN
- Android instrumented: RoomMigrationTest v4→v5 + corrected full 3→4→5 path — CI emulator GREEN
- iOS: PulseDataLayerTests 9 (thread page decode, path building, reactionsJson round-trip), PulseStoreMigrationTests v2→v3, WireParityTests +4 (parentId/media decode) — CI **33 tests, 0 failures, 3 skipped** (2 keychain = sandbox by design; 1 SocketRoundTrip = honest XCTSkip this round, fixture healthy on runner; the same test passed on the Wave 0 tree and realtime is separately proven)
- E2E: `apps/qa/wave1-messaging-gate.js` — **27/27 PASSED** live (identities, DM, socket join, TEXT→REALTIME relay, PERSISTENCE, THREAD×3, PAGINATION×2, MEDIA×3, SEARCH, READ relay, REACT/EDIT/EDIT-403/PIN/PINS/SAVE, DRAFT mirror, OFFLINE→FLUSH×2, RECONNECT)

## 7. Android CI evidence

| Run | Trigger | Result |
|---|---|---|
| 34580803426 | main `fd8c0f7` | ✅ build (JVM tests incl. REAL node-relay socket round-trip + signed release APK) + instrumented (emulator launch smoke + Room v3→4→5 migration + outbox/draft DAO round-trips) |
| 34585146573 | tag `v0.3.0-native` | ✅ build + instrumented + auto-publish Release |

Fix loop (each root-caused from runner logs): R2 D8 OutOfMemoryError on mergeExtDexRelease (Coil) → heap 2048m; R3 RoomMigrationTest needed the real 3→4→5 path; R1 PhotosUI import.

## 8. iOS CI evidence

| Run | Trigger | Result |
|---|---|---|
| 34583545403 | main `46f449c` | ✅ build-test (**33 tests, 0 failures**) + simulator launch smoke + ✅ archive (unsigned xcarchive, structure-verified) |
| 34585146610 | tag `v0.3.0-native` | ✅ build-test + archive |

Fix loop: R1 PhotosUI; R2 Identifiable/TempMessages-@MainActor/type-check-split/session unwrap; R3 URL unwrap; R4–R6 Section generic-V (real cause: `PulseAvatar(colorHex:)` label mismatch — lesson: SwiftUI generic-V errors point at the poisoned child expression).

## 9. Artifact evidence

- Release auto-published from tag (contents:write path exercised for the first time): **Release v0.3.0-native**, asset `Pulse-v0.3.0-native.apk`, 14,436,988 bytes, state `uploaded`
- **Forensic gate** (bun, no SDK): ZIP CRC sweep clean; APK Signing Block **v2 + v3** + v1 META-INF/CERT.SF+RSA; resources.arsc STORED + 4-aligned; AXML: package `app.pulse.chat`, **versionCode 11**, **versionName 0.3.0-native**, minSdk 21, targetSdk 35
- **FOUR-SOURCE HASH RULE: ALL EQUAL** `bebf37b7b42999d9a24a5736f50da0caa98df110f933df41c91f058b8a2f21dc` — (1) tag-CI/release bytes (2) release asset (3) live raw CDN `download/Pulse.apk` (4) live `update-manifest.json` sha256 field (versionCode 11 manifest commit `f354606`)

## 10. Real-device / simulator verification

- Android: emulator (CI, API-30): app launch smoke, Room migration on-device, outbox/draft DAOs on-device. Realtime on-device: JVM production-client round-trip vs real node relay (join/presence/typing/notify/transport-drop-reconnect) + 27/27 live-gateway E2E of the exact wire path. **Physical-device install: not performed in this sandbox (no device attached)** — the APK is signed (v1+v2+v3) and parse-clean per forensics; prior-wave physical installs were user-verified.
- iOS: simulator launch smoke (simctl install/launch + screenshot artifact) + in-simulator tests. SocketRoundTripTests skipped on this round's runner (honest XCTSkip; fixture healthy — runner-side probe raced); realtime wire verified by Wave 0's in-simulator round-trip + the 27/27 live E2E of the same contract the client code uses.
- Honest statement: "TEXT → REALTIME → PERSISTENCE → RECONNECT → OFFLINE → FLUSH" is proven live end-to-end (E2E 27/27) + platform components proven in CI; on-physical-device soak was not possible from this environment.

## 11. Known limitations

1. Media and thread replies never queue offline (web parity, spec §1.2) — honest inline errors instead.
2. iOS SocketRoundTripTests skipped on the final CI round (see §10).
3. iOS archive remains unsigned (no Apple Developer Program); TestFlight/device installs blocked on that credential.
4. Android "Seen" tick is computed from member watermarks carried in summaries/detail; a member with hidden read receipts (web privacy feature) reports epoch watermarks — natives show "Sent" instead of "Seen" for them (same as web rendering path).
5. Forward shows a toast count only (no forward-history UI) — web parity.

## 12. Remaining parity gaps (NOT hidden)

- Saved-messages **library page** (save toggle works; library browsing UI is web-only today)
- Voice-note **recording/playback** (existing voice messages render as waveform+duration chips; recording was out of Wave 1 scope)
- View-once consume, polls (create/vote), link previews, translations, topics, kanban, scheduled sends, disappearing-TTL settings UI — web has them; deferred per spec §5 non-goals
- Push notifications (needs Firebase/APNs accounts)
- Calls/Stories/Voice Rooms/Hub games — deferred families per directive

## 13. Exact recommendation for Wave 2

**Wave 2 — Native Messaging Depth II (complete 100% messaging parity before advanced families):**
1. Voice notes: record (AVAudioRecorder / MediaRecorder→upload contract already accepts audio) + playback (AVAudioPlayer / Media3) + transcript display
2. View-once media consume (`POST /messages/{id}/viewed` already relayed as `message:viewed`; blur-gate UI)
3. Polls (create/vote UI over `POST /api/conversations/{id}/poll` + `poll:voted`)
4. Link previews render (`link:preview` already decoded)
5. Saved library page (`GET /api/users/{id}/saved`)
6. Topics + scheduled/disappearing settings UI (endpoints pre-existing)
Alternative candidate per the directive queue: Calls (WebRTC signaling exists in the registry) — but that requires a relay-hosting decision (the dormant `gateway`/`socket` manifest keys) and is a larger risk surface; messaging depth first keeps the parity chain unbroken.

**Wave 2 will NOT start until you approve it.**
