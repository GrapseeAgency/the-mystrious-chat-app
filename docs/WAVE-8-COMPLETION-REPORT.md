# PULSE — WAVE 8 COMPLETION REPORT — PLATFORM & HARDENING

**Implementation Wave 8 · master-spec §7 "Wave 7" family — Platform & hardening**
Platforms: native Android (Kotlin + Compose) and native iOS (Swift + SwiftUI). Behavioural truth: repo-root web app; wire contracts live-verified during the wave (all E2E evidence below re-ran against the real backend).

**Numbering resolution (standing record):** implementation Wave 7 closed master-spec §7 "Wave 6 — Collaboration & Hub". This wave implements the next spec family verbatim: **spec Wave 7 — Platform & hardening** (session tokens A-1/A-2 · settings completions · accessibility honor-OS pass · performance pass · R8/proguard · push Tier 2 + iOS signing where external deps exist).

---

## 1. Feature IDs — implementation status

| Item (spec source) | Status | Evidence |
|----|--------|----------|
| **Session tokens (§3.11 A-1)** — issue at create/reclaim | **DONE (both platforms + backend)** | `POST /api/users` → 201 `{user, token}`; NEW `POST /api/users/login {name}` → 200 `{user, token}` (rotates; 404 honest); tokens: 32 B hex raw shown once, sha256 stored (`User.sessionTokenHash`) |
| **Session tokens (A-2)** — natives store + send; server verifies | **DONE** | Android: `SessionTokenStore` (Keystore AES-GCM ciphertext in DataStore) + Bearer on every Ktor request + socket `join{userId, token}`; iOS: Keychain (`PulseKeychain.sessionTokenAccount`) + Bearer on the request-building seam + join token; server `src/proxy.ts` optional-verify (headerless = pass for web migration; present-but-invalid = 401) — live E2E cases 1–9 |
| **Relay join gate (A-2)** | **DONE** | pulse-socket verifies presented tokens via `GET /api/internal/verify` (x-pulse-key, 60 s cache, transport-fail-open); invalid → `join:error` + server disconnect; token-less accepted — live E2E cases 17–19 |
| **Rotation handling** | **DONE** | login rotates hash → old token 401 (proxy + relay); natives catch 401/`join:error`, clear the credential, surface re-login |
| **F-SE-01 Account** | **DONE (both)** | profile card + Copy user ID (clipboard + toast) + edit-profile link |
| **F-SE-02 Appearance** | **DONE (both)** | color mode + ambient FX (existing, integrated) + wallpaper picker (5 tokens, shared `PulseWallpaper` single source) |
| **F-SE-03 Chat** | **DONE (both)** | bubbleRadius md/lg/pill + density cozy/compact pickers; Drafts & Outbox manager over real local stores (per-item delete + clear-all, fully offline); consumed by the chat room (bubble corners, list density, wallpaper wash) |
| **F-SE-04 Notifications** | **DONE (both)** | notifPreviews/notifSound/notifVibrate toggles (server blob) + Quiet hours LOCAL (on/start/end HH:mm, verbatim web window math incl. wrap + degenerate); gates Android reminder notifications via versioned channels + iOS in-app alerts/haptics |
| **F-SE-05 Privacy & Security** | **DONE (both)** | lastSeen/readReceipts/typing toggles → server-enforced prefs blob + Blocked accounts link (W6 surface) |
| **F-SE-06 Real-time & Voice** | **DONE (both)** | gateway host + live timed probe (`GET /api/users` honest latency) + socket connected state + honest offline copy |
| **F-SE-07 Accessibility** | **DONE (both)** | reducedMotion toggle (dual-write server blob + local) **+ honor-OS**: Android reads `ANIMATOR_DURATION_SCALE == 0`; iOS `@Environment(\.accessibilityReduceMotion)`; hapticsOn local toggle; TalkBack/VoiceOver labels + headings + 48 dp targets on all new screens |
| **F-SE-08 Data & Storage** | **DONE (both)** | footprint stats (DB file bytes + image cache bytes), clear image cache / drafts / outbox, updates row (Android LiveUpdater card; iOS App Store note per spec) |
| **F-SE-09 About** | **DONE (both)** | version + GitHub link |
| **Performance pass (§3.14)** | **DONE to sandbox-verifiable depth** | Android: R8 shrink (8,802 dex classes from the full set), prefs read-through on Room cache, poll lifecycle unchanged (already visible-gated W0); budgets (cold start ≤2 s etc.) require hardware — **UNVERIFIED on device, honestly** |
| **R8/proguard (§7)** | **DONE (Android)** | `isMinifyEnabled = true` + `isShrinkResources = true` + `app/proguard-rules.pro` conservative posture (ALL app classes kept → reflective-decode risk surface removed; libraries shrink+obfuscate; kotlinx-serialization/socket.io/ktor/enum guards); local `assembleRelease` + **dex-verified** (`WirePulsePrefs`+serializers, `SessionTokenStore`, `io.socket.client.*` present); APK 23.97 MB vs 35.65 MB unminified |
| **Push Tier 2 (A-3)** | **EXTERNAL BLOCKED** | needs FCM/APNs developer accounts — documented, not started (spec: "when external deps exist") |
| **iOS signing/TestFlight (§7)** | **EXTERNAL BLOCKED** | needs Apple Developer Program — unchanged (CI ships unsigned simulator artifact per W0 decision) |

