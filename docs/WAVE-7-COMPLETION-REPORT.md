# PULSE — WAVE 7 COMPLETION REPORT — COLLABORATION & HUB

**Implementation Wave 7 · master-spec §7 family · Feature IDs F-RO-01…10 + F-HB-01…10**
Platforms: native Android (Kotlin + Compose) and native iOS (Swift + SwiftUI). Behavioural truth: repo-root web app; wire contracts with file:line citations in `docs/WAVE7-COLLAB-HUB-PARITY-SPEC.md` + worklog Task 7-a.

**Numbering resolution (standing record):** the master spec's "Wave 6 — Collaboration & Hub" family is **implementation Wave 7**. Implementation Wave 6 closed the Social Graph/Discovery/Identity Safety family (spec §3 remainder). Reports that say "remaining: Spec Wave 6" refer to the SPEC's numbering.

---

## 1. Feature IDs — implemented end-to-end (both platforms)

| ID | Surface | Android | iOS | Transport |
|----|---------|---------|-----|-----------|
| F-RO-01 | Polls | pre-existing (regression-verified) | pre-existing (regression-verified) | socket `poll:voted` + REST |
| F-RO-02 | Red packets (create/atomic grab/expire refund) | RedPacketCard + detail/grab sheet, 20 s bubble poll | packet card + `Wave7PacketDetail`, 20 s bubble poll | REST parity polling |
| F-RO-03 | Tic-tac-toe games (seats/turn/win-line/join) | TicTacToeCard, 1.5 s poll only while active+visible | game card + sheet, same gate | REST parity polling |
| F-RO-04 | Tournaments (standings/join/finish) | TournamentCard (creator/admin finish) | TournamentCard + detail | REST |
| F-RO-05 | Kanban (3 columns, move/add/assignee/delete rules) | KanbanSheet, native move controls, 1.5 s poll while open, background-paused | sheet + cards, same cadence | REST parity polling |
| F-RO-06 | Whiteboard (normalized canvas, batch strokes, undo/clear) | Compose Canvas sheet, ≤40 strokes/call, undo own-latest, clear two-tap, 900 ms since-delta | SwiftUI Canvas sheet, same contract | REST parity polling |
| F-RO-07 | Events (RSVP going/maybe/no, check-in window) | EventsSheet, check-in inside window, +15 XP copy | EventsSheet | REST |
| F-RO-08 | Reminders (relative parse, local notify, due-loop) | `parseRelativeReminder` port, WorkManager one-shot + 30 s due-loop, POST_NOTIFICATIONS gate, `firedAt` PATCH | `PulseReminderNotifications` UNUserNotificationCenter + 30 s due-loop | REST |
| F-RO-09 | Message → rich-object actions ("Add to board", "Remind me", attach palette rows: Whiteboard/Red packet/Events/Game/Tournament/Kanban, groups-only gating + toasts) | ChatRoom long-press + attach palette + header leaderboard entry | ChatRoom long-press + attach palette | in-app |
| F-RO-10 | XP / leaderboard display (server-authoritative) | LeaderboardSheet (room) / global | LeaderboardSheet | REST |
| F-HB-01 | Wallet hero (coins/gems/streak/checkedInToday) + ledger | live Hub rewrite (static stub deleted) | live HubView rewrite (wallet tiles deleted) | REST |
| F-HB-02 | Check-in (+streak copy; 409 body includes wallet → honest toast) | ✓ | ✓ | REST |
| F-HB-03 | Transfer (@handle, amount validation) | ✓ | ✓ | REST |
| F-HB-04 | Swap (rates pc/gem 100/80 + stats, min/multiple 100) | ✓ | ✓ | REST |
| F-HB-05 | Tasks (personal kanban CRUD) | ✓ | ✓ | REST |
| F-HB-06 | Market (list + buy `{ok,wallet}`, honest 402 on INSUFFICIENT) | ✓ | ✓ | REST |
| F-HB-07 | Logs (stream, ≤80, live append while open) | ✓ | ✓ | REST |
| F-HB-08 | Apps catalog — 100 apps bundled offline (NO catalog GET on server) | `assets/hub_catalog.json` generated from `src/lib/hub-catalog.ts` via `tools/gen-hub-catalog.mjs` | `Resources/hub_catalog.json` same source | bundled asset |
| F-HB-09 | Install / uninstall + My apps (fan-out per-app GET — replicated, no batch endpoint invented) | fan-out | fan-out | REST |
| F-HB-10 | Communities (auto-provisions `#NNN · name` real group, founder admin → opens room) | ✓ | ✓ | REST |

