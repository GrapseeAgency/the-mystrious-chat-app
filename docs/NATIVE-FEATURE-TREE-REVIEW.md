# Whole-product native review — open work, not a completion certificate

Review date: 2026-10-19. Baseline: `511a22e`. Account sign-in/recovery remains deferred.

The app is not just messaging. Previous message/outbox repairs did **not** establish
correctness of stories, live rooms, calls, collaboration, Hub, profiles, settings,
saved items, navigation, or their interactions. Old comments saying “verified”,
“zero dead controls”, or “fixed” are not acceptance evidence.

## Coverage vocabulary

- **Indexed:** source path, hash, symbols, and lexical risk signals recorded. Not a behavioral inspection.
- **Targeted inspection:** particular entry points and transitions were read/traced. Not every function in the file.
- **Repaired in source:** a concrete transition changed; native compilation and acceptance may still be pending.
- **Executed check:** the exact test layer and result are stated below. SQL checks and syntax parsing are not device tests.

`NATIVE-SOURCE-INVENTORY.csv` enumerates **321 production files: 200 Kotlin and 121 Swift**.
It covers all twelve Android modules' `src/main` Kotlin and all Swift beneath
`apps/ios/Pulse`. This deliberately excludes native tests, generated sources,
resources/manifests, build scripts, extensions outside Pulse, and the backend.
Those exclusions need their own review; this is not a claim that every repository
file was audited. Regenerate with `python3 scripts/native/review_inventory.py`.
The CSV marks every file as indexed, never automatically as inspected or safe.

## Check the route, not just the module

- Android `MainActivity.PulseRoot` mounts `mirror/MirrorRoot`. That root uses
  `MirrorExtras.MirrorStoryViewer`, **not** the imported `StoryViewerScreen`.
  This pass repairs the actual viewer too, and connects it to the common story machine.
- Android `MirrorRoom` opens `MirrorVoiceRoomSheet`, `MirrorStageSheet`, and
  `MirrorSpaceSheet` from `MirrorRoomLive.kt`. `VoiceRoomsOverlay` is imported in
  MainActivity but not mounted there. A functional-looking standalone voice engine
  therefore does not prove that these visible sheets have audio.
- iOS `RootView` actually mounts ChatsView/HubView/ContactsView/ProfileView and the
  Settings/Stories/Saved sheets. `Features/Mirror/MirrorRootView.swift` is not the
  authoritative route merely because of its filename.

## Targeted repairs in this pass

Paths below are relative to `apps/android` or `apps/ios`. None of these rows is a
whole-feature certification.

| Area | Source-backed failure and trigger | Source change | Verification boundary |
|---|---|---|---|
| Android active stories | Photo story has an imagePath but the viewer renders only a background/caption; a slow mark-view POST blocks animation; no expiry filter or foreground gate | `app/.../mirror/MirrorExtras.kt`: render Coil image; drive the viewer through `StoryViewerStateMachine`; separate receipts from progress; expiry reconciliation and RESUMED/app-lock gate. Primary duration now uses the common 5-second machine rather than its separate 6-second timer | Pure machine tests added; UI/image loading/lock transitions not executed |
| Story interaction, both platforms | Timer continues behind viewer/delete UI; releasing a hold can accidentally unpause another pause reason; background wall-clock jump advances unseen stories | Independent gesture and external pause gates; monotonic playback clocks, wall time only for expiry; captured delete/viewer IDs; foreground loops. Android cancelled press is no longer treated as a navigation tap | Kotlin and Swift pause-composition regressions added, unrun |
| iOS story gestures/start | Viewer ignores its supplied startUserId; hold needs movement to be noticed; dragStart is reset to “now”, so a later short tap can count as a long hold | Initialize StateObject with requested author; cancellable stationary hold timer; reset gesture sentinel | Source trace; native gesture acceptance pending |
| Story viewer requests | An old viewers-list response repopulates a closed or different story's sheet; cancelled Swift polling can issue one more request | Request generations/reset invalidation on both platforms; captured target IDs; Swift polling cancellation checks; Swift feed latest-request/session fence | Source trace; delayed network acceptance pending |
| iOS room permission ownership | Join → microphone prompt → leave → grant restarts capture and marks the room joined; same-room rejoin defeats an ID-only guard | Cancelled task plus generation/room/session fences; retry permission fenced too; queued audio callbacks retain the owning generation | Two fake-audio tests drive the actual session model, added but unrun |
| iOS audio teardown | stopCapture sets captureConfigured=false without removing installed tap; next join installs a second tap. Peer nodes also remain attached; jitter host time is treated as a duration | Remove installed tap, detach playback nodes, unwind failed startup, no-op deactivation when never acquired, and absolute host-time scheduling; roster removal releases peer hardware buffers | Source trace; join/leave/rejoin, routes/interruption and audible timing require device tests |
| Android voice service | Three-argument framework startForeground is called on API21–28; stopping launches a service from background | ServiceCompat start/stop, stopService for teardown | Source/API review only; service is not yet wired to the current mirror voice entry |
| Android call answer | A's permission dialog completes after A ended and B started ringing, accepting/declining B | Capture the requested call ID; consult live snapshot and incoming-ringing state before applying consent | Pure permission gate regression added, unrun |
| iOS call preparation/callbacks | Old SDP failures abort a successor call; old peer callbacks use one shared bridge; ending while an outgoing camera prompt is open leaves an idle machine that later starts a call | Per-call bridge; call fences around awaits/catches and deferred answer task; cancellable outgoing preparation intent even while idle | Camera-prompt cancellation fake-media regression added, unrun; old SDP/WebRTC callback integration still pending |
| iOS whiteboard | Failed POST removes the durable pending prefix anyway; reset → new drawing → old ACK removes unrelated strokes; clear announces success before the request | Retain failure for explicit retry; generation+exact-prefix ACK; clear awaits server verdict, excludes in-flight local flush, disallows drawing while clearing; stop cancelled polling before next request | Three queue race regressions added, unrun. Not a server-side exactly-once or board-epoch solution |
| Android wallet/swap | Detached job reads mutable recipient/amount/direction after tap; late transfer ACK clears subsequently edited fields; unknown balance rendered as zero | Capture payment inputs synchronously, validate amount, revision-guard transfer clears, use screen-owned main scope; unknown balances render “—” and wallet failure gets a notice | Source trace only; financial endpoint semantics/retries not certified |
| iOS Hub | Failed market/log refresh replaces real content with an empty list; overlapping wallet loads can restore stale balance; My Apps only checks first 24 catalog entries | Keep last real lists with error; latest-request/session fences; check-in single flight; query complete catalog with supersession guards | Source trace only; fan-out remains sequential, network/device acceptance pending |
| iOS saved library | Offline failure sees saved IDs and declares “loaded”, but never loads message bodies, rendering a false empty library | Actual cached saved-items query and hydration; request/session fences; single-flight unsave per item | Actual SQL query **2/2 passed**; native GRDB rehydration regression added, unrun |
| Android contacts | DM creation continues in detached IO scope after leaving, invoking navigation off main | Screen-owned main scope, cancellation check before navigation, single pending creation and visible error | Source trace; navigation cancellation acceptance pending |
| iOS profile cover | Busy gate is set after photo decoding, allowing overlapping selections; old upload can patch/apply a new session | Busy before first await; capture API/viewer/session ownership; fences before PATCH and local adoption/removal | Source trace; picker/upload/device acceptance pending |
| iOS settings app lock | A delayed biometric enable result can override a subsequent disable or dismissed settings session | Confirmation token/session fence, invalidated on dismissal and newer intent | Source trace; biometric hardware acceptance pending |

## Feature-tree ledger and next transitions to inspect

“Targeted” below refers only to the flows named above or explicitly described here.
An indexed tree must not quietly become “reviewed” because a sibling was repaired.

| Product tree | Android implementation to follow | iOS implementation to follow | Current coverage / outstanding transitions |
|---|---|---|---|
| App shell/navigation/share/deep links/lock | MainActivity, MirrorRoot, ShareInActivity, security, notify | RootView, PulseApp, Core/Support deep-link/share/lock helpers | Entry routing targeted. Back-stack restoration, external intents, lock overlays, and permission-return navigation not exhaustively inspected |
| Chats/message river/composer/media/threads | MirrorRoom, RoomComposerDraft/Timeline; feature-chat alternate surfaces | ChatRoomView, ThreadView, VoiceNotes, MediaPickerSupport | Prior tranche targeted. Durable media, selected-media replacement, alternate-screen parity remain open |
| Stories/tray/composer/viewer | MirrorExtras/MirrorRoot; feature-stories | Features/Stories | Viewer targeted/repaired above. Primary composer ownership/publish failure and full feed cache/poll lifecycle still open |
| Voice room | MirrorRoomLive plus feature-voice engine/audio/service | VoiceRoomSessionModel/AudioEngine/VoiceRoomView | Permission/teardown targeted. Active Android UI audio routing **blocked**, see P0 list |
| Stage | MirrorRoomLive; StageRoomStateMachine/StageScreen | StageView/StageModel/VoiceRoomSessionModel | Join/seat path traced; audio/role transitions **not correct end-to-end** |
| Spatial space | MirrorRoomLive; SpaceBoardStateMachine/SpaceScreen | SpaceView/SpaceModel/VoiceRoomSessionModel | Open/leave entry points sampled. Coordinate ownership, reconnect, multiple-room switching not exhaustively inspected |
| Direct/video calls | feature-calls engine/overlay/media/Telecom | PulseCallEngine, media provider, CallView/CallKit | Permission and call ownership targeted. Telecom/CallKit, process death, audio-route interruptions pending |
| Group calls | GroupCallEngine/Overlay | PulseGroupCallEngine/GroupCallView | Indexed + call exclusion references; no fresh end-to-end group-call review in this pass |
| Events/RSVP/check-in | MirrorRoomBoard | Wave7Surfaces | Handlers indexed/read in surrounding code; delayed mutation versus poll, duplicate submits and timezone transitions pending |
| Kanban/tasks | MirrorRoomBoard | Wave7Surfaces | Mutation entry points sampled; concurrent moves/reorder/delete and linked-message ownership pending |
| Shared whiteboard | MirrorRoomBoard | Wave7Surfaces, PulseWhiteboardDraft | Swift queue/clear targeted. Android failure retention and reset/write ordering remain open |
| Games/tournaments/leaderboards | MirrorRoomCards/Live/GroupManager | Wave7Surfaces, game model helpers | Indexed; no complete game-state/reconnect/finish replay review |
| Packets/gifts/polls/topics/reminders/scheduled sends | MirrorRoomCards/Tools/Actions, reminder notifier | Wave7PacketDetail, PollTopicSurfaces, MessagingSurfaces, reminder helpers | Prior scheduled-send fixes are narrow. Claim/expiry/deep-link/timezone and duplicate-action behavior pending |
| Hub wallet/check-in/transfer/swap | MirrorHub; alternate feature-hub | HubView | Targeted above; ambiguous accepted payment responses, market purchasing, backend idempotency pending |
| Hub catalog/install/community | HubCatalog, MirrorHubAppPage/Hub | HubView/catalog resources | My Apps truncation targeted. Install/uninstall concurrency and community navigation/membership pending |
| Saved/search/bookmarks | MirrorProfileExtras/RoomActions/SubPages | SavedLibraryView/Chats search | Swift offline saved query targeted. Search supersession, stale saved-toggle semantics and original-message deletion pending |
| Contacts/user discovery/profile links | MirrorContacts, feature-calls ContactsDirectory | ContactsView/UserPage/AddContact/BlockedList | Android DM navigation targeted. Swift openDM currently creates/notifies inbox but does not navigate; navigation contract still needs repair/review |
| Profile/avatar/cover/stats | MirrorProfile/Extras | ProfileView/ProfileEditView/AvatarCropView | Swift cover targeted. Avatar crop/upload, edit intent, statistics errors, concurrent profile fields pending |
| Groups/channels/members/invites/moderation | MirrorGroupManager/RoomInfoPage/Extras | GroupInfoView/RoomInfoSheet, ChannelsView, Wave6Surfaces | Android metadata/action scopes sampled. Role changes, removed membership, owner handoff, invite revocation pending |
| Automations/webhooks/integrations | MirrorGroupManager/RoomTools | RoomIntegrationsSurfaces | Action handlers sampled only. Secret copy/delete, create retry, permission loss and bot-trigger ordering pending |
| Settings/privacy/notifications/themes/data recovery | MirrorSettings; feature-settings | SettingsView, prefs, PushCenterView | App-lock race + prior recovery targeted. Preference ordering/rollback, quiet-hour rollover, notification denial, destructive data actions pending |
| Storage/transport/protocol/offline sync | data/core/domain/protocol | Core/Storage, Networking, model DTOs | Prior message/outbox work + saved query targeted. No universal cancellation, ownership, deletion or membership reconciliation guarantee |
| Visual/accessibility/FX/shared UI | ui, MirrorPrimitives/FX/NavDockStyles | Core/FX, support/theme/motion, NavDockStyles | Indexed. TalkBack/VoiceOver, dynamic text, reduced motion, keyboard/IME, RTL and density acceptance pending |
| Identity/onboarding/recovery | SessionViewModel, feature-settings onboarding | Features/Identity, session/prefs | Sign-in/recovery implementation intentionally deferred by user; not marked complete |

## Confirmed open blockers and hypotheses — keep them distinct

### Source-confirmed open defects / unsafe gaps

1. **P0 — active Android Voice/Stage sheets are signaling-only.** Their PTT handlers
   change UI state and emit voice:ptt, but neither captures PCM nor consumes
   voice:chunk for playback. The separate engine/overlay is not wired into these
   entry points. Repair needs one actual membership/audio owner, not another
   independent UI timer or green “speaking” ring.
2. **P0 — iOS Stage-only join does not establish the local audio seat.**
   `joinStage` emits voice:join, but never calls voice.beginJoin/sets voice.conversationId
   or starts the audio engine. Incoming voice chunks are gated on that missing ID.
   Audience playback and promoted-speaker capture must be repaired together with
   plain-voice/stage membership arbitration. Permission/teardown repairs above do
   not fix this route.
3. **P0 — cross-feature audio ownership is not enforced universally.** iOS direct
   and group calls exclude each other, but the voice-room audio wrapper assumes
   it cannot overlap a call without an equivalent cross-owner gate. Recorder,
   room, call and media-player transitions need one explicit arbitration policy.
4. **P1 — Android whiteboard drops an unsynced stroke on transport failure.**
   `MirrorWhiteboardSheet.commitStroke` removes it; detached IO jobs also race
   reset/clear and poll. Swift fixes are not Android parity.
5. **P1 — board reset vs in-flight server POST has no end-to-end epoch contract.**
   Local generation guards prevent a late ACK deleting new drafts; they cannot
   stop an already-sent old POST landing after a server clear. Swift draft keys
   are conversation-only, and lost ACK retry can duplicate without server IDs.
6. **P1 — settings/group metadata still have asynchronous ordering gaps.** Android
   `savePrefs` uses detached IO jobs without a serialized preference revision;
   `MirrorGroupManager.refresh/act` mutate Compose state in IO and can overlap
   polling/actions. Their presence is source-confirmed; precise user-visible
   reorder reproductions and correct server contracts still need tests.
7. Existing open items remain: staged media/durable media/process death, hard
   deletion/membership reconciliation, universal ownership fencing, Room11 schema
   export, physical audio/OS acceptance, and trusted signing. This pass does not
   supersede those limitations.

### Still investigation, not established defects

- Whether space drag ACKs/reconnect overwrite a newer local target under every
  ordering; whether every game end/reward is server-idempotent.
- Whether the visible channel/member role state consistently follows server
  revocation across all caches and open sheets.
- Whether reminders/calendar boundaries remain correct through timezone/DST
  changes, OS notification denial, app lock and process death.
- Whether optimistic marketplace/app-connect actions recover from lost ACKs
  without a duplicate economic or membership operation.

## Checks actually executed locally

- `python3 tests/native/test_outbox_sql_contract.py`: **4/4 passed** (prior Kotlin outbox SQL contracts).
- `python3 tests/native/test_saved_library_sql_contract.py`: **2/2 passed** (actual Swift SELECT, minimal SQLite fixture; not GRDB schema migration verification).
- `node --experimental-transform-types --test tests/server/*.test.mjs`: **10/10 passed**, approximately 11.1s. Backend messaging tests do not validate these new UI/audio paths.
- Tree-sitter Kotlin/Swift syntax scan of changed native sources: no parse errors;
  this is **not** a type-check, native build, or execution test.
- `git diff --check`: passed.

The preceding iOS test run for `511a22e` exposed six outbox test methods placed
inside the fixture enum instead of `PulseOutboxTests` (two could not access the
private sender/engine helpers). They are moved into the XCTestCase in this pass;
this repairs both test compilation and discovery, not production behavior.
The earlier Android run and iOS archive succeeded, but do not verify this patch.

New native regressions: Kotlin story pause composition (2), call permission gate
(1); Swift story pause composition (2), whiteboard ACK/reset (3), saved-body
rehydration (1), voice permission ownership against fake audio (2), outgoing call
camera-prompt cancellation against fake media (1). **12 new test methods are
source additions, not locally executed passes.** No local Android/Xcode toolchain
or physical-device certification is claimed.

## Next repair order

1. Wire one active Voice/Stage audio owner on Android and repair Stage-only audio
   on iOS; include role demotion, cross-call exclusion, permission denial, same-room
   rejoin and surface close tests before treating either as usable audio.
2. Android collaborative write ownership/retention and backend board epochs;
   event/kanban/game/packet mutation ordering and duplicate acknowledgement paths.
3. Profile/settings/group/channel/Hub remaining mutation queues and cache fences;
   continue following each entry point in the inventory rather than assuming
   alternate implementations are the live route.
4. Native compiler/test results and device acceptance are separate gates, not
   substitutes for this investigation.

## Platform references consulted