## 2. Android changes

Session/tokens (crew state, verified + integrated): `data/local/SessionTokenStore.kt` + `data/local/SecureSessionStore.kt` (Keystore AES-GCM), `PulseApi.kt` (Bearer attach `authHeader()`, 401 → typed AUTH failure + invalidation hook), `PulseSocketClient.kt` (join token), `OnboardingViewModel/OnboardingScreen` (create → save token; reclaim → `POST /api/users/login`), `SessionViewModel`, `di/DataModule.kt`, `protocol/SocketContracts.kt`, test fakes.
Settings UI (orchestrator, after crew context limit): `feature-settings/SettingsRootScreen.kt` (root grouped section list + 9 sections, web SECTION_MAP labels verbatim), `SettingsViewModel.kt` (prefs optimistic pipeline, drafts/outbox rows, footprint, probe), MainActivity routes (`settings`, `settings/{section}`) + dock dead-toast REPLACED by real navigation.
Consumption: `ui/PulseWallpaper.kt` (shared token→brush source), `ChatRoomScreen.kt` + `ChatRoomViewModel.kt` (bubble corners, density gap, wallpaper wash), `notify/ReminderAlertPolicy.kt` + `ReminderNotifier.kt` (versioned channels per sound/vibrate combo + quiet-hours at show time), `PulseApplication.kt` (prefs mirror collection).
Build: `app/build.gradle.kts` (R8 on), `app/proguard-rules.pro` (new), `gradle/libs.versions.toml` (icons-extended version pin — the artifact mirrors classic Material icon names, not Lucide-style), versionCode 20 / 0.10.0-native.

## 3. iOS changes

Crew (CI-verified): `PulseKeychain.swift` (session token account + rotation drop), `PulseAPIClient.swift` (Bearer on the request-building seam + login/settings/updateSettings endpoints), `PulseSocketClient.swift` (join payload builder + `updateToken` + `joinError` signal), `PulseSession.swift` (token-aware client rebuilds, 401/rotation reaction), `OnboardingView.swift` (create/login flows), `IdentityPickerSheet.swift`, `WireWave8Dtos.swift`, `PulseWave8Logic.swift` (quiet-hours verbatim port), `PulsePrefs.swift` (+server blob fields + local quiet/haptics), `SettingsView.swift` (+721 lines: 9 sections), `DraftsOutboxManagerView.swift` (GRDB rows), `ChatRoomView.swift` (wallpaper washes + bubble/density consumption), `ThreadView.swift`, `RootView.swift`, `PulseStore.swift`, `PulseHaptics.swift` (hapticsOn gate).
CI rounds: r1 hex literals → `Color(hex: String)` convention; r2 pre-login client init, force-unwrap compile-time regex, LinearGradient fill typing; r3/r4 fixture cold-boot join-wait tolerance (10 s → 30 s in the two relay round-trip tests — the first connector eats the fixture boot; warm fixture measured 19.8 s).

