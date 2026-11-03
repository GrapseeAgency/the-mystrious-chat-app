# Consolidated Audit Closure

Section 16 item 10 (OPEN — consolidated audit closure). This file is the
current, single disposition ledger for every feature transition; the
point-in-time reports under `docs/` are historical records, and the
superseded claims found in them are corrected HERE. The source inventory is
not a behavior checklist — behavior evidence lives in the cited suites.

## Superseded findings (explicit corrections)

1. **`docs/WAVE-8-COMPLETION-REPORT.md` (line ~103)** claims "headerless
   calls still pass — web keeps working unauthenticated by design; web token
   adoption is phase 2." **Superseded:** the owner-bound hardening
   (`requireActionOwner`/`requireActionActor` on ~95 routes) made headerless
   calls 401 there, and as of the R67-era credential adoption the web
   supplies the Bearer on shared `apiJson` calls. The proxy's
   headerless-optional window remains ONLY as a legacy-client tolerance, not
   a web design.
2. **`packages/protocol` `SOCKET_EVENTS`** is superseded by the
   `NATIVE-PARITY-MASTER-SPECIFICATION.md` socket-events contract (flagged at
   spec lines 37 and 837).
3. **`NATIVE-COMPOSER-IMPLEMENTATION/VERIFICATION.md`** earlier "all
   eleven closed" claims were rechecked and narrowed to items 6/7/8 by the
   documents themselves; the composer workstream's true closure evidence is
   the composer/outbox suites (both CI workflows gate them: 11 suites on
   iOS, outbox/journal suites on Android).
4. **worklog R47-ship** once carried a stale "push pending credentials"
   note (R47-ship-correction marks it done); R33-b's "channels-only photo
   via route" note was disproved at R36. The worklog R-entries are the
   correction log for earlier rounds by design.

## Deferred (explicit, user-directed)

- **Sign-in and account recovery (Section 16 item 1): DEFERRED by explicit
  instruction. Not implemented, not claimed.**
- **Broader credential migration (Section 16 item 2): DEFERRED.** The
  socket `join{userId, token?}` window (token optional, presented token
  verified against `/api/internal/verify`) and the proxy optional-verify
  remain by design until the optional-token identity work ships. Optional
  tokens are NOT treated as fully authenticated ownership anywhere.

## Corrections the user already granted (no longer counted as defects)

Per the Section 15/16 hand-off: the Android Voice/Stage wiring and iOS
Stage-init repairs, native scheduled-create retry identity, mounted Android
photo-story creation, Swift account/gateway-scoped settings caches, the
mention expiry/search-error fixes, and the Android search-router
List-vs-Result mismatch repair are all DONE and only listed in the receipts
that closed them.

## Per-area current disposition

| Area | Disposition | Current evidence (all green in CI) |
| --- | --- | --- |
| Identity + credentials (native Keystore/Keychain, web adoption, grace list) | CLOSED | `KeystoreSessionTokenCipherTest`, `PulseKeychainTests`, `KeychainRecoveryTests`, `section16-credential-adoption.test.mjs` |
| Endpoint authorization (message/file/discovery/socket-internal/legacy) | CLOSED (full closure deferred with item 2) | `section16-endpoint-authorization.test.mjs`, `requireActionOwner/Actor` call sites, proxy, relay `x-pulse-key` |
| Messaging lifecycle (send/edit/delete/Seen/manual-unread/replay) | CLOSED | `section15-paired-acceptance.test.mjs`, `message-mutation.test.mjs`, read-intent suites |
| Feed serializers (search/mentions/saved real wire shapes) | CLOSED | `section16-real-serializer.test.mjs`, read-history chain |
| Durable receipts (settings/profile/integrations/wallet/settings revisions) | CLOSED | Section 10–13 suites (`Section10WireTests`…`section13-settings.test.mjs`) |
| Background work (Android cold workers, iOS BG coordinator) | CLOSED (OS scheduling = device gate) | `OutboxColdFlushTest`, `BackgroundOutboxCoordinatorTests`, `InfoPlistContractTests` |
| Storage (migrations, corruption, disk-full, enqueue/ack reconciliation) | CLOSED | `PulseStoreUpgradeAcceptanceTests`, `RoomMigrationTest`, `StorageFaultBehaviorTests`, `upload-durability.test.mjs` |
| Legacy unowned data recovery | CLOSED | `LegacyDataRecoveryDeviceTest`, `PulseLegacyDataRecoveryTests` |
| Navigation/back-stack/restoration/interruption returns | CLOSED | `RoomNavRestorationTest`, `RoomPathSnapshotTests`, `ShareInGrantTest`, `NavigationHandoffTests`, `ConversationNavigationTests` |
| Scroll geometry (prepend/away/jump/restore) | CLOSED | `RoomScrollGeometryTest` + river anchored-scroll (R67-era), `RoomScrollPolicyTest`, `ThreadNavigationTests` |
| Sheets/back-gesture race | CLOSED | `MirrorSheetInteractionTest` (root-caused; bounded waits) |
| Accessibility (labels/semantics; reading order = device gate) | CLOSED-CODE | `MirrorSurfaceAccessibilityTest`, labeled sites audit in R67 receipt |
| Reduced motion + styling parity | CLOSED-CODE | `PulseMotionGateTests`, gated-sites audit (sheet/tray/dock/call/swipe), `PulseUiThemeTests` |
| Resource lifecycle (pollers/timers/sockets) | CLOSED | retirement-generation suites (`PreferenceObservationTest`, `RoomRequestCancellationTest`, `ResumedRoomReconciliationTest`, `SocketRoundTripTest`) |
| Push payloads (APNs/FCM wire + policy) | CLOSED (delivery = device gate) | `push-delivery.test.mjs`, `live-push-outbox.test.mjs`, `PushOriginGateTest` |
| Call handling (state machines + audio decisions) | CLOSED-CODE | `CallStateMachineTest` (both), `CallAudioRoutesTest`, `CallSignalingRoundTripTests` |
| Rollout coordination | CLOSED-AS-CONTRACT | `section16-rollout-drift.test.mjs`, `docs/ROLLOUT-COORDINATION.md` |
| Signing exposure | CODE DONE, EXTERNAL BLOCKERS | `docs/SIGNING-AND-ROTATION.md` (env override + rotation procedure; key escrow/Play out-of-band) |
| Production migration proof | EXTERNAL | rehearsal runbook in `docs/ROLLOUT-COORDINATION.md` |
| Sign-in/account recovery | DEFERRED (user-directed) | none, by instruction |

## Reading order for an auditor

1. `worklog.md` receipts R64–R67 for the section-by-section closure chains.
2. `docs/RELEASE-CANDIDATE-ACCEPTANCE.md` for the critical-scenario matrix.
3. This file for dispositions and superseded-claim corrections.
4. The point-in-time WAVE/NATIVE reports only as history, never as status.
