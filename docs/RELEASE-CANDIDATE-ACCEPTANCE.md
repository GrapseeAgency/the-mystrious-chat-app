# Release Candidate Acceptance Matrix

Section 16 item 8 (VERIFY — full exact-candidate acceptance). Status per
critical scenario, with the evidence that stands behind it. Verdicts:
**CI-PROVEN** (a test/workflow executes it on every push), **DEVICE GATE**
(requires real hardware / OS scheduling / push infrastructure), or
**EXTERNAL** (out-of-band for this repository).

## Identity, session, storage

| Scenario | Verdict | Evidence |
| --- | --- | --- |
| Create identity → server issues token (raw once, sha256 stored) | CI-PROVEN | `section16-credential-adoption.test.mjs`; users route + login route |
| Web adopts the credential (creation/reclaim/rehydrate; reuse-or-mint; sign-out drops it) | CI-PROVEN | same suite + `apiJson` attach test |
| Reclaim rotation keeps older devices signed in (grace list) | CI-PROVEN | same suite (first device survives reclaim) |
| Keystore/Keychain typed reads (absent vs unreadable, fail-closed) | CI-PROVEN | `KeystoreSessionTokenCipherTest` (5), `PulseKeychainTests`, `KeychainRecoveryTests` |
| Room migrations v3→v10 / GRDB v1→v16 historical upgrades | CI-PROVEN | `RoomMigrationTest` (17), `PulseStoreUpgradeAcceptanceTests` |
| Corrupt DB refusal with byte retention / honest recreate | CI-PROVEN | `PulseStoreMigrationTests`, `LegacyDataRecoveryDeviceTest` |
| Legacy unowned data: membership-proofed adopt/refuse+retain | CI-PROVEN | `LegacyDataRecoveryDeviceTest` (5), `PulseLegacyDataRecoveryTests` |
| Production schema rehearsal against a restored backup | EXTERNAL | `docs/ROLLOUT-COORDINATION.md` — not provable from this repo |

## Messaging, feeds, receipts

| Scenario | Verdict | Evidence |
| --- | --- | --- |
| Send → edit → delete with tombstone visible to the other device | CI-PROVEN | `section15-paired-acceptance.test.mjs` |
| Seen: read-state receipt v3, sender receives the notice | CI-PROVEN | same suite |
| Manual unread honest round trip (intent-guarded) | CI-PROVEN | same suite + `settings-write-order.test.mjs` |
| Offline catch-up: queued deliveries seen in order | CI-PROVEN | same suite (paired catch-up) |
| Lost-ack replay: same clientId → same message, no duplicate (200+replayed) | CI-PROVEN | same suite; native journals: `OutboxRetryJournalTest`, `PulseOutboxTests`, `ComposerJournalTests` |
| Feed wire shape through the REAL serializer (dates, authors, reactions, quotes, polls, redaction) | CI-PROVEN | `section16-real-serializer.test.mjs`; read-history chain |
| Endpoint authority: 401 headerless / 403 foreign / 200 owner per family | CI-PROVEN | `section16-endpoint-authorization.test.mjs` (message/file/discovery/legacy/open classes) |
| Durable settings/profile receipts (probe/settle, LWW ledger, provenance) | CI-PROVEN | `section13-settings.test.mjs`, `Section13SettingsDurabilityTests` |

## Realtime, calls, background work

| Scenario | Verdict | Evidence |
| --- | --- | --- |
| Socket round trip vs the real node relay (reconnect, join re-emit) | CI-PROVEN | `SocketRoundTripTest` (Android + iOS), relay fixture |
| CallKit/Telecom answer/decline/end state machine | CI-PROVEN (logic) | `CallStateMachineTest` (both platforms), `CallLogMapperTests` |
| CallKit coordinator / Telecom connection adapters end-to-end | DEVICE GATE | providers self-declare device-gated (`PulseCallKitProvider.swift:36-38`, `CallAudioManager` header) |
| Audio routes: earpiece/speaker decision table, focus-loss policy | CI-PROVEN (decision) | `CallAudioRoutesTest` (JVM decision table) |
| Audio routes on real BT/wired/interruption hardware | DEVICE GATE | no Bluetooth/SCO code exists to test; real routes need devices |
| Android cold workers: bootstrap, process recreation, cancellation, fault-injected execution | CI-PROVEN | `OutboxColdFlushTest` (R66-era fault injection) |
| iOS BGAppRefresh: schedule-first, expiration-cancel, suspended never succeeds | CI-PROVEN (contract) | `BackgroundOutboxCoordinatorTests`; real OS scheduling = DEVICE GATE |
| Actual locked/background APNs/FCM delivery | DEVICE GATE | payload shape CI-proven (`push-delivery.test.mjs` 30 tests); delivery needs real push infra |

## UI acceptance

| Scenario | Verdict | Evidence |
| --- | --- | --- |
| Back-stack/process-death restoration from IDs (no stale models) | CI-PROVEN | `RoomNavRestorationTest`, `RoomPathSnapshotTests` (R66) |
| System back dismisses sheets (not the underlying screen) — race fixed | CI-PROVEN | `MirrorSheetInteractionTest` (bounded waits; the two CI strikes root-caused) |
| Scroll geometry: prepend anchor id+offset, away-tail no-follow, exact jump, saver restore | CI-PROVEN | `RoomScrollGeometryTest` (Android), ThreadView kernel tests; iOS river anchored-scroll (R67) |
| Icon-only actionable controls labeled (TalkBack/VoiceOver) | CI-PROVEN | `MirrorSurfaceAccessibilityTest` label+click-action assertions; labeled call sites audit |
| Full TalkBack/VoiceOver reading-order pass on real devices | DEVICE GATE | labels + semantics are in code; reading order needs real screen readers |
| RTL + double font-scale render and dismissal contract | CI-PROVEN (emulator) | `MirrorSurfaceAccessibilityTest` |
| Large text beyond 2x, display scaling extremes, small displays, rotation | DEVICE GATE | emulator covers the composition contract; extremes need devices |
| Reduced motion: gated springs (sheet, tray, dock, call press, swipe) + OS probe | CI-PROVEN | `PulseMotionGateTests` (prefs gate), sheet snap gating, the audit table of gated sites |
| Autumn styling parity across repaired surfaces | CI-PROVEN (theme tokens) | `PulseUiThemeTests` (five themes); visual parity on device = DEVICE GATE |
| Resource budgets: poller/timer/socket lifecycle cancellation | CI-PROVEN | `PreferenceObservationTest`, `ResumedRoomReconciliationTest`, `BackgroundQueueFlushTest`, `RoomRequestCancellationTest`; battery/memory measurement = DEVICE GATE |
| Android API floor (28) acceptance | CI-PROVEN | emulator matrix leg `[28, 30, 34]` (R67) |
| Paired two-device full user flows on physical hardware | DEVICE GATE | server contracts CI-proven (paired suite); real-device UX passes pending |

## Explicit sign-off state

Successful builds are NOT this sign-off. With the matrix above, everything
executable from this repository IS executed and green in CI; the remaining
open gates are exactly the DEVICE GATE and EXTERNAL rows, which require
physical hardware, real push infrastructure, and a production rehearsal
that this repository's CI cannot perform. A release candidate is fully
signed off only after those rows carry their own evidence from real runs.
