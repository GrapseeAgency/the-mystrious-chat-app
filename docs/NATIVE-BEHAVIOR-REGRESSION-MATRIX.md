# Native behavior regression matrix

**Companion:** [2026-10-19 audit](NATIVE-BEHAVIOR-AUDIT-2026-10-19.md)

**Baseline source:** `9fc58f9c5a6fa0d034ab6fc538494cea5a11b3a5`

**Execution status:** **All cases below are NOT RUN in this audit.** These are acceptance specifications for the repair work, not passing test results.

## Test environment and safety

Use a disposable, access-controlled test deployment and synthetic accounts. Do not run authorization-negative cases against real users or production. Never attach tokens, signing credentials, private messages, or raw sensitive push payloads to CI artifacts.

Minimum fixtures:

- Two different accounts A and B, a third peer C, a shared A/B group, and separate private rooms.
- Two native devices (Android + iOS), plus same-platform paired-device runs for platform-specific failures.
- Two test gateways with intentionally overlapping synthetic room IDs for namespace tests.
- Fault-injection relay/API capable of pausing responses, committing then dropping a response, returning chosen statuses/Retry-After, and suppressing selected socket events.
- Server-side message/operation counts and sanitized lifecycle logs to distinguish UI appearance from actual delivery.
- Android low-API coverage consistent with the declared minimum (21), API 28 for pre-29 calls, API 30/34, and current supported target-device testing. Add latest available supported emulator images rather than assuming API 34 is sufficient forever.
- iOS 17 baseline and current supported iOS; physical signed devices for APNs/PushKit, Face ID, audio routes, and OS background behavior.

Test each **exposed** UI path: Android mirror (default), default iOS SwiftUI, and iOS mirror if it remains user-accessible. Do not substitute a legacy component test for the actual routed screen.

### Process death is not force-stop

- Android background-process recreation tests should use an OS-style process kill while backgrounded, with the scheduled WorkManager request intact. Explicit force-stop is a distinct user action that prevents ordinary background execution until subsequent user interaction; do not diagnose that platform rule as an app bug.
- For iOS, distinguish debugger-simulated BGTask execution, genuine system background launches, and explicit user force-quit. Scheduling is opportunistic. A green simulated handler test is not proof of real-world delivery timing.

## Required acceptance cases