## 4. Backend / API / relay changes (commit `5cea564`)

- `prisma/schema.prisma`: `User.sessionTokenHash String?` (pushed, non-destructive).
- `src/lib/session-token.ts` (new): generate (32 B hex) / hash (sha256) / bearer parse.
- `POST /api/users` → response now includes `token` (additive — web unaffected).
- **NEW** `POST /api/users/login` `{name}` → `{user, token}` (rotation; honest 400/404).
- **NEW** `GET /api/internal/verify?userId=&token=` (x-pulse-key) → `{valid}` — relay-facing.
- **NEW** `src/proxy.ts` (Next 16 proxy convention, `/api/:path*`): optional-verify — no header passes (web migration), present-but-invalid → 401 honest copy; `/api/internal/*` excluded.
- `mini-services/pulse-socket/index.ts`: join gate — presented tokens verified (cached 60 s, transport-fail-open), invalid → `join:error` + disconnect; token-less accepted.
- Web behavior change: NONE (headerless requests pass; verified by Web CI + web parity).

## 5. Storage / offline

- Android: Keystore-encrypted token in DataStore (survives process death; self-heals unreadable payloads); prefs blob mirrored into a DataStore-backed store — toggles work offline (optimistic local first, PATCH retries on next change, honest offline hint); drafts/outbox manager is fully offline.
- iOS: Keychain token (survives reinstall via backup semantics documented); same optimistic prefs pipeline via PulsePrefs + PulseStore; DraftsOutboxManagerView over GRDB.
- Quiet hours + hapticsOn are LOCAL-only on both platforms (web parity — never ride the server blob).

## 6. Tests — every count and result