**Zero scope expansion honored:** no game abandonment, no tournament auto-end, no batch-installs endpoint, no whiteboard socket channel — all per binding spec §2.

## 2. Android changes (files)

Data/protocol (commit `217b9df`):
- `apps/android/protocol/src/main/kotlin/app/pulse/protocol/Wave7Dtos.kt` — 40+ tolerant DTOs (red packets, whiteboard, kanban, events, reminders, games, tournaments, leaderboard, hub wallet/swap/transfer/tasks/market/logs/apps/community)
- `apps/android/protocol/src/main/kotlin/app/pulse/protocol/Wave7Logic.kt` — `parseRelativeReminder` 1:1 web port (2 porting bugs found+fixed during tests), board win-scan mirror, checkin window/reward rules, payload decoders
- `apps/android/data/src/main/kotlin/app/pulse/data/remote/PulseApi.kt` — **+42 endpoints** (redpacket create/detail/grab; whiteboard GET/POST/undo/DELETE w/ `requesterId` contract; kanban board/create/PATCH/DELETE; events CRUD+rsvp+checkin; reminders GET/POST/PATCH/DELETE; games create/list/detail/move/join; tournaments create/list/detail/finish/join; leaderboard; hub wallet/checkin/transfer/swap/tasks/market/logs/apps install/community)
- `apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt` — +37 repo members incl. `cachedWallet/cachedHubTasks/cachedReminders` read-through
- `apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt` — Room **v8→v9**: `wave7_cache(key,json,updatedAt)` + `messages.payloadJson` column; non-destructive `MIGRATION_8_9`
- `apps/android/data/src/main/kotlin/app/pulse/data/di/DataModule.kt` — migration wired
- `apps/android/data/schemas/.../9.json` — exported schema
- `apps/android/domain/...` — Models + PulseRepository interface
- `apps/android/app/src/main/assets/hub_catalog.json` + `tools/gen-hub-catalog.mjs` — 100 apps / 10 categories / taglines generated from the web single source
- `apps/android/app/src/main/java/app/pulse/android/notify/ReminderNotifier.kt` — channel + POST_NOTIFICATIONS gate + WorkManager one-shot

Room surfaces (commit `5f1c58d`): `feature-chat/.../Wave7Cards.kt`, `feature-chat/.../Wave7Sheets.kt` (752 lines), `ChatRoomScreen.kt` + `ChatRoomViewModel.kt` + `MessageSheets.kt` wiring, `MainActivity.kt`

Hub rewrite (commit `e55f517`): `feature-hub/.../HubScreen.kt` (+939/−124 — static "next wave" stub fully replaced), `feature-hub/build.gradle.kts` (+`:data` dep)

CI fix (commit `b62f89a`): `data/src/androidTest/.../RoomMigrationTest.kt` — appended `MIGRATION_8_9` to all six builder chains (compiled schema is v9; chains stopped at 7_8 → "A migration from 3 to 9 was required but not found" on emulator) **+ new instrumented test** `migration8To9AddsWave7CacheAndRoundTrips` (wave7_cache DAO round-trip incl. upsert-replace/key-isolation/delete + `messages.payloadJson` raw UPDATE/SELECT round-trip)

## 3. iOS changes (files) (commit `8aa69a0` + CI rounds `b786a77`→`9d9d194`)

- `Pulse/Domain/Models/WireWave7Dtos.swift` — 42 Codable+Sendable tolerant DTOs
- `Pulse/Core/Support/PulseWave7Logic.swift` — relative-reminder / board / window parity
- `Pulse/Core/Networking/PulseAPIClient.swift` — **+45 endpoints**
- `Pulse/Core/Storage/PulseStore.swift` — GRDB **v7**: `wave7Cache` + message `payloadJson`
- `Pulse/Core/Support/PulseReminderNotifications.swift` — UNUserNotificationCenter auth + calendar triggers + 30 s due-loop
- `Pulse/Features/Chat/Wave7Surfaces.swift`, `Wave7PacketDetail.swift`, `ChatRoomView.swift` — room cards + 9 sheets (kanban 1.5 s / whiteboard 900 ms since / game 1.5 s while active / redpacket 20 s)
- `Pulse/Features/Hub/HubView.swift` — live rewrite: wallet/checkin/transfer/swap/tasks/market/logs/100-app catalog matrix/communities/My apps
- `Pulse/RootView.swift`, `Pulse/Core/Support/PulseSession.swift`, `Pulse/Domain/Models/WireDtos.swift`, `Pulse/Resources/hub_catalog.json`, `project.yml`
- Tests: `PulseTests/Wave7WireTests.swift` (17), `PulseTests/Wave7LogicTests.swift` (15)

