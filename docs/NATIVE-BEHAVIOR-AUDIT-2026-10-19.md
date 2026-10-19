# Native behavior audit — Android / iOS

**Date:** 2026-10-19 (Asia/Dhaka)

**Audited source:** `9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5`

**Scope:** Kotlin Android app, Swift iOS app, and the server contracts those clients depend on. No web-UI audit or application-code changes.

**Status:** Source-level audit complete; runtime acceptance and device certification **not performed**.

**Companion:** [Regression matrix](NATIVE-BEHAVIOR-REGRESSION-MATRIX.md).

## Executive verdict

**Do not treat the current native apps as behaviorally release-ready.** The inspected paths contain account-isolation, authentication, delivery, lifecycle, and native-interaction defects. These are not solved by restyling the screens or by another successful compilation.

The biggest systemic problems are:

1. **Identity is not a secure boundary.** Name-only login, optional token enforcement, and request-supplied actor IDs undermine account ownership.
2. **Local storage and queued work are not scoped to an account.** Switching identities can show another account's cached data or submit its pending text under the new account.
3. **Session work outlives its owner.** Logout does not consistently stop background work; delayed startup can restore obsolete session state.
4. **Delivery is not a durable, idempotent protocol.** Client IDs stay local, transient failures can delete messages, and background workers depend on foreground initialization.
5. **Multiple UI implementations have diverged.** Android's shipping mirror path bypasses some older feature behavior; iOS's optional mirror has substantially weaker messaging behavior than its default SwiftUI screens.

**20 findings:** 2 P0, 14 P1, 4 P2. Severity is assigned to the audited implementation, not a claim that every defect has been observed on a handset.

- **P0:** account authorization / cross-account confidentiality or attribution; block release.
- **P1:** message loss, broken core functionality, privacy promises, or release integrity; fix before broad distribution.
- **P2:** substantial UX/lifecycle or verification gaps; address before declaring native-quality completion.

### Where to start

Do not start with a wholesale rewrite. First establish session ownership and authentication; then make message persistence/retry safe; then repair the **actual routed UI**, followed by native OS integration and release gates. Preserve useful existing Room/GRDB migrations, protocol models, call engines, and tests while replacing unsafe behavior contracts.

## Method, boundaries, and confidence

The inventory contains **217 Kotlin files / 102,751 lines** and **146 Swift files / 61,366 lines**, including tests. This was a risk-based, traced source audit, **not a line-by-line review of all 164,117 lines**.

Inspected paths include entry points and shell selection, session creation/teardown, REST/socket authentication, local schemas and outbox drains, message fetch/send/read, drafts and scrolling, Android recording/direct reply, app locks, foreground services, iOS background-task registration, call/push integration seams, and native CI/release definitions. Server inspection was restricted to contracts explaining native behavior: login, bearer verification, message writes, and message synchronization.

For each finding below:

- **Evidence** describes code present at the audited commit. Source links are pinned to that commit.
- **Consequence** is the behavior implied by that code; timing-, OS-, and deployment-sensitive outcomes still need reproduction.
- **Acceptance** defines how a future fix must be proven. These tests have **not** been marked passed here.

No production requests, unauthorized-account probes, production data changes, or release uploads were made. The credential pasted in chat was not used or stored; revoke it separately. Existing sandbox GitHub authentication was used.

### Verification actually available

- The checkout and active routing were inspected locally; no application code was modified.
- Native build tools (`java`, `swift`, `xcodebuild`, `adb`) were not available on PATH. No Gradle/Xcode build, native unit suite, emulator run, or real-device run is claimed.
- GitHub Actions queried for the **exact audited SHA** returned **zero runs** at audit time. Successful runs for other commits are not evidence for this snapshot.
- The existing manually dispatched Android “native audit” workflow builds and uploads to an existing release with `--clobber`; it is not a read-only audit. It was deliberately **not dispatched**.
- The documents' source targets/line ranges and finding-to-test references were checked before commit. That verifies report integrity, **not app correctness**.

## Expected native behavior: the baseline

Platform guidance is a baseline, not a requirement to copy another app's appearance:

| Area | Expected behavior for this app | Basis |
| --- | --- | --- |
| State and lifecycle | One authoritative data owner; UI observes it while relevant; ordinary screen work stops or suspends with lifecycle. | Android architecture/lifecycle guidance, S1–S2 |
| Offline messaging | A locally accepted message remains durable and visibly pending/failed until acknowledged or explicitly discarded. Cached data remains usable during network failure. | Android offline-first guidance, S2; product safety invariant |
| Persistent background work | A worker can initialize what it needs after process recreation; scheduling is not a promise of immediate delivery. | WorkManager / Apple background-task guidance, S3–S5 |
| Retry | Throttling is not permanent rejection; preserve intent and respect retry timing. Network uncertainty must not duplicate a send. | HTTP 429 semantics, S6; idempotency requirement derived from this app's retry design |
| Notifications | Direct reply must actually deliver RemoteInput text, target the intended account/room, and honor lock/privacy policy. | Android notification APIs, S7; product safety invariant |
| Calls | Use OS call/audio integration and API-compatible services; verify real background incoming-call delivery, not just foreground signaling. | S8–S9 |
| Interaction | Deleting a draft stays deleted; reading history does not jump to the bottom on every incoming message; back/recreation preserves meaningful context. | Explicit acceptance criteria for this audit, not claimed universal OS mandates |
| Security | Account identity must be authenticated; logout must end authority; app lock must cover cold entry; release signing secrets must not be distributed with source. | Security requirements for a private messaging product |