- Apple AVAudioNode install/remove tap contract:
  [1](https://developer.apple.com/documentation/avfaudio/avaudionode/installtap(onbus:buffersize:format:block:)),
  [2](https://developer.apple.com/documentation/avfaudio/avaudionode/1388717-removetap).
- Apple forum explanation of an already-installed tap causing restart failure:
  [5](https://developer.apple.com/forums/thread/51935).
- Earlier official Android lifecycle and foreground-service references remain in
  `NATIVE-BEHAVIOR-AUDIT-2026-10-19.md` and the session's prior remediation docs.


## Live audio / spatial-presence continuation (2026-10-19)

Baseline for this continuation: `0f952d3`. This is a targeted transition review,
not full coverage of every native tree. No authentication/recovery work was added.

### Source repairs and behavioral boundaries

- **iOS stage listener:** starts output-only audio without requesting microphone
  permission. Promotion offers explicit microphone enablement; demotion closes
  PTT, removes capture, invalidates pending permission, and returns to listening.
  Independent voice membership survives stage exit. Voice exit preserves an
  active stage listener seat. Demotion discards PCM tails without restarting the
  room sequence (otherwise peers discard resumed speech as stale packets).
- **Audio configuration ownership:** token exclusion between iOS room audio,
  calls and voice-note recorder; between Android internal room and call routers.
  Failed acquisition is checked before call media construction. Recorder restores
  the prior session on failure/stop/cancel. This is NOT universal media arbitration:
  Android recorder and note players are not covered, nor is OS interruption policy.
- **Android routes/focus acquisition:** minSdk is 21. Use legacy focus below 26,
  require a granted initial request, and choose the receiver rather than speaker
  for speaker-off on API 31+. The current focus-loss callback is still empty;
  transient/permanent focus loss, Bluetooth restoration, and device switching
  remain open acceptance/implementation work.
- **Permission and callback ownership:** Swift group-call preparation now has an
  attempt generation, preventing an old camera/mic result from joining a different
  attempt. CallKit activation no longer acquires audio from an unversioned late callback. Room
  caption responses are fenced by audio lease, identity/session, room and caption
  toggle generation; an old completion cannot clear a successor's busy flag.
  Swift tap callbacks retain their converter and owner generation, rechecking
  the gate after conversion. Android raw capture freezes its callbacks before the read coroutine starts,
  and caption accumulation is dispatched to the main owner. Incoming Android PCM
  must match an owned room and cannot be a self echo.
- **Space:** Swift assigns the conversation identity before opening and clears it
  on exit; late state is ignored. Both implementations retain the final throttled
  drag target and schedule a cancellable trailing emission. Android rejects wrong
  room/post-leave connection callbacks. Local timing uses monotonic clocks where
  changed; this does not prove offline synchronization or server ordering.

### Checks for this continuation

- Added **17 regression methods**: Swift listener/mic-role/seat ownership, late
  Space state, token exclusion, group permission rejoin, trailing movement and
  sequence preservation, plus four voice-note player refusal/switch tests;
  Android token/routing, event filtering and Space trailing
  movement/late connection tests. Existing audio test fake implements the extended
  playback/demotion protocol. Native execution results are recorded separately.
- Changed/untracked Kotlin and Swift parsed with tree-sitter; no syntax errors.
  This does NOT check types, platform availability, actor isolation or hardware.
- Local saved-library/outbox SQL contracts: **6/6 passed**. These are unrelated
  regression checks, not evidence that audio works. `git diff --check` passed.
- Physical-device capture/playback, route changes, permission prompts, interruptions,
  foreground/background and multi-client hearing/speaking remain **not verified**.

### Confirmed remaining hazards — do not mount the Android engine yet

The active Android Mirror Voice/Stage sheets still bypass the internal engine.
Inside that engine, stage-only outgoing PTT/caption state, role demotion, independent
voice-seat preservation on stage exit, delayed playback recreation and AudioRecord
release/read races still need repair. Stage focus refusal still needs visible error
presentation. Fixing filters/router code does not deliver audio to the active UI.
Swift note playback now checks preparation/play/resume/replay results, stops an
old player before constructing its successor, and halts inactive tickers. Chat note
downloads are cancelled/fenced on replacement or room exit, including stale error
toasts and session changes. The four fake-player tests do not exercise the async
download path or actual media decoding. Note players are not covered by the audio
configuration ownership gate, and full interruption recovery is not implemented. The shared call-session token
still relies on direct/group mutual exclusion, not independent per-call leases.
Caption response fences were inspected but have no delayed HTTP-response regression
fixture in this continuation. These gaps remain open rather than hidden by build success.

Reference used for focus acquisition/API compatibility (and the still-open loss policy):
https://developer.android.com/media/optimize/audio-focus


## Active Android live-audio ownership continuation (2026-10-19)

Baseline: `0f9f27e`. This section supersedes the earlier Android integration/open
raw-audio hazards where explicitly described below; it does not close the other
feature-tree findings or replace physical-device acceptance.

### Source changes

- Actual `MainActivity → MirrorRoot → MirrorRoom` Voice/Stage/Space entry points
  now open one session-owned `VoiceRoomsOverlay`. Legacy signaling-only composables
  are compiler-deprecated at ERROR level to prevent accidentally reviving that path.
  A closed-surface live pill and notification Leave action expose ongoing ownership.
- Shared voice/stage membership has a pure `RoomSeatOwnership` policy. Every stage
  participant has a real receive seat; stage-only PTT/captions have a conversation ID.
  Leaving one owner preserves the other's seat. Demotion stops PTT even when the
  voice surface is open; promotion does not silently re-enable a microphone.
  Permission attempts are one-shot, invalidated on leave/role/surface changes.
- One push-to-talk gesture implementation replaces conflicting overloads. Down
  starts actual capture; a short completed tap latches, a held release stops, and
  cancellation never becomes a request to latch. All durations are monotonic.
- `RoomAudioRuntime` owns a separate room generation and a serialized mic run.
  Stop can interrupt I/O, but ONLY the run's finally releases its device. Queued
  cancelled work cannot construct a successor's device. Each peer gets a bounded,
  ordered writer; peer removal cancels its preroll and releases after write exits.
  Partial writes are completed, failed/empty reads are bounded, and I/O failures
  surface errors rather than spinning or pretending audio is active. Jitter uses
  arrival times rather than adding another delay after every blocking write.
- Hardware adapter uses an API-21-compatible AudioTrack constructor. Foreground
  listening uses mediaPlayback; microphone service type is enabled only after
  explicit permission. Service startup/destruction failures tear down matching
  ownership. Notification actions carry a per-room token, including distinct
  PendingIntent identity, so an old notification cannot stop a new room.
- Room and call focus-loss callbacks conservatively END interrupted sessions;
  there is no automatic microphone restart on focus regain. Queued loss callbacks
  are scoped to their originating focus acquisition.
- Android visible note bubbles now use the same singleton player as feature-chat.
  Player preparation is asynchronous on its owning looper; attempts have a generation
  (not merely a message-ID check), account-bound downloads, completion/error handling,
  and truthful start/resume state. Room exit/background stops its matching note.
  Both native Android recorders and the shared player participate in the audio lease.
  Switching note targets stops the old player. Calls/rooms/recording cannot silently
  compete for configuration while a note owns it. Alternate recorder elapsed time
  is captured before resetting its clock; failed finalization discards rather than uploads.
- Shell cleanup distinguishes configuration destruction from real session teardown;
  live overlay visibility disables underlying chat read eligibility.

### Regression evidence / remaining gates

- Added **17 Android regression methods**: 8 fake-device runtime tests, 6 shared-seat/
  permission tests, 3 push-to-talk intent tests. These test the production runtime and
  policies, not MediaRecorder/MediaPlayer codecs, foreground services or radio routes.
- Changed-source Kotlin/Swift syntax checks and `git diff --check` passed. Existing
  SQL regression checks passed 6/6. Native compiler/test execution is a separate gate.
- Previous `0f9f27e` iOS archive succeeded. Its unit run exposed two test-contract
  issues: a Space fixture never joined despite testing joined-state reconciliation,
  and an outbox assertion compared JSON key order rather than decoded content.
  Both fixtures/assertions are corrected here without removing their data assertions.
- Device checks still required: API21/23/26/30/34+, permission denial/revocation,
  rotation while capturing, notification exit, speaker/Bluetooth/headset switches,
  incoming cellular calls, foreground-service limits, background capture and two-client
  audible delivery. Full interruption *resumption* is intentionally not implemented;
  this pass uses safe termination. Full Android note download/codec lifecycle lacks a
  fake-player integration fixture. iOS interruptions and note-playback lease participation
  remain open. Broader collaboration/Hub/storage/navigation findings remain open too.

Platform contracts consulted: Android AudioRecord stop/read/release and AudioTrack
write return values, API availability, and pause/flush for immediate stream stop:
https://developer.android.com/reference/kotlin/android/media/AudioRecord
https://developer.android.com/reference/android/media/AudioTrack


### iOS gesture/surface continuation

- Removed the PTT activation timer rather than merely adding another timer guard.
  Press starts capture immediately; release can stop or preserve an existing latch,
  never restart a revoked transmission. Gesture cancellation, dragging outside,
  surface disappearance, backgrounding and loss of eligibility stop the press.
  Durations use uptime. SwiftUI recognizer behavior still needs device/UI acceptance.
- Microphone permission retries have their own request generation. Closing or switching
  the surface invalidates a pending retry while preserving the listener seat, and
  a stale retry cannot clear a newer request's pending indicator. Closed surfaces
  cannot initiate PTT through a late UI callback. Closing an initial joining attempt
  cancels that attempt rather than opening capture after its UI has gone away.
- Switching Space rooms now leaves the old room first and resets old players/identity.
- Added **6 Swift regression methods** (three PTT policy, one closed-surface delayed
  permission, one Space switch, one unrelated surface surviving stage end). With
  the 17 Android runtime/seat/PTT tests and 3 passive-preemption tests this is
  **26 new native regression methods**, not a declaration of their execution result.
- Native compilation caught incorrect Java UUID construction in the new focus code;
  corrected to `UUID.randomUUID()` in both routers. No prior failed build is counted
  as a pass, and syntax parsing alone is not compilation proof.


### Validation and final ownership edges in this continuation

- At `14e8606`, the full Android JVM test step **passed**, including the 17 new
  fake-device/seat/PTT tests and compilation of the active app route. iOS archive
  at that revision also **passed**; its unit run was still in progress at this check.
- The API34 instrumented run at that revision **failed** at its emulator test step.
  Available annotations supplied only an exit code; signed blob logs could not be
  retrieved from this environment. The cause is not classified as infrastructure or
  application without evidence. Existing diagnostic extraction now also covers
  instrumented XML/Gradle failures so subsequent runs expose actionable assertions.
- Passive Android voice notes now cooperatively stop before an exclusive call,
  room or recorder acquires audio; they do not make an incoming call fail just
  because a note was playing. The gate rechecks ownership after the stop callback
  and never invokes that callback under its lock. Live sessions/recording do not
  implicitly preempt one another. Three tests cover yielding, exclusive exclusion,
  and a competing acquisition during the callback.
- A Swift stage end/exit invalidates pending microphone enablement and returns a
  surviving independent voice seat to listening. Ending a stage no longer dismisses
  an unrelated Space/Voice surface. The final changes after `14e8606` need their own
  native results; the earlier pass is not attributed to an untested successor.

## End-to-end collaboration repair (2026-10-19, after d5263ab)

This is a deeper trace of **one** additional tree, not a whole-product sign-off:
`MirrorRoom → MirrorWhiteboardSheet → bound repository → DataStore/API → SQLite`
and `ChatRoomView → Wave7WhiteboardSheet → PulseWhiteboardSession → draft store/API`.

Confirmed defects: the active Android canvas never called the existing draft store;
failed/uncertain POSTs deleted visible ink; geometry-based dedupe could mistake another
person's drawing for delivery; Swift delta append could not observe Undo; server writes
had no replay identity or reset precondition; Clear returned a timestamp different from
its stored marker. Brush closures and cancelled Android gestures could commit stale ink.

Source repairs:
- Operation UUID + observed reset epoch travel from draw-time persistence through POST.
  Transactional receipts survive Undo/Clear; an unseen old-epoch operation conflicts.
  Replaying a delivered-then-deleted operation acknowledges it without resurrection.
- Clear allocates a strictly increasing epoch inside the same transaction as deletion.
  Snapshot rows and epoch are read together. Undo selection/deletion is transactional.
- Both active native surfaces use screen-owned coordinators, authoritative snapshots,
  exact-ID reconciliation, ACK validation and explicit error states. Drawing persistence
  does not wait for an in-flight HTTP request. Cancelled/closed-screen ACKs cannot clear
  successor drafts. Android DataStore updates merge atomically across screen instances;
  Swift rereads the MainActor-owned draft store before append/ACK reconciliation.
- Storage is scoped by captured viewer + conversation, rather than a mutable later
  account identity. Old unscoped drafts are left untouched, not attributed to a new user.
  Unknown-epoch legacy entries in the scoped store remain retained, not silently replayed.
- New clients require `operationsVersion: 1` before upload: otherwise an old backend
  ignoring operation IDs could multiply drawings on automatic retry. Responses exceeding
  the existing 4,000-stroke cap explicitly report `complete: false`; native clients refuse
  to adopt the partial board. Pagination/large-board editing remains open.
- Swift long strokes are normalized/downsampled to at most 500 points, preserving both
  endpoints; malformed coordinates are excluded. Stationary taps produce dots. Android
  brush/surface transitions recreate the gesture handler, and cancellation discards it.

Deployment order: back up SQLite, apply
`prisma/patches/20261019_whiteboard_operations.sql`, deploy the endpoint/helper, then
ship native clients. This patch has NOT been applied to a production server here.
Legacy clients remain accepted but do not gain replay/reset guarantees. This change
also does not make destructive Undo/Clear requests themselves idempotent.

Evidence: **19/19 server transaction tests passed locally**, including 9 new whiteboard
cases (lost ACK, intentional identical strokes, sender isolation, conflicting key reuse,
Undo/Clear tombstones, reset fencing, rollback, concurrency, monotonic markers and
legacy compatibility). **10 Android coordinator tests and 6 Swift tests authored**;
native execution for this change is pending. Syntax checks are not compilation.
NSUserDefaults power-loss durability, gesture ordering, multi-window UI and real
network/device acceptance remain unverified. Full snapshots trade delta efficiency for
correct deletion reconciliation until a versioned paginated/tombstone stream exists.

Earlier audio follow-up d5263ab: iOS unit tests and archive subsequently passed; Android
JVM tests passed and emulator jobs advanced to release smoke. These results are NOT
attributed to the new collaboration changes.

Transaction reference consulted: https://www.prisma.io/docs/orm/v6/prisma-client/queries/transactions

### Reminder/event consequences and notification delivery follow-up

Additional traced path: native reminder action → HTTP verdict → local alarm state,
and iOS foreground due polling → OS enqueue → server resolve.

- Swift event deletion and reminder cancellation previously announced success before
  HTTP completed. Reminder cancellation also removed the offline alarm on failure.
  Per-resource single-flight confirmation now orders consequences after success;
  Resolve and Delete share the same reminder gate. Failed/cancelled operations cannot
  claim success. A repeated DELETE returning not-found confirms absence safely.
- Swift reminder creation now returns its verdict to the form. A failure keeps input;
  a late success only clears the submitted, unchanged fields, and does not dismiss a
  newly reopened sheet. Loading failure is no longer rendered as an empty reminder list.
- iOS calendar triggers previously dropped seconds and used a floating local timezone.
  They now retain seconds and an explicit UTC calendar/timezone for the chosen instant.
- iOS foreground polling previously marked reminders fired even when notification
  permission was denied, and repeated sound after failed resolve every 30 seconds.
  OS enqueue returns acceptance; only accepted/already-delivered notifications permit
  resolve. A viewer-scoped receipt suppresses repeat enqueue while resolve retries.
  Each item and late OS callback is fenced by session generation. Acceptance does not
  prove the user saw a banner; OS scheduling/Focus still need device acceptance.
- Active Android reminder actions are screen-owned/main-thread and use the captured
  repository; successful Resolve/Delete cancels the queued worker and visible alert.
  Countdown now advances while the sheet remains open. HTTP cancellation is not treated
  as an ordinary refresh result.
- Seven Swift regressions cover deferred/failed/cancelled consequences, resource gates,
  exact schedule instant, notification failure/retry, and owner-scoped receipt reuse.
  These tests are authored, not yet confirmed executed on this revision.

Compiler checks on ecbc55d exposed two issues in the new board code: Kotlin cannot
smart-cast a public nullable property across modules; Swift Canvas cannot call an
actor-isolated pure static helper. Fixed by a captured local value and an explicitly
nonisolated normalization function, respectively. Neither failed run is a pass.

Still open: Android due-loop/worker delivery deduplication and permission-denied resolve
semantics, system permission prompts and schedule recovery on real devices, iOS legacy
unscoped drafts, large-board pagination, and other collaboration/Hub trees. Reminder
creation itself has no server operation ID: an uncertain POST is not automatically
retried, but a deliberate retry can still duplicate it. No whole-reminder certification.

### Drawing-boundary regression and test synchronization

A further boundary check found Android's old downsampler could turn exactly 1,000
points into **501**, causing rejection of the finished drawing. The coordinator now
normalizes finite coordinate pairs, clamps them, supports dots, and samples exactly
500 points including endpoints. Two tests cover 501/999/1000/1001/10000 inputs and
malformed/stationary points. This brings this turn's authored native regressions to
**25 (12 Android + 13 Swift)**, in addition to 9 new executed server tests.
Swift async fixtures now wait for published ready/error state rather than assuming
that an HTTP callback expectation also means the coordinator has adopted its result.
Native results for successor commits remain pending. At 370e41e, server contracts
and web checks passed; native build/test jobs were still in progress.

## Reminder delivery/storage/transport continuation — 2026-10-20

Previous `f725d44` is now natively verified: Android JVM, API30/API34 instrumented
jobs (including release smoke), iOS unit tests and archive all passed. Those passes
apply to the previous revision, not the following source changes.

### Root causes repaired in source

1. Worker, foreground poll and custom boot receiver independently notified/ACKed.
   They could ring twice, resolve a denied notification, and resurrect cancelled
   cached rows after boot. A process-wide `ReminderDeliveryGate` now arbitrates
   worker/foreground delivery and confirmed cancellation using durable ARMED,
   ACCEPTED and FINISHED records. A cancellation tombstone precedes OS cancellation;
   stale workers and snapshots cannot re-arm it. Accepted receipts survive restart
   and let server acknowledgement retry without repeating sound.
2. Android notification acceptance checks runtime permission, app-wide notification
   enablement and the selected channel. Blocked delivery is retryable, not fired.
   Full string notification tags and scoped PendingIntent data prevent hash-collision
   replacement. Work carries its captured account/gateway, not a guessed current user.
3. Repository-level reminder consequences cover all UI entry points, including slash
   creation. Future rows are scheduled at create/refresh; manual Resolve/Delete finishes
   local ownership only after acknowledgement. Background ACK is separate so it cannot
   cancel the notification it just posted. The custom boot-cache replay was removed:
   WorkManager already persists work through reboot. KEEP avoids cancelling an active
   worker during a refresh. Unowned legacy work is not attributed to a new account;
   the next online foreground refresh schedules scoped replacements.
4. SharedPreferences mutates memory even if commit fails. The adapter exposes only
   confirmed writes to the delivery policy and forces subsequent persistence attempts.
   A failed receipt write after OS acceptance does not replay sound in the same process.
5. Swift's previous boolean receipt did not guard overlapping async enqueues or a
   cancelled reminder arriving in an already-in-flight due response. Single-flight
   tokens and terminal receipts now fence those races, retract late OS acceptance,
   and scope both receipts and notification identifiers by gateway plus viewer.
6. The reminders endpoint silently truncated at 100 rows. Timestamp+ID keyset paging
   now traverses tied timestamps and deleted boundaries, and binds cursors to owner
   and due/full filter. Both native transports collect all pages, reject looping cursors
   and fail rather than treating a later-page failure as a complete list.
7. Server Resolve was a read-then-write race and repeated DELETE could fail after a
   lost ACK. Transactional Resolve preserves the original firedAt; owner-checked
   Delete safely acknowledges an already-absent row.

### Evidence and boundaries

- Executed locally: **26/26 server tests**, including **7 new reminder contracts**;
  existing SQL contracts **6/6**. Syntax parsing/diff checks passed.
- Authored **17 native regressions**: Kotlin gate 8, Kotlin HTTP pagination 3,
  Swift URLSession pagination 3, Swift async delivery/cancellation/gateway scope 3.
  Native execution for these changes is pending, not inferred from f725d44.
- OS enqueue acceptance does not prove a person saw the reminder. OS notification
  posting and durable storage cannot be committed atomically: process death in that
  small gap can still repeat an alert. WorkManager is persistent, not an exact alarm;
  Doze, permission changes and OEM scheduling require hardware acceptance.
- Remote deletion on another device while this device is offline cannot revoke an
  already-scheduled local job without receiving that deletion. Global deletion sync,
  iOS logout-time cleanup of already-scheduled alerts, and receipt retention limits
  remain open. No whole-app or whole-reminder completion claim.

References: Android persistent-work documentation and notification channel importance:
https://developer.android.com/develop/background-work/background-tasks/persistent
https://developer.android.com/develop/ui/compose/notifications/channels

### Wallet rejection and repeated-tap continuation

A separate, source-confirmed failure crossed the server/native boundary: transfer
and swap transaction catch blocks returned an HTTP 402 Response **as the wallet
value**, then the outer handler JSON-wrapped it with HTTP 200. Tolerant native DTOs
could adopt the empty object and display success after a rolled-back transaction.

- Extracted transactional wallet contracts; errors are now returned at the HTTP
  boundary, never serialized as a wallet. Conditional debits prevent overdraft;
  recipient credits and swap legs enforce the Prisma/native integer limit. Ledger
  legs, log events and balances commit together or roll back together. GET exchange
  rates and POST calculations share constants.
- Both native transports validate successful monetary acknowledgements: matching
  owner, actual coin/gem fields, valid balance range, and a real transfer recipient.
  An unconfirmed HTTP-200 payload is an error with ledger-review guidance, not a
  zero balance or success toast.
- Swift transfer/swap share a synchronous single-flight gate taken before Task
  creation. Session generation is checked before sending and after awaiting; late
  results cannot toast success or dismiss another session's sheet. Active controls
  are disabled while the mutation is in flight. Android already had its visible
  synchronous busy/input capture gates from the earlier pass.
- **8 new SQLite wallet regressions passed**: conservation/two ledger legs, correct
  HTTP rejection, insufficient-balance rollback, recipient overflow rollback,
  safe swap arithmetic/ranges, concurrent spend bounds and unexpected error propagation.
  The full local server suite is now **34/34 passed**.
- **8 new native regressions authored**: Kotlin monetary ACK 2, Swift HTTP ACK 2,
  Swift real HubViewModel gate/session ordering 4. Total authored this turn:
  **25 native tests** plus **15 executed server tests**. Latest native execution is
  still pending; c31adda's iOS archive passed and its server/web checks passed.

Gap identified at the first wallet checkpoint (addressed in the continuation below): there was no durable client operation
ID for transfer/swap. A manual retry after an ambiguous lost ACK could duplicate a
completed operation. No automatic financial retry was added, and a tap gate is
not represented as a solution to that separate delivery problem. Real concurrency,
UI cancellation and ledger review still require native/device acceptance.

### Wallet lost-ACK continuation (supersedes the gap immediately above)

The tap gate was not treated as a substitute for monetary delivery correctness.
Both production native paths now persist an operation ID **before** sending and
reuse it for the same unresolved canonical instructions. A confirmed matching
receipt retires the pending ID; a later deliberate payment gets a fresh ID.

- Android uses the already account/gateway-scoped DataStore, atomic edit and
  compare-ID retirement. iOS uses an actor-serialized, atomic-file journal with
  canonical gateway + owner + instruction keys. Storage failures stop the send;
  corrupt IDs fail closed. Failures/cancellation retain unresolved operations.
- Native requests use **new `/wallet/operations/{transfer,swap}` paths** and
  require the matching ID in the reply. There is no fallback to unsafe legacy
  routes on 404. These new endpoints require the existing native credential to
  match the debit owner, not merely any valid user token.
- A server receipt, both balance changes, both ledger entries and the log commit
  in the same SQLite transaction. Replay returns the committed result; changed
  instructions under the same ID produce 409. Receipt insertion failure rolls
  back the entire spend. There is no receipt expiry/cascade that frees old IDs.
- **39/39 server contracts passed**, including 13 wallet cases. New receipt
  regressions cover lost transfer/swap replies, instruction conflict and owner
  scope, receipt-write rollback, repeated additive migration and concurrent
  same-ID retry. **6/6 existing SQL contracts** also passed.
- Additional native tests: Android DataStore reopen/compare-retire and operation
  endpoint/ACK validation; Swift journal reopen/ownership, gateway normalization,
  and real URLSession request-ID reuse after a simulated lost response. Combined
  with the earlier gate/ACK tests: **13 new wallet native tests**; **30** with the
  reminder tests. Native execution of this newest revision is pending.

**Deployment gate:** apply the additive, rerunnable
`prisma/patches/20261020_wallet_operations.sql` to the gateway database before
shipping these native money paths; it is also represented in `schema.prisma`.
The patch was exercised in isolated SQLite fixtures, **not applied to production**.
Missing table/old gateway fails closed, not as a legacy non-idempotent transfer.

**Limits:** journal protection requires preserved app storage and the same
instructions; a different recipient/amount/note is a new explicit intent. Replays
use the original result; native screens fetch current balances separately. A
renamed/deleted recipient can prevent confirmation and require ledger review,
but cannot rebind the same key to a different debit. Journal/receipt retention
is intentionally unbounded pending an explicit reconciliation policy. This does
not retrofit legacy/web monetary requests, market purchases or daily check-in
with the new receipt protocol, nor resolve the deliberately deferred legacy
header-less authorization migration. No global economy/security certification
or whole-app completion is implied.

### Marketplace double-charge continuation

Tracing the other debit path found an independent race: listing availability was
read **outside** its transaction and the subsequent `sold` update was unconditional.
Two buyers could pass the same stale `open` check and both pay the seller.

- Moved the listing read and conditional `open → sold` claim into the transaction
  with buyer debit, bounded seller credit, both ledger legs and log. Insufficient
  funds or overflow rolls the listing claim back too.
- A sold listing's winning buyer can replay a purchase confirmation without a
  second charge; another buyer receives 409. This uses the immutable single-sale
  listing identity, not a new expiring retry key or another schema migration.
- Swift purchase now shares the synchronous monetary gate and session fence;
  both native clients require `ok: true` **and** a valid owner-bound wallet instead
  of displaying success for any decodable HTTP-200 body.
- Four SQLite purchase cases passed (same-buyer retry, failed debit rollback,
  seller overflow and competing buyers). Full server suite: **43/43 passed**.
  Two more native malformed-purchase-ACK tests authored; **32** native regressions
  since f725 (17 reminder, 15 monetary), newest execution pending.

The initial monetary revision **3dcb913** has now passed its Android JVM tests and
Swift unit tests; its iOS archive also passed. That is not execution evidence for
newer receipt/marketplace changes. Legacy header-less authorization migration and
check-in semantics remain outside this repair; marketplace transaction safety is
not a claim that the full economic or authorization system is complete.

Final monetary guard pass: Swift journal begin/retire now checks task cancellation
on entry to the serialized file operation, preserving the pending ID if cancellation
won the actor hop. Android transfer-field and transport normalization now use
`Locale.ROOT`; a Turkish device locale must not rewrite `@INCI` to a different
recipient handle. Added one regression for each (**34 authored native regressions
since f725**). These are targeted safeguards, not broad cancellation/locale claims.

## Daily reward and room-sheet continuation — 2026-10-20

Further action-to-storage tracing found another transaction race and independent
screen lifecycle/draft-loss failures, rather than treating the wallet receipts as
whole-app correctness.

- **Daily rewards:** eligibility was read before the transaction. Two devices
  could both see yesterday and both collect today's coins. Eligibility, bounded
  credit, streak, ledger and log now share one transaction with a conditional UTC
  day claim. Same-day retries retain the existing 409+wallet contract. Future
  timestamps cannot award another day during clock rollback; reward/ledger failure
  leaves the day unclaimed. Bonus/streak arithmetic is bounded.
- **Daily native confirmation:** both transports reject incomplete/inconsistent
  reward acknowledgements instead of synthesizing a reward celebration. Swift
  check-in uses the same synchronous monetary gate/session fence as the other
  economic actions. The alternate Android feature-hub now has its own shared
  synchronous gate, owner fence, and supersession checks for wallet loads; it had
  remained unguarded even though the mirror screen was already protected.
- **Android Events/Kanban:** removed seven detached IO coroutine launches. Jobs
  are composition-owned, room/owner keyed, and resume UI state on the main scope.
  Cancellation is rethrown even when a repository wrapped it inside Result.
  Pre-write/in-flight stale polls cannot restore old rows, overlapping writes
  defer reads, and finally blocks release mutation state. Late create ACKs only
  clear the exact submitted draft revision. Actions on the same event/card cannot
  race each other locally.
- **Swift Events/Kanban:** creation callbacks now return an awaited verdict rather
  than immediately discarding drafts. Failed requests retain form contents. A
  sheet-owned coordinator gates per-resource actions, invalidates stale reads,
  cancels work on disappearance, and rejects late completions after teardown.
  Captured API values and session/room view identities prevent rebinding old sheet
  closures. Mutations trigger a reconciliation read even while live polling is
  paused. Failed initial loads are no longer presented as confirmed empty rooms.

Evidence: **48/48 server contracts** (five new daily-reward cases) and **6/6 SQL
contracts** passed; native syntax parsing and diff checks passed. **13 new native
regressions authored:** Android refresh ordering 3, cancellation 2, actual alternate
HubViewModel gate 2, reward ACK 1; Swift room coordinator races/draft retention 4,
reward ACK 1. Native execution of this revision remains pending. Source inventory
is regenerated; no local native build artifacts were added.

Limits: cancellation cannot undo an already accepted remote mutation. Event/card
creation still lacks a durable retry ID; a deliberate retry after a lost create
response can duplicate a row. Room lists still use existing server page limits.
The new gates address local request ordering, not cross-device edit versioning or
OS-level foreground/background timing. Legacy authorization migration remains
separate, and prior wallet/whiteboard deployment patches remain unapplied to
production. No all-features or device acceptance claim is made.

### Red-packet claim, refund and bubble continuation

The payout parser silently **filtered** corrupt entries, shifting recipient
indices. The detail endpoint's refund parser converted corrupt JSON to an empty
array and still closed the packet, permanently consuming its settlement cursor
without returning the unclaimed money. These were separate from transfer/swap.

- Claim/refund contracts now validate the complete positive-integer slice array,
  count and original total, plus the existing payout count/sum against the claimed
  prefix. Inconsistent escrow fails closed for reconciliation; no shifted payouts
  and no silent zero-refund settlement.
- Existing per-user grab rows are replay receipts, checked before expiry/fullness.
  The same recipient can recover a lost acknowledgement after expiry/refund without
  being credited again. Membership checks still apply. New claims reject at the
  exact expiry boundary. Refund uses a conditional cursor claim and bounded credit;
  overflow rolls back and leaves settlement retryable.
- Both native transports reject impossible payout ACKs. Replay copy distinguishes
  an old receipt from a new award; the mirror does not fire new payout particles on
  a replay. Swift grab intent is synchronously gated per packet.
- Android packet composer/bubble now use room/account-keyed, composition-owned
  scopes rather than detached IO tasks. Composer fields are frozen while sending,
  and the actual count/note/amount are captured at the tap. Bubble fetches respect
  mutation ordering, retry an initial failure, and advance the displayed claimed
  count after later participants collect (the old local override froze it forever).
  Removed the redundant detached post-send refresh: repository confirmation already
  caches the carrier message and schedules its owned conversation refresh.

Evidence: **59/59 server contracts passed**, including **11 new packet cases**:
corruption, replay after expiry/exhaustion, overflow rollback, authorization,
concurrency, refund conservation and claim/refund races. **17 native regressions
were authored in this continuation** (13 reward/room, 4 packet transport/DTO).
Native syntax/diff checks passed. The preceding 2f1d607 iOS archive passed; latest
native test/build execution is still pending and is not inferred from that archive.

Limits: packet **creation** still lacks durable lost-ACK replay; no automatic
create retry was added. Corrupt historic payouts require explicit reconciliation,
not guessed repairs. Refund is still lazy (triggered by detail reads); this is not
an expiry scheduler. A pending refund error is surfaced rather than silently
marking money refunded. Games/tournaments still have separate detached-action paths
that are not represented as fixed by the packet/room changes.

Verification follow-up: 2f1d607 Android JVM compilation exposed a missing
`kotlinx-coroutines-test` dependency in the app module for the new cancellation
test. The test only needs immediate suspend invocation, so it now uses the
existing core `runBlocking` API rather than adding a test scheduler dependency.
This was a test compilation failure, not a successful native regression run;
rerun verification is required.

### Game verdicts and native game/tournament flow continuation

This continuation fixes runtime behavior, not just expectations in tests:

- The winning board used to commit before best-effort XP/standings/log writes.
  `game-contract.ts` makes the board, bounded XP awards, both standings and audit
  event one transaction. A score/log failure leaves the move uncommitted and
  retryable. An immutable occupied square belonging to the same player is the
  move receipt, including after an opponent response or game end; replay never
  awards again. No new schema is required for these operations.
- Moves reject coerced cells, malformed boards, impossible turn counts and play
  before the O seat exists. Seat claims replay for the same member. Season
  join/finish are transactional: an existing join remains recoverable after
  closure, and repeated finish does not rewrite the timestamp or duplicate logs.
  Database contention gets a recoverable conflict rather than a raw Prisma error.
- Both Android presentations and Swift now retain confirmed boards on read failure,
  fetch before polling, retry failed initial loads, accept actual mutation replies,
  fence pre-mutation reads and gate actions synchronously. Polling pauses outside
  the foreground; requests belong to the card/sheet rather than detached tasks.
  The alternate Android game card previously **never loaded at all** when passed
  its actual `initial = null` argument. Its supposedly suspending rematch callback
  also returned immediately because the ViewModel launched another job inside it.
- Tournament cards refresh standings and reconcile after join/finish rather than
  displaying stale data. Unloaded is no longer labelled finished. Android list
  responses optionally include viewer membership, so joining produces a persistent
  Joined state, not another identical Join button. Failed refreshes retain prior
  results rather than inventing an empty season list.
- Game/season composers and rematch requests are owned and gated; failures retain
  drafts, cancellation does not publish late success, and malformed successful HTTP
  replies cannot clear a form or confirm someone else's seat/membership. Board
  responses must identify the requested match and acknowledge the requested square.
  Reopening an already-won mirror card no longer fires fresh win confetti.

Verification: production transaction helper passes isolated TypeScript checking;
**68/68 server contracts pass**, including rollback halfway through scoring,
second-player overflow, winning-move replay/races, corrupt boards, seat replay and
season lifecycle. Eleven native regression methods added (four transport checks
per platform, three alternate-card cancellation checks), plus corrected both
shared turn-gate expectations. Kotlin/Swift syntax and diff checks pass; native
execution for this revision remains pending. Earlier 5749fde iOS workflow passed;
its Android workflow was still running at retrieval. Counts are not device-flow
acceptance or proof that the rest of this large application is healthy.

Limits: creation/rematch still has no durable lost-ACK creation key. A request may
commit before cancellation/network loss; users are told to inspect the chat before
retrying, and no automatic create replay is attempted. Previously finished games
with missing historical awards cannot be safely guessed/backfilled. The existing
latest-running-season scoring policy is retained. Finishing permission UI still
uses creator identity; complete admin-capability discovery is not added. Legacy
identity/read authorization remains unchanged; this does not claim secure caller
authentication. Other native feature trees and older room paths still need work.

Follow-up inspection: blank/missing game or tournament IDs in old/malformed
message payloads could still start endless invalid-URL polling in the alternate
renderer. Both shared payload parsers now reject blank IDs, and the alternate
Android cards show an unavailable-link state without starting a request. Mirror
already had this payload guard. 05c881e server and web workflows have passed;
its native workflows are not yet verified.

### Durable game/season creation and alternate Swift room continuation

Traced creation through composer/rematch → API → transaction → carrier → local
acceptance. Previously, a committed creation with a lost response had no identity
on retry and could create another match/season. Local busy flags did not solve it.

- Native game/rematch and tournament creation now persist an instruction-scoped
  UUID before networking. Android uses its existing gateway/account-bound DataStore
  with a separate creation namespace; Swift uses a separate journal directory and
  explicit gateway/owner/room/instruction keys. Unknown failures/cancellation keep
  the ID. Old gateways are not silently retried through legacy creation endpoints.
- Server creation uses `MessageSendOperation` receipts and one transaction for the
  object, carrier, creator entry, read/archive updates and season log. A replay
  returns the original object even after its board/standings change or the invited
  opponent leaves. Changed instructions with the same key conflict. Replays do not
  mark-read/unarchive/notify again. Both carrier kinds obey room TTL. A failed
  realtime relay does not turn a committed creation into a failed creation response.
- Android caches the confirmed carrier before consuming the journal entry. Swift
  separates receiving an API response from accepting it into the owning screen;
  create/rematch callers acknowledge only after cancellation checks and carrier
  handoff. Receipt IDs and carrier→object references are validated. A stale ACK
  cannot retire a later operation. Recovery copy distinguishes the old creation
  from a newly created one.
- Removed/expired carriers return an explicit `creation_gone` receipt bound to the
  request ID. Only that verified terminal receipt releases the old local intent,
  allowing a **subsequent deliberate submission** to start a new object. Generic
  410s and mismatched IDs do not release it; server receipts remain as tombstones.
- New `/games/create` and `/tournaments/create` paths require a stable operation ID
  and bind the existing session credential to its owner. Legacy endpoints remain
  compatible; this is not an authentication migration of the entire application.
- Swift's alternate room had separate detached creation jobs that discarded the
  response. They now have owned synchronous gates, captured clients, cancellation
  checks, visible error state, carrier acceptance and correct journal retirement.
  Its failed refresh no longer wipes messages, and older reads cannot erase a
  newly accepted carrier. DM challenge shortcuts on both platforms refuse to
  silently create an open challenge when peer details have not loaded.

Deployment: requires the **existing** `20261019_message_send_operation.sql` receipt
patch (or equivalent schema deployment), not a new table. Missing receipt storage
fails before creation with an actionable 503. Neither schema nor main has been
changed in production. Creation receipts do not recover keys lost by uninstalling
or clearing local app data; notification relay still has no durable delivery queue.

Verification: **77/77 server contracts pass**, including nine new creation cases
(replay after board/season changes, concurrency, changed-intent conflicts, receipt
failure rollback, tombstones and missing schema). Isolated transaction-helper
TypeScript check and native syntax/diff checks pass. Nine native methods added
(four Android journal/transport, five Swift retry/ordering), not represented as
executed locally. Earlier a0ee538 iOS archive and tests passed; current native
revision execution is pending. Android a0ee538 was still running at retrieval.

Further source-confirmed work: the alternate Swift text/effect send path still
clears its draft before a `try?` send and does not use the established durable
outbox; its message list also lacks outbox reconciliation. The primary Swift
standalone effect sender has a separate temporary-message/delete-on-error path.
These are not fixed by creation receipts and remain next in the flow review.

### Android rich-payload preservation correction

Native compilation of 4f7d918 exposed a wrong `String` assumption in the new
receipt validator (`ChatMessageDto.payload` is actually `JsonElement?`). Tracing
that type revealed an older runtime defect in `ChatMessageDto.toDomain`: calling
`toString()` on a JSON-string payload double-encoded it; the local cache then
expected an object and discarded the carrier's fields. Added one object/string
normalizer for receipt validation and the wire→domain boundary. Game/season/packet
references and effect/sticker payloads now survive the subsequent cache mapping;
non-object values stay unusable. This does **not** reconstruct fields already
lost in old cached records: those require a successful server refresh. Added two
wire→domain→cache regression methods. 4f7d918's iOS device archive passed; Android
compilation failed on the above two expressions; corrected native execution is
pending rather than assumed successful.

### Swift text/effect delivery and alternate-room live reconciliation

- Replaced the alternate room's clear-draft → detached `try?` POST with a shared
  durable text enqueue operation. Text, effect, alias flag, quote and topic are
  committed with the optimistic message before the composer is cleared. An
  unavailable store leaves the composer intact. No separate sender or new retry
  identity is introduced: the existing account-owned outbox sends and reconciles
  the persisted operation. Empty armed effects now ask for text without disarming.
- The primary room's effect/slash-effect path also uses this operation instead of
  deleting the temporary message on an uncertain network result. Quote/topic/mask
  metadata now travel with the effect. Animation happens on outbox confirmation;
  local enqueue failure keeps the text (the effect chooser can be opened again).
- The alternate room rehydrates its cached timeline and pending bubbles on reopen,
  retains pending messages during refresh, swaps exact temporary IDs on delivery,
  and reconciles permanent rejections through the existing outbox recovery path.
  Queued messages display a clock, not success ticks. Confirmed messages say sent,
  not read without a peer read receipt.
- Added room-scoped live message/edit/reaction/deletion handling and foreground /
  reconnect refresh. Decodable events update immediately and invalidate older
  reads instead of requiring a full HTTP refresh per message. Partial events
  trigger authoritative refresh. Active-room ownership and the existing visible /
  unlocked read-receipt guard now apply to this presentation too.
- Alternate-room reactions now use owned, per-message mutation gates with failure
  feedback and follow-up reconciliation; queued local IDs are not sent as server
  reaction targets. Refresh waits for these mutations to settle.
- Fixed both SQLite cache-window reads: they selected the **oldest** 300 messages,
  hiding recent messages when reopening a long room/thread offline. They now take
  the newest bounded window, then return it in reading order with stable ID ties.

Added four native test methods for disk reopen/metadata/receipt replacement,
invalid sends, ordered pending merge and newest room/thread windows. Source
inventory now includes the shared delivery factory. Syntax/diff checks pass;
current Swift changes require native compilation/execution, not available locally.
This does not fix every send path: alternate-room attachments, stickers/location,
TTL idle-screen timers and full thread/history navigation still need deeper work.
Already-discarded historical drafts or carrier fields cannot be reconstructed from
local storage. No production SQL deployment or main-branch update has been made.

### Attachment ownership, durable delivery and safe preview opening

Traced picker/recorder → preparation → upload → message send → receipt → cache.
The previous send paths generated a fresh operation on retry; some also deleted
recordings after failed upload. Both native main composers had asynchronous work
that could overwrite a later selection or clear a later caption.

- Android staging now uses immutable selection IDs and owned preparation jobs.
  Late completion cannot attach file A's remote path to file B's preview, revive a
  discarded card, or clear a newer selection. Enqueue has a synchronous busy gate;
  caption/view-once edits are blocked during that transaction, and captions clear
  only when their selection actually clears. Voice upload failures retain a visible
  retry/discard card and the source file; voice topic metadata is captured before
  upload, not read from whichever topic happens to be open afterward.
- Android's shared media repository now commits the uploaded path and all send
  metadata into its existing account outbox, then returns the pending message.
  The single account drainer, rather than a screen-owned POST, delivers it with
  one stable ID. This covers media callers beyond the primary composer. No Room
  migration: the existing optimistic message + outbox envelope holds these fields.
- Swift's main and alternate composers now commit bounded attachment bytes and
  metadata into the existing SQLite outbox *before upload*. The engine records a
  successful upload path before attempting the message POST. Lost message ACKs
  reuse that path and operation ID, rather than uploading/sending another copy.
  Discard while upload is in flight cannot resurrect or send the operation. Upload
  or network uncertainty retains the bytes across restart. Failed intent has an
  explicit Settings → Drafts & outbox retry action using the original identity.
  This deliberately supersedes old comments/specs that said media never queues.
- Swift photo import and microphone permission requests now have lifecycle and
  account fences. Leaving a room cannot start a recorder from a late permission
  result. The alternate recorder is stopped on background/exit. Pending uploads
  show identifiable placeholders rather than empty bubbles. Invalid documents are
  rejected before upload; document reads are bounded, not unbounded file loads.
  Main photo previews now use JPEG data, not an invalid URL built from JPEG bytes.
- Swift preview downloads now require successful HTTP status and a nonempty,
  bounded file. Each download gets a separate generated directory. Remote display
  names cannot select `.`/`..`, escape a preview directory or replace an existing
  open document. The previous helper could delete a shared target based solely on
  the sender's name and open an HTTP error body as if it were the attachment.
- Alternate Swift bubbles now expose document/audio links rather than blank text,
  use the current client's gateway, hide deleted attachments, and do not bypass
  view-once consumption by loading the raw image. View-once opening is explicitly
  deferred to standard chat; this is not a newly implemented alternate-view burn UI.

Limits: Android's *pre-upload* staged bytes/selection are not yet journaled across
process death; successful uploads become durable when Send enqueues the message.
A lost upload response can leave an unreferenced server file (uploads do not yet
have receipts); the known-path/message phase is replay-safe. Swift queued bytes
use bounded base64 in the account SQLite database, not a new background uploader.
Queueing does not guarantee OS background execution. Picker RAW-image memory
limits, alternate-view native audio controls, idle expiry and the remaining rich
send paths still need review. No production schema migration/deployment occurred.

Verification: added three outbox upload/retry/discard tests, three attachment
storage/validation/preview-name tests and two Android selection/voice-metadata
methods. Syntax and diff checks pass; native execution of this tranche is pending.
For the previous 1602d34 revision, iOS archive and tests passed; Android JVM tests
and both API 30/34 instrumented jobs' debug tests passed, with release assembly /
release smoke still running at retrieval. Those results do not validate this new
attachment implementation. Inventory now covers 308 production source files.

### Android rejected-attachment recovery and outbox manager ownership

Continued the attachment flow through Settings recovery. Rejected media already
retained its local metadata but had no explicit retry action. Added an owner-bound
retry that resets only a rejected row, preserves the original operation ID/media
paths, and reuses the single drainer. Repeated taps are gated; retry status and
attachment labels are visible. The manager previously observed all outbox rows
and its clear-all deleted all temporary messages; listing/discard/clear now operate
only on rows whose retained message proves current ownership. Unknown-owner rows
remain quarantined, not adopted by the active account. No sign-in/recovery flow or
credential changes were made.

Extended the existing on-device OutboxOwnershipTest with failed-row visibility,
wrong-owner rejection, unknown-owner rejection and same-ID retry assertions. Ran
the actual new DAO SQL extracted from source against SQLite: ownership filtering,
compare-state retry and unchanged content passed. Kotlin syntax/diff checks pass;
Room/native compilation remains pending. This is outbox-specific: it does not
assert that every other local cache or draft manager has been similarly scoped.

### Android pre-upload restart recovery and bounded recording/import sizes

Closed the primary Android composer's pre-upload gap with separate **unsent draft**
and **queued message** states. A ready draft no longer depends on a picker URI or
recorder cache file. It survives a process restart with its caption/view-once
setting and is restored without sending. Send atomically inserts the optimistic
message and upload reference into the existing outbox and consumes that exact
draft selection. The single drainer uploads, persists the returned path with the
pending envelope, then POSTs the original operation identity. Message-ACK loss
therefore cannot re-upload a known file or create a new send identity. Discard
while upload is in progress is checked before recording its receipt or sending.

Implementation details and boundaries:
- Local Room **v11→v12** adds `attachment_draft` plus an optional outbox upload
  reference. All production/test migration chains register the non-destructive
  migration. This is a native device migration, not a production server SQL patch.
- Multi-MB bytes live in app-private account/gateway-namespaced files, not Room
  TEXT rows: large rows can exceed Android CursorWindow limits. Files are named
  from selection identity + content hash, written before journal insertion and
  verified on reopen. Room stores small references/metadata only. Captions use
  revision-checked updates and never rewrite the media bytes.
- Preparation captures an ephemeral repository-session key before suspending.
  A retired/replaced account binding cannot save or queue that old selection in
  the next account. Recordings capture this key when recording starts. Existing
  production databases were already account/gateway scoped; the preceding outbox
  owner-query changes are additional defensive checks, not evidence that normal
  account switching previously shared one production database.
- Successful upload receipt persistence permits deleting the local upload file.
  Interrupted imports/replaced/discarded drafts can leave unreferenced files;
  account-start cleanup removes them after a one-day safety grace. Referenced
  drafts/rejections and recent files are retained. Failed metadata edits or file
  verification never trigger a new automatic send.
- Oversized Android document/voice imports stop at a bounded read rather than
  allocating the complete provider stream and checking afterward. Both recorders
  now target mono AAC at 32 kbps: a ten-minute take is roughly 2.4 MB before container
  overhead, with margin below the gateway media ceiling; previously an unconstrained
  bitrate could make a nominally allowed recording unsendable.

Verification: authored three private-file/validation tests, a bounded-stream test,
and a real Room migration/reopen/revision/atomic-transfer/upload-receipt test.
Executed the actual new migration/update SQL against SQLite: legacy intent columns
survive, stale edits lose, and a failed draft→outbox transfer rolls back. Native
syntax/diff checks pass; this revision still needs Kotlin/Room/device verification.
The previous 443611b iOS archive/tests and Android build passed; release smoke was
still in progress at retrieval. An earlier 52d1d49 iOS run timed out in live call
signaling; the later unchanged Swift revision passed, but this is not proof that
intermittent signaling behavior is resolved.

Remaining limits: an import is not durable until preparation reports it saved;
in-progress recordings are not crash-reconstructed. Restoring a voice draft never
resumes a previously interrupted automatic Send without user action. The alternate
Android mirror's pre-upload path is not yet migrated to this draft journal. Upload
ACK loss can still orphan a server file because upload receipts are not implemented.
No claim is made that all attachment/call/lifecycle defects are resolved.

### Document-provider compatibility and Android storage controls

Followed saved attachments into Data & Storage. That screen measured only legacy
`pulse.db`, omitting the active per-account databases, and cleared Coil's files by
unlinking them under its live journal. It also reported success after a swallowed
failure. It now measures account databases/WAL files, the actual Coil disk cache
and retained attachment files; cache clearing uses Coil's memory/disk APIs, reports
failure honestly and leaves saved drafts/media untouched. Measurement failure keeps
previous figures with a visible warning rather than fabricating a fresh zero.

Document import now recognizes supported filenames when a provider reports generic
`application/octet-stream`, and normalizes MIME parameters such as CSV charset.
Previously valid PDFs/ZIPs could be rejected solely because of the provider's MIME
label. Added a JVM case for generic/parameterized MIME and unsupported binary data.
Syntax/diff checks pass; native verification is pending.

Attachment Send validation now reads/hashes/decodes bytes on IO before opening
Room's write transaction, then compares the immutable file reference and metadata
inside the transaction. A large document must not hold the database writer lock
while reading storage. Delivery also rechecks pending intent after upload-file
cleanup, which can suspend while a user discards the row. This prevents starting a
POST for an observed removed row; it cannot recall a request already in flight or
already accepted remotely. Removed obsolete comments describing media as online-only.

### Everyday chat: displayed reads, unread state, contact → DM and composer ownership

Traced the primary and mirror native rooms through read APIs, cached summaries,
member watermarks, directory loading, direct-conversation creation and text sends.
Kept the existing autumn/ember palette and layout; this is behavior work, not a
replacement UI.

Repairs:
- Native automatic reads now carry a displayed **server message ID**. The server
  validates that the row belongs to that room and advances the member watermark
  monotonically to its creation time, not request-time `now`. A later arrival
  remains unread. Unknown/foreign boundaries cannot clear the manual unread flag.
- Android primary receipts are driven by visible list rows while RESUMED, not
  ViewModel initialization or background socket arrival. Mirror uses its visible
  rows and existing panel/lifecycle gates. Swift primary/mirror use the rendered
  tail, foreground/unlocked session and common sheet/overlay gates; no loaded
  boundary means no automatic acknowledgement. Transient failures get bounded
  retries without inventing a successful local badge clear.
- Read privacy now applies to the live relay as well as fetched summaries. With
  receipts disabled, only the reader's own devices receive the update. Native
  caches refresh on receipts; Android excludes own-device receipts from partner
  Seen state. Swift no longer substitutes the current time for a malformed
  receipt, and ignores backwards progress.
- Android no longer clears a whole unread summary optimistically then restores an
  obsolete snapshot over incoming messages on failure. Confirmed reads refresh
  authoritative summaries. Explicit mark-unread changes notify only that user's
  devices. Anchored native reads require existing credential ownership; present
  credentials also bind legacy read/mark-unread and conversation-creation actors.
- Swift mirror now renders Queued / Sent / Seen / partial-group Read-by counts
  from actual member watermarks. Both primary message-info sheets label missing
  receipts **No read confirmation**, not Delivered: membership is not evidence
  that a recipient device received a message.
- Direct-conversation lookup/block checks/create are one serializable transaction
  with bounded contention retries. Reversed-member requests and response-loss
  retries reopen the same DM; concurrent contacts taps cannot both commit a new
  thread via this route. Existing blocked history remains accessible; new blocked
  pairs are refused. Both mirror directories retain loaded people and expose
  retry on fetch failure instead of silently replacing them with an empty list;
  Swift excludes the signed-in user from new-chat/group choices.
- Android primary text/effect sends capture the chosen topic and composer revision
  before suspending. Late acknowledgements no longer clear a newer draft; sticker
  and location sends do not consume unrelated text. Local edits reserve their
  draft journal version immediately, before debounce. Storage/validation failures
  can restore the submitted composer/quote only if no newer edit owns it, instead
  of losing text or overwriting the next message. Late initial draft restoration
  likewise cannot override a composer the user already touched.
- Android primary typing now expires per participant, refreshes while continuous
  typing continues, and does not clear another participant when one stops. Swift
  mirror no longer unconditionally scrolls a reader out of older history on every
  new message; initial loading, own sends and following the tail remain supported.

Verification: 85 server contract tests executed successfully, including eight new
real SQLite/Prisma read-boundary, privacy-recipient, membership/block and concurrent
DM cases. Changed Kotlin/Swift syntax parses; native type/device verification is
pending for this revision. The prior 4c673ce Android/iOS builds and tests completed
successfully, which does not verify these new changes.

Remaining boundaries: read state is still a **conversation-wide timestamp**, not
per-topic/per-thread or a timestamp-plus-ID cursor. Equal-millisecond messages and
new-member historical-read semantics need a schema-level follow-up. No independent
recipient-device delivery ACK exists. Legacy tokenless web compatibility is retained,
not presented as full server authorization hardening. This does not merge pre-existing
duplicate DMs, add phone-address-book sync, or reconstruct a crash between text
queue insertion and composer cleanup. Physical-device visibility, rapid sheet /
call-overlay transitions and loss-of-network sequences still require exercising.

Follow-up ordering checks: the Android composer clears its submitted UI text
**before** invoking the send operation, so synchronous validation failures can
restore it instead of being wiped immediately afterward. Swift room appearance
re-reports its visible boundary after claiming active-room ownership, covering
child-before-parent `onAppear` ordering when reopening cached history. The shared
server changes must be deployed with the new natives; an old `/read` handler
ignoring `messageId` cannot provide the new boundary guarantee.

### Send ordering, ambiguous acknowledgements, scheduled delivery and false Seen

A second end-to-end pass found independent immediate-send POSTs alongside Android's
outbox drainer. A new text could overtake an older pending message, and the same
operation could be attempted concurrently. Text/rich sends now persist first and
share a single dispatch gate with recovery. The immediate path is allowed only
for the queue head; otherwise it returns the already-durable queued envelope
without waiting for a media upload. Cancellation releases the gate and schedules
account-owned healing. A malformed success response or a failed local ACK write
keeps the original operation ID queued rather than encouraging a new send ID.

The shared Android transport classified several ordinary HTTP rejections as
UNKNOWN. Outbox handling now explicitly retains 400/409/410/413/415 responses as
rejected intent, excluded from automatic retries, instead of indefinitely retrying
a structurally unsendable message. Authentication/timeouts/rate limits/5xx remain
retryable. POST response decoding failures are server/unknown-ack failures, not
proof the user's message was rejected. Rate-limit holds use a monotonic clock,
apply to both immediate sends and recovery, and cannot be shortened by another
trigger. Android also propagates Retry-After delta-seconds/HTTP dates (server Date
used when present) into that policy. Swift's existing hold now uses an injected
monotonic clock rather than wall-clock time.

Read-state repairs:
- The primary Swift room seeded its own viewer's watermark into peer Seen math.
  Reopening a room could therefore claim another person had read a message based
  on the sender's own activity. Peer-only counting now excludes self, requires a
  valid viewer/message timestamp, and group footers report explicit Read-by counts.
  Fresh privacy/detail snapshots replace peer receipt data, fenced against newer
  live receipts and retired sessions. Android group labels also distinguish counts.
- Message POST, packet creation, room-object creation and scheduled dispatch used
  to update the sender's lastReadAt automatically. Background delivery is not proof
  of reading other people's messages. Those writes are removed; explicit read
  acknowledgements own read progress. This was necessary in addition to the last
  revision's visible-bound native requests.
- Inbox unread queries now exclude expired rows at request time, including the exact
  expiry boundary. Expired latest previews fall back to an unexpired row without
  requiring someone to open the chat and incidentally trigger its purge.

Following scheduled sends through dispatch exposed more gaps. Dispatch now checks
current membership, announcement-mode role and DM blocks inside its transaction,
waits through slow mode, and sets expiresAt from the room's current TTL. The old
participant read update incidentally rejected removed senders; the new explicit
membership guard preserves that safety without manufacturing read receipts.
Both native scheduled-message managers show the actual refusal reason rather than
labeling every cancellation as blocked or providing only a generic status.

Finally, Swift text/caption validation now uses the gateway's 2,000 UTF-16-unit
limit before journal insertion. Android persisted captions no longer allow 4,000
units only to fail after upload. This preserves the selected attachment/composer
for correction instead of uploading an envelope the message endpoint cannot accept.

Verification: all 90 server contract tests passed locally; new cases cover TTL
unread filtering, dispatch-time permissions/blocks/slow mode/TTL, and background
creation not advancing reads. New Kotlin cases exercise dispatch exclusion, queue
head ordering, cancellation release, retry holds, terminal-vs-transient failures,
header parsing and ambiguous successful responses. New Swift cases cover own-read
exclusion, monotonic holds and emoji/caption length before enqueue. These native
cases are authored but not executed locally; syntax parsing and changed TypeScript
helper type checks passed. Native builds for this tranche remain required.

Boundaries still open: retry fairness is account-wide FIFO, so a long cooldown can
hold another conversation behind it; retry deadlines are not persisted over process
restart. Historical membership/default-read timestamps, timestamp ties, topic/thread
read modeling, device-delivery ACKs, scheduled-send lost relay recovery and text
queue/composer atomic handoff still need deeper work. Server behavior changes require
server deployment alongside the native release. This is not a whole-app closure.

### Offline expiry, privacy-preserving cache and thread delivery

The next pass traced expiry beyond the ordinary history endpoint. Android's primary
room and thread streams only changed when Room emitted; a quiet/offline room could
keep an expired bubble indefinitely. The repository now exposes expiry-aware flows
for both readers, with a cancellable nearest-deadline wait and bounded wall-clock
rechecks. iOS primary/Mirror rooms, saved library and threads now react to a local
foreground visibility clock, not only socket arrivals. Shared native image viewers
close when their message's TTL elapses. These are visibility controls, not secure
erasure or revocation of an attachment already exported outside the application.

More importantly, the Swift SQLite writer dropped `expiresAt`, `viewOnce`, `anon`,
`anonAlias`, reply snippets and other envelope fields. Reopening offline could turn
a restricted attachment into an ordinary image. Store v10 now keeps the complete
wire envelope and a numeric expiry column. History windows, individual lookups,
thread counts and saved-cache reads apply expiry before LIMIT; transcription patches
update both representations. Deleted copies also retain the expiry/metadata instead
of silently making a tombstone permanent. A malformed full envelope is not decoded
through the older privacy-less fallback.

**Upgrade consequence:** confirmed v9 message cache and its preview text are
invalidated because missing privacy flags cannot be reconstructed safely. History
reloads from the server on connection. Durable local operations (`local_` rows),
outbox entries, drafts and account separation remain intact. This does not delete
server history. New migration/round-trip tests cover the cache refresh boundary,
view-once/mask/quote preservation, expiry boundary, and transcript updates.

Threads were another independent delivery path. Swift cleared the draft before a
non-durable POST, omitted the generated operation ID from the actual request, and
deduplicated successful replies by equal text. It now journals through the shared
text/outbox engine with parentId, consumes confirmations by operation ID, preserves
separate identical replies, and keeps validation failures in the composer. Thread
refreshes are session/revision fenced; incoming edits/deletions update existing IDs.
A dead/expired root disables the composer and hides its thread. Android's thread
composer now uses the validated use case, preserves a newer composer revision,
prevents duplicate in-flight taps and clears only after the repository accepts the
intent. Its former "replies won't queue" label contradicted the now-durable backend
path and has been corrected. Rejection notices are scoped by room/thread instead
of blaming whichever conversation happens to be open.

Backend counterparts: thread GET refuses expired/deleted/nested roots and filters
expired replies. Send validates the root again inside the message/receipt transaction.
Search, pinned messages and saved-library reads no longer disclose expired rows
while awaiting purge; saved reads also require current conversation membership.
No unrelated web presentation or autumn styling was changed.

The previous revision's native checks exposed two concrete follow-ups: the intended
Android malformed-ACK classification edit had missed the POST catch, and the Swift
monotonic-clock test was accidentally outside its XCTest class. Both source errors
are corrected here. All 91 server contracts pass locally; changed native syntax and
visibility-helper TypeScript checks pass. Newly authored native cache/expiry/thread
cases still require execution by the native builds; syntax checks are not substitutes.

Open boundaries include offline inbox summary expiry, quote/notification/exported
copies, wall-clock trust, comprehensive media playback expiry, account-wide retry
fairness and broader thread pagination/device exercise. No whole-app completion or
full disappearing-message erasure is claimed.

### Room-scoped cooldowns and remaining immediate-send bypasses

Queue tracing found an important distinction missing from both platforms: a room's
slow-mode response was treated like a gateway-wide rate limit. An earlier held group
message could therefore stop an unrelated DM. The server now identifies this verdict
with `code: slow_mode`; only an explicit message-endpoint 429 with that code permits
room-level bypass. Upload throttles, unknown/legacy 429s, network/authentication and
server errors remain conservative/global failures.

Both engines preserve FIFO within a room while skipping a held room for other ready
work. SQLite applies exclusions BEFORE the limit, so 75 older held messages cannot
hide the next healthy conversation behind a 50-row snapshot. Android keeps the shared
immediate/recovery writer gate and now evaluates its head against eligible rooms.
The drain rereads eligibility after each operation, retains rejected intent without
stopping valid followers, and counts held owner-owned pending rows separately from
ready rows. Global holds dominate all room holds. Swift uses a corresponding
SQLite head query and monotonic room deadlines; short cooldowns reschedule a sleeping
heal timer without cancelling an already-running recovery pass.

A deeper caller trace also found Swift's primary plain-text composer still posting
outside that engine, despite persisting a row first. It could race recovery, overtake
older operations, miss the shared length check and ignore a retry hold. Primary text,
stickers and map pins now enqueue through the shared delivery helpers. Mirror's
sticker/location sheets do the same and immediately hand the pending carrier back
to the room, including offline. The text/quote/topic/mask are owned by the durable
operation before clearing composer state. A late ACK cannot erase new input or
consume a newly armed mask. Streak receipt handling was moved onto the engine ACK
path rather than removed; effects, topic refresh and link unfurl remain wired to
confirmed delivery. Unfurl results are fenced against retired sessions/newer relays.
Swift Retry-After parsing now also supports server-relative HTTP dates and takes the
stricter positive body/header delay.

The server's slow-mode check was outside the message write transaction. Two devices
could both pass it before either write committed. Ordinary sends now use the same
transactional policy as scheduled delivery: current membership, announcement role,
blocking, slow mode and TTL are rechecked at the write boundary. Cooldown errors
carry both the machine code and Retry-After; no unconfirmed operation identity is
retired by this change.

Verification: 92 server contract cases pass locally, including two concurrent
transactional sends producing only one accepted message. Visibility/delivery helper
TypeScript checking (with Node typings) and changed native syntax checks pass.
Authored native regressions cover long-prefix room starvation, global/upload throttle
isolation, room/global deadline precedence, bounded FIFO recovery, retained rejection,
header parsing, and rich queued metadata. Native execution for this revision remains
required. Prior-revision checks revealed an omitted Android expiry extension import
and an iOS assertion expecting the old empty-reaction normalization; the import is
fixed and the assertion now checks exact v10 envelope equality. This does not imply
all runtime paths or devices have been validated.

Remaining limits: cooldown state is process-local, unknown failures still stop the
account queue intentionally, heal cadence can add recovery latency, offline inbox
summary expiry remains open, and draft-to-queue handoff is not yet crash-atomic.
Backend/native co-deployment is needed to obtain scoped slow-mode behavior; old
servers safely retain global behavior. The autumn presentation is unchanged.


### Crash-atomic composer handoff (2026-10-20)

Traced normal/effect/anonymous sends from both native presentations to local storage.
Previously the queued operation and draft clear were separate writes. A process exit
between them could restore the sent text as a new composer, and a delayed journal or
debounce could resurrect it even without a crash.

- Swift queues the optimistic message, complete envelope and explicit empty draft in
  one GRDB transaction. Composer journals reserve ordered revisions synchronously;
  old writers cannot run over a consumed revision. Separate handles for the same
  database share the fence. Rollback does not advance it or discard the front buffer.
  Primary keystrokes now journal directly from the model mutation; Mirror gained
  synchronous local journaling/restoration rather than an unpersisted text field.
- Android uses the same Room transaction for message/outbox/draft consumption, with
  an explicit room/account/revision token passed only by composer sends. Journal
  versions and mutexes belong to the reused account database handle, not a particular
  repository lifetime. Debounced saves retain their original token, instead of
  reserving a fresh edit after Send. Mirror journals actual text mutations rather
  than staging stale text on disposal; an ACK's UI clear is not a new persistent edit.
  The on-device demo send also consumes its draft with its local message write.
- Empty local snapshots override stale server drafts in restore and conversation
  previews; draft-manager lists/counts omit those tombstones. Android clear-all uses
  tombstones and revision fencing rather than deletion that old pending writes undo.
  The effect drawer no longer deletes the saved draft before enqueue succeeds.
- A newer edit is not owned by an older send. Thread, sticker, location and attachment
  sends without a composer token do not consume unrelated text. A token from another
  account/store/room is rejected before persistence. The autumn presentation is unchanged.

Verification in this checkout: changed Kotlin/Swift sources parse; `git diff --check`
passes; the existing 92 server contracts pass (no backend change in this tranche).
New XCTest/Android regressions cover delayed writers, reopen, newer edits, noncomposer
sends, ownership rejection, and an injected SQLite draft-write failure rolling back
message/outbox inserts. Native compilation and execution are delegated to GitHub's
native runners, not claimed from the syntax check.

Limits: this is local crash-atomic handoff, not a cross-device draft-conflict protocol.
Server draft mirroring is best effort; interrupted requests/other devices still need
server-side revision ordering. Unsaved keystrokes can still be lost on storage failure
or process death before their journal write commits. Native device/background checks,
offline inbox summary expiry, and the other open feature-tree boundaries remain work.


### Offline inbox expiry and authoritative snapshot replacement (2026-10-20)

The message timeline's TTL filter did not protect the inbox. Android discarded
`lastMessage.expiresAt` when mapping summaries to Room; Swift rebuilt a permanent
synthetic message from a cached snippet. A failed poll left those previews and badge
counts on-screen indefinitely. Counting cached history instead would be wrong: it is
a bounded/partial window, and the preview need not be an unread message.

- The server now aggregates unread rows by expiry in one query, returning the total
  and its `unreadExpirations` schedule from the same aggregate. Expired/deleted/own/read
  rows are excluded. Permanent unread stays in the count but has no deadline bucket.
- Android persists the preview deadline and unread schedule (Room v13) and applies a
  clock-driven projection to the shared conversation flow used by both presentations.
  Expired text, author/file/type decorations disappear; only expired unread buckets
  subtract from the badge. New Room emissions replace pending timers; stopped collectors
  cancel them. iOS preserves the complete summary envelope in GRDB v11, projects it on
  cache restore, and reevaluates both visible inbox presentations independently of network
  polling. Optimistic pin/mute/archive/manual-unread copies preserve expiry/privacy fields.
- Old inbox caches have no trustworthy expiry provenance. Migration retires their
  derived previews/counts, not room identities, messages, drafts, or durable operations.
  New snapshots restore those derived fields. This may temporarily hide a legacy badge
  while offline; it does not mark any server messages as read.
- Android detail endpoints omit inbox-only fields. Opening room info, changing a group
  photo/name/TTL, or receiving another detail response now merges those fields instead
  of resetting preview/unread/pin/archive state to DTO defaults. Explicit zero counts
  on real summaries remain authoritative.
- Successful complete lists replace cached server-room snapshots atomically, including
  empty lists; Android's local demo room remains local. Delayed list responses are fenced,
  and Swift polling now updates the persistent cache too. An empty iOS response no longer
  falls back to stale cached rooms. Draft/outbox recovery is not deleted with inbox rows.
- Manual-unread remains independent of numeric unread on both platforms and in both
  Unread filters. No autumn styling or layout changes were made.

Executed here: 93/93 server contracts, including grouped deadlines and permanent-unread
retention; standalone TypeScript checking of the visibility/aggregate helper; changed
native syntax and diff checks. Added native clock/deadline, restart, migration, empty
snapshot, and detail-merge regressions. Native compilation/execution for this tranche
still requires the GitHub runners. The preceding draft-handoff Android build and both
API 30/34 instrumented jobs passed; iOS archived successfully but its test compile found
three missing async awaits in the new GRDB rollback test, corrected in `633a7e1`.

Limits: exact offline unread decay needs the updated backend's schedule. Older servers
still provide preview expiry, but native clients do not guess which unrelated unread
messages have expired. Device wall-clock trust, unknown offline deletions/read receipts
from other devices, notification/exported copies and full cached-media revocation remain
open. An expired preview clears rather than inventing an older preview from partial local
history; the next successful server refresh selects the current latest live message.


### Saved-library consistency, explicit Unsave, and detached selections (2026-10-20)

The Android saved observer watched only bookmark markers, then read message bodies
through unobserved one-shot DAO calls. Editing or deleting a saved message did not
necessarily redraw the library. Mirror fetched once into detached state and stayed on
Loading forever after failure. Swift's library expired rows on a clock, but did not
observe message-cache changes; a late library response could overwrite a newer relay
edit/deletion. Both libraries used a save toggle for Unsave, making response-loss retries
capable of saving a message again.

- Android now observes one Room join across messages, saved markers and conversation
  metadata; deleted rows are excluded and a cancellable clock removes expired items
  offline. Primary and Mirror saved screens render that stream, not stale HTTP-returned
  copies. Mirror failures stop loading and expose Retry without changing autumn styling.
  Primary Unsave unwraps repository failures instead of accidentally treating Result.failure
  as a successful runCatching result.
- Saved refresh commits message versions and exact marker replacement together on both
  platforms. Android serializes library fetch/save/remove operations and applies the
  existing per-message revision fence inside the transaction; local mutation ACKs now
  advance that fence too. Swift observes the GRDB join, preserves cache rows changed since
  a fetch began, and fences refresh against bookmark changes. Primary Save updates the
  local marker immediately after acknowledgement and suppresses duplicate pending taps.
- DELETE /api/messages/{id}/save is an explicit owner-authenticated removal. Repeating it
  cannot recreate a bookmark; it also permits cleanup after message deletion or leaving
  a room. Native Unsave calls DELETE, validates the acknowledgement, and never falls back
  to the toggle. POST Save rejects already-expired messages. Legacy POST remains a toggle.
- Primary-room pin/search selections now reconcile current cached content and expire
  detached snapshots. Android cancelled searches cannot publish after the next query;
  Swift query/pin responses respect session/message revision fences and retain fresh
  response content rather than overwriting it with an older loaded-window copy. Missing
  history alone is not interpreted as deletion. Mirror pin/search sheets and global
  spotlight results still need their own full lifecycle trace.

Executed: 94 server contracts pass, including repeat-safe owner-scoped removal without
an extant message; visibility/helper TypeScript checking and changed-native syntax/diff
checks pass. Added native saved-clock/selection/acknowledgement, observed-join, deletion,
late-refresh and rollback regressions; native runner execution for this source remains
pending. Previous b5109bb iOS execution passed. Its Android compile found a duplicate
Serializable annotation (and the Conversation annotation displaced onto the new expiry
model); 0ea0fd3 restores one annotation to each class. Parsing alone did not catch that
compiler error and is not claimed as compilation.

Backend/native co-deployment is required for explicit Unsave; an older server's 405 is
reported, not retried through a potentially destructive toggle. The saved API remains a
100-item window. Global search, notification/exported copies, media viewers, and broad
membership-revocation/cache policy are not established by these repairs. No whole-app
correctness or completed-feature-tree claim is made.

### Global search, Mirror pin/search sheets, and partial bulk deletion (2026-10-21)

- Android global search, Mirror home search and Spotlight now collect a cancellable
  expiry-aware stream instead of retaining a single HTTP snapshot. Query changes clear
  older hits immediately. Known authoritative message mutations retire affected search
  snapshots, including local delete/edit ACKs; fresh matching edits require a re-query.
- Swift global search clears old results/loading state on query change/close and rejects
  responses from cancelled queries or retired sessions. Its visibility clock removes
  expired/deleted/view-once/known-mutated results. Local primary-room edit/delete ACKs
  now advance the same revision fence used by remote envelopes. Query values encode
  separators such as &, + and # instead of letting them alter URL parameters.
- Search DTOs retain expiry/view-once/anonymous metadata. Backend search filters unsafe
  retained previews, masks anonymous sender identity, and returns the actual visible hit
  count. Native search chrome also masks anonymous authors for older compatible servers.
  Swift can resolve an uncached conversation on search-result activation instead of
  silently ignoring the tap; existing cached rooms remain navigable offline.
- Explicit owner-authenticated DELETE /api/messages/{id}/pin removes a pin rather than
  toggling it. Membership validation and removal share a SQLite transaction. Both native
  clients require an explicit positive acknowledgement; there is no legacy-toggle
  fallback. Primary-room Unpin and Mirror Unpin await this operation and suppress repeat
  pending taps. Legacy **Pin** still uses POST toggle and is not claimed repeat-safe.
- Android refreshes pin snapshots atomically, retires absent cached pin flags, and
  protects newer mutations from older fetches. Mirror pin sheets now show load/action
  errors and Retry, reconcile current cached versions and expire detached previews.
  Swift pin removals update both the serialized GRDB payload and indexed pin field.
  Mirror search sheets and primary selections exclude deleted, expired and view-once
  bodies. These behavior changes preserve the autumn palette/layout.
- Android Clear My Messages previously deleted **all** selected cached rows if just one
  request succeeded, hiding rows whose deletion failed. It now caches only acknowledged
  tombstones, retains failed rows for retry, and reports complete failure. Its history
  source is still bounded to 500 rows; this is not an unlimited bulk-delete guarantee.

Executed locally: 98/98 server contracts (including real SQLite repeated Unpin and
nonmember refusal), standalone search/pin-helper TypeScript checks. Added native tests
for search expiry/cancellation/metadata, encoded queries, required acknowledgements,
view-once selections and GRDB pin-payload consistency. Native compiler/device execution
must run on GitHub; parser checks are not compilation. No generated native builds are
stored here. Inventory updated to 322 production sources.

Still open: thread-root navigation and server revalidation of stale message targets;
unknown offline changes and broad membership-revocation/cache rules; media/export/
notification copies; legacy Pin toggle replay; bounded global search (newest 1,200 rows,
30 hits); server ordering without per-message versions. Search invalidation removes a
known-mutated snapshot instead of discovering a newly matching edit automatically.
Explicit Unpin requires backend/native co-deployment; older servers fail visibly. This
entry records targeted repairs, not whole-app or feature-tree completion.

### Older-history navigation and thread availability (2026-10-21)

Traced primary Kotlin/Swift search/pin/quote jumps into older pages and thread
read/send surfaces, plus the backend thread reader. Autumn styling is unchanged;
Android adds a normal themed “Load earlier messages” action for sparse history.

- Android primary history now retains a composite `(createdAt, id)` cursor from
  raw pages. Hidden replies and other-topic rows advance it, timestamp ties do not
  collapse into one boundary, and anchor deletion from cache cannot turn a message
  ID into a malformed timestamp query. Paging no longer depends on Room having
  emitted a newly fetched page to the UI before the next request starts.
- Manual history, retries and jumps share cancellable ownership. New jumps/topic
  changes cancel the previous task; cancellation is rethrown rather than published
  as an error or allowed to clear a newer operation's loading state. A repeated page
  is detected. Sparse ranges have a bounded five-page batch plus a manual continuation.
- Both primary chat screens distinguish a thread reply from a river message and
  open its validated root instead of repeatedly searching the filtered main timeline.
  Swift clears a conflicting topic filter before jumping. Repeating a jump to the
  same message issues another scroll request on both platforms; it is not swallowed
  by a remembered message ID. This opens the correct thread but does **not yet**
  guarantee scrolling/highlighting the exact reply inside a long thread.
- Android preserves typed HTTP status for thread failures. Explicit 403/404/410
  denial hides cached parent/replies and disables sending until a successful retry;
  network/500 errors alone are not interpreted as revocation. Reconnection retries
  thread validation. Cached content is masked immediately, not after a separate
  Room emission, and a refused/expired thread cannot hand off a pending file download.
- Swift rehydrates and rechecks the cached root rather than trusting the sheet's
  original snapshot. Deleted/expired/nested/foreign roots are unavailable, including
  an expired cache row hidden by ordinary message reads. Thread lightboxes close on
  known deletion/expiry of their selected message. Android uses the same root shape
  and deadline checks for reply admission.
- Backend thread replies are explicitly scoped to the authorized root's conversation,
  not just its parent ID. Root/reply deadlines are checked again before response
  construction so a slow read does not knowingly deliver already-expired content.

Executed locally: 99/99 server contracts, including actual SQLite cross-room reply
exclusion; standalone TypeScript checking of message-visibility; native source syntax
and diff checks. Added Kotlin raw-cursor/timestamp-tie/root-denial regressions and Swift
cached-expired-root/availability regressions. Native compiler/device checks require the
GitHub runners and are not replaced by parser success. Inventory: 324 production files.

Official reference consulted: Kotlin kotlinx.coroutines `docs/topics/coroutines-cancellation.md`
(cancellation exceptions must be rethrown; structured child cancellation), retrieved
from https://github.com/Kotlin/kotlinx.coroutines via GitHub API.

Remaining: exact thread-reply anchoring, beyond-window target lookup (jumps still cap
at 14 pages), thread pagination, Mirror parity for the new stable composite cursor,
authoritative revocation persisted across navigation/restart, media/export copies and
server message-version ordering. These are bounded runtime repairs, not whole-app
completion or a blanket offline authorization guarantee.

### Mirror thread sending and exact reply anchoring (2026-10-21)

Followed the actual autumn Mirror thread sheet separately from the primary Android
thread view. It still used a frozen root, swallowed load failures, rendered raw body/
author fields, and launched detached send callbacks that cleared whatever draft existed
when an older send finished. Primary-screen fixes did not cover this implementation.

- Mirror now observes the current root and replies through lifecycle-aware streams,
  applies expiry on a clock, masks content immediately after an explicit thread denial,
  and exposes load/send failures and Retry. Reconnection refreshes validation. Transport
  failure alone does not revoke otherwise-live offline cache. Composer send admission
  rechecks the root at tap time, rather than relying on the last rendered enabled state.
- Mirror sends use the shared text send use case in a sheet-owned coroutine. The body
  and composer revision are captured before suspension; acknowledgement clears only
  that revision, including correct A→B→A handling. The existing RoomComposerDraft kernel
  and its regressions are reused. Opening another root/account disposes the old sheet
  scope. Failed sends keep text; draft persistence across sheet dismissal is NOT added.
- Mirror text bubbles render deletion/view-once placeholders and anonymous aliases,
  rather than exposing retained captions or the real sender name. This is text-preview
  protection, not a completed media-consumption/revocation design.
- Mirror search jumps retain the reply ID in addition to its root. A lazy, keyed reply
  list scrolls to that reply with a short existing-accent outline. It accounts for the
  parent row and does not interpret a missing target as row zero. An authoritative load
  containing the target waits for the Room emission instead of prematurely reporting
  a missing reply. Deleted/expired/wrong-thread targets are rejected.
- Swift retains the reply ID through root validation and sheet presentation, gives each
  reply a stable scroll ID, and anchors/highlights the selected reply after layout. A
  newer target resets navigation without destroying the composer. Known missing/expired
  targets report unavailability instead of silently opening an unrelated position.
- Swift tail following now checks the visible tail geometry and pending jump intent.
  Incoming replies do not yank a user away from older content; explicitly sending returns
  to the tail. A missing lazy tail reports infinity, not zero (zero would falsely imply
  that the reader was at the bottom).

Verification: preceding e0f506f Android build + API30/API34 instrumentation and iOS tests/
archive all passed. For this new source: 99/99 unchanged backend contracts pass, changed
native syntax/diff checks pass, and new Kotlin presentation/index and Swift navigation/
scroll-policy regressions are added. Native compilation/device execution for this new
batch remains pending on GitHub; parser success is not compilation. Inventory: 326 files.

Still open: primary Android navigation callback carries only a root (exact-reply work
here covers its active Mirror counterpart); long-thread pagination; durable thread drafts
across dismissal/restart; persisted cross-screen membership revocation; media/export
copies; beyond-window target lookup and server ordering without message versions.
The autumn palette is retained. No whole-app completion claim is made.

### Device-local thread drafts and atomic send handoff (2026-10-21)

Traced closing/reopening a thread through both native draft stores and the durable send
transaction. Thread drafts were only view state, unlike main-room composers. Simply
saving them under the room ID would overwrite the river draft; independently clearing
a new draft after sending would reintroduce replay/lost-typing races.

- Added a reserved, length-delimited local thread namespace in the existing draft table.
  It uses the existing account/gateway-isolated database; no destructive migration is
  needed. Main-room and separate-root drafts remain independent. Thread keys never go
  to the legacy server draft endpoint. Settings draft managers label them as thread
  replies and can delete them; existing bulk clear includes them.
- Extended draft-consumption tokens to bind the parent/root as well as room, account or
  storage identity, and revision. Both Room and GRDB now consume that specific scoped
  draft in the same transaction as the optimistic message and outbox operation. A main
  token cannot consume a thread draft, nor a thread token a different root/main draft.
  Rich and demo sends share the Android scope guard. Untokened sends still consume no
  composer draft.
- Android thread editors journal each change in a finite account-bound writer, retain a
  front buffer for rapid reopen, and observe revision-tagged draft snapshots. Reads use
  the same lock as enqueue/clear and re-read storage, not stale Room emission payloads.
  The editor keeps the token associated with its actual text, rather than capturing a
  potentially newer other-editor revision at send time. Queue-clears are observed while
  a network acknowledgement is pending; newer typing survives an older send.
- Primary and Mirror Android thread editors restore without generating a new write and
  bind work to the captured account repository. Save failures retain buffered text and
  show a warning. Primary thread input rejects an over-limit edit instead of allowing a
  later storage truncation to silently replace the visible text. Local success clearing
  does not stage a second draft-clear that could erase newer text.
- Swift threads reuse the journal's synchronous front buffer, off-main writer and owner
  fence, with their root-specific namespace. Text changes journal immediately; enqueue
  consumes the draft atomically. Account transitions disable/close the old thread editor.
  Journal snapshots now carry the storage revision: Settings deletion or a committed
  send invalidates an older in-memory pending copy before rapid reopen can restore it.

Verification: 99/99 unchanged backend contracts passed locally. Added Kotlin token/key
unit checks, extended real Room account-isolation/instrumentation coverage for independent
roots, handoff, newer typing, restart/account return, write-failure rollback and bulk clear;
added Swift journal/GRDB tests for reopen, root/account guards, rollback, newer typing and
pending-buffer deletion. Native syntax/diff checks passed; new native compilation and
execution remain pending on GitHub. Prior 5532442 Android build and iOS tests/archive
passed; its instrumentation status was still pending when last checked. Inventory stays
326 production files. Autumn styling is unchanged.

Limits: thread drafts are local, not cross-device synchronized. Ordinary dismissal does
not cancel finite storage writes, but abrupt process termination before SQLite commits
can still lose the very last keystroke; disk failures are reported rather than claimed
as durable. Root deletion/revocation retains recoverable draft text, not permission to
send. Long-thread pagination, broader revocation/media/export policies and beyond-window
lookup remain open. This is a targeted runtime repair, not whole-app completion.


### Inline failed-thread recovery, preserving the original operation (2026-10-21)

Traced definitive send rejection through durable storage and the actual thread surfaces.
The optimistic bubble disappeared while its recoverable intent was accessible only in
Settings. Retyping/reposting could create a second operation; a stale discard could
also delete an intent that another surface had already retried.

- Primary Android, active Mirror sheet and Swift threads now observe their own failed
  replies and provide Retry original, Copy text, and an explicit discard confirmation.
  Actions leave the current composer untouched. Empty attachment captions cannot wipe
  the clipboard. Existing autumn colors/materials are reused.
- Android filters by owner, room, root and failed status through the retained hidden
  optimistic envelope, not rich-payload JSON. Swift filters the account-local outbox
  by room and decoded routing envelope. Malformed Swift envelopes remain available in
  Settings rather than being guessed into a thread.
- Retry reuses the original clientId, queue row, metadata and upload receipt. Root
  liveness, room/root association and failed status are revalidated inside the storage
  transaction. Missing/deleted/expired roots cannot be retried inline. Explicit denial
  also disables the UI action; a locally live root is not proof of server authorization.
- Discard checks the same scope and failed status atomically with deleting the retained
  intent/temp bubble. A stale confirmation after Retry cannot delete the queued send.
  Root unavailability still permits recovering/copying/discarding the user's own intent.
- Swift reinstates the pending bubble when retry is accepted, cancels observation on
  dismissal, and fences/clears recovery state at session transitions. Android uses the
  captured account repository and lifecycle-aware list observation; the router's list
  reset behavior also covers the added observation API.

Verification: 99/99 unchanged server contracts pass locally. Added real Room DAO and
repository regressions for scope, missing/deleted/expired roots, original identity,
unchanged draft and stale discard; added GRDB/Swift observation and mutation regressions
for the equivalent flows and malformed envelopes. Changed native files pass syntax
parsing and diff checks; these are not a substitute for native compilation/execution,
which remains pending for this batch on GitHub. Inventory remains 326 production files.

Limits: this is failed-intent recovery, not full thread pagination or proof of delivery.
Generic Settings queue controls retain their existing semantics. Revocation across all
screens, beyond-window navigation and media/export lifetimes remain open. No merge or
deployment is implied, and no whole-app correctness claim is made.


### Preserve raw history boundaries and reject stale refresh seeds (2026-10-21)

Traced “load earlier”, cold/search/quote jumps and topic changes through the actual
Mirror room, primary Android ViewModel, Swift room model and message-page endpoint.
The Android cache reconciler correctly drops an HTTP row after a newer deletion, but
callers then incorrectly derived pagination from those filtered rows. An entire dropped
page looked like end-of-history. Mirror also retained just an ID, which stopped being a
valid paging anchor once that local row was purged. Swift latest-page responses could
reset a history boundary established by a newer action.

- Added an Android history-page result that keeps the raw response's timestamp/ID cursor
  separate from reconciled display rows. Both native Android room paths consume it;
  legacy page APIs remain compatible. Hidden/deleted/reconciled-away rows still advance
  the boundary without resurrecting their message content. Stable composite cursors go
  to the existing backend `before`/`beforeId` contract without a local anchor lookup.
- Mirror no longer assumes the oldest arbitrary pin/search cache row proves contiguous
  history. A new paging scan starts with the latest server page, keeps raw progress and
  skips duplicate/hidden-only pages in bounded batches. A jump cannot rewind a farther
  manual history boundary. Primary Android cold jumps can seed history even if they
  superseded the initial load before it established `hasMore`.
- Mirror jumps no longer poll an empty cache or swallow coroutine cancellation. The
  target must enter the current Compose entry model before index-based scrolling, with
  the history-control row accounted for. Deleted/expired targets produce an unavailable
  notice; a superseded task cannot consume a newer jump. Explicit thread target IDs stay
  intact. Network/display failures retain an actionable error rather than a false jump.
- Swift now keeps cursor, exhaustion and revision together. Page starts/commits, empty
  terminal pages and topic/jump resets invalidate older latest-page seeds. A general
  refresh cannot seed a topic's boundary. Topic reload merges the bounded cache into
  already-expanded history instead of truncating it; selecting a topic refilters the
  display immediately. Automatic topic healing uses the same cancel/reset/reload path.
  Topic refresh captures its store/client/session epoch across suspension.
- Corrected the previous outbox DAO regression's final assertion: `count()` counts only
  pending sends, not all stored failed intent. It now verifies one pending and four total
  stored rows separately; no production queue semantics were changed to satisfy it.

Verification: 99/99 unchanged server contracts pass locally. Added Kotlin raw-boundary
regressions (including a fully reconciled-away page), Swift stale-seed/empty-terminal/
A→B→A regressions and expanded-history retention coverage. Changed native files pass
syntax and diff checks; native build/device execution for this new batch is pending.
Previous 54f76de Android build and Swift tests/archive passed; Android instrumentation
reported the added outbox test, whose incorrect pending-vs-total assertion is corrected
here. No claim that parser checks prove native runtime behavior.

Limits: scans remain bounded (five pages per manual batch, fourteen per jump); a very
old uncached target can still require further history. Legacy all-replies thread loading,
primary Android's root-only navigation callback, cross-screen revocation and media-copy
lifetimes are still open. A fresh Mirror scan can revisit already-cached pages rather
than skipping an unproven gap. Autumn styling is unchanged; no merge/deployment implied.


### Seen requires a visible, unobscured row and an owned acknowledgement (2026-10-21)

Traced read acknowledgements from primary Android/Mirror list layout and Swift lazy
views through their schedulers to the existing server watermark transaction. Found
Swift's lazy tail `onAppear` being treated as viewport visibility; a new cached tail
could be acknowledged before its row was displayed. Refresh/reconnect calls also
bypassed the intended dwell. Mirror suspended reads for only a few of its sheets, while
both Android lists restarted their read debounce on unrelated message-list emissions.

- Swift now measures row and tail frames in the actual scroll viewport. Read boundaries
  come from the newest eligible measured row, including when reading older history,
  not from the newest cached row or creation of a prefetched tail. A row needs a 44-point
  visible slice (or its full height if shorter). Tail following also uses measured
  geometry rather than lazy appearance. Missing/zero viewport geometry is not visible.
- Both Android surfaces use the matching density-scaled overlap rule against actual
  layout viewport offsets. Pending and deleted rows do not anchor Seen. Latest rows are
  sampled through Compose state, so an unchanged visible boundary does not restart its
  600ms wait just because an off-screen message or unrelated row changed.
- Mirror's read/attention gate now includes its search, pins, composer/media, scheduling,
  reminders, collaboration, safety and other room-owned covering surfaces. Primary
  Android retains its existing modal/privacy/lifecycle gates. Swift includes photo/file
  pickers, staged media, inactive scenes and privacy veil, with initial gating applied.
- Swift enforces the 600ms monotonic dwell at network admission, so independent refresh
  triggers cannot bypass it. Foreground/readiness/reconnect resume with a fresh dwell;
  retry backoff cannot be bypassed by refresh either. Retries are bounded and rearmed by
  a changed visible boundary or an explicit resume/reconnect. An unchanged acknowledged
  boundary is not repeatedly posted.
- Each Swift read attempt carries a boundary revision and unique request identity.
  Responses from an old room, an A→B→A visit, or an interrupted visibility period cannot
  acknowledge/release a replacement request or poison its retry state. Room ViewModels
  also bind visibility callbacks to the session generation captured at creation. A real
  accepted older receipt can refresh the inbox without claiming the current row was read.

Verification: 99/99 unchanged backend contracts pass locally. Added Kotlin overlap tests
(including clipping, content padding, density, invalid dimensions and integer overflow)
and Swift geometry/dwell/backoff/request-ownership regressions. Native syntax/diff checks
pass; compilation and native execution for this new batch remain pending on GitHub.
Official AndroidX LazyListItemInfo source was checked: item offset/size are container-axis
pixels, matching the viewport overlap calculation. No palette or bubble redesign.

Limits: this acknowledges a displayed boundary, not proof that a human read every word.
A continuously changing boundary can conservatively delay its acknowledgement. Existing
server timestamp-only read semantics, per-thread/topic unread granularity, timestamp ties,
all system/context-menu overlay lifecycles, and historical missed-message badge accounting
still require further work. Server monotonic receipts and privacy fanout remain unchanged;
this does not claim complete Seen/unread correctness or whole-app completion.

### Arrival identity and reactive cached thread counts (2026-10-21)

Corrected two separate local UI accounting paths; these are not changes to server unread totals.

- Swift's jump badge now tracks distinct eligible peer arrivals, rather than changes in
  rendered list length. Loading older history, snapshots, own sends, replayed relay events,
  and unrelated deletion cannot invent arrivals or erase unrelated pending IDs. Deletes,
  expiry and topic changes reconcile individual pending IDs. Catch-up classification uses
  a captured canonical tail and topic revision; initial history discovery is not an arrival.
- Tapping Jump no longer clears its badge before scrolling succeeds. Clearing requires an
  unobscured foreground/current-room tail AND measured visibility of the latest live
  canonical row, so yesterday's geometry cannot clear a just-appended message. Removed
  the unused length-delta helper/tests rather than retaining a second contradictory policy.
- Swift reply chips observe a room-scoped GRDB aggregation over live same-room roots and
  replies, excluding deleted/expired rows and retained failed echoes. Duplicate events do
  not increment counts. Root and reply expiry deadlines requery through the visibility
  clock even without database writes; queued snapshots crossing a deadline are rechecked.
- Primary Android derives reply chips from raw expiry-aware room rows, not the filtered
  main-river StateFlow (which suppresses reply-only changes). The main river, selection
  reconciliation and counts share one upstream query/decoding/expiry subscription.

Verification: 99/99 existing server contracts pass; production reply-count SQL separately
checked with SQLite for room isolation, failed/deleted rows and root/reply deadlines.
Added seven Swift and three Android regressions covering arrival identity, catch-up/replay,
root/reply lifetime, GRDB observation and retained failed echoes. Syntax/diff checks pass;
these new native tests have not yet compiled/run. The previous ef6b1d1 build, Swift tests
and Android API 30/34 instrumentation passed, which is not verification of this new batch.

Limits: reply chips describe cached replies, not authoritative totals for unloaded history.
The local jump badge is not a server unread counter; catch-up requires a known baseline,
with same-timestamp ordering conservatively using the composite cursor. Legacy unused
aggregate APIs remain separate. Large-thread pagination, exact primary-Android reply
navigation, cold Swift jumps and broader revocation/media-copy behavior remain open.
Autumn styling is unchanged. No merge/deployment or whole-app correctness claim.

### Cold history jumps and exact thread targets (2026-10-21)

- Swift no longer treats the initial `hasMore=false` and absent cursor as proof that
  history is exhausted. An explicit jump without a boundary requests a latest page,
  then advances through raw composite cursors under the existing request/session fence.
  A known terminal boundary still stops scanning. Empty initial loads can be retried.
- Primary Android's feature-chat handoff now carries root ID, exact reply ID and request
  revision rather than losing the target at the root-only callback. Consumption is
  request-owned. ThreadScreen accepts the target directly or from saved-state arguments,
  waits for its confirmed reply to reach the rendered model, rechecks after a frame, and
  scrolls/highlights that reply with the parent-header offset. Pending jumps suppress
  automatic tail following; sending explicitly supersedes a suspended jump. Corrected
  ordinary tail following's missing parent-header offset as well.
- Mirror's active room search/jump path now loads and validates the live root AND selected
  reply before opening the sheet. A cached parent no longer bypasses that check. The
  shared target-index predicate rejects missing, foreign, deleted, expired and wrong-root
  replies, including when the parent itself has become unavailable. It never substitutes
  the first reply for a missing target.
- Both Kotlin and Swift root-availability gates reject optimistic `local_` root IDs.
  Reply/send/retry paths cannot use a pending parent as though it had a server identity;
  drafts remain kept while that root is unavailable.

Verification: five new native regressions (two Swift, two domain Kotlin, one Mirror Kotlin)
cover cold/terminal history boundaries, reply identity, parent lifetime and pending roots.
13 changed native files pass syntax checks; 99/99 existing server contracts pass locally.
Native compilation and UI/device execution for these changes are still pending.

Scope: MainActivity currently mounts MirrorRoot; the primary feature-chat callback fix is
module wiring, not a claim that a separate primary navigation graph is shipping. Any host
of ChatRoomScreen must forward the new optional reply ID into ThreadScreen. Explicit
Mirror thread search jumps now require a successful availability request; ordinary offline
thread/draft handling is unchanged. Full-thread loading and the fourteen-page room jump
limit remain; this does not add thread pagination or persist revocation across navigation.
No autumn palette/bubble redesign and no merge/deployment implied.

### Transactional message actions and monotonic cached privacy state (2026-10-21)

While tracing thread lifetime, found message action races: edit/delete read permissions
separately from writes, repeat-delete returned a tombstone before checking the requester,
view-once consumers could overwrite the first consumer, and native full-row acknowledgements
could restore content after a newer tombstone. Android reaction failures left optimistic
chips behind and successful reactions relied on a later bounded room refresh.

- Edit/delete/react/viewed routes now share a serializable transaction for membership,
  owner checks where required, message/root lifetime, the write and response snapshot.
  Repeat-delete is checked for ownership/membership too. Delete clears pin state; deleted
  or expired rows and unavailable thread parents reject new edits/reactions/consumption.
  Contention reports 409, not an automatic toggle replay. View-once's first consumer is a
  conditional write; the additive `consumed` response distinguishes a new consumption.
- Mutation fanout includes the actor's other devices. Relays retain neutral poll metadata;
  the direct response remains viewer-aware. Existing transport identity migration is
  unchanged: this is not an authentication overhaul.
- Shared serialization redacts body, attachments, rich payload, quotes, translations and
  preview/poll content from tombstones. Quoted deleted/expired parents expose no original
  body, and view-once quotes expose a placeholder rather than their caption.
- Room's transactional message upsert and GRDB's shared write path preserve deletion,
  consumption, view-once flags, earliest expiry and newer edit stamps across late rows.
  Tombstones retain identity/routing/deadlines but not media/body payload. Cache reads
  normalize old tombstones too. Pending send/draft intent is not discarded by this policy.
- Swift river/thread merges apply the same rules. Network ingestion returns committed
  canonical rows rather than feeding raw stale HTTP inputs back into the UI. Android
  fetched rows and socket fanout likewise use the actual retained cache state.
- Android reactions apply the returned server row, serialize one in-flight action per
  message, retain existing state on failure and no longer depend on a bounded refetch.
  Primary and active Mirror surface failures. Mirror reactions use room-owned coroutines
  rather than detached IO launches. Swift reactions are persisted, session-fenced and
  gated per message instead of updating only the in-memory river.

Verification: 106/106 server contracts pass locally, including seven new transaction,
concurrent-consumer and disclosure regressions. New pure TypeScript helpers pass strict
standalone type checking. Added three Kotlin merge tests, one real Room DAO test and four
Swift merge/storage tests; native parsing passes, but new native compilation/device tests
remain pending. Previous 83f99d5 Android build and iOS archive passed; its iOS test compile
failed because two assertions called dictionary `isEmpty` as a function. Corrected that
mistake separately in 20510c0; no claim that the earlier tests passed.

Limits: these are monotonic known-row fields, not a total server revision for reactions,
pins or equal-timestamp edits. Cache purge is not a durable tombstone/revocation ledger.
Existing view-once UIs still reveal before consumption acknowledgement; the atomic server
stamp alone does NOT make that flow safe. Public media lifetime, full-thread pagination,
revocation across navigation and legacy credential migration remain open. No autumn
palette changes; backend changes require deployment with the native work.

### View-once reveal requires this request's grant (2026-10-21)

Followed the consumption stamp into the actual renderers. Swift and primary Android
opened the image before the POST completed, so rejection/offline failure still revealed
it. Mirror's main image renderer did not gate view-once at all; its thread used only a
placeholder without an opening action. These were separate from the server stamp race.

- Native consumption now requires `consumed=true` from this request, matching message,
  room, author and thread scope, a live image and a confirmed consumer. A cached viewed
  stamp, replay acknowledgement, malformed response or legacy missing flag is NOT a
  reveal grant. The authoritative burn is still cached when a replay is refused.
- Kotlin repository and Swift session own per-message in-flight gates. Account/room,
  cancellation, foreground and parent-availability checks prevent a late response from
  opening unavailable content. Swift double taps do not cancel/reconsume the first tap.
- Primary Android and Swift room/thread viewers await the result instead of opening
  optimistically. Mirror gates incoming view-once images before constructing AsyncImage,
  hides their captions, and provides explicit grant-based opening in both river and thread.
  Failures are surfaced without revealing the image. Mirror's granted viewer blocks Seen.
- The shared Android lightbox uses a secure dialog for view-once, disables Coil memory/
  disk caching for that request, and closes on stop or expiry. Swift uses a dedicated
  ephemeral URLSession with no shared URL cache, drops view-local decoded data on close,
  hides content/closes on inactive scene or detected screen recording. Live deletion/root
  invalidation closes selected media through the owning surfaces. This does not promise
  that iOS can prevent screenshots.
- Native forwarding rejects view-once, deleted and expired sources. Recipient copy paths
  are blocked for view-once captions; quote composer previews use a placeholder. This
  does not remove already-copied/exported material or redesign the autumn surfaces.

Verification: 106/106 server contracts remain green locally. Added two Kotlin gate tests,
three Swift gate/receipt tests and one Kotlin protocol receipt test; 23 changed/new native
files pass syntax parsing, not native execution. Fixed the newly added MessageMergeTests
actor annotation: 9d4d2bf iOS production archive passed, but test compilation reported its
call to MainActor-isolated timeline from a nonisolated test. The test class now explicitly
uses MainActor. Latest native build/device execution is still required.

Deployment/order: deploy the 9d4d2bf server receipt before these apps. Older servers do not
return a grant flag; new clients deliberately refuse to reveal rather than silently
falling back. A lost acknowledgement, cancellation or image-download failure after a
successful consume can spend the one-time grant without displaying it; there is no
invented reopen permission or automatic consumption retry.

Remaining privacy boundary: media URLs are still public-style cached upload URLs, not
per-view signed capabilities. Server media authorization/lifetime, prior image caches,
other-device screenshots, historical quote copies and full cross-navigation revocation
need further work. Large-thread pagination remains open. Nothing here is a claim of
complete view-once secrecy, whole-app correctness, merge or deployment.


## 2026-10-21 — thread refusal survives offline reopening

Broken path: a thread GET returned 403/404/410, the sheet hid replies, then navigation
recreated its view-local flag. An offline reopen could display the same cached replies
and enable a new send or a retry. This repair keeps an account/gateway-scoped refusal
in existing KV storage; it is not a destructive message deletion or a schema migration.

- Both Android thread surfaces observe persisted refusal before presenting their parent.
  Room reply/room observations exclude denied replies, including after process restart;
  cache clearing preserves the reserved metadata namespace. Swift thread reads/counts
  exclude denied replies and ThreadView consults the shared session/store decision.
- Only a fresh, identity-checked, live canonical parent can clear the refusal. Parent/reply
  cache writes and metadata removal commit together. Ordinary message refreshes and
  transport failures do not clear it. Swift thread-sheet and search-jump requests share
  this validation path. No backend deployment is needed specifically for this ledger.
- Per-root request fences reject superseded responses; starting another request and
  applying a result are serialized with Android's cache mutex / Swift's MainActor.
  An already received Android denial persists even if navigation cancels its caller.
- Composer queue insertion, explicit thread and generic recovery retry, forwarding and
  reply-photo grant opening respect known refusal. Drafts, failed outgoing identity,
  envelope and upload receipts are retained. Revalidation remains available.
- In-memory denial protects this process if persistence fails; disk failure cannot promise
  refusal survives restart. Raw single-message lookup remains available to reconciliation
  and recovery. This is not global cache/media erasure or comprehensive export revocation.

Verification: 106/106 existing server contracts pass locally (no server change in this
tranche); 17 changed native files syntax-parse. Added Room disk-reopen/cache-clear/account
isolation coverage; Swift reopen/isolation/invalid-root rollback/blocked enqueue and
retry coverage; Kotlin and Swift superseded-ticket regressions. These tests still need
native execution on the pushed revision, not merely parser success. bb619bd Android
build/API30/API34 passed; its iOS archive and simulator compile passed but storage tests
had obsolete deletion/consumption expectations. 1ac720f updates those expectations;
its additional viewOnce assertion matches the merge rule for a stored viewedAt stamp.
The latest observed 1ac720f iOS archive passed; other native jobs were still pending.

Remaining: unbounded thread API and Swift's 300-row cached thread window; full lifecycle,
exports/notifications and historical media URL revocation; broader room membership
revocation. Autumn UI tokens/layout are unchanged. This is not whole-app completion.

## 2026-10-21 — queued-thread dispatch and consistent server reads

Follow-through from the refusal ledger found an existing queued send could bypass the
new composer/retry guard. Android immediate dispatch/background drain and Swift's outbox
now check refusal before upload and again before message POST. A denial arriving during
upload retains the completed receipt but holds the original operation as failed; a fresh
thread validation alone does not auto-send that failed operation. Explicit retry keeps
its clientId and avoids another upload. Other queued messages can continue. Swift's live
engine also receives the session's in-memory fallback denial if disk persistence failed.
Three Swift engine regressions exercise pre-upload refusal, in-memory-only refusal and
mid-upload refusal/revalidation/explicit retry; Android uses its existing retained-failure
DROP classification rather than deleting queued content. Actual native execution pending.

The thread server endpoint previously fetched root, participant and replies separately.
It now reads all three in one Serializable transaction, with expiry rechecked after
reply selection. Removed members/deleted, nested or expired roots cannot produce an
accepted snapshot; busy/conflicting reads map to 409, not a persistent access refusal.
Tombstones and viewer-aware serialization remain intact. The wire shape is unchanged;
this server change requires deployment separately from the native source changes.

Verification: four new thread-read contracts pass, including real SQLite membership/
root/room/expiry cases and transaction-client/conflict mapping checks. Full server suite
110/110 passes. Strict TypeScript checking passes for message-visibility.ts (not the
whole application). Four changed native files syntax-parse; that is not compilation or
runtime validation. Unbounded thread paging, global membership revocation, exports and
public media URL authorization remain open. No autumn styling changes.

## 2026-10-21 — realtime readiness is accepted join, not socket transport

Continuing the runtime trace found both native clients publishing connected=true from
the transport connect callback before the relay's asynchronous user-join validation.
Actions driven by that flag could run before user-room routing existed. Swift also set
connected=true from any presence broadcast, even before its own accepted join.

- Android publishes readiness only after a decoded joined acknowledgement; isJoined
  includes that acknowledgement. Disconnect/error/rejection resets it. Connection
  replacement and readiness callbacks share a monitor so a retired socket cannot flip
  the new socket's readiness after its identity check.
- Swift publishes readiness after joined, not transport/presence. Its Socket.IO wrapper
  is MainActor-isolated and explicitly uses handleQueue(.main); explicit reconnect setup
  replaces handlers rather than stacking them. Removed the extra disconnected reconnect
  join emit; the next transport connect sends join once. No automatic offline signaling
  replay was invented.
- Socket.IO v16 source explicitly says the client is not queue-safe and rejects emit
  unless status==connected. Its option contract requires a serial handleQueue. References:
  https://github.com/socketio/socket.io-client-swift/blob/v16.1.0/Source/SocketIO/Client/SocketIOClient.swift
  https://github.com/socketio/socket.io-client-swift/blob/v16.1.0/Source/SocketIO/Client/SocketIOClientOption.swift
- Swift relay/call round-trip tests now use that actor boundary, assert readiness cannot
  precede joined, keep one observer rather than replacing it between phases, and always
  disconnect their clients through defer. No timeout was increased or failure skipped.

Execution evidence: 1ac720f iOS archive passed, but its unit job failed with call-relay
join/offer timeouts (not the previously corrected storage assertions); another socket
round-trip was skipped because its health probe did not find the fixture. Full logs could
not be fetched through the available network hosts. This readiness/queue repair is a
confirmed source-level defect, not a claim to have proven the cause of those timeouts.
fa92095's iOS archive has passed; its tests and Android jobs were still in progress at
last inspection. This tranche's five native files syntax-parse; native execution remains
required. Server code is unchanged from the 110/110 run in 7d3e4c3. Theme untouched.

Still open: disconnected 1:1 call signaling has no durable replay/ack contract; complete
reconnect and lifecycle behavior needs further tracing. Large-thread API/cache pagination
has not yet been repaired. These are not whole-app completion claims.

## 2026-10-21 — call engine binding, stale callbacks and SDP completion

Continued after PR #3 merge, on arena/318a70b4-the-mystrious-chat-app. The checkout
matched merged main before edits. No theme, sign-in or recovery redesign.

Confirmed Android source defects: CallEngine never bound its private CallStateMachine
although dispatch/poll return immediately without a viewer; raw WebRTC SDP callbacks
mutated the machine off-main and lacked a peer/call ownership check; remote SDP was
marked ready before its completion, local SDP was sent before setLocalDescription
success; PeerFailed/deadline terminal paths could omit ReleaseMedia.

Repairs:
- Engine binds/reset-isolates the account owner and captures an account-bound repository
  for outgoing preparation/signals/log writes. Explicit cancellation, a replacement
  attempt, incoming call, account change or relay loss invalidates pending DM lookup.
- All Android SDP/ICE/peer-state/video callbacks hop to main and must own the current
  peer lease/call/account. Lease invalidation precedes close. Local SDP emits follow
  successful application; remote ICE drains and answer creation await remote SDP success.
- Terminal transitions release media once, including timeout and peer failure paths.
  Cleanup attempts native resource disposals independently so one failure cannot skip
  audio-focus restoration or foreground-service stop. Setup errors no longer masquerade
  universally as microphone permission denial.
- Java Socket.IO queues disconnected emits. Call signals now check joined readiness on
  its EventThread before emit, preventing an offline call offer from entering that queue.
  Reference: https://github.com/socketio/socket.io-client-java/blob/socket.io-client-2.1.1/src/main/java/io/socket/client/Socket.java
- Swift signaling exposes actual joined readiness and local transport-dispatch failure
  (NOT peer/relay acknowledgement). Offline start, disconnect during camera/mic setup,
  failed offer/answer dispatch and relay loss terminate/invalidate preparation. Mic denial
  now ends immediately; closing the error does not fabricate a missed call.
- Both engines hold early local ICE until their offer/answer has been dispatched and
  discard that buffer on teardown. Outgoing local failures/cancellation/setup timeout
  before offer dispatch produce no phantom missed-call row; dispatched-call history
  policy remains unchanged.

Verification: 110 existing server contracts pass (no server edit in this tranche).
12 changed/new native files syntax-parse, not compilation or hardware execution. Added
Kotlin owner-reset, setup-failure, timeout cleanup and stale-work regressions; Swift
engine regressions cover offline start, camera/mic disconnect ownership, immediate mic
refusal and no phantom history. Existing microphone/ring test expectations were updated
only where the runtime contract deliberately changed. Native Actions execution pending.
Physical camera, microphone, Bluetooth/audio routing and two-device ICE remain unproven.

Remaining: server acknowledgement/idempotency for call signaling, full reconnect/group
mesh lifecycle, pending call UX while a DM lookup is running, thread paging, and broader
media/export revocation. This is not a whole-app or call-stack completion claim.


## 2026-10-21 — offline Swift thread history beyond the initial window

ThreadView loaded only the latest 300 saved replies before attempting the network.
An offline user could neither scroll to older saved replies nor open an older cached
search/deep-link target. Added scoped SQLite keyset paging over raw (createdAt,id),
including pending local IDs, without relying on the anchor still existing. The query
checks the live canonical root, persistent refusal, reply expiry, and failed-echo
ledger on every page. Removed/undecodable anchors cannot stall cursor advancement.

ThreadView now offers earlier saved replies with stable-ID prepend anchoring, keeps
an expanded cursor across reconnect loads, and searches subsequent saved pages for
an offline target with cancellation/session fencing. Cache absence is reported as
not saved offline, not proof of remote deletion. Denial/pruning clears page state.
The existing autumn appearance is unchanged. The server still returns an unbounded
thread snapshot: this repairs offline reachability, not server/API pagination.

Added GRDB regressions for 450 timestamp-tied replies, a deleted page anchor and new
arrival between pages, pending/failed echoes, foreign room/cursor isolation, and
root expiry/refusal between reads. Syntax parsing and execution of the actual SQL
against Python SQLite passed; these are not Swift compilation or device evidence.

Native Actions for the preceding call tranche exposed two Kotlin assertions: the
pre-offer microphone guard had not actually been applied, and a hangup fixture had
not dispatched its offer. c1a6811 applies the missing runtime guard and makes that
fixture explicitly exercise an already-dispatched call. Native rerun pending.


## 2026-10-21 — Swift group-call admission and mesh lifetime

Group call preparation checked only a broad connected flag, ignored signaling-send
failure, and disconnect teardown did not invalidate suspended permissions. The mesh
accepted answer/ICE without current room/call matching; several async SDP completions
and all bridge callbacks could mutate replacement peers. Opening another conversation
could replace a live call's displayed roster. The join path had no admission timeout.

Repairs:
- Check joined transport readiness before/after permission waits. Teardown invalidates
  preparation, ring work, peer bridges and SDP ownership before native close. Each SDP
  suspension checks call generation AND exact peer identity before the next operation.
- Per-peer negotiation gates suppress duplicate concurrent SDP. Local ICE waits for
  SDP dispatch; early remote/local queues are bounded. Peer failure closes that peer.
- Bind admission to the relay's conversation/call ID; targeted messages must match the
  viewer, roster and deterministic offer direction. Foreign-room state cannot replace
  a live roster; authoritative removal ends local media. Old-call ended events do not
  terminate the admitted replacement call.
- Only the admitted roster starts ring API work; full/rejected joins no longer ring
  other members prematurely. Failed dispatch and unacknowledged join timeout end setup.
- Teardown disables the shared microphone and restores audio only if this engine
  acquired it. Dismiss actions cannot silently reset an active call. Probe completions
  are fenced against a newer probe, room switch, join lifetime or relay roster.

Added controlled permission, rejected-dispatch, admission-timeout, foreign roster,
member removal, suspended-offer and old-bridge regressions. The audio-session seam
avoids requiring physical audio activation to exercise ownership logic. Both changed
Swift files syntax-parse; native compilation/execution remains pending. No theme edit.

Limits: the protocol still has no client join-attempt nonce/acknowledged SDP transaction,
so this does not prove same-room pre-admission replay impossible. Physical multi-peer
ICE/media, Bluetooth, Android group engine parity, cross-device admission ownership,
relay membership authorization and network reconnection need further work.


## 2026-10-21 — Android group-call ownership, SDP and actual entry paths

The Android group engine had parallel defects: mutable-account repository dispatch,
no owner reset, ignored SDP completion, unfenced old peer callbacks, room/call-unscoped
answer/ICE/ended events, and cleanup that enabled the mic before dropping references.
A failing disposal could skip subsequent cleanup. Group emits still used Java
Socket.IO's disconnected send buffer despite disconnect ending the local call.

Repairs:
- Capture an account-bound call repository; account changes invalidate setup, peers,
  rings and probe results. Main-thread peer bridges require their exact registered
  observer and call generation. SDP steps await native completion and recheck exact
  peer identity. Duplicate negotiations are gated; early ICE is bounded/ordered.
- Scope targeted signals to admitted room/call, viewer and roster; stale ended/full
  events cannot terminate an unrelated call. Member removal ends media. Ring API work
  waits for admission and unacknowledged joins time out. Group signaling uses the same
  EventThread readiness gate as 1:1, not disconnected SDK buffering.
- Native releases run independently; mic is disabled and audio/video tracks, peers,
  capturer, sources, helper, factory and ADM are disposed. Partial camera failure also
  releases acquired resources. An idle group teardown cannot stop the 1:1 foreground
  service. Cross-engine exclusion now also applies from 1:1 toward group calls.

Tracing the ACTIVE Mirror shell—not a retired scaffold—found additional dead wiring:
GroupCallViewModel.setIdentity had no caller, GroupCallBanner had no mount, the ongoing
probe learned about rooms only on a start-call tap, group notices had no collector,
and outgoing toolbar calls never requested microphone/camera permission. Wired those
paths, including room close, and guarded delayed permission results by current owner,
room/ring and idle state. Incoming rings expire locally and clear on disconnect. Reused
the existing banner, whose Emerald token is already autumn orange; no theme redesign.

Validation: nine changed/new Kotlin files syntax-parse; added pure signal-ownership
regressions for stale call, foreign room/recipient, self/nonmember sender and missing
admission. All 110 existing server contracts pass (no relay edit in this tranche). Swift thread fixtures now force timestamp ties instead of using wall-clock
creation times. 0073c3a's iOS unsigned device archive succeeded; simulator compilation
also reached unit tests. Native execution for these uncommitted-at-review Android edits
is not yet available. Physical group media and permission UI remain unverified.

Open: relay-side conversation authorization, cross-device admission, nonce/ACK-based
same-room replay fencing, full native engine execution and actual two-device calls.

## 2026-10-21 — muted group calls still rang online members

The group ring route described mute as suppressing every ring, but sent the realtime
banner to all other participants BEFORE filtering only the push targets. Both native
clients could therefore ring an online muted conversation. The route now uses one
participant/mute snapshot and the same deduplicated eligible recipient set for both
channels, excluding the caller and skipping delivery entirely when no one is eligible.
Mute expiry at the boundary restores both channels. No account/auth redesign.

Two focused delivery regressions and all 112 server contracts pass. The shared dispatch
helper passes strict targeted TypeScript checking. This does not solve relay admission
authorization, remote push credentials/delivery or idempotency of repeated ring requests.


## 2026-10-21 — group relay device ownership and replacement calls

The relay keyed group membership only by user ID. Disconnecting ANY other socket for
that user removed the real calling device; unjoined devices could also emit SDP/ICE or
leave the call, and targeted signaling was broadcast to every recipient device.

Group members now retain the admitted server-side socket ID. Only that endpoint may
signal/leave; disconnect of an unrelated device does not change the roster. Signals go
to the recipient's admitted socket. Explicit join from a different device publishes
old-device departure before re-admission, so existing peers close/rebuild rather than
reusing the old media connection. The old endpoint receives its own ended event. A full
target room refuses admission BEFORE evicting an existing call on another device.

Explicit stale call IDs are no longer overwritten with the current server call ID.
Swift's actual mesh path now attaches the admitted ID; Android already supplied it.
Omitted legacy IDs remain compatible, so this is NOT full nonce/ACK replay protection.
Default deployment port stays 3003; tests use an isolated ephemeral listener.

Verification: the REAL relay was launched with Socket.IO clients (not the simplified
native fixture). Regressions exercised unjoined same-account injection/leave, recipient
device isolation, unrelated disconnect, device takeover/reconnect rosters, stale-device
callbacks and full-room admission. All 117 server tests pass; full relay strict targeted
TypeScript checking passes; three Swift files syntax-parse. Native runtime and hardware
results remain separate. Existing sign-in migration behavior was not redesigned.

Open: equivalent 1:1 relay device ownership, conversation membership authorization,
legacy identity/session verification, same-socket identity changes, missing call-ID
legacy sends, and physical multi-device media. GitHub rejected cancellation of obsolete
Actions runs with integration-permission 403; no runs were canceled or checks bypassed.

## 2026-10-21 — 1:1 relay owner loss and competing devices

The equivalent 1:1 relay bug was confirmed: disconnect of an unrelated session removed
an active call. Worse, active disconnect used the unanswered timeout branch, deleting
the server session WITHOUT notifying the surviving endpoint. Duplicate offers could
cancel their own ring as busy, a new offer from another caller device could silently
drop the old call, and multiple callee devices could overwrite the accepted SDP.

Caller ownership is now the originating socket; the first answering callee socket wins.
Unchosen devices stop ringing, cannot overwrite the answer or terminate/inject ICE into
the answered call. Answer/established ICE/terminal delivery targets the actual endpoint.
Unrelated disconnects preserve the call; actual owner loss emits hangup immediately.
Unanswered callee departure cancels only when its last available socket disappears.
Same-owner duplicate offers are idempotent; a competing device/new call cannot evict
an existing call. Relay duration fallback starts at answer rather than counting ringing
(it still cannot measure actual media establishment).

The real relay regression now covers duplicate offers, competing devices, first-answer
ownership, unchosen reject/hangup/ICE, passive disconnect and immediate active-owner
hangup. All 118 server tests and strict relay TypeScript checking pass. Native behavior
on physical devices is not proven by these server checks. Authentication migration,
same-socket identity rebinding and conversation authorization remain deferred/open.


Validation follow-up: the previously dependency-free server-contract workflow had no
Socket.IO packages for the new real-relay regression (the local checkout had them).
Added a narrowly scoped relay dependency install and relay/helper path triggers. The
initial 6bf6279 server-contract run failed; it is not a runtime or native pass claim.
GitHub log download returned EOF and its annotation was tail-truncated; the fresh-job
dependency omission is directly visible in the workflow. A rerun must confirm the fix.


## 2026-10-21 — offline reply links reach their saved thread

After fixing Swift thread paging, tracing the entry path found that both native room
jump handlers still required a successful thread HTTP request before opening ANY cached
reply. Thus older offline thread pages existed but a saved reply link could not reach
its thread at all. Added exact root/reply cache reads guarded by account-scoped storage,
root/reply identity and lifetime, persistent/in-memory refusal and failed-echo exclusion.
The read does not clear denial or pretend to be a complete/validated server snapshot.

Room jump handling falls back ONLY for transport failure, not HTTP access refusal,
superseded validation, decode failure or cancellation. Swift opens the existing cached
thread sheet; both the active Android Mirror path and ChatRoomViewModel use the same
transport-only resolution helper. Existing caller cancellation/session fences remain.

Added pure policy/visibility regressions, Swift SQLite target regressions, and Room
query assertions across denial/reopen. Thirteen native files syntax-parse and the actual
Swift SQL executes correctly for scope/failed-echo/refusal cases. New native execution
is pending. Separately, 0073c3a's iOS simulator build/tests AND unsigned archive succeeded;
090779b's Android and iOS workflows succeeded. The server workflow dependency correction
81a1ead now passes (real relay tests included). These are not physical-device proof.

Still open: the fourteen-page out-of-cache jump limit and efficient server anchor/window
navigation; removing that cap blindly could download arbitrarily large histories. Full
thread HTTP responses remain unbounded. No theme or account/sign-in redesign.


## 2026-10-21 — live-room packets must not replay after disconnect

Continuing beyond calls found that Android voice/stage/space still used raw SDK emit
while its comment promised disconnected packets were dropped. Java Socket.IO buffers
those emits, including voice PCM, transcripts, PTT and moderation/movement actions.
They now share the EventThread joined-readiness/active-socket gate used for calls. SDK
emit errors are contained on that event queue. Actor-bearing repository room sends
also reject a payload whose identity differs from their bound account.

Swift's typed live-room helpers and generic session adapter now share its joined-ready
call dispatch gate rather than emitting during transport-connected/before-user-joined.
No durable-room replay is introduced: existing engines explicitly rejoin on readiness.
These changes preserve the documented best-effort API and the autumn appearance.
Source parsing/diff checking only for this small follow-up; native/device verification
is pending. Congested-but-connected media buffering, stage acknowledgment and relay
conversation/identity authorization remain separate open work, not solved by this gate.


### Offline jump follow-through: legacy Android thread route

The active Mirror sheet already anchors a cached reply from its observed rows, but
ThreadScreen waited for a successful HTTP reply-ID list before attempting any anchor.
Its ViewModel now records an exact offline target only after transport failure and an
account/denial-checked cache lookup; the renderer waits for that target's Room propagation.
A single cached target is kept separate from the full server reply inventory, so it
cannot falsely establish absence/deletion of a different target. Cancellation/refusal
still cannot unlock this route. Added repository-path JVM regressions for online,
transport cache hit/miss, refusal, superseded validation and cancellation; execution pending.

Follow-up traced but not repaired here: thread surfaces do not currently submit visible
reply read boundaries, although server unread counting includes replies. Fixing this
requires explicit read-surface ownership so the obscured room cannot overwrite the
thread's boundary, plus lifecycle/dwell/lock gating in both Android thread surfaces.
Do not call thread Seen/unread complete based on room-only receipt repairs.

## 2026-10-21 — thread visibility owns Seen, not the covered room

Implemented the live-thread read-boundary gap identified above. Swift ThreadView now
measures actual bubble overlap and submits only the last visible live server-backed
root/reply through the existing foreground/lock/600ms dwell/retry gate. A thread lease
keeps the covered room's refreshes from clearing/replacing that boundary. Returning to
the room restores its latest eligible boundary in either dismissal callback order;
retired threads cannot update another thread or account/room. Just before dispatch,
thread expiry/refusal is rechecked against canonical storage, not only the 1s UI timer.

Android's mounted MirrorThreadSheet uses a shared lifecycle-RESUMED 600ms viewport reader,
with host lock/live-surface eligibility, lightbox/confirmation occlusion, exact live
root/reply filtering and bounded retries. It rechecks lifetime before each send. The
legacy, currently unmounted ThreadScreen also exposes this reader with a safe-default
host eligibility parameter. Android incoming-attention leases prevent cleanup of the
obscured room from clearing a newly active thread's notification suppression.

Server explicit message-boundary receipts now transactionally reject deleted/expired
messages and replies with missing/deleted/expired/nested/foreign-room parents. These
refusals leave both lastReadAt and manualUnread unchanged. Deliberate mark-all remains
separate and continues using the latest server row, not the phone clock.

Validation: 119/119 real server contracts passed (including unavailable read anchors and
live relay scenarios); targeted strict TypeScript passed. Twelve native source/test files
syntax-parse and diff checks pass. Added Swift surface-lease, Kotlin visible-target, and
Android attention-ownership regressions; native compilation/execution for these changes
is pending, and device-level Seen behavior is not yet verified. Autumn visuals unchanged.

Still separate/open: offline read-intent persistence after leaving a thread, cumulative
conversation watermark semantics across unseen gaps, and unread aggregation/expiry of
replies whose ROOT is unavailable (existing unread snapshots group by the reply's own
expiry). Do not equate this live-surface repair with all Seen/unread behavior being solved.

## 2026-10-22 — unavailable thread replies stop keeping inbox badges/previews alive

The unread snapshot counted a reply using only its own deletion/expiry fields. A
permanent reply under an expired/deleted root could therefore leave an unread badge
which the user could not clear by opening that thread. The snapshot now aggregates a
same-room canonical-root join in a single SQLite statement, excluding unavailable roots
and grouping each reply at min(reply expiry, root expiry). It does not download message
history or infer authoritative counts from the native cache. ORM unread predicates use
the same visibility rule; values remain bound SQL parameters.

Inbox preview selection now also skips unavailable-root replies and falls back to the
next visible message. Preview-only DTO copies inherit an earlier root deadline, allowing
both existing native offline expiry schedulers to remove the preview on time, even when
that device never downloaded the thread. Canonical database message expiry is unchanged;
root tombstones still use the existing redacted preview serializer.

Added actual SQLite cases for deleted/expired/foreign/nested/missing roots, inclusive
expiry boundaries, mixed root/child buckets, bound values, and preview fallback. Added
Swift/Kotlin offline bucket regressions. Server suite 123/123 passed; targeted strict
TypeScript checks include the serializer and pass; native regression syntax checks pass.
Native execution for this tranche is pending. The prior 21aa7a1 Android build + API30/34
instrumentation and iOS build/tests/archive all passed; that is not physical-device proof.

Still open: offline read-intent persistence after leaving a thread, cumulative-watermark
semantics across unseen gaps, and out-of-cache history-window navigation. No autumn
styling changes. Existing cached envelopes created by older servers do not acquire a
root deadline until refreshed; this change does not claim retroactive offline knowledge.

## 2026-10-22 — conversation actions confirm instead of silently toggling or pretending success

Native Pin requested a toggle, so a lost response followed by retry could undo the pin.
Both native transports now send an explicit desired boolean. The server applies it in
a Serializable transaction; repeated Pin preserves the original ordering timestamp,
repeated Unpin stays removed, and legacy callers that omit the field retain toggle
compatibility. Invalid explicit types are refused; presented credentials are owner-bound.
This does not redesign the remaining legacy tokenless authorization policy.

Android pin/archive/mute/manual-unread mutations no longer persist optimistic flags on
failure. Requests serialize per room, publish only after confirmation, and schedule an
account-owned reconciliation even after cancellation/lost responses. The mounted row
options sheet now unwraps Result rather than throwing it away, stays open with an error
on failure, prevents simultaneous actions, and uses composition-owned jobs. Export and
clear-message actions use the same error/cancellation handling. Swipe actions likewise
stop using detached jobs and surface failure. No theme redesign.

Swift row options now capture the client/session, block duplicate work, retain errors
instead of silently dismissing, and cancel on departure. ChatsViewModel's single/batch
operations also publish only confirmed fields, reject overlapping row actions, invalidate
pre-confirmation list responses and bind every batch to one client/epoch. Cancellation
or account change stops the remaining batch instead of retargeting later iterations.
Legacy sheet callbacks are epoch-guarded as well.

Actual SQLite pin replay/concurrency/isolation scenarios pass; full server suite is
126/126. Strict pin-helper TypeScript passes. Added Swift URLProtocol boolean/failure
transport cases and Kotlin confirmed-action refusal/cancellation/apply regressions.
Native compilation/execution for these changes is pending; syntax checks are not device
proof. Open work still includes disconnected read-intent persistence, gap-aware history,
and broader inbox/action/contact paths, not whole-app completion.


## 2026-10-22 — identity-bound chat opening and concurrent Note to Self creation

Android's mounted New Chat sheet previously raced two directory requests, represented
network failures as an empty directory, detached create jobs from the sheet, and could
silently fail to open a just-created chat before the Compose list received its row.
The sheet now has one debounced directory load with explicit loading/error/retry and
composition-owned, confirmed create actions. Root navigation resolves the exact ID from
the repository snapshot or detail endpoint, applies message-jump metadata with that room,
and rejects superseded/tab-changed/canceled requests. Note to Self previously called the
regular two-member DM endpoint with the viewer's own ID; it now uses the dedicated self
endpoint. Spotlight personal-chat opening uses the same owned, exact-member gate.

Server Note to Self lookup and creation were separate statements, allowing simultaneous
requests/devices to create duplicate private notebooks. The lookup/create now shares a
Serializable transaction with bounded conflict retry and a retryable 409 on exhaustion.
Only a non-group, self-flagged, exactly-one-owner row qualifies; malformed shared rows
are not reused as private notebooks or ordinary DMs. Supplied credentials are checked
against the requested owner; legacy tokenless authorization is not redesigned here.
Existing historical duplicates are not merged or deleted.

Swift Mirror's duplicate-name DM selection and asynchronous-refresh creation race are
replaced with exact member identity and direct navigation using the returned summary.
Creation is client/epoch/cancellation-bound with visible errors; stale profile/stats/
wallet completions cannot cross a refresh revision. **Mounting caveat:** RootView mounts
ChatsView and ContactsView, and no MirrorRootView call site was found in Pulse. These
Mirror changes alone do not repair the primary Swift Contacts/New Chat surfaces.

Full server suite: 128/128 passed; strict TypeScript checks of the touched helper pass.
Seven changed/new native files syntax-parse; Kotlin navigation and Swift identity cases
were added but have not been compiled/executed yet. These checks are not device proof.
Inventory regenerated separately; autumn styling unchanged. Still open: primary Swift
contact actions, cold external deep links missing from the inbox, group/channel creation
lost-acknowledgement ambiguity, durable offline receipts, and history-window pagination.


## 2026-10-22 — primary Swift Contacts and New Chat actually complete their actions

RootView's mounted ContactsView stored an ObservableObject in @State, discarded the
returned DM instead of navigating, and gave each row a local spinner that was never
cleared. It now uses @StateObject and a model-owned pending identity; success hands the
returned conversation to RootView, switches to Chats, and opens that identity without
another detail fetch. The Add Contact callback now uses this same tab handoff. Loading
and action failures remain distinguishable, errors are visible, and directory retry is
available. Directory responses are latest-request/session fenced.

Contact DM/voice/video resolution is one owned task with overlap prevention, captured
client/engine, exact returned-member/type validation, cancellation and epoch checks.
Leaving Contacts, opening another surface/profile, or blocking cancels pending opening;
late responses cannot start a call or navigate through the canceled task. This is not a
claim about physical microphone/camera behavior or the whole call pipeline.

The mounted NewChatSheet likewise cancels creation on dismissal/session change, captures
selection/client before awaiting, disables conflicting edits while busy, and validates
the acknowledged members/type before invoking its navigation callback. It shows an
in-sheet failure and directory retry instead of a disappearing/toast-only failure. Group
lost-response ambiguity remains: the warning tells users to check Chats before retrying,
but durable operation IDs are not implemented here.

Added Swift identity/response/cancellation/refusal regression cases used by these actual
surfaces. Five touched Swift files syntax-parse; native compilation/execution remains
pending. No new server behavior in this tranche (128 server cases passed on f2930bb).
Autumn styling retained. User-profile message actions, Note to Self's refresh-dependent
Swift opening and cold root deep links still need their own follow-through.


## 2026-10-22 — primary Swift Note to Self opens without a second network prerequisite

The mounted ChatsView previously discarded the confirmed self-create summary, awaited
an independent inbox refresh, then set a flag consumed by a change callback. A failed
refresh (or the callback firing before the flag) left an existing notebook unopened.
It now returns and opens the identity-validated create response directly, retains and
persists the confirmation independently of the full inbox, and reconciles afterward.
A full refresh clears that temporary confirmation without replacing other offline rows
with a one-item inbox snapshot.

The Note to Self lookup accepts only a self-flagged, non-group, one-member row owned by
the current viewer. It also checks complete wire summaries in the existing offline row
cache, so a saved notebook can open without the create endpoint. Old cache rows lacking
membership/self provenance do not fabricate a private notebook. Shared/foreign/self-
misflagged responses are refused. The opening task is canceled on leaving the tab,
account change, opening another room or the chat/call composer; response and callback
both check ownership/cancellation. The obsolete open-on-next-refresh flag is removed.

Added Swift cached-offline/returned-response and malformed-notebook regressions. Three
Swift files syntax-parse; native compilation/execution for this change is pending.
The earlier f2930bb iOS archive succeeded; simulator tests and Android were still running
at the last check, and those results do not validate this later change. Autumn unchanged.
Profile/Add Contact asynchronous opening and broader navigation arbitration remain open.


## 2026-10-22 — profile and Add Contact navigation stays with its screen

Following Contacts through to UserPage exposed another dead-end: Message and Rooms in
Common only set the session's room handoff, but RootView mounts exactly one tab and no
Chats receiver exists while Contacts is visible. UserPage now forwards the resolved room
through Contacts' RootView callback, which switches tabs and hands over the same summary.
Message work is explicitly owned/canceled by the profile view, checks session/peer after
awaiting, and validates returned membership through the shared navigation helper. New
message attempts cannot orphan the previous task handle; opening another room, reporting
or blocking supersedes pending messaging.

Add Contact nested its Message button inside its profile button. Those are now sibling
controls with distinct targets. DM creation is owned, identity-validated, cancellation/
account-fenced, and the directory has an explicit retry/latest-load gate. Dismissing the
sheet or choosing a profile cannot later redirect the user through its old DM task.

Profile data reads capture one client/peer/session and check ownership after each await;
failed block-state reads retain known state rather than asserting unblocked. Block/unblock
uses the captured client, applies the confirmed desired state before optional refresh,
and invalidates pre-action block reads so stale profile loading cannot undo confirmation.
View-owned block work also stops updating a replaced profile/session.

Three Swift files syntax-parse; these paths use the previously added native navigation
regressions, but there is no new native execution/device claim. c12b4f6 iOS archive passed
at the last check; simulator tests were still running. No autumn palette changes. Root
external-deep-link arbitration and other feature-tree lifecycle work remain unresolved.


## 2026-10-22 — cold room links resolve outside the inbox and respect newer intent

Android's external pending-room effect only waited for a matching inbox row, so a cold
link outside the current summary page could remain pending indefinitely. It now enqueues
the same owned exact-ID repository/detail resolver as manual navigation and binds the
jump target to that request. The Compose resolver captures the immutable effect-key
request, rather than reading a newer mutable request when an older keyed effect starts;
otherwise an effect restart could cancel/clear a request it did not actually own. Launcher
tab requests supersede pending opens even when requesting the already-selected tab.

Swift root links now use exact-ID offline summaries first, then detail fallback. They
capture client/session/tab and a unique task ticket; later links cancel earlier work,
and changing tab or navigation intent prevents a late success/error from replacing the
new destination. Accepted external navigation dismisses the old root sheet. A shared
navigation generation is advanced by primary chat/contact/composer/profile intents and
back-stack changes. It is deliberately separate from activeRoomId: room visibility can
change during foreground/sheet restoration without representing a new user destination.
The primary asynchronous create/open paths check this generation before navigating, so
an older pending creation also cannot override a newer link.

Added Swift exact-ID cache/detail/refusal, canceled-link/notebook, and navigation-intent
versus visibility regressions; Android reuses the existing exact-ID resolver regressions.
Ten native source/test files syntax-parse. Native compilation/execution for this tranche
is pending; 83eb07e iOS archive passed, not device acceptance. No server change or autumn
restyling. This resolves room selection, not arbitrary message-window pagination: the
14-page message jump limit, offline receipt persistence, lost group-creation ACKs, and
other feature trees remain open.


### Follow-through — capture Android ownership before scheduling, refuse pre-canceled work

The queued root request now stores the bound repository and viewer at enqueue time,
not when the LaunchedEffect eventually begins. It checks that viewer again before
publishing success/error. MainActivity already keys the subtree by viewer/gateway;
this additionally closes the late-start ownership window rather than relying solely
on disposal timing. Both native navigation helpers and the confirmed-action helper
check cancellation before invoking an adapter, as well as afterward: a caller already
canceled must not begin a mutation just because that adapter completes synchronously.
Added Kotlin no-dispatch-after-cancel cases. Five Kotlin files syntax-parse; native
execution is pending. This does not undo a mutation already committed by the server.


## 2026-10-22 — consumed Swift navigation handoffs cannot resurrect during publication

The room/profile subscribers cleared their @Published property inline while handling
that property's publication. @Published emits before the setter completes; a reentrant
nil assignment can be overwritten by the original assignment, retaining a supposedly
consumed route for replay on the next subscription. PulseSession now defers consumption
until the setter finishes and checks a per-delivery UUID plus session generation. A
queued cleanup for an earlier room/profile cannot erase a newer delivery. Root's writable
linked-room input uses the same deferred, delivery-tagged clearing boundary. The room
jump payload remains associated with the original handoff; old cleanup does not touch
a newer jump target.

Added three Combine-subscriber regressions exercising actual PulseSession publications:
room consumption, preservation of a newer room/jump, and profile consumption/newer-route
preservation. These native tests have not executed yet; three Swift files syntax-parse
and diff checks pass. Reference semantic cross-check: OpenCombine's Published.swift
explicitly documents willSet publication (community compatibility implementation, not
an Apple runtime test): https://github.com/OpenCombine/OpenCombine/blob/master/Sources/OpenCombine/Published.swift.
Autumn UI unchanged; this is a targeted handoff repair, not full navigation acceptance.


## 2026-10-22 — visible Seen recovery no longer permanently exhausts after an outage

Tracing the mounted Android room and both thread/legacy room readers found a three-try
cutoff. Swift's session similarly stopped after four attempts. HTTP can recover without
a relay reconnect or a new viewport boundary, leaving Seen stuck until the reader moves
or reopens. These surfaces now retry temporary failures while the same live boundary
remains visible, with exponential delay capped at 60 seconds (and longer server-supplied
Retry-After respected). Dwell, lifecycle/foreground, occlusion, task cancellation and
boundary ownership remain prerequisites. Android's mounted room also re-arms on relay
connection changes and checks expiry immediately before each attempt.

Android read transport previously erased HTTP status/retry metadata into an untyped
IllegalStateException. It now preserves PulseApiException so explicit auth/access/
validation refusals stop polling rather than becoming endless transport retries. Swift
blocks that boundary after refusal until fresh visibility/revalidation; 408/409/425/429
and server/network failures remain recoverable. Server read transaction contention now
returns a retryable 409, not an unclassified failure. No optimistic server Seen is claimed.

Added Kotlin recovery after eight failed attempts, visibility exit, refusal, Retry-After,
and cancellation cases; Swift policy/gate cases; server contention classification cases.
Server suite 129/129 passed; strict read-helper TypeScript and nine native syntax checks
passed. New native tests/builds are pending. Prior 284eb78 Android and iOS workflows both
completed successfully, but that does not validate these edits or prove device behavior.
Inventory regenerated. Autumn unchanged. Durable read intent after leaving/killing the
surface, cumulative unseen-gap semantics and cross-device manual-unread replay ordering
remain open; this change intentionally does not introduce an unsafe offline receipt queue.


## 2026-10-22 — an unreadable outgoing queue is not a successfully drained queue

Swift's countPendingOutbox swallowed SQLite errors and returned zero. The engine could
therefore report a successful empty drain and stop its recovery timer even though sends
remained on disk. The count is now throwing, with all callers adapted. The engine keeps
its last known count plus an explicit storageUnavailable state, reports an incomplete
flush when it cannot determine the queue, and retains its recovery/network triggers.
Only a confirmed empty count stops recovery; no synthetic queue count or delivery is
invented. Local acknowledgement failures retain the same operation identity for retry.

Android already propagated storage failures, but several drain callers only logged them,
and the recovery timer could exit without scheduling another pass. The shared serialized
drain now invokes its recovery callback on non-cancellation exceptions, after releasing
the writer. Startup also enters that guarded drain without an unguarded preliminary count.
Account cancellation is rethrown and does not schedule work for a retired repository.

Added real SQLite unavailability/restoration and acknowledgement-write fault regressions
for Swift, plus Kotlin writer-release/recovery/cancellation cases. Eight native files
syntax-parse and diff checks pass; native execution is pending. Server behavior unchanged
from c815be6 (129/129 passed). No autumn styling changes. This is recovery from transient
storage failures, not repair of a corrupt database or a promise that lost storage can be
reconstructed. Offline receipt persistence and broader feature trees remain open.


## 2026-10-22 — cleanup must not erase newer composer intent

The Swift manager used unconditional room-key draft deletion and swallowed load/delete
errors. A displayed draft could therefore clear newer text, including a journal revision
reserved but not yet committed. The shared write fence now tracks settled revisions;
manager deletion compares both that revision and the exact persisted row under the fence
and SQLite transaction. Confirmed clears still leave tombstones and fence late writers.
Failed-send deletion compares displayed state so a stale refusal cannot discard a retried
send. Manager actions are account/session-owned, refresh the canonical outbox event path,
and expose changed/failed operations instead of claiming empty or complete on errors.

Settings' duplicate one-tap Swift destructive actions now open the review/confirmation
manager. Counts propagate storage errors instead of calling an unavailable queue empty;
footprint uses the active isolated database plus sidecars, not obsolete Documents/pulse.sqlite.
Mounted Android Data & Storage bulk actions now require confirmation, bind the selected
repository before dispatch, use composition-owned jobs, prevent repeat dispatch, and show
storage errors instead of swallowing them in detached IO scopes. This does not yet add
revision-matched bulk deletion to Android or durable deletion of already-in-flight sends.

Added regressions for reserved/committed/late draft writes, stale refusal/retry transitions,
newly enqueued messages surviving snapshot discard, actual account footprint paths, and
throwing total-outbox counts. Fourteen modified native files syntax-parse; diff checks pass.
Native builds/tests for these new edits have not been confirmed. Autumn palette unchanged;
this is not a whole-app completion claim.


Native follow-up: 2951f0d's iOS simulator application build and device archive succeeded,
but test compilation exposed four new SQLite fault-injection writes that select GRDB's
async overload inside async tests. Those calls now explicitly await the write; the
synchronous sender callback retains its synchronous write. Test execution is not yet
confirmed. GitHub check annotations supplied the compiler diagnostics (full log download
redirected to a host unavailable in this environment).


## 2026-10-22 — mounted Android outbox recovery controls

The mounted MirrorSettings Chat section exposed only Copy and Discard, despite the
repository already supporting explicit retry under the original operation ID. It now
wires Retry original through the captured account repository, fences repeated row actions,
and displays outcomes/errors. Discard requires confirmation and a transactional comparison
of owner, room, creation identity and displayed rejection state; a stale failed-send row
cannot discard an operation that has since been retried. Copy and destructive/retry actions
use separate rows so their labels fit small devices. Existing autumn palette is unchanged.

Added Room instrumented coverage for owner/room/identity mismatches, refusal/retry changes
and preservation of later enqueues. The exact production DAO DELETE query was also executed
against local SQLite: seven guard/state checks passed. Five modified Kotlin files syntax-
parse and diff checks pass; native Room/instrumented execution of this tranche is pending.
The Android retained echo is intentionally NOT deleted on rejection: it remains the owner
proof and retry envelope, hidden from normal timelines. That existing behavior was traced
and preserved, not incorrectly replaced by a Swift-style echo deletion.


## 2026-10-22 — photo-less channels and directory navigation

Traced both native create adapters: they correctly omit an absent optional photo, but
POST /channels used undefined for both omission and invalid input and rejected every such
request. Omitted/null/blank photos now mean no photo, malformed types/paths still reject.
Directory responses now provide the same conversationId alias as creation. Swift also
accepts legacy id-only directory rows (id is the room identity), rejecting conflicting IDs.
Creation timestamps, participant and first message now share the one nested create; removed
redundant post-commit DB reads/writes that could report failure after successful creation.

Swift Channels previously dismissed before resolving a room, reused mutable session API
state after suspension, treated failed directory loads as empty, and allowed old photo
uploads to overwrite newer selection. Actions now retain their originating API/session and
navigation intent, cancel/invalidate on dismissal or form cancellation, gate late loads,
serialize row actions, and version photo selection from the start of decoding. Opening
keeps the directory until the exact room resolves. Acknowledged creation is immediately
retained in the list; a subsequent opening failure says created-but-not-opened rather than
encouraging duplicate creation. Unconfirmed/cancelled creation explicitly warns to check
the directory: group/channel lost-ACK idempotency remains unresolved, not papered over.

Added actual directory JSON identity tests and optional-photo/identity server contracts.
Full server suite 132/132 passes (/home/user/channel-recovery-server.log); strict pure-helper
TypeScript and three modified Swift syntax checks pass. Native UI/device execution of this
tranche remains unverified. Channel leave/admin concurrency and directory message-lifetime
semantics are still under review. Autumn styling unchanged.


## 2026-10-22 — member-role response used the wrong viewer

The targeted member promote/demote route serialized its successful conversation response
as the target member instead of the requesting admin. buildConversationDetail includes
viewer-private fields (including myDraft), so this was a real cross-member projection error,
not merely a label issue. The route now retains the validated requester ID and uses it for
the response. Concurrent group/channel role/leave checks remain outside a single snapshot
in several handlers; those are the next governance paths being repaired.


## 2026-10-22 — membership governance is one transaction, not a stale preflight

All three group-member/channel-subscription route families now share serializable
membership operations. Requester authority, target role, last-admin counts, oldest-member
succession and membership writes are read/committed together. Previously, two departures
could each observe the other admin, or a demotion/removal could act on a stale role. Channel
last-admin protection also applies through the generic group leave alias, closing that
bypass; ordinary groups still auto-promote their oldest remaining member and may become
empty when their sole member leaves. Storage/contention conflicts fail explicitly with 409.

Role assignments and authorized removals/additions are repeat-safe. A lost self-demotion
acknowledgement can confirm the already-member state without regaining any admin capability.
Duplicate channel joins do not reset admin role or read watermark. Successful leave events
include the leaver's other sessions. Supplied bearer credentials must match the action owner;
legacy tokenless behavior is retained, so this is not a claim of full authentication coverage.
Former/unknown participants still cannot probe private group counts via repeat leave.

The earlier requester-vs-target private projection fix is preserved and exercised by the
actual targeted PATCH handler with injected transport dependencies, including a mismatched-
credential rejection before mutation. Real SQLite contracts cover competing departures,
demotion/departure, competing self-demotions, successor removal, trigger-induced rollbacks,
repeat operations and ownership. Initial fixture-only date/default-column errors were fixed;
no production error was suppressed to pass them. Targeted 13/13 and full server 145/145 pass
(/home/user/group-governance-server.log); strict helper TypeScript and diff checks pass.
No native appearance changes. Invite joins, broader metadata permission races, durable
creation receipts and native runtime confirmation remain open rather than implied complete.


## 2026-10-22 — invite redemption, rotation and sheet ownership

Invite creation/rotation and redemption now use the same serializable membership boundary.
Concurrent lazy link creation converges (or explicitly returns a retryable conflict); joins
recheck the code inside the membership write transaction, preserve existing role/read state,
and roll back if the list timestamp write fails. Supplied credentials bind to the requester.
Invite characters now use node:crypto randomInt rather than Math.random; the official Node
v22 documentation confirms bounded selection avoids modulo bias:
https://github.com/nodejs/node/blob/v22.x/doc/api/crypto.md#cryptorandomintmin-max-callback .
This is not a durable idempotency receipt for explicit link regeneration.

Mounted Android paste-to-join now recognizes raw codes, native invite URIs and web ?join=
links without contacting the pasted host. It captures input/repository before dispatch,
uses a composition-owned coroutine and respects cancellation. The deep-link sheet ViewModel
has a surface lease, cancelable preview/join work, immutable account ownership, single-flight
join and one-shot result consumption. A previous sheet's result/disposal cannot navigate or
clear its successor. Preview transport/permission errors offer Retry rather than falsely
claiming the invite was reset. Typed API failures survive the repository adapter. Confirmed
join results no longer wait for an inbox refresh; background refresh exceptions are contained
and their debounce slot is cancellation-safe without erasing a successor's slot.

Swift invite previews distinguish definitive invalid links from transient failures, retain
account/navigation ownership across both join and opening, and keep confirmed membership
when only opening fails. Channel actions now also bind ownership before task enqueue. Group
info's invite controls fence duplicate/late rotations, cancel on dismissal, reject stale
detail reloads, disable copying during rotation, and hide the old code after an unknown
rotation result; Get invite link lazily retrieves the current code without forcing another
rotation. Other GroupInfo metadata actions remain under review, not implicitly fixed.

Added native parser, lease/cancellation/retry/outcome and invalid-preview regressions, plus
three real SQLite invite lifecycle contracts. Full server 148/148 and targeted membership/
invite 16/16 pass (/home/user/invite-recovery-server.log); strict helper TypeScript and eleven
native syntax checks pass. New native test execution is pending. Historical native evidence:
94c7404 iOS succeeded after the async fixture fix; 04b33a9 iOS build/tests/archive and Android
build succeeded (Android instrumented jobs were still pending when inspected). Those runs
do not validate these newer invite edits. Autumn palette unchanged.


## 2026-10-22 — conversation-detail reads need membership, not just a room ID

GET /conversations/:id previously loaded by ID alone and serialized the room for any
supplied viewer. It now filters on the viewer's membership inside a read transaction, so
the permission predicate and included message/member snapshot cannot race a removal.
Nonmembers receive the same 404 as an unavailable room and never enter private-field
serialization. A supplied bearer credential must also own the viewer ID. Important limit:
legacy tokenless compatibility remains; this is not a claim that claimed user IDs are fully
authenticated without credentials. That broader authentication migration remains deferred.

Three actual-handler regressions pass with injected storage/transport boundaries: denied
nonmember read without serialization, authorized viewer projection, and mismatched supplied
credential rejection before DB access. Full server rerun for this tiny follow-up is pending;
previous invite tranche was 148/148. No native appearance changes.

## 2026-10-22 — group settings and member picker own their mutations

Swift GroupInfo now claims one write synchronously, captures its API/viewer, and retires
writes and detail reads on disappearance/account change. Rename, posting mode, TTL, slow
mode, personal/room privacy, role changes, removal, leaving and photo changes use the same
ticket gate. An old completion cannot release a successor or publish into a retired sheet.
The sheet host is keyed by account generation and room. Photo ownership begins before
image decoding, and cancellation/owner checks separate decoding, upload and metadata PATCH.
An uploaded file may remain orphaned after cancellation; no upload rollback is claimed.

Member-picker directory reads and additions now have their own lifetime, captured selection,
current-account callbacks, retryable loading and canceled-task fences. Selection is locked
while adding. Confirmed removal is separate from subsequent roster loading; permission or
uncertain transport failures refresh settings rather than retaining stale editable controls.
Personal screen privacy still writes the device-local comfort preference immediately.
Missing/mismatched member, slow-mode and personal-privacy acknowledgements no longer invent
success from request values. Empty explicitly acknowledged additions remain valid duplicates.
The rename prompt/validation now agrees with the server's 48 UTF-16-unit cap.

Conversation metadata PATCH validates every supplied field before changing anything:
malformed photo objects no longer mean remove-photo, and invalid booleans cannot silently
ride along with valid fields. Current participant/admin permission and the write share a
Serializable transaction; contention returns 409 rather than overwriting with stale authority.
Participant-level room privacy remains allowed, including DMs. Supplied bearer ownership is
checked; legacy tokenless claimed-user compatibility remains, not complete authentication.
The response uses a fresh member-filtered requester snapshot, never an unrestricted detail.
Native helpers reuse the photo contract; TypeScript allows explicit .ts imports for the
no-emit build and directly executed server contracts.

Evidence: 156/156 full server tests pass (/home/user/group-settings-server.log), including
SQLite metadata authorization and actual GET/PATCH adapter regressions; strict helper
TypeScript and five Swift syntax checks pass. New mutation-gate/URLProtocol acknowledgement
tests are added, but their native compilation/execution is pending. a25c1bb iOS build/archive
and web/server checks succeeded; Android jobs were still pending at inspection. None of
those historical results validate these new native changes. Autumn styling is unchanged.
Remaining: TTL/slow-mode/personal-privacy endpoints still need their own transactional
permission work; durable operation receipts and broader channel-directory semantics remain
open. This is not a whole-feature-tree or physical-device correctness claim.

## 2026-10-22 — finish the backing TTL, throttle and personal-privacy write paths

The three endpoints left open above now bind supplied credentials and perform current
membership/authority checks with their writes. TTL remains participant-level in both DMs
and groups; slow mode is group-admin-only. Personal privacy updates the matching membership
row directly inside the transaction and cannot update another user's flag. Numeric preset
validation is shared with the write helper. TTL's response uses a member-filtered requester
snapshot, so a later removal cannot expose a detail response. Slow-mode changes notify the
current room recipients; personal-privacy changes notify only the owner's sessions.
Serializable contention returns 409. Tokenless legacy identity remains explicitly unchanged.

163/163 full server regressions pass (/home/user/group-controls-server.log), including real
SQLite permission/revocation/DM-policy cases and actual route-adapter owner, validation,
recipient-scope and post-write membership tests. Strict helper TypeScript and diff checks
pass. This resolves the three endpoint items above, not operation-receipt, authentication,
directory-visibility or native-device coverage gaps. Android's mounted room kebab and room
information actions are the next runtime path being inspected; fake-success TTL fallback
and detached menu actions have been found there but are not fixed by this server tranche.