## 4. Backend / API / relay changes

- **Zero new backend routes. Zero new socket events.** All non-poll surfaces use web parity polling (spec rule: keep same transport). Polls were already wired on both platforms.
- **One real backend defect fixed** (commit `dda750c`, found by the wave E2E): `src/app/api/hub/market/[id]/buy/route.ts` — INSUFFICIENT funds was swallowed by a catch fall-through returning `200 {ok:true, wallet:{}}`; now honest `402` with the verbatim error. Native surfaces already render server error strings verbatim.
- Relay (`mini-services/pulse-socket`): unchanged.

## 5. Persistence / offline

- Android: Room v9 `wave7_cache` (read-through, stale-mark offline; network success overwrites, network failure serves stale) + `messages.payloadJson` for offline-surviving rich-object carriers. Non-destructive migration, emulator-tested (see §6).
- iOS: GRDB v7 `wave7Cache` (same shape/semantics) + message `payloadJson`.
- Catalog is a bundled asset — 100-app matrix browsable fully offline on both platforms.
- Grab/transfer/buy/swap/checkin remain network-required with honest failure (no fake offline success).
- Reminders: local schedule fires offline (WorkManager / UNUserNotificationCenter); marking `firedAt` requires network (documented behavior).

## 6. Tests — every count and result

Android JVM (local run, XML-aggregated, `BUILD SUCCESSFUL in 1m 37s`, dev server stopped for RAM per house rule):
- `:protocol:test` **103 tests, 0 failures** (incl. 19 new Wave7LogicTest)
- `:data:testDebugUnitTest` **50 tests, 0 failures** (incl. 21 new Wave7ApiTest)
- `:domain:test` **45 tests, 0 failures**
- `:feature-voice:testDebugUnitTest` **65 tests, 0 failures**
- `:feature-chat/:feature-calls/:feature-settings/:feature-hub:testDebugUnitTest` — NO-SOURCE (compile-only; compiles via CI build job)
- **Total: 263 JVM tests, 0 failures** (40 new Wave 7)

Android instrumented (CI emulator, `connectedDebugAndroidTest`):
- Before fix: **5 failures** — every `RoomMigrationTest` case ("A migration from 3 to 9 was required but not found"; root cause: Wave 7 added `MIGRATION_8_9` to production but not to the test builder chains)
- After fix (`b62f89a`): **all green**, incl. the new `migration8To9AddsWave7CacheAndRoundTrips` (6 tests ran across the two shards in CI log; 0 failures)

iOS XCTest: **245 total, 0 failures** across 20 test files (32 new Wave 7: Wire 17 + Logic 15) — CI build-test + xcarchive green.

Live runtime E2E (`apps/qa/wave7-runtime-e2e.js`, real backend `localhost:3000`, first run at `dda750c`, **re-run fresh at end of wave**):
- **93 PASS / 0 FAIL** (both runs). Family breakdown: hub 25, redpacket 12, events 10, tournaments 9, kanban 9, games 9, whiteboard 8, reminders 6, leaderboard 3, fixture 2. Covers happy paths + failure paths (400 verbatim strings, 402 INSUFFICIENT, 409 checkin/idempotency, ownership rules, turn gates).

## 7. CI result (all workflows)