Android JVM (local, XML-aggregated, `BUILD SUCCESSFUL`):
- `:protocol:test` **122 / 0** (19 new Wave8LogicTest — prefs merge/clamp parity, quiet-hours math, preview-text policy)
- `:data:testDebugUnitTest` **69 / 0** (21 new Wave8ApiTest + 5 SessionTokenStoreTest)
- `:domain:test` **45 / 0**, `:feature-voice:testDebugUnitTest` **65 / 0** (fakes extended)
- **Total 301, 0 failures**; `:app:compileDebugKotlin` green; `:app:assembleRelease` (R8) green + dex-verified locally
Android instrumented (CI emulator, tag run): migration suite green (incl. Wave 7's 8→9 case; no Wave 8 schema change — token hash lives on `User` via Prisma, not Room).

iOS XCTest (CI): **245 + 20 new Wave 8 (10 Wire + 10 Logic) = 265 tests, 0 failures** (tag CI run 02:07:50Z SUCCESS).

Live E2E (`apps/qa/wave8-runtime-e2e.js`, real backend + real relay): **19 PASS / 0 FAIL** — token issue/rotation/401 semantics (9 cases), settings blob GET/PATCH/clamp-to-defaults web truth (5), internal verify (3), relay join gate valid/invalid/token-less (3). One expectation was corrected to web truth during authoring (junk prefs values fall back to DEFAULTS, not the previous stored value — that is the route's actual clamp semantics).

## 7. CI results (all workflows)

| Workflow | Commit / ref | Result |
|---|---|---|
| Pulse Web CI | `2fc2803` | **SUCCESS** — the Wave 7 stale-red is now cleared by a real green run at HEAD |
| Pulse Android CI (main: JVM tests + R8 assembleRelease + emulator instrumented) | `6692313` | **SUCCESS** |
| Pulse iOS CI (main: build-test + archive) | `6692313` | **SUCCESS** (rounds r1–r4: hex-literal typing, pre-login client init, regex unwrap, LinearGradient typing, fixture cold-boot join tolerance) |
| Pulse Android CI (tag `v0.10.0-native`) | tag run 02:07:50Z | **SUCCESS** |
| Pulse iOS CI (tag `v0.10.0-native`) | tag run 02:07:50Z | **SUCCESS** |

## 8. Release / version / tag / artifact bytes

- Tag **`v0.10.0-native`**; versionCode **20**, versionName **0.10.0-native** (Android build.gradle defaults; iOS `CFBundleShortVersionString`).
- Tag CI published the GitHub Release; asset **Pulse-v0.10.0-native.apk** 23,973,927 bytes, GitHub digest `sha256:5540c8bb29e0b96093ed79a51ec173d5310c07a744cb99ecff22c59ee080b2ec`.
- **Byte verification:** re-downloaded from the release URL → local sha256 `5540c8bb…b2ec` == GitHub digest ✓ (also == the locally built R8 APK size class: 23,973,920 vs 23,973,927 — CI build, not byte-identical, per the non-reproducibility rule the CDN pins the CI asset).
- CDN `download/update-manifest.json` pinned (versionCode 20 / 0.10.0-native / tag asset / digest); mirror `download/Pulse.apk` swapped to the verified bytes (post-copy hash identical).
- `gateway`/`socket` manifest fields remain `""` — the public-bridge pin is still an open operator item (unchanged by this wave, documented per directive).

## 9. Hardware-unverified (four-tier honesty)

- **Release R8 on-device smoke** — the shipped APK is the first R8-minified release; assemble + dex class-presence verified, runtime behavior on a real device NOT TESTED (no hardware in sandbox). If anything breaks: rollback = flip `isMinifyEnabled` back (one line) + retag.
- **Real notification delivery** (reminder channel sound/vibration variants, quiet-hours suppression at OS level) — HARDWARE REQUIRED.
- **Keystore/Keychain runtime behavior** across backup-restore / device-migration paths — unit + CI covered the logic; device paths UNVERIFIED.
- **Performance budgets** (§3.14: cold start ≤2 s, 60 fps scroll, room open ≤400 ms) — require mid-tier physical devices; code-level items (R8, query hygiene) shipped, numbers UNVERIFIED.
- **Haptics on device** (hapticsOn gate end-to-end).
- **Push Tier 2 / TestFlight** — EXTERNAL BLOCKED (accounts don't exist), not hardware.

## 10. Remaining limitations (honest)

- Token REST enforcement is optional-verify (spec's own migration semantics): headerless calls still pass — web keeps working unauthenticated by design; web token adoption is phase 2 per §3.11.
- Quiet-hours gates in-app alerts + reminder notifications; OS-level notification channels for MESSAGES (not reminders) await push Tier 2.
- iOS continues to ship unsigned CI artifacts (no Apple account).
- The gateway/socket manifest bridge pin for the user's phone remains open (operator-level, unchanged).
- Subagent crews hit the infra context limit mid-implementation — the orchestrator completed and verified the remaining Android UI + all gates directly; every crew-authored line was compiled/tested before shipping (4 Android compile fixes, 4 iOS CI rounds).

## 11. Continuation point (STOP here per directive)

Wave 8 (Platform & hardening) is implementation-complete to sandbox-verifiable depth. **Every master-spec §7 family now has an executed implementation wave** (spec W0→exec W0; W1→W1; W2→W2; W3→W6; W4→W5; W5→W3; W6→W7; W7→W8) — the parity inventory §2 is materially covered on both platforms. Next steps per the standing directives: (a) the **final full-app audit** (deferred until now — explicitly NOT started), and (b) open hardware gates Wave 3-HW / Wave 5-HW (+ this wave's R8-on-device smoke) + the operator bridge-pin item. Do NOT start any new feature family without explicit approval.
