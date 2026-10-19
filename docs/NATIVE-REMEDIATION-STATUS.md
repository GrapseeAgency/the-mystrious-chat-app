# Native remediation ledger

Companions: [audit](NATIVE-BEHAVIOR-AUDIT-2026-10-19.md) · [acceptance matrix](NATIVE-BEHAVIOR-REGRESSION-MATRIX.md)

Delivery: [draft PR #1](https://github.com/GrapseeAgency/the-mystrious-chat-app/pull/1), branch `arena/394d20ac-the-mystrious-chat-app` → `main`.

**Not release-ready. This is a partial implementation, not closure of all 20 findings.** The original audit describes its pinned baseline and remains unchanged. “Implemented, verification pending” below is not a passing acceptance test. In particular, a compiled application does not prove authorization, delivery semantics, or device behavior.

## Finding-by-finding status

| Finding | Status | Implemented / remaining |
|---|---|---|
| N01 — Authentication | **Partial; P0 open** | Rejected client credentials no longer silently drop the authorization header. Android stops transport/work instead of automatically reclaiming a name; unreadable encrypted credentials block hydration and preserve recovery evidence. Server missing-token access, name-only issuance/recovery, and actor binding remain unchanged. Requires a server/client credential migration, not another client-only check. |
| N02 — Account isolation | **Partial; P0 open** | iOS uses account+gateway-hashed SQLite namespaces and does not import legacy unowned data. Android queue selection verifies ownership before limiting the batch. Follow-up implements complete per-account/gateway repository instances, Room and DataStore namespaces, pinned transports and rebinding observations; see [account isolation](NATIVE-ACCOUNT-ISOLATION.md). Device/late-task coverage and legacy recovery remain open. |
| N03 — Session ownership | **Partial** | Android repository stop cancels owned work and disconnects/rejoins on identity changes. iOS startup uses generation checks, cancels startup/reminders, stops its outbox, and leaves call/voice modes. Detached view/network tasks still need a full account-context audit; these edits do not guarantee that every stale callback is isolated. |
| N04 — App lock | **Implemented, verification pending** | Cold-launch gates wait for persisted policy, Android re-arms on background, iOS has Face ID usage text. Physical biometric, rapid lifecycle and notification-interaction cases remain unverified. |
| N05 — Android cold worker | **Partial** | Worker loads persisted endpoint/viewer/token, checks identity, preserves retry, uses unique KEEP and cancels on logout/rejection. Cross-account atomicity and process-recreation behavior remain unverified. |
| N06 — iOS background outbox | **Implemented, verification pending** | Declares background fetch, can bootstrap a cold account store/API, rechecks identity, honors cancellation and reuses an active engine. Actual OS scheduling and expiration behavior need device evidence. |
| N07 — Send idempotency | **Implemented core; validation/deployment pending** | Durable server operation receipts, stable native retry IDs, exact-ID reconciliation and transactional local acknowledgements are implemented; six SQLite-backed server tests pass locally. Main text composers persist before network I/O. Deploy the additive server schema first; ancillary media/process-death and paired-device cases remain open. See [delivery contract](NATIVE-DELIVERY-CONTRACT.md). |
| N08 — Reconnect convergence | **Open** | Durable change cursors, backlog pagination, edits/deletions/tombstones and membership convergence still need protocol and client work. |
| N09 — Queue durability/payload | **Partial** | Removed silent oldest-entry trimming, retained transient/auth/throttle failures, preserved additional Android retry metadata, and restricted Android sends to known owners. Follow-up makes acknowledgement transactional, reports enqueue failure instead of claiming success, and preserves Swift quote/topic/privacy envelopes. Permanent-rejection recovery, ancillary paths and full fault-injection coverage remain open. |
| N10 — Voice notes | **Partial** | Android finalizes/releases recorder before reading audio; cleans up initialization failure; permits retry/discard of a finalized failed send while the room remains open. Detached upload ownership, navigation/background behavior and physical playback still need work/tests. |
| N11 — Notification Reply | **Implemented, verification pending** | Explicit reply PendingIntent is mutable on API 31+; regression test added. Real system RemoteInput, lock and cold-process cases still need instrumentation. |
| N12 — Read/attention | **Partial** | iOS requires ready+foreground+active room+unlocked. Android read collection requires RESUMED, unlocked and visible room, excluding listed root/room overlays; active-room attention is suppressed. Policy marks the visible conversation read, not individual viewport bubbles. All overlay/call occlusion paths, multi-device badge convergence and lifecycle races remain unverified. |
| N13 — iOS mirror | **Implemented, verification pending** | Incomplete optional mirror removed from exposed root/settings routing; implementation files retained but persisted flag no longer selects it. Release UI assertion remains to run. |
| N14 — Draft preservation | **Partial** | Android saves blank drafts immediately. Nonblank debounce/navigation loss, safe route restoration and account-scoped drafts remain open. |
| N15 — Scroll policy | **Partial** | Tail-follow helper and five regression cases added; preserved selected tab. Actual prepend/arrival/jump geometry and process-restored room routing remain unverified. |
| N16 — Lifecycle polling | **Partial** | Lifecycle-aware Android state/polling, cancellation propagation in API calls; iOS foreground and stale-reminder guards. Remaining detached jobs/pollers and instrumentation counters are open. |
| N17 — Signing boundary | **Open; external recovery required** | Exposed tracked signing material has not been silently replaced. Establish distributed certificate/upgrade requirements, recover or rotate through the authorized distribution process, move secrets to protected CI, and clean exposed history. Deleting a file alone does not revoke a key. Do not distribute these CI artifacts as trusted production updates. |
| N18 — Bootstrap ordering | **Partial** | iOS sequences endpoint→API→account store→features, with generation checks and explicit storage-error Retry. Android gates UI on endpoint/token/vault hydration with failure Retry; repository token load precedes socket/refresh. Gateway migration, credential-to-origin binding and race/fault-injection tests remain open. |
| N20 — Validation coverage | **Partial** | CI expands native JVM suites and manual/package triggers; audit workflow no longer publishes releases, APK identity check fails correctly; ownership, token, outbox, lock/namespace, scroll and reply tests added. Exact-candidate checks plus the full device matrix remain required. |
| N19 — Call API compatibility | **Implemented, verification pending** | Uses ServiceCompat foreground-service APIs. Lowest-supported/28 and modern call permission/media cases remain unverified; existing CI emulator matrix is 30/34 only. |

## Evidence observed so far

First implementation commit: `ef124ae09e644eb2ea2ecdd7b22925f87bc3560e`.

- [Android run 39208454956](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/39208454956): expanded JVM test step **passed**; release assembly and emulator jobs were still running at this checkpoint.
- [iOS run 39208454787](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/39208454787): simulator build and unit-test steps **passed**, unsigned device archive job **passed**; launch-smoke step was still running at this checkpoint.
- Follow-up startup/read/recorder changes accompanying this ledger are **not covered by those earlier checks**. Their new PR checks must be examined separately.
- Local `git diff --check` passed. No local Android/Xcode toolchain or physical-device execution is claimed. Generated build products remain CI artifacts, not repository additions.
- T01–T21 remain **NOT RUN as complete acceptance scenarios**. Unit-test subsets are not substitutes for the fault-injection/device specifications.

## Merge/release gates

1. Finish server credential issuance/recovery and authenticated actor binding, with an explicit migration for existing identities. Never claim ownership from a display name.
2. Finish Android account+gateway storage isolation and stale-task fencing, preserving existing pending data without importing unknown-owner rows.
3. Implement end-to-end operation idempotency and complete reconnect reconciliation; test lost acknowledgments and backlog mutations.
4. Finish remaining partial findings above and run the acceptance matrix on the exact candidate.
5. Resolve signing-key exposure using the authorized production distribution/recovery process; do not invent a new signing identity and break upgrades.

Keep the PR draft. Do not merge merely because compile/unit checks turn green.

## Continuing implementation

Commit `92e09c8` adds server/native operation identity, six passing local SQLite contract tests, atomic native acknowledgements, and pre-network text persistence. Its web lint and iOS unsigned archive passed; remaining native jobs were still running at this checkpoint. The first server-contract CI run failed (logs were inaccessible from this environment); the workflow now pins Ubuntu 24.04 and exposes synthetic-test diagnostics through the Checks API for investigation. No failure is counted as a pass.

The accompanying Android namespace changes and Swift enqueue error handling require their own new-candidate CI run. Neither device isolation nor all audit scenarios are certified here.