| Workflow | Commit / ref | Result |
|----------|--------------|--------|
| Pulse Android CI (main: JVM tests + assembleRelease + emulator instrumented) | `b62f89a` | **SUCCESS** |
| Pulse iOS CI (main: build-test + xcarchive) | `9d9d194` | **SUCCESS** (r15; rounds r1–r15 fixed Wave 7 iOS compile/test issues: public Wire types, optional-decode widening, UIKit color inits, ViewBuilder type-check splits, wallet/ledger optional-ledger shape, fixture delimiting) |
| Pulse Android CI (tag `v0.9.0-native`: + release publish) | tag run 2026-09-15 | **SUCCESS** |
| Pulse iOS CI (tag `v0.9.0-native`) | tag run 2026-09-15 | **SUCCESS** |
| Pulse Web CI | `dda750c` FAILURE — **stale, root-caused, fix landed**: 9 lint errors, all `@typescript-eslint/no-require-imports` in standalone node scripts (`apps/qa/wave*-*.js`, `apps/android/.../pulse-relay/server.js`, `apps/ios/PulseTests/Fixtures/server.js`) — non-web code caught by repo-root eslint scope because the commit touched `src/**` (the first src-touch since the scope drifted). Fix: `325e6e4` added `apps/qa/**`, `apps/android/**`, `apps/ios/**` to eslint `ignores`. Web CI could not re-run on the fix (path filters don't match config-only commits). **At HEAD: `bun run lint` exit 0 with the same config (locally verified).** Web CI green pending the next `src/**` push — honest label: locally-verified clean, CI-level green not claimable this wave. |

## 8. Release / version / tag / artifact bytes

- Tag **`v0.9.0-native`** → tag CI published the GitHub Release, `target: main`.
- versionCode **19**, versionName **0.9.0-native** (Android `app/build.gradle.kts` defaults; iOS `CFBundleShortVersionString 0.9.0-native` in `project.yml`).
- Asset **Pulse-v0.9.0-native.apk**: 35,648,706 bytes; GitHub-reported digest `sha256:36f560ed6b3619da1b4894bfba0458436306276004ba3cefbbad5675edbe4035`.
- **Byte verification:** asset re-downloaded from the release URL → local `sha256sum` `36f560ed…4035` == GitHub digest ✓; size match ✓.
- Structural spot-check of the downloaded APK: 177 entries, `classes.dex` present, **`assets/hub_catalog.json` present** (Wave 7 marker).
- aapt2 badging NOT re-run this wave — the sandbox Android SDK was reset mid-session (documented; badging evidence for versionCode comes from the build-script source + CI build from that exact commit).
- CDN manifest `download/update-manifest.json` pinned: versionCode 19 / 0.9.0-native / tag-asset URL / sha256 `36f560ed…4035`; mirror `download/Pulse.apk` binary swapped to the verified release bytes (re-hashed after copy: identical digest).
- `gateway`/`socket` manifest fields remain `""` (public-bridge pin is still pending from the earlier operator request — unchanged by this wave).

## 9. Hardware-unverified (stays open, four-tier honest)

All sandbox-verifiable behavior above is CODE/CI + RELAY verified. The following require physical devices and are **NOT TESTED — HARDWARE REQUIRED** (no physical Android/iPhone in sandbox; Wave 3-HW and Wave 5-HW gates remain OPEN and untouched):
- Real notification delivery: Android FGS/POST_NOTIFICATIONS runtime prompt on device, WorkManager fire at `remindAt` under Doze; iOS UNUserNotificationCenter permission dialog + banner + foreground presentation.
- Stylus/pencil input on the whiteboard canvas (touch verified only via code paths).
- Haptics on grab/check-in/move actions.
- Release-APK on-device smoke (install/badging on real device).

## 10. Remaining limitations (honest)

- Polling cadences are web-parity (1.5 s / 900 ms / 20 s / 30 s) — same battery profile as web; no socket push invented for these (per spec no-scope-expansion).
- iOS has no `UIBackgroundModes audio` (Wave 5 documented ACTUAL limitation — unchanged).
- No game abandonment / no tournament auto-end server-side (web truth; natives must not invent them — enforced).
- Web CI has no green run at HEAD yet (see §7) — locally clean; next src-touch run will confirm.
- Android emulator badging not re-verified locally (SDK reset mid-session) — version evidence is script-source + CI build chain.

## 11. Continuation point (STOP here per directive)

Wave 7 (Collaboration & Hub) is implementation-complete with the gates above. Master-spec families remaining: **Wave 8 = executed-spec "Wave 7" family** — resolve exact family/feature IDs from `docs/NATIVE-PARITY-MASTER-SPECIFICATION.md` at next-wave start (do NOT guess numbering; repeat the numbering-resolution step used this wave). Open operator items (not wave-gated): public bridge gateway/socket manifest pin (user phone reachability), Wave 3-HW + Wave 5-HW physical runs. Final full-app audit: NOT started (per directive).
