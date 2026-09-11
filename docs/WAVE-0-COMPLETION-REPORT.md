# PULSE — WAVE 0 COMPLETION REPORT

**Wave:** 0 — Native Foundation / Contract Completion
**Status:** ✅ COMPLETE — all 16 mandates evidenced, all quality gates green, release shipped
**Release:** [`v0.2.0-native`](https://github.com/GrapseeAgency/the-mystrious-chat-app/releases/tag/v0.2.0-native) · versionCode 10 · APK sha256 `4dadcd91e1aca6f8db2e7f12a0a906dede9e4756a1bd84ae57615d12db0e7d84`
**Scope discipline:** the 175-feature set was NOT implemented (mandate 16 honored). Wave 0 = transport, persistence, offline core, session, CI only.

---

## 1. Files changed

Full changeset `46e63ee..cdf6696` (Wave 0 on `main`): **67 files, +7,149 / −271**.

### Created (Android, 15)
| File | Role |
|---|---|
| `protocol/src/main/kotlin/.../SocketContracts.kt` *(modified to full registry)* | 22 C→S / 27 S→C / 11 notify whitelist + tolerant payload DTOs |
| `protocol/src/test/.../SocketContractsTest.kt` | 12 payload/registry tests |
| `protocol/src/test/.../SocketRoundTripTest.kt` + `pulse-relay/{server.js,package.json,package-lock.json}` | REAL Socket.IO round-trip vs spawned node relay (JVM) |
| `data/.../local/SecureSessionStore.kt` | Keystore AES-GCM encrypted viewer identity + endpoint overrides |
| `data/.../remote/ManifestEndpoints.kt` | `update-manifest.json` gateway/socket override hook (dormant-safe) |
| `data/schemas/.../4.json` | Room v4 schema export |
| `data/src/androidTest/.../RoomMigrationTest.kt` | REAL v3 DB → MIGRATION_3_4 on emulator, rows survive |
| `domain/.../model/Outbox.kt`, `usecase/FlushOutboxUseCase.kt` (+test) | outbox domain + FIFO ≤50 / stop-at-first-failure engine |
| `app/.../OutboxWorker.kt` | WorkManager expedited flush worker (network constraint) |
| `app/src/androidTest/.../AppLaunchSmokeTest.kt` | emulator launch gate |

### Deleted (Android)
- `feature-stories/` module entirely (stub, unrouted) + settings entry
- `feature-calls/CallsScreen.kt` (stub; Contacts retained)
- `ktor-client-websockets` dead dependency

### Created (iOS, 8)
| File | Role |
|---|---|
| `Pulse/Core/Support/PulseKeychain.swift` | kSecClassGenericPassword session store (AfterFirstUnlockThisDeviceOnly) |
| `Pulse/Core/Support/PulseOutboxEngine.swift` | FIFO ≤50, temp→real swap, 4xx drop / network retry, 60s self-heal |
| `PulseTests/PulseStoreMigrationTests.swift` | GRDB v1→v2 migration + outbox/draft round-trips |
| `PulseTests/PulseOutboxTests.swift` | 6 engine tests (order, cap, swap, failure classes) |
| `PulseTests/PulseKeychainTests.swift` | Keychain round-trip (skip-tolerant in sandbox) |
| `PulseTests/SocketRoundTripTests.swift` + `Fixtures/{server.js,package.json}` | REAL socket round-trip in the simulator vs runner-side relay |

### Modified (47 total) — key ones
- **Android:** `PulseSocketClient.kt` (27 S→C handlers, reconnect re-join, 800ms→5s backoff), `PulseDatabase.kt` (v4 + MIGRATION_3_4), `PulseRepositoryImpl.kt` (outbox triggers + flush), `PulseEndpoints.kt` (override), `MainActivity/SessionViewModel/OnboardingViewModel/PulseApplication` (session wiring, manifest precedence), `ChatsViewModel/ChatRoomScreen` (drafts, queued banner, pending indicators), `app/build.gradle.kts` (versionCode 10 / 0.2.0-native), `libs.versions.toml`, `settings.gradle.kts`
- **iOS:** `PulseSocketClient.swift` (full event set, `.reconnect` re-join, verified option names), `PulseSession.swift`, `PulseStore.swift` (v2 migration + read paths), `PulseEndpoints.swift` (three-tier: user field > manifest > offline), `PulseApp/RootView/ChatsView/ChatRoomView/ContactsView/NewChatSheet` (outbox triggers, drafts, offline seed, report wiring, avatar fix), `project.yml` (BGTask id, scheme `PULSE_RELAY_URL`)
- **Contract:** `packages/protocol/src/contracts.ts` (full authoritative registry), `packages/schemas/pulse-protocol-v1.schema.json` → v2
- **CI:** `.github/workflows/android-ci.yml` (emulator gate + tag release + permissions), `.github/workflows/ios-ci.yml` (relay steps, sim resolver, launch smoke, archive)
- **CDN:** `download/Pulse.apk` (v10 bytes), `download/update-manifest.json` (versionCode 10 + gateway/socket keys)

---

## 2. Architecture changes

1. **Single authoritative contract (mandates 1–3):** `packages/protocol/src/contracts.ts` is now the complete typed registry — 22 client→server, 27 server→client, 11 `/notify` whitelist events with typed payloads (incl. the full `call:*` family). Stale per-platform mirrors were removed/replaced; `pulse-protocol-v1.schema.json` bumped to v2 with socket definitions. REST DTOs unchanged.
2. **Realtime power-up (mandates 4–5):** both natives moved from partial/dormant subscriptions to the FULL S→C registry, with join re-emitted on every (re)connect, exponential reconnect (800ms→5s cap), connection-state surface, tolerant decoding, single pump into DB + UI.
3. **Offline-first core (mandates 6–8):** Android Room v4 (`outbox`, `draft` tables, non-destructive `MIGRATION_3_4`) and iOS GRDB v2 (same tables, same semantics) — identical outbox algorithm on both platforms (FIFO ≤50, stop-at-first-failure, optimistic `local_<clientId>` temp rows, temp→real swap, 4xx drop + network retry with attempt bump); triggers: app start w/ pending, socket reconnect, foreground, 20s/60s self-heal timers, WorkManager (Android) + BGAppRefreshTask (iOS); drafts with 600ms debounce save, composer restore, blank-clears.
4. **Secure session (mandate 9):** Android Keystore AES-GCM vault (`SecureSessionStore`) + iOS Keychain (`PulseKeychain`), both persisted across restarts and re-applied cold before network.
5. **Endpoint deployment hook:** `update-manifest.json` may carry non-empty `gateway`/`socket` — consumed by BOTH platforms at launch (user field > manifest > honest offline). Shipped as empty strings today (dormant, schema visible); a future relay deploy is a CDN-only edit.
6. **Native structure (mandates 10–11):** stub screens removed (StoriesScreen module, CallsScreen); navigation is the approved native shells (Material / HIG), not web-replicas.

---

## 3. Contract changes

- `contracts.ts` → 22 CLIENT_EVENTS / 27 SERVER_EVENTS / 11 NOTIFY_EVENTS, typed payloads (message lifecycle, presence, typing, polls, links, translation, voice/stage/space, `call:offer|answer|ice|reject|cancel|hangup`).
- `pulse-protocol-v1.schema.json` → **v2** with socket definitions.
- Platform registries generated FROM the contract (Android `SocketContracts.kt` 12-test parity suite; iOS 27/27 subscription; `WireParityTests` untouched and green).
- No REST DTO changes. No server behavior changes (backend already served this surface).

---

## 4. Android verification (mandate quality gate)

| Gate | Evidence |
|---|---|
| Clean build | `:app:assembleRelease` GREEN on CI runs 34554724673 (main) + 34556442999 (tag) |
| Tests pass | JVM: 26 tests, 0 failures (SocketContractsTest 12, SocketRoundTripTest 5 — REAL node relay incl. transport-drop auto-reconnect re-join, LiveGatewayParityTest 2, FlushOutboxUseCaseTest 5, SendMessageUseCaseTest 2) |
| App launches | `AppLaunchSmokeTest` on API-30 emulator (instrumented job) GREEN |
| Session works | SecureSessionStore (Keystore) round-trips; onboarding→vault write wired; CI+local verification |
| Socket.IO connects | JVM round-trip vs REAL socket.io relay: connect→join→joined ack→presence→typing→/notify |
| Realtime round-trip | message:new envelope delivered + decoded; reconnect via raw-transport drop → auto-reconnect → join re-emit |
| Room persistence | `RoomMigrationTest` on emulator: REAL v3 DB (raw DDL + seed rows) → MIGRATION_3_4 → rows intact + outbox/draft DAO round-trips; schema 4.json committed |
| Offline outbox | FlushOutboxUseCaseTest (order/stop-on-failure/swap) + DAO tests + WorkManager wiring |
| **Installable artifact (13)** | **Forensic gate PASS:** ZIP 164/164 entries CRC-clean; v1 `META-INF/CERT.SF`+`CERT.RSA`; APK Signing Block **v2 (0x7109871a) + v3 (0xf05368c0)**; AXML walk: `app.pulse.chat`, **versionCode 10**, **0.2.0-native**, **minSdk 21** (the historical "problem parsing the package" cause — minSdk 26 — is dead), targetSdk 35; `resources.arsc` STORED + 4-aligned; 14,050,152 bytes; **installed & launched on the CI emulator** (instrumented job) |

---

## 5. iOS verification (mandate quality gate)

| Gate | Evidence |
|---|---|
| Clean build/archive | `xcodebuild` build GREEN + `generic/platform=iOS` **unsigned xcarchive** GREEN (jobs on 34555428959 + tag run 34556442993), structure-verified, zipped artifact |
| Tests pass | **16 tests, 0 failures** (2 keychain skips = sandbox limitation by design): PulseOutboxTests 6, PulseStoreMigrationTests 4 (GRDB v1→v2 PROVEN, reopen idempotent), SocketRoundTripTests 1, WireParityTests 3 |
| App launches | Launch smoke: `simctl install` + `launch` → **`app.pulse.chat: 11581` (PID)** + screenshot artifact (`pulse-launch.png`) |
| Session works | PulseKeychain viewer envelope (XCTSkip-tolerant tests; production path compiled+linked, launch-proven) |
| Socket.IO connects | **REAL round-trip INSIDE the simulator** vs runner-side node relay on 127.0.0.1:3995 (simulator shares host loopback; scheme injects `PULSE_RELAY_URL`): join→joined ack, presence both users, typing relay, POST `/notify` → `message:new` decode parity |
| Realtime round-trip | Same test — notify payload decoded via `PulseSession.decodeMessage` and asserted |
| GRDB persistence | PulseStoreMigrationTests: v1+v2 coexistence, round-trips, reopen survival, UNIQUE dedupe, blank-draft clears; read paths wired into Chats/ChatRoom offline seeds |
| Offline outbox | PulseOutboxTests (FIFO, 50-cap drop-oldest, temp→real, stop-at-first-network-failure, 4xx drop-and-continue) + BGTask registration |

**Engineering note:** the original design (test spawns node via `Process`) cannot compile — the iOS SDK has no `Process`. Redesigned: CI starts the relay as a runner step; the test connects through the loopback. Fixture smoke-tested locally end-to-end before the CI round.

---

## 6. CI verification (mandates 12, 14)

| Platform | Workflow | Result |
|---|---|---|
| Android | `android-ci` on `main` @ `28d74d0` (run 34554724673) | ✅ **success** — JVM tests + signed release APK artifact + emulator instrumented gate |
| Android | `android-ci` on tag `v0.2.0-native` (run 34556442999) | ✅ build+instrumented **success** (publish step 403'd on read-scoped GITHUB_TOKEN → fixed with `permissions: contents: write`; release created via PAT from the tag build bytes) |
| iOS | `ios-ci` on `main` @ `523c896` (run 34555428959) | ✅ **success** — build, 16 tests, launch smoke, unsigned archive |
| iOS | `ios-ci` on tag `v0.2.0-native` (run 34556442993) | ✅ **success** |

**Fix loop honesty (5 rounds, each root-caused from logs):**
1. `4.json` malformed (`orders:{}` → `[]`, `fieldPaths` → `columnNames`) — Room 2.6.1 re-reads the export at compile time; + iOS GRDB publisher `Failure=Error` sink (`.replaceError(with: [])`).
2. `:app` missing `kotlinx-serialization-json` runtime (transitively hidden by `:protocol`'s `implementation` scope); + iOS `Process` unresolved.
3. iOS `Process` = architectural (no Process in iOS SDK) → relay redesign; + JUnit4 `assertNotNull` arg order in migration test.
4. simctl UDID resolver printed the device NAME not the UUID → launch smoke `Invalid device: iPhone` → UUID extraction fixed.
5. Tag release 403 → `permissions: contents: write`.

**Delivery chain:** tag `v0.2.0-native` → tag CI green → Release [386759368](https://github.com/GrapseeAgency/the-mystrious-chat-app/releases/tag/v0.2.0-native) + `Pulse-v0.2.0-native.apk` (asset `uploaded`) → CDN commit `cdf6696` (`download/Pulse.apk` + `update-manifest.json` v10 + dormant `gateway`/`socket` keys).

**Four-source hash rule — ALL EQUAL `4dadcd91e1aca6f8db2e7f12a0a906dede9e4756a1bd84ae57615d12db0e7d84`:**
1. Tag CI artifact (`pulse-release-apk`, run 34556442999) ✅
2. Release asset (`releases/download/v0.2.0-native/Pulse-v0.2.0-native.apk`) ✅
3. Raw CDN mirror (`raw.githubusercontent.com/.../main/download/Pulse.apk`) ✅
4. Manifest `sha256` field (live) ✅

---

## 7. Remaining blockers

| Blocker | Impact | Owner/gate |
|---|---|---|
| **No public relay deployment** | Natives ship offline-first; `gateway`/`socket` manifest keys sit dormant (both clients adopt them the day a host appears — CDN-only change) | Deployment decision (needs a hosted origin) |
| **iOS signed distribution / TestFlight** | Archive is unsigned (valid build artifact); device installs need signing | Apple Developer Program account (external credential) |
| **iOS Keychain tests skip on CI** | 2 skipped (simulator keychain sandbox); production keychain path is compiled, linked, and launch-proven, not unit-proven | Acceptable; revisit with signing entitlements |
| **release publishing used PAT** | Tag-publish automation now has `contents: write` but the shipped release was created via user PAT (GITHUB_TOKEN grant landed one commit after the tag) | Next tag build exercises the automated path |
| **voice/stage/space/call UI** | Typed signals arrive; no native UI (Wave 0 = transport per plan) | Wave 1+ scope |

## 8. Exact next wave recommendation

**Wave 1 — Native Messaging Surface Parity (transport → product).** The foundation is proven end-to-end; the highest-leverage next increment is the message surface the foundation exists for:

1. **Realtime UI on both platforms:** typing indicators, presence dots, read receipts, live conversation list reordering from socket events (transport already pumps everything into signals/store).
2. **Media messages:** photo capture + upload + delivery states on natives (REST contract already served; outbox generalizes to media kinds).
3. **Conversation depth:** replies, reactions, pinning, edit/delete (all 27 events already decoded — only UI + intent wiring missing).
4. **Distribute:** Android v10 → devices (in-app LiveUpdater already offers Release + browser hatch); decide relay hosting to activate the endpoint hook.
5. **Defer:** calls/voice rooms (needs relay + WebRTC decisions), push notifications (needs Firebase/APNs accounts), Stories (removed as stub).

Wave 1 will NOT start until you approve it.