| Test | Findings | Setup / action | Pass invariant | Required evidence |
| --- | --- | --- | --- | --- |
| **T01 — Authentication / actor binding** | N01 | On a test server, try missing/invalid credentials; use A's valid credential with B's sender/user ID; try name-only reclaim without ownership proof after migration. | Protected operations reject unauthorized actors without mutation. Valid A requests succeed as A. Token removal never increases authority. | API integration assertions and database mutation counts; corresponding socket-join checks. |
| **T02 — Session expiry / multi-device recovery** | N01, N09 | A signs in on two devices. Expire/revoke one credential while messages are pending. Recover through the chosen authentication flow. | Explicit revocation works; one device's normal login does not arbitrarily revoke another. Expired sessions retain pending intent and cached reads without unauthenticated writes. | Session/queue state transitions and server actor IDs; no secrets. |
| **T03 — Account and gateway isolation** | N02 | A queues offline text and a draft; sign out; enter B, first offline then online. Repeat with another server using the same room IDs. Return to A. | B sees none of A's local data and cannot flush A's work. A's pending work remains correctly attributed/recoverable according to documented policy. | UI assertions, account-scoped DB rows, captured request actor/operation IDs. |
| **T04 — Logout / switch races** | N03 | Pause manifest load, validation, message fetch, and outbox responses in separate runs. Sign out or switch A→B, then release old responses. Repeat with connected socket. | No stale task reconnects A, mutates B state, sends after logout, or clears B on A's delayed 404. Socket membership matches current session. | Controllable async unit tests plus transport and UI integration logs. |
| **T05 — App lock across entry paths** | N04 | Enable lock; cold launch from icon, deep link, and notification; background/resume before/after configured timeout; attempt reply while locked; cancel biometrics. | No sensitive frame/send before successful unlock; cancellation stays locked; behavior matches documented timeout policy. No preview leakage in app switcher. | Physical-device recording with synthetic data, lock-state assertions, Face ID/Touch ID/passcode results. |
| **T06 — Android cold worker** | N05, N18 | Queue several messages offline; background and OS-kill process; restore network and run scheduled work without Activity. Mutate queue during a flush. | Worker reconstructs account/config/token, sends once, does not drop for absent bootstrap, and does not cancel itself repeatedly through queue observation. | WorkManager integration tests + API actor/message counts + device logs. |
| **T07 — iOS background task** | N06, N18 | Invoke handler with no foreground engine; inject initialization failure, network failure, and expiration. Test real signed-device submission with Background App Refresh enabled/disabled. | No success-as-no-op. Pending intent survives cancellation/failure; completion is reported once and truthfully. No exact execution-time guarantee is asserted. | BGTask configuration/handler tests and signed-device scheduling logs. |
| **T08 — Lost-ack idempotency** | N07 | Commit send server-side then drop HTTP response; restart/retry; race HTTP result with socket echo. Separately send identical text twice deliberately. | One server message per operation key. Deliberate separate sends remain separate. One reconciled local bubble per operation. | Server unique-key integration tests, local reconciliation tests, two-device UI. |
| **T09 — Reconnect reconciliation** | N08 | Disconnect one client; edit/delete/react to existing messages, remove membership, add more than a fetch page of messages, include equal timestamps. Reconnect. | Mutations/tombstones/room removal converge; no skipped middle page or timestamp-tied row; pending local sends survive refresh. | Full local/server state comparison, cursor logs, pagination tests. |
| **T10 — Queue policy / full payload** | N09 | Return 401, 403, 422, 429 with Retry-After, 5xx, and cancellation separately; enqueue 51+ items; send topic/effect text offline. | No silent loss/ghost bubbles; auth holds; throttle retries after policy delay; permanent failure retains actionable content; capacity is explicit; original payload/owner preserved. | Queue state-machine and persistence tests with fake clock + server payload assertions. |
| **T11 — Android voice-note lifecycle** | N10 | Record >1 second and send; play on peer; repeat with upload failure, very short clip, permission denial, backgrounding, and back navigation. | Recorder stops/finalizes before reading file, microphone indicator clears, audio is playable, retry file survives only as intended, canceled files clean up. | Physical audio playback, recorder state tests, upload bytes/format validation. |
| **T12 — System notification Reply** | N11, N04, N05 | Type a distinctive reply in the OS notification, with app foreground, background, and dead; repeat locked, signed out, and after switching account. | Exact text reaches only intended account/room or is explicitly held/rejected. No empty-input notification disappearance masquerades as success. | Real PendingIntent/RemoteInput instrumentation, receiver unit tests, server receipt. |
| **T13 — Read / unread / active-room attention** | N12 | Peer sends while recipient room is visible, scrolled away, covered by another screen, backgrounded, or locked. Read on another device too. | Read acknowledgments obey documented visibility policy; badges converge; no duplicate ping for the room actively being read; no false reads while hidden. | Paired-device state assertions, read watermark logs, notification capture. |
| **T14 — iOS mirror exposure** | N13 | Enable mirror if still available; send offline/fail/restore; receive live messages; tap FAB/Calls/Updates/phone/video; open a notification/deep link. | Every exposed control works or is clearly unavailable; no text loss or history blanking; live data and routing match default feature behavior. If removed, release cannot expose the flag. | UI automation on both shell modes and source/build configuration assertion. |
| **T15 — Draft and navigation restoration** | N14 | Type/save, clear, leave/reopen; type then leave before debounce; background OS-kill and restore with a room open. Repeat across account switch. | Cleared text does not resurrect; accepted draft is preserved; route restores safely from IDs; no cross-account routing or draft. | Compose UI/process-restoration tests and scoped persistence assertions. |
| **T16 — Scroll anchoring** | N15 | Read history while messages arrive; prepend older history; open jump-to-message; explicitly jump latest and send. | History anchor remains stable unless user/action policy requests scrolling; own send/jump works; new-message indicator is accurate. | UI tests recording visible message ID and scroll offset before/after updates. |
| **T17 — Lifecycle / resource ownership** | N16, N03 | Instrument poll/request/timer counts; foreground→background→foreground repeatedly; cover/remove rooms; logout during requests. | Foreground-only polling stops or suspends; one poller resumes; old work is canceled/ignored; ongoing-call exceptions are explicit. | Request counters, coroutine/task ownership tests, lifecycle instrumentation. |
| **T18 — Signing boundary / upgrade continuity** | N17 | Build from a clean reader-access checkout; run authorized release workflow; test upgrade from a real previously distributed certificate. | Source alone cannot sign trusted production updates. Protected authorized build signs; rotation plan preserves supported upgrade paths. | Certificate fingerprints (not keys), protected-workflow logs, install/upgrade result. |
| **T19 — Startup ordering** | N18 | Delay each of endpoint resolution, token load, viewer hydration, store open, lock state, and socket join independently; include gateway switch. | No write before prerequisites; no token sent to obsolete gateway; no partial identity state rendered as ready; background and foreground use same bootstrap contract. | Deterministic coordinator tests and cold-start UI/API trace. |
| **T20 — Android call API compatibility** | N19 | Start/end calls on lowest supported API and API 28; repeat on modern OS with camera/mic denied and background transitions. | No missing-method crash; proper foreground notification and service lifetime; media stops on end; denial surfaces safely. | Lint/API checks, emulator call smoke, physical permission/audio results. |
| **T21 — Release-candidate proof** | N20 | Build/test exact candidate SHA, exercise actual shell and critical cases, create artifacts without release side effects. | Results map to exact source. APK identity failure fails CI. iOS simulator/unsigned archive is labeled honestly; signed distribution uses protected provisioning. | Immutable artifact names, SHA, check URLs, test reports, device sign-off. |