### Research sources (consulted before conclusions)

- **S1 — Android architecture recommendations:** [1](https://developer.android.com/topic/architecture/recommendations). Recommends unidirectional state and lifecycle-aware collection.
- **S2 — Offline-first data layer:** [2](https://developer.android.com/topic/architecture/data-layer/offline-first). Describes local/network sources and observable local reads.
- **S3 — Persistent work:** [1](https://developer.android.com/develop/background-work/background-tasks/persistent); unique-work cancellation semantics: [2](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).
- **S4 — Apple background execution:** [6](https://developer.apple.com/videos/play/wwdc2019/707/); task completion/expiration: [1](https://developer.apple.com/videos/play/wwdc2025/227/). Runtime is system-controlled, not a guaranteed timer.
- **S5 — Apple background configuration:** [1](https://developer.apple.com/documentation/bundleresources/information-property-list/uibackgroundmodes); Apple's explanation of missing-mode/identifier submission failure: [2](https://developer.apple.com/forums/thread/123660).
- **S6 — HTTP 429:** [1](https://datatracker.ietf.org/doc/html/rfc6585), section 4. Rate limiting may include `Retry-After`.
- **S7 — Android direct reply:** [1](https://developer.android.com/develop/ui/compose/notifications/create-notification); mutability contract: [4](https://developer.android.com/reference/kotlin/android/app/PendingIntent).
- **S8 — Android foreground services:** [1](https://developer.android.com/reference/android/app/Service); compatibility wrapper: [2](https://developer.android.com/develop/background-work/services/fgs/launch).
- **S9 — Apple incoming VoIP integration:** [1](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit).

## Actual entry-point map — important before fixing anything

- **Android:** `MainActivity` → authenticated branch → `MirrorRoot` → mirror room scaffold → `MirrorRoom`. The mirror is the active authenticated shell, not just a screenshot/demo artifact. Evidence: [`MainActivity.kt:509–558`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/MainActivity.kt#L509-L558), [`MirrorRoot.kt:995–1045`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoot.kt#L995-L1045).
- **iOS default:** `PulseApp` → `RootView` → default tab panels → `ChatsView` / `ChatRoomView`. Evidence: [`PulseApp.swift:54–72`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/PulseApp.swift#L54-L72), [`RootView.swift:299–319`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/RootView.swift#L299-L319).
- **iOS optional:** the Settings `ui.mirrorAudit` flag selects `MirrorRootView` / `MirrorRoomView`. **Mirror-only findings do not automatically apply to default iOS.** Evidence: [`SettingsView.swift:598–600`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Settings/SettingsView.swift#L598-L600), [`MirrorRootView.swift:225–256`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Mirror/MirrorRootView.swift#L225-L256).

Changing only older Android feature screens will not repair defects in the active mirror path. Likewise, do not mistake unfinished iOS mirror behavior for a diagnosis of all default iOS screens.

## Findings at a glance

| ID | Priority | Affected | Confirmed source defect / gap |
| --- | --- | --- | --- |
| N01 | P0 | Both + native API boundary | Name-only reclaim, missing-token acceptance, no authenticated actor binding |
| N02 | P0 | Both | Unscoped cache/outbox permits cross-account data and send attribution |
| N03 | P1 | Both | Incomplete logout and stale asynchronous session resurrection |
| N04 | P1 | Both, different paths | iOS cold launch bypasses persisted app lock; Android unlock persists across backgrounding |
| N05 | P1 | Android | Outbox worker lacks cold-process identity bootstrap; replacement churn |
| N06 | P1 | iOS | Background outbox relies on live UI-created engine and lacks fetch mode |
| N07 | P1 | Both + message API | No end-to-end idempotency for retryable sends |
| N08 | P1 | Both + message API | Creation-time delta cannot reconcile missed mutations / complete large gaps |
| N09 | P1 | Both | Lossy outbox failure, capacity, and metadata policies |
| N10 | P1 | Android active mirror | Voice file uploaded before recorder is stopped/finalized |
| N11 | P1 | Android | Immutable direct-reply intent prevents intended text handoff |
| N12 | P1 | Both, different paths | Read acknowledgments do not represent active visible reading |
| N13 | P1 | iOS optional mirror | Silent failed sends, non-live room, disconnected controls/routing |
| N14 | P2 | Android active mirror | Cleared drafts resurrect; shell state not saved for process recreation |
| N15 | P2 | Android mirror + optional iOS mirror | Message-count changes force scroll even when reading history |
| N16 | P2 | Both | Foreground-intended polling not tied to lifecycle |
| N17 | P1 | Android release | Distribution signing material is committed with credentials |
| N18 | P1 | Both | Startup exposes authenticated work before endpoint/token readiness |
| N19 | P1 | Android calls | API-29 foreground-service overload used on declared API-21+ support |
| N20 | P2 | Native CI/distribution | Build/launch gates do not establish behavior or installable iOS release |

## Detailed findings

### N01 — P0 — Authentication does not establish account ownership

**Evidence:** [`route.ts:21–55`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/src/app/api/users/login/route.ts#L21-L55), [`proxy.ts:23–50`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/src/proxy.ts#L23-L50), [`route.ts:217–228`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/src/app/api/conversations/%5Bid%5D/messages/route.ts#L217-L228), [`route.ts:436–449`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/src/app/api/conversations/%5Bid%5D/messages/route.ts#L436-L449). Android retries login by stored name: [`SessionViewModel.kt:62–83`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/SessionViewModel.kt#L62-L83). iOS clears the token and rebuilds an authenticated-identity API client without one: [`PulseSession.swift:162–170`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L162-L170).

**Observed:** Login requires an existing display name, not proof of ownership. The proxy passes requests without a bearer token; with a token, it verifies that it belongs to *some* user but does not bind that user to route `senderId`/`userId`. The message route trusts the supplied sender for its membership check. This is not made secure by encrypting the token on-device.

**Consequence:** A deployment using this contract cannot promise private authenticated accounts. A caller can claim another identity; possessing one account's accepted token does not itself prevent acting as another supplied sender. Token rejection is also treated differently by the two native clients. Repository comments explicitly describe self-declared identities and a token-migration mode: these are intentional legacy choices, not an accidental encryption bug. If the product is only a public demo, label that limit clearly; conventional private-account behavior requires a different contract.

**Repair:** Define genuine authentication/recovery, derive actor identity server-side, require valid sessions on protected REST/socket operations, and migrate old clients deliberately. Keep offline reads possible, but pause authenticated writes on expiry rather than downgrade them to anonymous requests. Use revocable per-device sessions.

**Acceptance:** In an isolated test deployment, reject missing/invalid tokens and account-A-token/account-B-actor requests; prove legitimate multi-device login and logout/revocation. See T01–T02. **Do not silently flip this contract without a migration plan.**

### N02 — P0 — Cache and outbox have no account namespace

**Evidence:** Android database name and queries: [`PulseDatabase.kt:529–580`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt#L529-L580), [`PulseDatabase.kt:653–683`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt#L653-L683), [`PulseDatabase.kt:889–893`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt#L889-L893). Android drain uses current `viewerId`: [`PulseRepositoryImpl.kt:1590–1608`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L1590-L1608). iOS always opens `pulse.sqlite`: [`PulseSession.swift:313–320`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L313-L320); unscoped outbox/drafts: [`PulseStore.swift:68–84`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Storage/PulseStore.swift#L68-L84); sender comes from the current session: [`PulseSession.swift:397–409`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L397-L409).

**Observed:** Conversations, drafts, and pending entries are not keyed by account and gateway. Identity transitions do not partition these rows. iOS explicitly retains the outbox across `stop()`; Android refresh upserts without removing previous-account conversations.

**Consequence:** Account B can encounter account A's cached conversations/drafts. A pending message can be submitted as B if B belongs to the room; otherwise rejection can delete A's pending message. Different configured servers also share local key space.

**Repair:** Namespace persistent and in-memory state by stable `(server, account)` identity. Store immutable sender ownership on every queued operation and enforce it at flush time. Migrate ambiguous legacy entries into a quarantined/recoverable state, not guessed ownership. Account switching must not flush another account's work.

**Acceptance:** A queues offline, logs out, B signs in; no A cache appears and no A send occurs as B. Re-entering A restores its pending queue. Repeat across two gateways with colliding room IDs. T03.

### N03 — P1 — Logout does not reliably end session work

**Evidence:** Android `forgetViewer` and null bootstrap: [`SessionViewModel.kt:154–175`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/SessionViewModel.kt#L154-L175). Socket connect mutates the intended user then returns if already connected: [`PulseSocketClient.kt:188–220`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/remote/PulseSocketClient.kt#L188-L220). iOS teardown/startup: [`PulseSession.swift:180–200`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L180-L200), [`PulseSession.swift:211–257`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L211-L257), [`PulseSession.swift:262–311`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L262-L311).

**Observed:** Android clears preferences/token but does not reset repository `viewerId`, disconnect its socket, or cancel repository work. A different-user `connect` on an already-connected transport need not rejoin. On iOS, `stop()` does not cancel `reminderDueTask` or the untracked `startRealtime` task. Startup can resume after an `await` and bind the old identity again; a delayed identity-validation 404 can also stop a newer session.

**Consequence:** Logout can leave old-account events/work alive. Account switching can mix current REST identity with an old socket membership. A slow response can resurrect or destroy the wrong session.

**Repair:** One session coordinator with a tracked task scope and generation/epoch. Teardown cancels work, disconnects transport, ends relevant media ownership, clears transient routing, and detaches storage. Every asynchronous continuation verifies its session epoch before mutating state. Bind socket membership to the confirmed identity, not just transport connection.

**Acceptance:** Pause manifest/validation/REST responses, switch or sign out, then release them. Old work cannot reconnect, display data, send, or clear the new account. T04.

### N04 — P1 — App-lock lifecycle is inconsistent and bypassable on cold iOS entry

**Evidence:** iOS starts `needsLock = false` and arms only when backgrounded: [`PulseAppLock.swift:30–48`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseAppLock.swift#L30-L48), [`RootView.swift:178–190`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/RootView.swift#L178-L190). Android stores a process-lifetime unlock: [`PulseAppLock.kt:49–63`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/security/PulseAppLock.kt#L49-L63), [`PulseAppLock.kt:116–125`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/security/PulseAppLock.kt#L116-L125); `onStop` does not relock: [`MainActivity.kt:317–327`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/MainActivity.kt#L317-L327).

**Observed:** A new iOS process begins unlocked even if the persisted preference is enabled. Android explicitly keeps an unlock for the process lifetime rather than rearming after leaving the app.

**Consequence:** Cold-launching iOS can reveal chats without authentication. Returning to an already-unlocked Android process has a different privacy contract from iOS. The Android choice could be a product policy, but the current generic “App lock” promise does not make the distinction clear.

**Repair:** Initialize a locked/bootstrap state before sensitive content mounts; define and implement a consistent immediate/background-timeout policy. Cover deep links, notification reply, app-switcher previews, and enable/disable transitions. Verify Face ID usage-description configuration before signed-device testing; the inspected project spec contains none.

**Acceptance:** Enable lock, terminate normally, relaunch through icon/link/notification, and background/resume at each timeout boundary. No sensitive frame or send before unlock. T05.

### N05 — P1 — Android persistent outbox worker is not self-sufficient

**Evidence:** [`OutboxWorker.kt:31–69`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/OutboxWorker.kt#L31-L69), [`PulseRepositoryImpl.kt:239–267`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L239-L267), [`PulseRepositoryImpl.kt:1590–1608`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L1590-L1608), [`SessionViewModel.kt:86–93`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/SessionViewModel.kt#L86-L93).

**Observed:** `doWork()` calls `flushOutbox()` without loading an account or awaiting endpoint/token readiness. The repository begins with `viewerId = null`; drain sends `viewerId ?: ""`. A foreground ViewModel schedules unique work using `REPLACE` on every nonempty queue emission, including delivery/attempt changes.

**Consequence:** A process recreated solely for WorkManager cannot safely drain the queue. If a configured endpoint is ready, an empty-sender validation response can permanently drop entries; if not ready, work retries without delivering. Queue mutations can cancel active workers rather than simply coalesce pending work (S3).

**Repair:** Give the worker durable account-scoped input and a bootstrap independent of Activities/ViewModels. Refuse to drain until identity and endpoint are ready. Choose a non-self-canceling scheduling policy, propagate cancellation, and retain bounded backoff. Check expedited-work foreground compatibility on supported pre-Android-12 devices.

**Acceptance:** OS-kill the background process (not Android force-stop), restore connectivity, and let WorkManager run without opening the UI. Messages send once as their owner; queue mutations do not restart/cancel each attempt. T06.

### N06 — P1 — iOS background outbox can report success without doing work

**Evidence:** [`PulseApp.swift:72–94`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/PulseApp.swift#L72-L94), [`project.yml:46–58`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/project.yml#L46-L58), [`PulseSession.swift:397–409`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L397-L409).

**Observed:** The BGTask handler calls `PulseOutboxEngine.active?.flush()` and unconditionally reports success. That static engine is installed by foreground session startup. The project declares `remote-notification` and `voip`, but not the `fetch` mode for its `BGAppRefreshTaskRequest`. Submission errors are swallowed.

**Consequence:** The scheduled path is misconfigured; even after configuration is fixed, a background launch with no initialized engine can be a successful no-op. Cancellation/failure has no honest completion result. An earliest date of 15 minutes is not a delivery guarantee (S4–S5).

**Repair:** Configure the appropriate background mode; initialize an account-scoped store/client in the background handler; return a real drain result; handle expiration and cancellation. Keep foreground/network restoration triggers because iOS can defer or deny background runtime.

**Acceptance:** Test scheduling failure, expiration, no live scene/engine, network failure, and disabled Background App Refresh on signed hardware. Assert queue integrity and truthful completion, not an exact execution time. T07.

### N07 — P1 — Retried sends have no end-to-end idempotency

**Evidence:** Android creates a local UUID but does not transmit it: [`PulseRepositoryImpl.kt:909–962`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L909-L962), [`PulseApi.kt:328–365`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/remote/PulseApi.kt#L328-L365). iOS likewise omits a client operation key: [`PulseAPIClient.swift:245–282`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Networking/PulseAPIClient.swift#L245-L282). Server message creation and model: [`route.ts:569–605`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/src/app/api/conversations/%5Bid%5D/messages/route.ts#L569-L605), [`schema.prisma:412–455`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/prisma/schema.prisma#L412-L455).

**Observed:** Local `clientId` reconciles optimistic rows but is not a server-enforced request identity. No corresponding uniqueness constraint/idempotency handling is present in the inspected message route/model.

**Consequence:** Server commits → response is lost → client retries → a second real message can be created. Content-based echo cleanup cannot distinguish retries from intentionally repeated identical messages.

**Repair:** Generate/persist one operation key before first send; transmit it on every attempt; server enforces uniqueness scoped to authenticated sender and returns the original committed result. Reconcile by that key. Do not promise transport “exactly once”; implement effectively-once creation despite at-least-once attempts.

**Acceptance:** Drop the response after server commit, restart, retry multiple times, race socket echo with HTTP completion, and intentionally send identical text twice. One row per operation, not one row per text. T08.

### N08 — P1 — Delta sync cannot repair all missed changes

**Evidence:** Android creation cursor: [`PulseRepositoryImpl.kt:637–669`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L637-L669). Default iOS delta handling: [`ChatRoomView.swift:4025–4064`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Chat/ChatRoomView.swift#L4025-L4064). Server uses strict `createdAt > cursor`, descending newest window, then a limit: [`route.ts:193–213`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/src/app/api/conversations/%5Bid%5D/messages/route.ts#L193-L213). Android conversation refresh only upserts: [`PulseRepositoryImpl.kt:621–627`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L621-L627).

**Observed:** Reconnection asks for newly created messages, not edits/deletions/reactions to older rows. A gap larger than the page limit returns the newest tail; advancing to its newest timestamp skips the intervening gap unless separately paged. Timestamp ties also need a stable tie-breaker. Android's inbox refresh does not reconcile absent conversations.

**Consequence:** Missed socket events can leave deleted/edited messages, reactions, or removed-room metadata stale. Long offline periods can leave history gaps after apparent synchronization. Upsert is not full reconciliation.

**Repair:** Use a server change cursor/sequence including tombstones and mutations; page forward until caught up before advancing the durable cursor. Alternatively implement bounded authoritative reconciliation with explicit deletion/membership handling. Never prune local pending sends as a side effect of refreshing a server page.

**Acceptance:** Disconnect, mutate old rows/remove membership, add more than one page of messages including timestamp ties, reconnect, and compare the full local state with server truth. T09.

### N09 — P1 — Outbox policies discard recoverable intent or alter payloads

**Evidence:** iOS drops any 4xx, including 429/auth: [`PulseOutboxEngine.swift:234–251`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseOutboxEngine.swift#L234-L251), [`PulseOutboxEngine.swift:312–324`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseOutboxEngine.swift#L312-L324). Android drops auth-class failures: [`PulseRepositoryImpl.kt:1592–1608`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L1592-L1608). Android capacity deletion: [`PulseDatabase.kt:681–683`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt#L681-L683); iOS trimming: [`PulseOutboxEngine.swift:173–182`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseOutboxEngine.swift#L173-L182). Android queued rich text loses metadata: [`PulseRepositoryImpl.kt:1038–1052`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L1038-L1052); queue shape: [`Outbox.kt:12–24`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/domain/src/main/kotlin/app/pulse/domain/model/Outbox.kt#L12-L24).

**Observed:** iOS treats temporary throttling as permanent failure. Both clients discard auth-rejected pending entries rather than hold them for reauthentication. Both silently remove the oldest queue entries beyond 50 without consistently settling their optimistic bubbles. Android stores only text/core fields for sends that may have topic/effect metadata; drain sends plain text.

**Consequence:** A message that looked queued can disappear, remain as an unsendable ghost, or arrive with different semantics. A temporary 429 is not proof that retry can never succeed (S6).

**Repair:** Model durable `pending/sending/awaitingAuth/retryAt/failed/sent` states. Respect throttling, preserve full immutable payloads, and reject new enqueue visibly or retain an explicit failure when capacity is reached. Permanent rejection should leave recoverable text and an actionable error, not silently delete user intent.

**Acceptance:** Inject 401, 403, 422, 429 with Retry-After, 5xx, cancellation, queue overflow, and a topic/effect send. Assert explicit states, correct retries, no ghost bubbles, and exact payload preservation. T10.

### N10 — P1 — Android voice recording is uploaded before finalization

**Evidence:** Recorder setup and cancel/send branches: [`MirrorRoom.kt:978–1063`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoom.kt#L978-L1063).

**Observed:** `cancelRecording()` calls `MediaRecorder.stop()`, but `sendRecording()` only flips Compose `recording = false`, then reads/uploads the file. It releases the recorder only after successful send; upload failure leaves it allocated. Changing UI state does not stop the native recorder.

**Consequence:** The upload can contain an unfinished MP4/M4A; capture can continue while the UI appears stopped, especially on failure. This is both a core messaging and microphone-lifecycle defect.

**Repair:** Stop/finalize first, handle too-short/stop errors, release in guaranteed cleanup, then upload the completed file. Retain a retryable file until confirmed success or explicit discard. Bind capture and upload ownership to lifecycle/session.

**Acceptance:** Record/send/play back on another device, then repeat with failed upload, permission denial, backgrounding, cancellation, and leaving the room. Microphone indicator must stop; valid retained audio remains retryable. T11.

### N11 — P1 — Android notification Reply uses the wrong PendingIntent mutability

**Evidence:** [`PulseMessagingService.kt:124–148`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/push/PulseMessagingService.kt#L124-L148), [`PulseReplyReceiver.kt:53–67`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/notify/PulseReplyReceiver.kt#L53-L67).

**Observed:** The RemoteInput reply action uses `FLAG_IMMUTABLE`. Direct reply needs the system to add reply data to that intent (S7). The receiver treats missing text as unusable and can cancel the notification.

**Consequence:** The visible Reply control can fail to deliver the typed text and dismiss its notification instead. Receiver parser tests alone do not exercise the OS PendingIntent handoff.

**Repair:** Use an explicitly targeted mutable reply PendingIntent with appropriate API handling; retain immutability for ordinary open intents. Add account/lock checks and cold-start initialization, not just a flag fix.

**Acceptance:** Use the real system notification UI on supported OS versions; verify exact text delivery, notification outcome, account binding, lock behavior, and dead-process handling. T12.

### N12 — P1 — Read receipts do not consistently mean active reading

**Evidence:** Android mirror marks read on entry only: [`MirrorRoot.kt:1011–1023`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoot.kt#L1011-L1023); foreground attention has no active-room gate: [`IncomingAttention.kt:61–84`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/notify/IncomingAttention.kt#L61-L84). Default iOS marks peer messages read inside a socket subscription without checking scene/visibility: [`ChatRoomView.swift:3927–3951`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Chat/ChatRoomView.swift#L3927-L3951); disappearance does not tear down that subscription: [`ChatRoomView.swift:3898–3905`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Chat/ChatRoomView.swift#L3898-L3905).

**Observed:** Android's inspected active-room path acknowledges the initial load but not subsequent peer arrivals as part of visible reading; incoming attention can ping for the room already on screen. On iOS, a retained room view model can acknowledge arrivals while the app is inactive or another surface covers the room, before suspension.

**Consequence:** Android can keep a room unread while the user reads it; iOS can report content read when it was not actually visible. These are different bugs, not one shared implementation.

**Repair:** Define a visibility-aware read watermark (foreground + unlocked + relevant room visible, with a documented viewport policy). Coalesce acknowledgments; do not equate socket receipt with reading. Suppress redundant active-room alerts without suppressing unrelated-room notifications.

**Acceptance:** Two devices, room open/covered/backgrounded/locked; send new messages in each state and verify badges, receipts, and alerts. T13.

### N13 — P1 — Optional iOS mirror lacks default-screen messaging guarantees

**Evidence:** Flag gate: [`RootView.swift:299–308`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/RootView.swift#L299-L308). Placeholder tabs and no-op FAB: [`MirrorRootView.swift:235–255`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Mirror/MirrorRootView.swift#L235-L255). Decorative call controls: [`MirrorRootView.swift:1400–1420`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Mirror/MirrorRootView.swift#L1400-L1420). One-time room load and silent send: [`MirrorRootView.swift:1620–1660`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Mirror/MirrorRootView.swift#L1620-L1660). Default route handoff is consumed in `ChatsView`: [`ChatsView.swift:204–219`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Chats/ChatsView.swift#L204-L219).

**Observed:** Mirror `send()` clears the draft then uses `try?` on a direct API send, without the normal optimistic/store/outbox path. `load()` converts fetch failure to an empty message list. The room has no equivalent live message subscription. Calls/Updates tabs use a later/placeholder view; FAB is a no-op; phone/video are images rather than actions. MirrorRoot owns separate navigation and does not consume the default session pending-room bridge.

**Consequence:** With this Settings flag enabled, offline sends lose typed text, incoming messages need another reload, failures can blank history, and visible controls/deep-link handoffs do not match their promises. **This is not the default iOS shell.**

**Repair:** Either remove the experimental toggle from distributable builds or bind mirror presentation to the existing tested feature state/actions. Do not maintain a second direct-network implementation of chat. Every exposed shell needs the same behavior acceptance suite.

**Acceptance:** Enable the flag and run send/retry/live arrival/deep link/FAB/call/tab scenarios, or verify the flag cannot expose unfinished behavior in release. T14.

### N14 — P2 — Android deleted drafts resurrect; shell context is not restored

**Evidence:** [`MirrorRoom.kt:454–466`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoom.kt#L454-L466), [`MirrorRoot.kt:114–126`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoot.kt#L114-L126).

**Observed:** Draft saving returns early for blank text, so deleting the composer contents never deletes the persisted draft. Tab/open-room state uses plain `remember`, not saved route state. The manifest handles many configuration changes itself; that does not make the state survive process recreation.

**Consequence:** Clear → leave → reopen can restore text the user deliberately removed. OS process recreation can return to the default tab/home rather than the prior room. Text typed immediately before leaving can also miss a canceled debounce; this needs runtime timing verification.

**Repair:** Persist explicit draft clears; flush outstanding draft intent at appropriate boundaries. Save minimal route IDs and reconstruct validated room state, rather than serializing stale whole conversation objects.

**Acceptance:** Save a draft, clear it, leave/reopen/restart; no resurrection. Restore a background OS-killed process without leaking another account's route. T15.

### N15 — P2 — Message-count changes force the reader to the bottom

**Evidence:** Android active mirror: [`MirrorRoom.kt:1065–1068`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoom.kt#L1065-L1068); iOS mirror: [`MirrorRootView.swift:1456–1458`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Mirror/MirrorRootView.swift#L1456-L1458). The default iOS room already has a near-tail visibility guard: [`ChatRoomView.swift:1558–1583`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Chat/ChatRoomView.swift#L1558-L1583). Preserve that behavior rather than treating all iOS rooms as defective.

**Observed:** These mirror paths scroll to the newest row when the message count changes without checking whether the user is already near the bottom. Android excludes an active jump request, but not ordinary history reading or prepending older rows.

**Consequence:** A new message or pagination can steal reading position. This is a concrete interaction policy, not a styling issue.

**Repair:** Auto-scroll only for initial positioning, a user's own send where appropriate, or when already near the bottom. Preserve the visible anchor when prepending history; show a new-message/jump-to-latest affordance otherwise.

**Acceptance:** Read older messages while receiving messages/loading history. No unexpected jump; deliberate send and jump-to-latest still work. T16.

### N16 — P2 — Polling described as foreground-only is not lifecycle-bound

**Evidence:** Android mirror repeatedly fetches conversations/stories/folders/mentions: [`MirrorRoot.kt:315–333`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/mirror/MirrorRoot.kt#L315-L333). Activity reminder loop persists until lifecycleScope destruction, not stop: [`MainActivity.kt:273–320`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/MainActivity.kt#L273-L320). iOS inbox poll: [`ChatsView.swift:2777–2800`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Features/Chats/ChatsView.swift#L2777-L2800); reminder loop: [`PulseSession.swift:262–280`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L262-L280).

**Observed:** Composition lifetime / task existence is being treated as foreground lifetime. Android also uses non-lifecycle-aware Flow collection in the active mirror. iOS does not automatically receive `onDisappear` merely because its scene becomes inactive.

**Consequence:** Polling and refreshes can continue while no screen should own them (until iOS suspension where applicable), waste requests, and resume with stale work. Ordinary polling is not a substitute for OS-backed background delivery.

**Repair:** Bind ordinary screen work to lifecycle/scene state, use lifecycle-aware Android collectors, separate persistent operations, and cancel/coalesce obsolete refreshes. Explicitly design exceptions for ongoing calls.

**Acceptance:** Instrument request counts while foreground/background/covered; verify controlled resumption and no duplicate pollers. T17.

### N17 — P1 — Android release-signing secrets are distributed with source

**Evidence:** [`build.gradle.kts:48–56`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/build.gradle.kts#L48-L56), [`build.gradle.kts:78–84`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/build.gradle.kts#L78-L84); tracked file `apps/android/keystores/pulse-release.keystore`. Credential values are deliberately not repeated in this report.

**Observed:** Release signing uses a repository-tracked keystore and literal credentials. This gives anyone with repository read access the signing material used by this configuration, not just permission to inspect source.

**Consequence:** The repository access boundary also becomes the app-update signing boundary. A compromised reader/clone can sign an APK with that identity. This audit does not claim the repository is public or that an attack occurred.

**Repair:** Treat this signing material as exposed to repository readers; inventory actual distributed certificates, plan compatible key rotation/app-signing migration, move credentials to a protected release environment, and require approvals. Removing the file from the latest tree alone does not revoke historical exposure. Do not delete/replace it blindly and strand installed users.

**Acceptance:** Clean source checkout cannot produce a trusted production signature without protected release authorization; upgrade continuity is verified with the real shipped certificate lineage. T18.

### N18 — P1 — Endpoint/token hydration is not one atomic startup barrier

**Evidence:** Android separately hydrates token/vault and emits `hydrated` from preferences: [`SessionViewModel.kt:37–52`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/SessionViewModel.kt#L37-L52), [`SessionViewModel.kt:113–126`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/SessionViewModel.kt#L113-L126). Repository starts socket and parallel refreshes before token loading completes: [`PulseRepositoryImpl.kt:247–267`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt#L247-L267). Activity flushes on start: [`MainActivity.kt:273–282`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/src/main/java/app/pulse/android/MainActivity.kt#L273-L282). iOS builds models/outbox before awaiting manifest-driven rebinding: [`PulseSession.swift:228–257`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L228-L257), [`PulseSession.swift:283–310`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/Pulse/Core/Support/PulseSession.swift#L283-L310).

**Observed:** “Preferences emitted,” “identity restored,” “endpoint ready,” “credential loaded,” and “session joined” are separate racing states. Android's shell can bootstrap a persisted viewer while another coroutine still loads credentials. iOS can start delivery against the pre-resolution client before the manifest override is applied.

**Consequence:** Cold start can issue tokenless/wrongly configured requests, transient offline results, or premature drains. Optional-auth behavior currently masks some ordering errors; enforcing authentication without fixing bootstrap would expose them.

**Repair:** Explicit startup state machine: resolve local account/lock/config/credential → bind account-scoped storage/client → publish ready → synchronize. Render cached UI safely while bootstrapping, but do not send before its prerequisites. Share the same bootstrap with background workers.

**Acceptance:** Delay each dependency independently and in all orderings; no authenticated write before readiness, no old-gateway token leakage, no onboarding/shell flicker caused by partial hydration. T19.

### N19 — P1 — Call foreground service invokes a newer API on supported older Android

**Evidence:** [`CallForegroundService.kt:25–46`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/feature-calls/src/main/kotlin/app/pulse/feature/calls/CallForegroundService.kt#L25-L46); declared minimum: [`build.gradle.kts:21–26`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/android/app/build.gradle.kts#L21-L26).

**Observed:** The service always calls the three-argument `startForeground(id, notification, type)`; only the *type value* is guarded by OS version. That overload was added in API 29 (S8), while the app declares minSdk 21.

**Consequence:** Reaching this call-service path on API 21–28 risks a missing-method crash. Passing `0` does not make the overload exist. Current CI's API 30/34 devices cannot catch this compatibility defect.

**Repair:** Use `ServiceCompat.startForeground` or a correctly version-guarded overload, and audit start/stop branches across the supported range. Add lint/API compatibility and a lower-API call smoke, or explicitly raise the supported minimum as a product decision.

**Acceptance:** Start/end voice calls on API 21/28 and video/voice calls on current Android with permission denial/background transitions. T20.

### N20 — P2 — Existing CI is not a native behavior acceptance gate

**Evidence:** [`android-ci.yml:1–39`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/.github/workflows/android-ci.yml#L1-L39), [`android-ci.yml:86–132`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/.github/workflows/android-ci.yml#L86-L132), [`ios-ci.yml:1–35`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/.github/workflows/ios-ci.yml#L1-L35), [`ios-ci.yml:142–187`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/.github/workflows/ios-ci.yml#L142-L187), [`android-native-audit.yml:47–68`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/.github/workflows/android-native-audit.yml#L47-L68), [`project.yml:24–29`](https://github.com/GrapseeAgency/the-mystrious-chat-app/blob/9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5/apps/ios/project.yml#L24-L29).

**Observed:** Android's JVM command lists selected modules, not every module containing tests; instrumentation targets API 30/34. Existing iOS build/unit/launch and unsigned-archive jobs are valuable, but do not prove multi-device behavior or signed incoming push. Manual Android “audit” package verification ends with `|| true`, so a mismatch cannot fail that check; the workflow uploads to an existing release. iOS project/CI explicitly disables signing; the archive is not a ready-to-install signed iPhone distribution. Native push jobs target main/tags, and PR path filters are narrower than push filters for shared packages.

**Consequence:** Green compile, unit fixtures, and launch screenshots can coexist with all the behavior defects above. An unsigned archive must not be presented as a usable IPA/TestFlight build. Shared changes and active-shell behavior need explicit gate coverage.

**Repair:** Keep current tests, add all applicable unit/lint suites and the regression scenarios below, exercise actual routed shells, use artifact-only branch workflows, and separate distribution from validation. Add protected signing/export only when Apple/team provisioning and appropriate credentials are configured. Never publish over a release as a side effect of an audit.

**Acceptance:** Exact-SHA check results plus artifacts, UI assertions, sanitized device logs, and test-deployment state verification. Require the matrix's critical scenarios before release, with physical-device cases explicitly signed off. T21.

## Additional concerns requiring deeper verification (not counted as proven defects)

These are deliberately not given “confirmed bug” status from this pass:

- **Background incoming calls:** iOS `PulseCallKitProvider.swift` describes the VoIP route as dormant and alert-push-only transport. Inspect current deployment/transport and prove a signed suspended-app call before promising WhatsApp-like incoming-call reliability. Foreground socket ringing is insufficient.
- **Audio routes and interruptions:** Bluetooth, wired headset, speaker, phone-call interruption, camera/mic denial, and simultaneous 1:1/group-call exclusion need paired-device execution.
- **Notification privacy:** Validate room mute, quiet hours, previews, lockscreen content, account changes, token rotation, and duplicate suppression through actual FCM/APNs delivery; foreground toast logic is not proof of background notification policy.
- **Secure storage/backups:** Android token code contains a plaintext fallback on unsupported/failed encryption paths; inspect backup rules, API support policy, key invalidation, and restore behavior before asserting encrypted credential storage universally.
- **Accessibility:** TalkBack/VoiceOver names and focus, large fonts/Dynamic Type, touch targets, contrast, reduced motion, RTL, and keyboard/safe-area behavior have not been certified from screenshots/source.
- **Persistence failure:** iOS falls back to an in-memory store if opening the file fails and swallows several storage errors. Disk-full, migration failure, and crash consistency require fault-injection tests; “queued” must not be shown for an undurable write.
- **Settings consistency:** Optimistic settings patches and server-whole-object replacement need response-reordering/offline tests so fast toggles do not overwrite newer choices.
- **Large media/performance:** Main-thread decoding, very large history/media, memory pressure, upload progress/cancellation, pagination limits, and launch performance remain unmeasured.

## Repair sequence and exit criteria

| Stage | Work | Must be true before moving on |
| --- | --- | --- |
| 1 — Stop privacy/authority leakage | N01–N04, N17; decide auth migration and signing recovery | Account boundaries proven; logout terminates old authority; lock protects every entry path |
| 2 — Make delivery durable | N05–N09, N18 | Cold-process bootstrap works; retries cannot duplicate or silently lose accepted text; reconciliation completes |
| 3 — Repair the actual native surfaces | N10–N16 | Recording, replies, receipts, drafts, scroll, and exposed shell controls satisfy acceptance tests |
| 4 — Verify OS compatibility/distribution | N19–N20 + unresolved device cases | Supported OS range and signed hardware verified; exact-SHA artifacts and release gates available |

Prefer small, independently testable changes with regression tests over another large “parity” wave. A test that asserts an unsafe historical behavior is not a reason to preserve it; change the contract deliberately and test the new invariant.

## Definition of completion

**This audit's finish line:** researched baseline, verified routed code paths, evidence-backed prioritized findings, repair order, explicit regression cases, and a GitHub-pushed documentation commit. No application fixes are claimed.

**The apps' finish line:** implemented repairs and passing acceptance evidence on the exact release candidate. This report is the starting map for that work, not a release certificate.