## Additional physical-device / product gates

These cover unresolved areas, not additional confirmed audit findings:

1. **Incoming calls:** APNs/FCM/VoIP delivery while screen locked and app suspended; answer/decline/end from OS UI; caller cancellation before answer; no duplicate presentation; network switch during call; TURN-only connectivity.
2. **Audio/video:** earpiece/speaker/wired/Bluetooth transitions; OS interruptions; permission revoked; incoming second call during an active 1:1/group call; hold/mute semantics; camera background restrictions.
3. **Notification policy:** per-room mute expiry, quiet-hour boundaries/time zones, preview privacy, notification denial/re-enable, token rotation, old-account token cleanup, duplicate push/socket arrival.
4. **Durability:** disk-full, SQLite open/migration failure, keychain/keystore invalidation, backup/restore, process death during enqueue/ack reconciliation. A durable-queue success must require successful persistence.
5. **Accessibility and layout:** VoiceOver/TalkBack focus and labels; large text, display scaling, RTL, reduced motion; keyboard and safe areas; low-height displays; accessible errors and non-color-only states.
6. **Media and load:** interrupted upload/download, large file/memory pressure, very long chat history, slow network, duplicate taps, canceled picker, attachment permission lifetime.
7. **Settings races:** delayed/reordered toggle responses, offline edits then refresh, per-account preferences, and local-only security settings that must never be overwritten by server preference sync.

## Recommended automated layers

- **Pure Kotlin / Swift state tests:** session epochs, queue classification, payload encoding, bootstrap transitions, receipt gating, scroll decisions. No sleeps where a controllable clock/scheduler suffices.
- **Persistence tests:** real Room/GRDB migrations, account namespace, transactional enqueue/reconcile, restart recovery, capacity behavior. Include corrupt/legacy input handling.
- **API/socket integration tests:** authenticated actor binding, operation-key uniqueness, repeated request responses, paged change replay/tombstones, rejected joins, reconnect under network faults.
- **Native UI tests:** routed Android mirror and all exposed iOS modes. Use semantic accessibility targets, not coordinate-only screenshots.
- **OS/device tests:** WorkManager recreation, RemoteInput, real recording, biometrics, notifications, calls, audio routes, and opportunistic iOS background execution.

Fixtures should assert the intended safe behavior rather than merely encode today's implementation. Keep backend and client contract tests linked so one side cannot claim success with an incompatible payload.

## Safe CI and artifact plan

The current audit did **not** change or dispatch workflows. For remediation:

1. Add an **artifact-only** manually dispatched native validation lane that accepts this session branch/ref and records exact SHA. Do not reuse release-clobbering audit steps.
2. Android: run all relevant module tests/lint, build the intended variant, validate package/version/signature, and run UI/OS cases on a meaningful API matrix. Store APKs as workflow artifacts, not Git-tracked binaries.
3. iOS: run Xcode build/unit/UI tests on macOS. Upload simulator products/xcresults; label unsigned archives as unsigned. Add signed export/TestFlight only after a protected Apple signing/provisioning setup exists.
4. Keep production release publication separate, approval-gated, and dependent on the required checks and device sign-off.
5. Do not interpret a passing launch screenshot, an isolated socket fixture, or a successfully assembled APK as proof of message durability or privacy.

## Execution record template

For every run, record:

```text
Candidate SHA:
UI mode / build variant:
Device + OS:
Test deployment identifier (no credentials):
Test IDs:
Result per test: PASS / FAIL / BLOCKED / NOT RUN
Expected vs observed behavior:
Sanitized evidence link:
Fix / regression test reference:
Reviewer and date:
```

**Release acceptance:** no open P0; no unmitigated core P1; all critical cases applicable to the release passed on the exact candidate; unresolved hardware/deployment cases explicitly signed off. A blocked test is not a pass.
