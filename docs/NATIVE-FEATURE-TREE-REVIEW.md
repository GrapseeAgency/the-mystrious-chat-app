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

## 2026-10-22 — Android room-menu truth, owned actions and cache projection

The mounted room kebab previously used getOrDefault(requestedTTL) after a rejected timer
change, ignored mute Result failures, and initialized the timer to Off without adopting the
fetched detail. It now shows unknown metadata as Not loaded, adopts confirmed server values,
serializes settings changes before dispatch, exposes pending state, reports errors and
refreshes when the room becomes visible again. A pre-write detail read cannot overwrite
its acknowledgement. Room state is keyed by viewer/room and uses current cached conversation
rows rather than a permanently frozen open-room snapshot.

Mute, TTL, quick-phrase edits and typing no longer launch detached scopes in this scaffold.
They use composition-owned jobs and the captured account repository. Quick phrases adopt
the acknowledged row/deletion instead of turning a later list-refresh failure into an edit
failure. Room-info's host now has the same keyed/bound lifetime and an owned refresh callback.
This does not yet repair every mutation/read-order race inside the larger room-info page.

Android PATCH decoding now treats an invalid acknowledgement as an unconfirmed server
outcome, not an input-validation failure. Metadata responses must belong to the requested
room; requested boolean settings must be present. Slow-mode/personal-privacy ACKs must
explicitly carry the requested value, including zero/false. TTL replies require a supported
explicit value for the right room; neither platform invents the request as a fallback.
Swift and Android TTL notices use the actual returned policy, which can have been superseded
by another participant before the server's detail read. Swift metadata ACKs likewise reject
wrong-room/missing-setting echoes. Incomplete Android metadata reads no longer synthesize
Off privacy/throttle states.

An additional storage race was found: cacheConversation read mutable viewerId AFTER a
suspending Room lookup. Eight conversation-cache entry points now capture the request owner
and repository epoch before network work. The storage transaction maps with that captured
viewer, rejects retired epochs around suspended reads/writes, and rolls back rather than
adopting a response under a changed account. DM/group/notebook creation also returns the
actual cached projection. This is not a durable creation receipt or permission-revocation
solution for already cached media.

Nine changed native files pass syntax parsing; new Android MockEngine regressions cover
missing/false/zero/wrong-room ACKs and authoritative TTL adoption, and Swift URLProtocol
coverage is extended. Native execution for this tranche is pending. Historical 1829490
Android build-test and iOS archive succeeded; iOS build/test was still running at inspection.
The last full server run remains 163/163 at 8b8e8af (no server changes in this tranche).
Autumn styles and colors are retained. Still open: room-info mutation ordering/refresh
races, transport-loss operation receipts, directory visibility and broader device coverage.

## 2026-10-22 — room-info response ownership and explicit membership acknowledgements

Android room-info now fences metadata reads by request ticket, invalidating pre-write
responses at action boundaries. Permission/uncertain failures request current settings rather
than repeatedly offering stale controls. The two privacy switches are single-flight, disabled
while pending, and keep their pending display through the follow-up read rather than briefly
snapping back to the old flag. Repository cancellation is rethrown instead of being recast as
an ordinary failure and allowed into subsequent work. These changes preserve the existing
failed-load/retry state and amber controls.

Add-member responses must contain a distinct subset of the requested IDs; an explicitly
empty list remains an already-member success, whereas a missing list cannot dismiss the
picker as successful. Kick/leave need an affirmative ACK; invite administration needs a raw,
usable normalized code, not an empty string or arbitrary link. Undecodable DELETE mutation
responses are unconfirmed server outcomes. New MockEngine regressions cover those cases;
three Kotlin files pass syntax checks, native compilation/execution is pending.

Important next storage issue: page-level tickets cannot stop an older detail request from
writing into the shared conversation cache before its UI result is discarded. The current
account-epoch fence protects identity, not same-account read/write ordering. Tracing that
cache ordering and post-leave room resurrection is the next repair; not claimed fixed here.

## 2026-10-22 — retire stale detail snapshots at the shared storage boundary

Android now has a per-room mutation-generation fence, separate from account identity and
page request tickets. Concurrent readers do not cancel each other. Group/settings/membership
writes invalidate snapshots at dispatch and completion, including uncertain outcomes;
conversation-update/read relay events also retire older detail reads. Detail and group-meta
loads validate the viewer/room, check their token inside the Room storage transaction around
suspensions, and re-read once if superseded. Old pre-join denials and pre-leave successes
therefore aren't simply promoted into the current cache. Continued contention surfaces a
retryable error; this is not an unbounded retry or a server revision protocol.

Known-room writes share a per-room mutex with confirmed pin/mute/archive/unread actions.
Captured owner/epoch checks surround queued dispatch and storage. Confirmed kick projects a
removal onto the execution-time cached roster; group/channel departure removes the cached
room transactionally. Inbox snapshot versions are invalidated at write boundaries and inside
those deletes, preventing a pre-departure list response from restoring the row. Bulk inbox
refresh also rechecks owner/version after suspended writes and rolls back a superseded
snapshot. Post-mutation reconciliation lives in the repository scope, independent of whether
the initiating sheet remains visible. Channel joins no longer await an unrelated list refresh.

The native channel subscription adapters also needed explicit ACK validation: Android's
unsubscribe DTO defaults ok to true, and Swift accepted absent optional fields. Both now
require actual membership/leave evidence and plausible counts before claiming success;
Swift path IDs are encoded. Empty/error-shaped 200 bodies cannot delete a cached channel
or mark a directory card joined. The optional DTOs remain decoding models, not proof of ACK.

Added four pure fence regressions (parallel readers, both write boundaries, room isolation,
retirement) and channel-ACK cases in Android/Swift transport tests. Seven native source/test
files pass syntax parsing; execution of this tranche's native tests remains pending.
Historical cb75e70 iOS build/test and archive succeeded; Android build-test was still running
at inspection. Server code is unchanged from the 163/163 run at 8b8e8af. Inventory is 343.
Limits remain: no cross-device server metadata revision/operation receipts, no claim of full
HTTP+Room race-injection or device coverage, and broader navigation/media/directory policies
remain under review. Autumn palette unchanged.

## 2026-10-22 — confirmed departure and the alternate group manager

Tracing beyond storage found a second broken boundary: successful leave dismissed the info
surface, but left its sending room open. Android room-info/channel leave and the classic
manager now tell the host about a confirmed departure separately from ordinary Back. The host
closes the matching chat/PiP and same-room pending open. A newer destination still resolving
retains its navigation context instead of being cancelled/re-dispatched by the older ACK;
the departed room is hidden and its read/call-room visibility retired during that wait.
Exit-animation reading additionally requires the exact active room, not just any open room.

Swift closes the info sheet first, holds the composer/read gate through dismissal, then
retires the departed navigation entry. Chats now owns typed room routes with entry identity;
cleanup removes matching older entries without popping a newer destination or later re-entry,
and does not invent a new navigation intent that would cancel an in-flight open. A regression
covers route identity, older duplicates, newer destinations/re-entry and already-retired routes.

The classic Android manager bypassed the earlier room-info repairs: promote/demote/kick
returned Result values to a Unit callback and silently discarded failures; pending was always
false, and refresh failures retained stale authority. Its host is now keyed/account-bound,
refresh is composition-owned, and mutations have one synchronous pending gate, real Result
handling, cancellation propagation, metadata-ticket retirement and failure reconciliation.
Add-members participates in that gate, snapshots selection, disables pending edits, and reports
zero actual additions honestly. Failed metadata clears authority. Existing child loaders also
convert unexpected thrown failures without swallowing cancellation. This does not establish
ordering/idempotency of every webhook/tournament operation in those child sections.

Seven changed native source/test files pass syntax parsing; native execution/device navigation
coverage of these changes is not yet established. Palette is unchanged. Server source is
unchanged; no new server pass-count claim is made for native-only changes.


## 2026-10-22 — durable ordinary group/channel creation receipts and native retry ownership

Ordinary groups and channels still always created another room if the first response was
lost. They now accept owner-scoped creation IDs backed by ConversationCreationOperation.
The room, membership, initial channel post and receipt commit in one Serializable
transaction. Repeating identical instructions returns the existing identity; changing
instructions/kind under the same key is rejected. Receipts survive deletion: a removed
room produces an ID-bound creation_gone verdict, never a replacement. Departed creators
cannot recover a room they no longer belong to. Later metadata/membership changes are not
reapplied from the old creation form. Group member existence is checked only for a new
creation, not before a replay that might reference a subsequently deleted member.

Both native transports use **versioned create-v2 paths**, require the echoed ID and an
explicit replay flag, validate returned identity/ownership and never downgrade to a legacy
POST. An old gateway therefore refuses rather than ignoring the key and duplicating a
creation. The existing account/gateway-scoped durable journals retain the key across
transport failure, cancellation, malformed acknowledgements and app restart. Identical
canonical instructions reuse it; explicit acceptance releases it for a later intentional
new group/channel. Channel creation includes its conversation summary, so accepting/opening
it no longer requires a second unrelated detail fetch. Android persists the room before
retiring the operation; Swift primary New Chat/Channels and the alternate Mirror caller
persist history and explicitly acknowledge the receipt before retiring it. Verified group
replays may contain a roster changed since the original creation. Raw/malformed responses
cannot use that exception without a confirmed receipt.

The mounted Android group sheet also used a detached job, swallowed Result failures and
never reset Creating. It now binds the repository/scope, snapshots the selected instructions,
prevents pending edits, propagates cancellation, resets pending state in finally and displays
failure. Its directory excludes self and distinguishes loading/failure with retry. The autumn
palette is unchanged.

**Rollout requirement:** back up the live DB and apply
`prisma/patches/20261022_conversation_creation_operations.sql`, deploy the server routes, then
release the native clients. The patch is additive/rerunnable and intentionally has no FK
cascade or expiry. Missing schema fails with 503 before creation; clients do not silently use
unsafe legacy routes. No production deployment was performed here. Prisma's declarative
schema records the receipt model; helpers use parameterized SQL, so generated Prisma client
artifacts need not be checked in for the additive patch.

Executed: seven targeted SQLite/entry-point cases and full server suite 170/170; strict
TypeScript checks include the creation helper and both legacy/versioned route entry points.
Added Android transport/version-path/refusal tests and Swift durable-reopen/receipt-retirement/
roster-replay/channel-acceptance tests. Native syntax checks pass, but native compilation,
test execution and device acceptance of this tranche are pending. The existing DataStore
creation journal also has restart/stale-ACK tests. Inventory regenerated, not a safety claim.

Limits: this cannot merge duplicates created by older clients; legacy POST without a key
still creates separately. Recovery keys are per canonical instruction (including uploaded
photo path); pending forms/photo selections are not restored automatically. Editing an
uncertain form is a different creation intent, not a retry. Cross-device metadata revision,
queue/receipt durability beyond these operations, media revocation and other feature trees
remain open. A receipt is evidence of server creation, not proof that the user saw the UI.

### 2026-10-22 — explicit reaction state and owned drawer acknowledgement

Traced the actual chip/picker/drawer actions beyond the earlier transactional-toggle
repair. Both native stacks still sent toggles: after a committed request lost its reply,
a retry could remove the reaction the user intended to add. The primary Swift who-reacted
drawer also dismissed immediately, bypassed the message mutation/cache acceptance path
and discarded the returned row.

- Added `/api/messages/[id]/react-state`, requiring a boolean desired state. Add/add and
  remove/remove are no-ops on replay, scoped to the actor and emoji. The shared serializable
  mutation still checks current membership and message/thread lifetime even for no-ops.
  Existing legacy `/react` toggle behavior is unchanged. Credential-bearing requests bind
  the credential to the actor; this is not the deferred authentication migration.
- Kotlin and Swift use only the state endpoint, verify the receipt, returned message ID
  and actual owner/emoji state, and never fall back to a toggle on older gateways. UI taps
  capture the intended add/remove from the displayed message before asynchronous work,
  rather than deciding to invert a potentially newer cache after suspension.
- Android applies accepted reaction rows with account-session checks and a transactional
  message-event fence. A late full-row acknowledgement cannot overwrite a message event
  observed after dispatch. Primary and mounted Mirror actions retain visible failures.
- Swift river, drawer and alternate Mirror now share a per-session/per-message operation
  gate, lifetime checks, session fences and checked storage acceptance. UI application
  re-reads canonical storage after awaiting; a stale callback cannot retire a newer gate.
  The drawer stays open with pending/error state, cancels its owned task on dismissal,
  and closes on accepted completion rather than on the initial tap. Autumn styles remain.
- Tightened group create-v2 to require `isGroup: true`; malformed versioned group calls
  cannot fall through to legacy DM creation without creation-receipt semantics. Group
  creation also notifies current participants to refresh their inboxes (in 4ca89aa).

Verification: server suite 174/174 passed, including actual temporary SQLite explicit-state
replay/owner/rollback/lifetime cases and a dependency-injected route validation/fanout case.
Strict TypeScript helper/routes check passed with the existing minimal Next declaration.
Fifteen changed/new native files passed syntax parsing. Added Kotlin mocked transport and
Swift URLProtocol coverage for matching receipt/state, replay and old-gateway refusal;
these new native tests/device flows have not yet been executed here. Earlier creation
commit 4ca89aa's iOS archive passed; its remaining native jobs were still running when
checked. That is not validation of this newer reaction tranche.

Rollout: deploy the new reaction-state route before releasing these native changes; no
additional reaction schema patch is needed. The earlier creation receipt SQL patch remains
required for that feature. No production deployment performed. Limits: no total server
revision for all message mutations, no durable reaction-intent queue, and no guaranteed
offline fanout. Identical repeated desired states are safe; concurrent opposite intents
from different devices still follow server commit order. Existing exported media and
broader durable read/history gaps are not claimed fixed by this work.

### 2026-10-22 — complete-or-error bulk history, acknowledged clearing and export sharing

Continued from reactions into export and clear-chat. Android used only one newest-500
page for both. Swift stopped after 24 pages and swallowed later fetch failures/cancellation
into a successful prefix; empty rooms were failures. Swift's clear sweep reread mutable
session API/viewer state between deletions, discarded tombstones and called total deletion
failure "nothing to clear". Mounted Android Mirror export merely named a private cache
file without opening a share chooser.

- Both clients now require the existing `syncVersion: 1` snapshot protocol for complete
  history, with a server-fixed upper boundary, ascending timestamp/ID progress and cursor
  continuation. Empty rooms succeed. Network/cancellation/version/progress failures cannot
  yield a partial successful list. Replies are included, not just visible river rows.
- These in-memory bulk operations explicitly refuse over 50,000 rows or 32 MiB of encoded
  page data before an export/clear starts. This is an honest resource bound, not full-scale
  streaming export support. A streaming design for larger rooms remains open.
- Bulk deletion captures its owner/client/session, checks ownership before and after each
  request, validates each deletion receipt and persists confirmed tombstones. It stops at
  the first unconfirmed deletion and reports confirmed progress instead of false empty/full
  success. Swift's individual delete now also uses the returned validated tombstone rather
  than inventing one from an old message copy. Confirmed deletions cannot be undone by
  canceling later work; a lost reply can still represent a committed deletion.
- Android gates competing bulk work per room and shows detailed errors; Swift owns and
  cancels the sheet task, blocks competing bulk taps, shows pending/failure state, and
  refuses stale-account sharing. Export output names are unique and failed/retired output
  is removed rather than overwriting another export. Mounted Mirror now launches the
  existing FileProvider share chooser with read grants.
- New transcripts suppress view-once content and expired rows at formatting time, and
  Android now masks anonymous authors (Swift also uses an anonymous fallback when an alias
  is absent). This does not revoke any already-shared files, grants or external copies.

Checks: ten changed/new native files parsed, diff whitespace check passed. Added Kotlin
and Swift complete-history tests for more than 24 pages, timestamp ties, empty rooms,
interrupted/old/malformed scans and explicit resource limits. These new native tests and
bulk-device workflows have not run locally. The existing server snapshot/transaction
suite last passed 174/174; this tranche changes native code, not server history semantics.
No new DB patch needed; gateways must already support snapshot sync. No production deploy.

Follow-up verification of the preceding reaction tranche: Android's mocked transport test
caught my incorrect cast of response **text** to JsonObject. Fixed in 9bec3ac by decoding
JSON first; the failed version must not be treated as verified. Creation 4ca89aa's Android
build and iOS tests/archive passed when inspected; reaction e04ead5's iOS archive passed,
while its Android unit test and debug assembly jobs failed. Debug assembly emitted no
compiler annotation and downloadable logs were blocked, so the existing diagnostic step
now exposes bounded build errors too. Replacement native builds/device checks remain
pending; no successful latest-head native build claim is made.

Reaction follow-through: found a second Android call-site issue while tracing the mounted
bubble, independently of the unavailable build log. `MirrorBubbleRow` still required a
`(String, String) -> Unit` callback, while its direct `::toggleReaction` reference had been
changed to accept `(Message, String)`. Replaced that stale method reference with an adapter
that uses the displayed entry's message, preserving explicit tap-time intent. This repairs
the source-level mismatch; replacement native compilation remains pending.

### 2026-10-22 — forwarding is durable acceptance, not nested-result false success

Traced both mounted Android and primary forward pickers. They wrapped a returned
`Result` in `runCatching` and counted the outer success even when the actual forward
failed. Mirror also launched detached work and left its picker live after completion.
The repository posted before persisting, did not durably retain media forwards on an
uncertain reply, and downgraded unsupported rich kinds to text. Swift forwarded before
journaling, dropped sticker/location payloads, discarded successful returned messages,
and consulted mutable session state between targets.

- Android now reads and validates the canonical source and its thread/room availability,
  then atomically commits a new optimistic message and complete outbox envelope before
  any network dispatch. The existing owner-scoped dispatcher handles delivery, retry,
  process recovery and permanent rejection. Stored media, sticker and location metadata
  survive; unsupported poll/system/game/etc. carriers explicitly refuse rather than
  masquerading as text. Forward copies do not inherit source reply/topic/pin/identity.
- Mounted Mirror uses a composition-owned coroutine, captured account/session, actual
  `getOrThrow` acceptance, visible failures and pending reset. Primary multi-target
  forwarding retains only failed selections and no longer dismisses on the initial tap.
  Both say **queued**, not delivered, after durable acceptance.
- Swift uses the same queue-first semantics with a complete envelope and optimistic row.
  The source is reread from storage; source privacy and thread availability are checked
  again in the GRDB enqueue transaction. The synchronous MainActor batch cannot switch
  its client/account between targets; accepted destinations are removed from selection
  before retry. Directory loading is owned, epoch-fenced and can show saved targets offline.
- New Kotlin plan tests cover stable metadata, stripped source routing, rich payloads,
  unsupported kinds and unavailable content. Swift tests cover disk reopen, exact queued
  identity/payload, stale tombstone/thread refusal and unsupported/malformed inputs.

Checks: 174/174 existing server contracts pass; 12 changed/new native files parse and
whitespace checks pass. This native tranche has not been compiled or device-tested locally.
The preceding 9464df1 iOS archive passed when checked; its other native jobs were still
running. No source-availability validation on the server was added: forwarding remains a
client-side content copy, not a permission-bearing server forward endpoint. The copied
intent is not revoked if its original changes after enqueue, and already-shared media
URLs are not revoked. Failed delivery is exposed through the existing outbox recovery.
No schema rollout is required for this tranche; no production deployment or merge.
Autumn palette and styling remain unchanged.

### 2026-10-22 — poll vote/close transaction, frozen cache and owned native actions

Followed poll cards through native actions and both routes. Vote checked open state and
membership outside its write transaction; close did not check current membership at all.
A vote could pass the precheck, then write after closure. Native cached full rows could
reopen a closed card; Swift applied responses to UI before silently ignored store failures,
and mounted Android launched detached vote jobs with swallowed failures.

- New serializable `mutatePoll` helper owns membership, creator authorization for close,
  message/thread lifetime, option ownership, vote movement/close, and returned snapshot.
  Same-choice retries do not rewrite a vote. Closure and vote cannot cross the open-state
  check; failed snapshot reads roll writes back. Repeated close still requires current
  membership and source availability. Contention is an explicit 409, not silent success.
- Routes bind credential-bearing requests to the actor and fan out neutral poll metadata
  to all current recipients, including the actor's other devices. No schema change.
- Native merge policies retain a known closed poll and its final tally across late open
  snapshots or unrelated message updates. Tombstones still remove poll content. This
  protects both local storage and the Swift in-memory river/thread merges.
- Android gates one mutation per poll, captures owner/session, checks returned poll ID,
  selected option/creator closure and writes under the message event/cache fence. A
  confirmed close may freeze only the poll field without undoing newer unrelated fields.
  Mounted Mirror now uses owned coroutine work and visible errors; primary actions also
  surface the server reason and fence enqueue-time ownership.
- Swift uses a shared per-poll session gate, captured client/owner, strict acknowledgement,
  checked storage and canonical UI re-read. Rejected storage or stale-account responses
  cannot become a success toast. Close acknowledgements must actually contain a closed
  poll owned by the closing creator.

Verification: 180/180 server contracts passed, including five actual SQLite transaction,
replay, rollback, lifetime and close/vote concurrency cases plus route validation/fanout
contracts. Strict helper/routes TypeScript check passed (minimal Next type declaration).
Added Kotlin/Swift frozen-state merge cases, Swift GRDB acceptance coverage and mocked
poll acknowledgement tests; native syntax checks passed, native tests/device acceptance
for this tranche remain pending. Forwarding commit 4a3c31c's iOS archive passed when
checked; that is not proof of this newer work.

Rollout requires deploying the changed poll routes/helper. Native freezing does not
retroactively repair votes that an older server already accepted after closure. There
is still no total revision for open poll tallies or a durable offline vote queue. Poll
creation's lost-ack identity is separate and not covered here. Autumn styling unchanged;
no production deployment, merge, or whole-app clearance.

### 2026-10-22 — legacy reaction aliases, unique members and mounted chips

Stored emoji aliases and stable IDs could represent the same visible reaction while
being counted and removed as different database rows. Native old caches also computed
desired state from raw strings, and mounted Mirror chips rendered stable IDs as words.

- Read serialization groups canonical IDs and counts unique members. The existing
  serializable mutation now finds all actor-owned aliases, removes the whole logical
  group, or converges an add to one canonical row. Other people and reaction types are
  untouched; failed snapshot reads roll alias cleanup back.
- JavaScript, Kotlin and Swift normalize text/emoji presentation selectors consistently.
  Primary and mounted native desired-state calculations normalize cached aliases too.
- Primary/Mirror chips and Swift's people drawer deduplicate aliases by member. Mounted
  chips use the existing icon registry instead of showing the raw word `heart`/`fire`.
  Existing autumn colors, chip layout and styling are retained.

Verification: 184/184 server contracts passed, including real SQLite alias cleanup,
explicit replay, legacy toggle and rollback coverage. Scoped TypeScript check and ten
native syntax parses passed. Added Kotlin registry and Swift registry/cache-group tests;
these are not locally compiled/device-tested. No schema migration needed, but changed
serializer/mutation must be deployed together. No durable offline reaction queue, total
reaction ordering or guaranteed event delivery is claimed. Not deployed or merged.

### 2026-10-22 — edit/pin/delete acceptance and action-menu failure handling

Traced the primary and mounted action paths, not just their routes. Android edit/pin
responses unconditionally rewrote cached rows, and deletion accepted any message body.
The mounted menu swallowed failures and called its deletion callback regardless of the
server result. Its Unpin action was actually a toggle. Both Android composers processed
slash commands before checking edit mode; editing `/remind ...` could create a reminder
instead of editing text. The primary editor also discarded the pre-edit composer.

- Android now shares a per-message edit/pin/delete gate, captures account/session before
  dispatch, validates identity/edit content/tombstone receipts, and commits under the
  cache event fence. A late edit advances only text/editedAt across unrelated events,
  respects newer edits, and cannot revive a tombstone. Late pin rows cannot rewind events.
  Explicit unpin uses the same gate and owner/cancellation checks.
- Primary edit completion is fenced by editor generation and text revision, so a late
  success cannot close a different/newly modified editor. Draft and reply context return
  after cancel/success. Both Android editors treat slash-prefixed edits as literal text;
  mounted edits use composition-owned work, retain errors and do not claim outbox storage.
- Mounted menu actions use owned coroutine work and show failures; deletion callbacks
  run only after confirmed deletion. Unpin uses explicit removal. Save/task errors are
  no longer swallowed. Corrected the prior chip change to dispatch Phosphor glyphs to
  the Phosphor renderer (not Lucide's missing-glyph fallback), retaining autumn styling.
- Swift validates edit/pin HTTP receipts and shares account-owned edit/pin/unpin/delete
  acceptance across the primary room and mounted pin drawer. Captured client/store,
  cancellation, identity, one-action gate and canonical reread precede UI success. Edits
  overtaken by unrelated events patch only text with monotonic edit merging; deletion
  always redacts. Pin UI no longer writes an unpersisted HTTP row first.
- Server pin toggle now uses the same serializable membership/lifetime/write/snapshot
  verdict as edits/deletion. Pinning an expired/deleted/unavailable thread is refused;
  explicit unpin can clean a stale pin but still requires membership. Delete/unpin no
  longer read a different post-transaction snapshot for their pin receipt/fanout. Actor
  devices receive neutral pin updates; credential-bearing POSTs bind the action owner.

Verification: 187/187 server contracts pass, with added actual SQLite pin/deletion race,
permission/lifetime, replay and snapshot rollback cases. Scoped strict TypeScript passes;
14 native files syntax-parse. Kotlin receipt/merge and Swift mocked receipt/GRDB tests are
added, not executed locally. Earlier 9537f11 iOS archive passed when checked, not validation
of this tranche. No schema change or production deployment. Legacy positive Pin remains
a toggle, not a durable/replay-safe set command, and pins still have no total revision.
Saved/task backend ordering, durable edit recovery and broader action paths remain open.
No whole-app clearance; autumn palette/layout unchanged.

### 2026-10-22 — explicit native pin-add after uncertain acknowledgements

Followed the remaining positive-Pin toggle called out above. A repeated Pin after a
lost response could remove the pin. Native Android/Swift now use `pinMessage` and the
new `/api/messages/{id}/pin-state` endpoint with `pinned:true`; unpin remains an explicit
removal. The server checks boolean state, commits using the shared serializable helper,
and returns an identity/state receipt alongside the transaction snapshot. Repeated adds
keep the original pin stamp and attribution; they do not toggle or steal another pinner's
credit. Legacy web toggle remains supported separately.

Both native decoders require the matching receipt and a live, actually pinned message.
There is no fallback to the toggle route: an older gateway fails visibly rather than
inverting state. Updated primary/mounted calls and Kotlin fake repository implementations.
Added real SQLite replay/attribution and route validation/fanout cases, Kotlin MockEngine
receipt/no-fallback checks, and expanded Swift mocked receipt validation.

Verification: 189/189 server contracts, scoped strict TypeScript, 13 native syntax parses
and whitespace checks pass. Prior 077ba72 iOS archive passed when checked; these new
native tests have not run locally. No DB migration; deploy the new backend route before
clients if Pin must remain available throughout rollout. No total pin revision, durable
offline pin queue or exactly-once ordering across another person's intervening action is
claimed. No production deployment/merge; autumn styling unchanged.

### 2026-10-22 — bounded native thread transport and complete acceptance

The native thread GET returned every reply in one unbounded body. Replaced native use
of that endpoint with `/messages/{id}/thread-page`: 1..200 replies per request, stable
createdAt/id ordering, root/room-bound cursors and an initial upper tuple. Each page
rechecks membership, root lifetime and replies inside the existing serializable read.
Raw page positions advance even if the entire batch expires before delivery. Removed
cursor rows do not stall continuation; new ordinary tail arrivals do not extend it.

Kotlin and Swift now assemble validated pages before accepting any as complete thread
history. Missing versions/metadata, wrong roots/rooms/reply ancestry, reordered rows,
repeated cursors, cancellation and later-page errors cannot become a successful prefix.
There are explicit 10,000-reply / 16 MiB encoded-data / 512-page safety ceilings, not
silent truncation. Exceeding them retains existing cache and surfaces a failure; a fully
incremental, virtually rendered server-paged thread beyond these ceilings remains open.

Known thread refusal codes retain 403/404/410 semantics. A missing new route or an
unrelated proxy/credential denial is converted to retryable validation failure, not a
persistent "this thread was revoked" ledger entry. No legacy unbounded fallback. Android
thread acceptance/denial writes also capture and recheck owner/session. Swift no longer
silently drops individual cached replies when a storage read throws. Mounted Android
shows the actual thread-load failure reason. Autumn presentation is unchanged.

Verification: 194/194 server contracts pass, including five new actual SQLite paging,
tie ordering, cursor deletion, membership/lifetime, expiry-only-page and input cases.
Scoped strict TypeScript and 13 native syntax parses pass. Added Kotlin/Swift page-loader
and mocked API cases for continuation, refusal classification and rejection of partial
results; native tests/device verification are not run locally. Prior 27ed91d iOS archive
passed when checked, not validation of these new files. No schema change; deploy the new
route before native rollout. Legacy web thread GET is unchanged. Pages are separately
authorized snapshots, not one database snapshot across the whole load; same-time
backfills and edits still require later synchronization. The independent 14-page main
river jump limitation and durable offline read receipts are not fixed by this tranche.
No deployment, merge or whole-app clearance.

### 2026-10-22 — navigation is not Seen, identity-bearing read acceptance

Found an eager mark-all still wired in primary Android chat-list navigation: tapping
any ordinary/archive/search conversation called `markRead` before a room or message was
visible. Removed it. Existing room/thread viewport dwell now owns automatic receipts;
manual mark-all remains a separate deliberate action.

Both native clients previously treated any successful empty HTTP response as confirmation
of read state. Added `/conversations/{id}/read-state` with mandatory explicit `visible`
(with anchor) or `all` intent and authenticated ownership. The response binds version,
reader, conversation, mode, message boundary and committed timestamp. Kotlin/Swift require
that exact receipt and validate that the watermark reaches the visible cached message.
Missing or malformed receipts cannot retire the visibility retry gate. No fallback to a
legacy endpoint that might ignore an anchor and mark everything read. Legacy web route
remains available separately.

Android rechecks the cached anchor/owner, preserves cancellation and returns storage
failures instead of crashing manual actions. Manual/batch actions capture their account
before scheduling; batch progress only counts distinct IDs. Swift batch mark-all no
longer writes unread=0 over arrivals received during the request: canonical inbox refresh
owns counts, and accepted selections are removed so retry only targets failures.

Verification: 198/198 server contracts pass, including route intent/receipt/privacy/refusal
coverage and a navigation regression guard; existing real SQLite watermark tests remain
passing. Scoped strict TypeScript and ten native syntax parses pass. Added Kotlin/Swift
mocked acknowledgement tests for identity, mode, dates, missing/false bodies and old-route
refusal. Native tests/device verification are separate; preceding 6f1d88a iOS archive
passed when checked. No schema change; new route rollout required before updated clients.

This does not add durable offline read replay or per-message/per-thread unread accounting.
The server still uses a cumulative conversation timestamp, so skipped older messages,
same-timestamp boundaries, thread-versus-river gaps and manual-unread ordering remain open.
No production deployment, merge or full-app clearance. Autumn theme unchanged.

### 2026-10-22 — saved/pinned selections honor known thread availability

Followed detached saved previews after thread refusal, root deletion and expiry. Saved
SQL joined the child only: a known rejected/dead thread could still expose its reply
through the library. Server saved/pinned selection also checked child lifetime without
consistently checking the canonical root.

- Android Room and Swift GRDB saved projections now depend on the thread-denial table
  and cached parent. Known deleted, nested or foreign-room roots hide their saved replies.
  Unknown offline parents remain unknown, not falsely classified as revoked. In-memory
  denial state also filters the library if a refusal could not be persisted.
- Cached parent expiry becomes a **preview-only** deadline; existing native expiry timers
  hide the saved row at that deadline without rewriting canonical reply expiry. One-time
  messages are excluded from detached saved previews. Swift retains a checked store as
  the only display source rather than falling back to unvalidated HTTP rows; observer
  results and account changes are fenced. Android saved refresh cannot overwrite a new
  session with an old account's late snapshot, and cancellation is not rendered as error.
- Server saved and pinned lists use serializable read snapshots with current membership
  and live root checks, including cross-room root rejection at delivery. Credentialed
  selection requests are bound to the owner. Saving one-time or unavailable-thread rows
  is refused. Pinning one-time content is now refused inside its existing transaction;
  old private pins can still be explicitly removed. Native pin receipts reject them too.

Verification: 200/200 server contracts pass, including actual SQLite saved-selection
ancestry/privacy/membership coverage and one-time pin refusal. Both native saved SQL
queries were executed directly against SQLite for unknown/dead/foreign/denied parents.
Added Room instrumentation and GRDB tests for denial, canonical-vs-preview expiry and
one-time filtering; existing Kotlin expiry tests now include one-time rows. Scoped strict
TypeScript, 13 native syntax parses and diff checks pass. Native compiler/device checks
remain separate.

No schema migration, production deployment or merge. The library still has its existing
server cap of 100; this is not a complete paginated saved-library redesign. Offline
expiry enforcement uses known cached parent metadata: an unknown parent's deadline is
not invented. Legacy save-add remains a toggle with pre-write permission checks, not a
new atomic/replay-safe save protocol. Media URLs are not revoked. Autumn theme unchanged;
no full-app clearance.

### 2026-10-22 — explicit saved-state writes and truthful native acceptance

Primary Android/iOS menus labelled **Save** invoked a toggle: tapping Save again or
retrying a lost response could silently remove the bookmark. Save checks and creation
also used separate reads/writes, and native timestamps came from the phone rather than
the committed first-save time.

- New serializable saved-operation helper owns add authorization, message/root lifetime,
  one-time refusal, desired-state write and final receipt. Retried adds preserve the
  first timestamp; removal touches only this owner's marker and stays available after
  departure/deletion. Snapshot failure rolls writes back; contention returns 409.
- Native `/save-state` requires explicit boolean state and owner credentials. Both
  decoders validate owner, message, state, version and server timestamp; no legacy toggle
  fallback. Legacy web toggle/removal now share the atomic helper without changing their
  established response compatibility.
- Primary Save always adds. Mounted Android's labelled Save/Unsave sends that exact
  desired state. Android serializes marker acceptance with library refresh and checks
  owner/cancellation before dispatch/commit. Swift shares a session action gate and
  saved-revision fence between room/library; marker storage must succeed before toast.
  Neither path rewrites current content from a stale tapped snapshot.
- Native removal remains recoverable on failure and no longer swallows cancellation or
  updates another session's UI. Corrected three Swift test `.isEmpty()` calls to Boolean
  property access in b672d83 after the preceding runner exposed that compile error.

Verification: 206/206 server contracts pass, including six new SQLite/route cases for
replay, original timestamps, privacy/ancestry, owner isolation, departure, contention and
receipt rollback. Scoped strict TypeScript, 17 native syntax parses and diff checks pass.
Kotlin/Swift mocked saved-state receipts and old-route refusal coverage were added; native
compiler/device verification for this tranche remains separate. No schema change; deploy
the state route before clients. Saved pagination/cap, durable offline intent and global
cross-device saved ordering/refresh remain open. No deployment, merge or full-app claim.
Autumn styling unchanged.

### 2026-10-22 — event participation, attendance/XP and scheduling acceptance

Traced events through both native surfaces and the API, beyond message actions.
A stale RSVP authorized check-in after departure; attendance was stamped separately
from XP, so a failed reward was permanently lost on retry. RSVP/delete authorization
and creation/list snapshots were also detached from their writes/reads. The primary
Android form discarded user input immediately and interpreted local times as UTC.

- Serializable event mutations now recheck current membership, creator/admin deletion,
  RSVP status, attendance window and tally. Attendance, +15 XP and final count commit
  together or roll back together. Replays return the original attendance with no second
  bonus, including after the window closes; departure still denies historical replay.
  Presented credentials are bound to the actor; existing non-bearer browser handling
  is preserved, not an authentication redesign.
- Create/list permission, data and name snapshots now share a transaction. Creation
  remains non-idempotent across lost responses; no claim that retry duplication is solved.
- Native RSVP/check-in/delete acceptance requires version, exact owner/event, canonical
  row/tally, and a real attendance stamp/consistent XP verdict. Swift no longer fabricates
  an empty-ID event on missing creation data. Both reject incomplete/foreign creation.
- Primary Android actions are awaited, per-event gated and account-bound. Form fields
  survive failed creation and edits made during a request; polling cannot overwrite a
  pending mutation. Read failure no longer pretends an empty list is confirmed. Check-in
  window refresh actually invalidates Compose time state and stamped rows stop inviting
  another XP claim. The sheet lifetime is keyed to room/account.
- Shared strict local-date parser handles Dhaka +06:00 correctly and respects explicit
  positive/negative ISO offsets. Impossible dates and DST gaps/overlaps require correction
  or explicit offset, rather than rolling time forward. Both Android event surfaces use it;
  the primary reminder absolute-time fallback benefits too. Mounted Swift creation captures
  its API/session before task dispatch, fences completions and cancels on disappearance;
  fields cannot be silently edited then discarded during submission.

Verification: nine new actual SQLite/route cases cover revotes, departure, window endpoints,
atomic XP failure/retry, final-snapshot rollback, concurrent check-in, delete authority and
owned routes. Full server suite 215/215; scoped strict TS, 14 native syntax parses and diff
checks pass. Added native receipt matrices and Dhaka/DST parser tests; native compilation/
device results for this event tranche are not yet known. Earlier saved-state archive and
server checks passed; its native build/test jobs were still running at last inspection.

Remaining: create lost-ACK identity, bounded event history/list pagination (cap 50), global
cross-device revision/ordering, durable offline actions and persisted native event snapshots.
Deploy backend receipt changes before these native clients. No schema change, deployment or
whole-app clearance; autumn appearance unchanged.

### 2026-10-22 — private activity boundary discovered through task conversion

Following message→board conversion revealed a separate, urgent disclosure: its
message-derived task title is written to LogEvent, and `/api/hub/logs` returned
all actors' raw rows/metadata. Anonymous-send rows linked the real sender to the
room/message ID; private folder names, transfer details and whiteboard reset
journal rows were also eligible for this global feed. This is not public activity.

- Activity GET now requires the existing credential to own `userId`, queries only
  that actor, omits internal/unknown journal categories and orphan rows, and sends
  `private, no-store` plus `Vary: Authorization`. Kind filters cannot bypass owner
  scope. No operation journal is deleted or rewritten (whiteboard correctness kept).
- Versioned `{activityVersion:1,userId,logs}` is required by both native APIs; every
  row must identify that same owner. Even an empty legacy/global response is refused.
  Android repository rejects account/session changes before accepting a result.
- Primary Hub reads capture their API/account before launching, discard superseded
  completions and keep genuine history on refresh failure. Mounted Android now loops
  every 12 seconds while STARTED (the previous code refreshed only once after the
  initial load), uses an account-keyed lifetime and reports failure instead of clearing
  history into a fake empty result. Swift Hub state resets with session generation.
- Browser query/cache keys are owner/version scoped and validate the receipt too. The
  legacy demo browser has no credential injection in `apiJson`; it now displays a
  verified-session error rather than receiving private data by supplying a user ID.
  No new sign-in/recovery flow or anonymous-access fallback was introduced.

Verification: three new real SQLite/evaluated-route tests verify actor isolation,
anonymous metadata exclusion, kind scoping, journal retention and denial before DB reads.
218/218 full server contracts, scoped backend TS, Hub TSX syntax-only check, 12 native
syntax parses and diff checks pass. Added Kotlin/Swift wrong-owner/legacy/orphan receipt
matrices and updated the Swift wire fixture. Full native/React type checking for this
tranche is still separate. Event Android build exposed one missing Compose import;
8d83ff9 adds `rememberCoroutineScope` (subsequent native result not yet known).

Important: this is a source correction, NOT a verified production closure. Backend
rollout is required; previously returned activity cannot be recalled. Task-board source
privacy/lifetime, operation receipts/ordering and creation retry identity are still under
investigation; this urgent leak interrupted that trace. Wallet/task private read paths
also need continued inspection. Autumn colors/layout preserved; no merge/deployment claim.

### 2026-10-22 — task-board source boundaries and canonical action acceptance

Traced room board create/convert/move/assign/delete through Kotlin, Swift and API.
The conversion handler fetched deletedAt but never checked it; expired/view-once
content and inaccessible roots could become permanent titles. Permission checks,
append-position reads, writes and activity/receipt work were separate. Swift synthesized
success cards from empty responses; Android accepted default-filled DTOs. Primary Android
cleared the title before an asynchronous creation had succeeded.

- Added a serializable board operation shared by read/create/update/delete. The existing
  credential must own the actor, membership/assignee/creator/admin checks happen within
  the operation, and implicit append ranks cannot be concurrently calculated from stale
  snapshots. Replaying a move to the same column retains position. Strict integer bounds
  reject coerced/null/overflow positions. Receipt/name/journal failures roll back writes.
- Conversion rejects deleted/expired/foreign/missing sources and unavailable roots.
  View-once/disappearing sources (including a root deadline) cannot become permanent
  tasks. Explicit edited titles are respected after source validation. Provenance is
  returned (previous serialization omitted it); activity no longer copies plaintext titles.
- Both native APIs require a versioned owner/room receipt and canonical card identity,
  timestamps, desired title/column/position/assignee/source state. No empty/bare-card
  success fallback. Delete requires an exact positive receipt. Board reads reject wrong
  room/owner, duplicate rows and incomplete legacy responses.
- Primary Android awaits actions, gates writes/poll acceptance, preserves failed/newer
  title drafts, and keys the sheet to room/account. Conversion acquires pending state
  before launching, and reads/writes reject retired account completions. Swift conversion
  and mounted creation capture their API/session before dispatch and fence UI results;
  mounted creation cancels on disappearance without erasing an edited draft.
- Existing 500-card truncation is now an explicit complete-or-error boundary: oversized
  existing boards return 413 rather than a misleading prefix; additions at capacity fail
  before writing. Incremental/paginated large boards remain open.

Validation: eight real SQLite/route cases cover source/privacy/ancestry, custom title,
provenance, explicit moves, permissions, rollback, concurrency, capacity, position bounds
and denial before DB access. Full server suite 226/226, scoped strict TS, 14 native syntax
parses and diff checks pass. New native canonical-receipt matrices await runners/device
validation. Previous b70badd Android build and API30/API34 instrumentation passed; Swift
archive passed but thread tests failed because two fixtures omitted required `kind`.
9e3b08f corrects those fixtures without weakening production decoding or assertions.

Boundaries: backend rollout required; legacy credential-free browser board access now
fails closed (no sign-in redesign). Creation lost-ACK identity, cross-device revisions,
durable offline board work, retroactive removal of previously copied titles and large-board
pagination remain unsolved. No schema/deployment/whole-app correctness claim. Autumn unchanged.

### 2026-10-22 — personal Hub ownership, task acceptance and cache races

Wallet history and all personal-task CRUD routes trusted a supplied userId rather than
binding it to the requester. Mounted Android moved/deleted tasks optimistically in
unstructured IO scopes, and creation cleared input before any verdict. Shared cache
read-through code recomputed the active viewer after HTTP completion, allowing a delayed
page to be labelled with another session's cache key. Swift task reads/writes had analogous
late API/store capture and refresh races.

- Wallet GET and personal-task GET/POST/PATCH/DELETE require the existing actor credential
  before data access. Task writes include immutable ownerId in their final DB predicate.
  Wallet initialization/balance/ledger serialization is one Serializable snapshot with
  deterministic ledger ordering and private/no-store headers; failed history lookup rolls
  initialization back. Public aggregate swap rates were not made account-private.
- Personal-task responses identify their owner. Both native APIs reject incomplete/foreign
  tasks, invalid timestamps/status/title, ignored mutation intent and unconfirmed deletion.
  Wallet reads verify wallet ownership and require an actual ledger array rather than
  accepting an empty/default success. No bare Swift mutation-object fallback remains.
- Native task controls now wait for acceptance, retain failed/newer drafts, gate conflicting
  actions and refuse pre-write/superseded refresh results. Mounted Android no longer launches
  unstructured IO scopes that optimistically move/delete rows; its task surface is owner keyed.
- Android snapshot reads capture owner/key and check account/session before cache persistence
  and UI return. Task reads and mutations serialize; the previous task snapshot is invalidated
  before dispatch so an uncertain outcome cannot resurrect old task state from disk. A local
  invalidation failure prevents dispatch. Verified read caching remains best-effort, cancellation
  is rethrown, and legacy unlabelled task caches are ignored via the v2 owner key.
- Swift captures task API/store/epoch before task launch, validates cached owner, invalidates
  cache before mutation and fences late reads. Targeted cache deletion retains other owners'
  data and private thread-denial journals. Hub state remains reset by session generation.

Validation: four real SQLite/evaluated-route contracts cover missing/wrong owner denial
before data access, financial/task isolation, valid intent and exact deletion, and wallet
snapshot rollback. Full server suite 230/230; strict scoped TS and 11 native syntax parses
pass. Added Kotlin/Swift wallet/task receipt matrices and Swift targeted cache invalidation
coverage. Native compilation/device validation for this tranche remains separate.

Boundaries: deployment required; legacy credential-free browser personal data actions now
fail closed, without changing sign-in/recovery. Creation lost-ACK deduplication, task history
pagination, durable offline task writes and cross-device action revisions remain open.
Legacy wallet mutation endpoints still require a separate bypass review; these read guards
are NOT a claim that all financial endpoints are safe. Autumn preserved; no full-app claim.

### Legacy monetary entry points — ownership bypass repair

- Closed direct HTTP bypasses around the protected v2 transfer/swap routes: legacy transfer, daily check-in, swap, market purchase and listing creation now require the existing credential to belong to the supplied actor before business-data access. No sign-in/recovery redesign.
- Market history can no longer be obtained by supplying another seller's ID. Anonymous open listings and aggregate exchange rates remain public; public/other-seller listings never expose buyer details. Market snapshots are private/no-store with Authorization variance.
- Listing creation and its activity event now commit in one Serializable transaction; a journal failure cannot leave an apparently failed but published listing. Transfer/swap/listing prices reject boolean/string coercion instead of silently charging a coerced amount.
- Added six evaluated-route/real SQLite regressions using the actual session-token and ownership helpers: missing/forged/other-owner credentials, authorized direct mutations, purchase replay, sold-history privacy, transaction rollback and malformed monetary input. Full server suite: 236 tests passed; scoped TypeScript and diff checks passed. These are not native UI/device verification.
- Still open: native marketplace read/mutation lifecycle and draft acceptance, listing-creation lost-ACK identity, market pagination, legacy transfer/swap clients without operation IDs. Existing native v2 transfer/swap idempotency remains intact. Requires backend rollout; credential-free private web actions intentionally fail closed. Autumn theme unchanged.

### Marketplace native acceptance, drafts and stale results

- Marketplace GET/create/buy now return `marketVersion: 1` and the verified actor; buy additionally names the purchased listing. Creation returns the actual seller profile rather than an incomplete row with a blank native seller. Listing order now breaks timestamp ties by ID.
- Kotlin/Swift require the version/owner, unique IDs, usable seller/title/price/asset/time and consistent open/sold/private-history state. Creation must confirm the submitted title and price; purchase must confirm the requested listing and a canonical owner wallet. A sold row with a deleted buyer remains valid (the schema deliberately nulls that relationship). Old bare/unlabelled responses are refused, not announced as success.
- Android repository market operations capture/fence the account. Primary Android and Swift market models capture their repository/client before scheduling, invalidate old reads around mutations, gate repeat taps, share the wallet gate for purchases, and reject late results after session replacement. Accepted purchases immediately retire the obsolete open row. Failures refresh without inventing success.
- Primary Android/Swift listing forms keep their draft and remain pending until confirmation, with inline failure text and conflicting controls disabled. Loading/error/known-empty are distinct; failed reads preserve prior confirmed rows. Android mounted Market now uses composition-owned main-thread coroutines, an owner/epoch key, an owned repository and RoomRefreshGate instead of detached IO coroutines, optimistic draft clearing and failed-read-to-empty fallback. Header refresh callbacks are also structured and owner-fenced.
- Added Kotlin/Swift marketplace acceptance matrices (including unrelated purchase receipts, ignored listing fields, leaked buyer details, foreign sold history and deleted-buyer history), updated positive wire fixtures and added three Android market gate/callback regressions. These native tests are authored, not executed locally. Evaluated HTTP routes with SQLite: full server suite remains 236 passing; scoped TypeScript, 13 changed native syntax parses and diff checks passed. Syntax parsing is not native compilation or device verification.
- Open boundaries: listing creation can still duplicate after a lost acknowledgement; no durable marketplace creation journal/offline draft persistence; no pagination beyond the existing 60-row board; seller/buyer account deletion and broader wallet surfaces still require wider lifecycle review. Backend must roll out the versioned contract before upgraded clients can accept market responses. No theme changes or whole-app completion claim.

### App connections and community joining — owned state and truthful navigation

- Closed supplied-user-ID impersonation in app connect/disconnect/join and personalized reads using existing native credentials. Anonymous catalog previews remain public. Noncanonical app IDs (`01`, `1.0`, `1e0`, etc.) no longer create separate installation counts or shadow communities.
- Added `hub-app-operation.ts`: Serializable connection writes plus exact returned snapshot; bounded six-person connector preview with separate count/owner lookup; atomic community creation, founder membership, idempotent joining and final membership snapshot. Concurrent first joins converge through the unique app key and retryable conflict response. No migration required.
- Community directory responses explicitly select safe identity/count fields. They never contain drafts, invite codes, messages, streaks or read markers; nonmembers receive no roster. Joined users receive the roster needed by the current native panel, not full private conversation detail.
- Added versioned app/owner receipts and Kotlin/Swift checks for requested connection state and actual community membership. A preview is never a successful join acknowledgement. Android mounted App detail no longer opens a room after a failed join by falling back to a GET preview ID; direct Open also requires confirmed membership.
- Mounted Android App detail is keyed by app/account/session, uses a bound repository and structured main-thread work, preserves confirmed data on failed reads, invalidates pre-mutation reads and shows action failures. Kotlin primary and Swift Hub models capture owner/client before scheduling, gate per-app mutations, fence reads and navigation callbacks, and update connection rows only from accepted receipts. Swift displays inline pending-action errors. The primary Android My apps scan now covers all catalog entries, not only the first 24, with sequential bounded traffic and per-app read fencing.
- Seven evaluated-route/real SQLite regressions cover credential refusal, aliases, exact replay/counts, older connector lookup outside the six-person preview, private-field/roster redaction, rollback and competing founders. Added native acceptance matrices and updated positive fixtures (including actual disconnect response and joined roster). Full server suite: 243 passing; scoped TypeScript and 13 native syntax parses passed. New native tests/builds were not run locally. The previous marketplace commit `0b2f423` now has successful Android build/API30/API34, Swift build-test/archive, web and delivery checks; that does not verify this new tranche or full-app behavior.
- Remaining: full joined-roster pagination, historical alias-key reconciliation, durable offline app actions, cross-device revisions and broader category/catalog entry-point lifecycles. New native acceptance requires backend rollout; credential-free personalized web actions intentionally fail closed. Autumn theme unchanged.

### Catalog scans — failed requests are not disconnections

- Traced the remaining mounted Android catalog/category entry points after the app-detail repair. They spawned detached IO children and converted failed per-app requests into `installed == false`, allowing an outage to replace My apps with an apparently verified empty set.
- Added `appInstallSnapshot`: a complete-or-error owner/app-verified scan of all requested catalog IDs, with at most eight structured children at a time. Failure or cancellation does not publish a partial connected set. Root/category pages capture an account-bound repository, fence publication by epoch, retain the prior snapshot on failure, expose retry/error states and cancel work on page disposal. Root connect/disconnect now waits for a confirmed receipt and rejects stale pre-write scans.
- Primary Kotlin and Swift catalog views now distinguish unknown/failed scans from known-empty My apps; confirmed entries remain visible while incomplete scans offer Retry. Repeated scans are gated. Swift no longer labels an unknown install count as zero.
- Added four JVM tests covering app 100, eight-child concurrency, failed/foreign response refusal and cancellation without starting another batch. Five changed native files passed syntax parsing and diff checks; these new native tests were not executed locally. No backend change in this follow-up; server suite remains the 243-pass run from the preceding tranche. `fb2e75a` web/server checks passed; native checks were still running when inspected.
- Remaining: request-per-app scan cost (no batch endpoint yet), cross-device refresh/push, catalog-load failure UX and durable offline operations. Complete-or-error intentionally favors a truthful prior snapshot over a misleading partial replacement. Theme unchanged.

### Game/tournament mutation entry points — actor impersonation bypass

- Broadened the runtime trace beyond Hub. Game join/move, tournament join/finish and both legacy creation URLs trusted supplied actor IDs; player/member/creator checks inside the transaction did not prove who sent the request. Versioned creation wrappers alone did not protect directly callable legacy URLs.
- All six mutation handlers now bind the existing credential to the actor before business-data reads or dispatch. Existing atomic board/XP/standings/log updates, seat claiming, creation receipts and replay behavior are unchanged. This does not redesign sign-in.
- Added six evaluated-route regressions using the actual token/owner helpers: missing/forged/other-owner requests never dispatch; owner credentials reach the business helper with the same actor. The tests deliberately stop at authorized dispatch; existing SQLite game tests cover the transaction behavior. Full server suite: 249 passing; scoped TypeScript and diff checks passed.
- Open next: game/tournament GET list/detail privacy (still legacy read policy), native read ownership/acceptance and mounted game/tournament action lifecycle. These mutation guards are not clearance of the whole game tree. Backend rollout required; autumn unchanged.

### Private game/tournament reads and cached denial handling

- Replaced the legacy open-read policy on all four game/tournament list/detail GET routes. Existing credentials must own `userId`; current room membership, resource lookup, player profiles, standings and joined flags are read in one Serializable transaction. Removed participants lose reads even if they created or joined the game/season. Responses are private/no-store with Authorization variance.
- Added `gameReadVersion: 1`, owner and room scope to read envelopes. Kotlin/Swift send the owner on GET and refuse unlabelled/foreign scope, wrong IDs/rooms and missing expected containers; Kotlin list responses also reject duplicate IDs. Swift no longer accepts a bare tournament as a private read. Native mutation acceptance remains separate from these read contracts.
- Android game/season repository reads and mutations now fence account replacement; read denial remains a typed `PrivateGameUnavailableException` instead of disappearing into a generic offline error. Primary and mounted Android cards retire cached board/standings on denied/gone reads. Swift card loaders now propagate errors instead of swallowing them with `try?`; denied/gone reads clear snapshots while transient failures retain last confirmed state. Read publication still obeys the existing mutation/read gates and lifecycle cancellation.
- Four evaluated-route/SQLite tests cover ownership, outsider/removed-member refusal, exact scope/joined data and denial-before-private-read. Added Kotlin/Swift acceptance matrices, Swift denial classification and Android denial propagation regression; updated positive list fixtures. Full server suite: 253 passed; scoped TypeScript, 15 changed native syntax parses and diff checks passed. Native compilation/device tests for this tranche were not executed locally.
- Corrected a previous Android compile failure separately in `7f4e046`: missing `kotlinx.coroutines.ensureActive` import in `MirrorHubAppPage.kt`. The earlier `fb2e75a`/`14fbca4` Android build/instrumentation failed on that compile error; Swift tests/archive and web/server checks succeeded. The correction/new native work still requires its own build verdict.
- Open: resource lifetime after carrier-message deletion/expiry, list caps (25 matches / 5 seasons), full standings pagination and broader game/tournament UI review. Authorization is checked when each read runs; this is not an instantaneous cross-device cache-revocation system. New read acceptance requires backend rollout. Autumn theme unchanged; no whole-app clearance.

### Folder ownership, destructive membership saves and native editor lifecycle

- Traced list/create/rename/reorder/delete/membership replacement end to end. Previously, an existing folder ID was sufficient to mutate/delete it, and GET/POST trusted the supplied owner. All five operations now require the existing credential to own the actual folder (or the requested list/create owner). Credential check, resource ownership, membership validation, writes and response snapshot share one Serializable transaction.
- Membership replacement now refuses the whole request for malformed, unknown, foreign or removed chats instead of silently dropping values and potentially clearing the folder. Only explicit `[]` clears it. Reads hide chat IDs after membership revocation. Create + log are atomic; reorder moves and normalizes the entire owner's sequence in one transaction rather than requiring two independently failing swaps. Delete leaves conversations intact.
- Native list/write acceptance requires numeric `folderVersion: 1`, owner, expected folder and exact requested changes/membership. Generic `{ok:true}` no longer confirms a membership save; deletion requires the exact folder ID and explicit deleted/ok flags. Repository requests fence account replacement. Native tests cover positive/missing/foreign/version/wrong-result envelopes (authored, not locally executed).
- Mounted Android folder checkbox selection is now observable Compose state (the previous mutable Set could change without redrawing). Writes use an owner-keyed composition scope rather than detached IO scopes, serialize repeat taps, capture drafts, gate stale refreshes and publish confirmed changes. Primary Android no longer wipes the folder list on refresh failure or clears create/membership/rename drafts before acknowledgement; move/delete failures are surfaced. Hidden existing memberships are retained in the primary planner.
- Swift folder reloads expose failure/retry instead of verified-empty UI; owner-generation/read tickets gate publication. Create/edit/delete/membership snapshots are applied from acknowledged responses; drafts stay on refusal. Reorder is one awaited atomic move, not an optimistic swap followed by two `try?` calls. The membership planner preserves selected archived/filtered chats rather than silently deleting them. Autumn styling unchanged.
- Six evaluated-route/SQLite regressions cover ownership, malformed/foreign/removed membership, scope/ordered receipts, revoke filtering, atomic moves, create-log rollback, replacement/reorder rollback and delete preserving chats. Full server suite: 259 passed (`/home/user/folder-contracts.log`); scoped TypeScript, 10 changed native syntax parses and diff checks passed. Kotlin/Swift compilation/device execution is still pending; syntax is not compilation.
- Follow-up to the game read tranche: traced all three web GET consumers, added required owner query and owner-keyed game/tournament caches. TSX syntax checked. Web `apiJson` still does not send a session credential; private games/folders intentionally refuse credential-free web requests. This is a coordinated web credential rollout dependency, not permission to restore supplied-userId trust. No new sign-in/recovery work was added.
- Latest observed `7f4e046` checks: web, archive and server delivery succeeded; Android build/API30/API34 and Swift build-test were in progress. This is not a verdict on the new folder tranche.
- Remaining folder boundaries: cross-device last-writer-wins edits (no revision precondition), create lost-ACK idempotency, durable offline writes and unbounded large-folder lists/membership. Full-app audit remains open.

### Quick-phrase phantom rows, false deletion and editor drafts

- Traced both Android decoders, repository variants and primary/mounted editors plus Swift managers. Android creation unwrapped `phrase` and then decoded `PhraseEnvelopeDto`, yielding a null phrase; the repository manufactured a successful blank-ID row. Creation now decodes exactly once, verifies returned ID/text/position and maps only the actual row. The list decoders reject missing/malformed containers instead of silently manufacturing an empty list or dropping malformed rows.
- Android primary delete wrapped `Result<Unit>` in `runCatching` without unwrapping it: a server failure became outer success and removed the chip. It now requires the underlying success before retirement, preserves rows on failure, gates pre-write refreshes and uses account-session checks/finally cleanup. Create draft is retired only by an acknowledged callback. Both repository phrase-read variants and writes now fence account replacement.
- Backend GET/POST/DELETE require the existing credential to own the user path. Private GET is no-store/Vary Authorization. Count/quota/next-position/create share one Serializable transaction, preventing concurrent requests from exceeding 12 phrases. Delete is an owner-filtered atomic deleteMany and echoes the exact user/phrase/deleted flags; Kotlin/Swift refuse generic or wrong-target success. Creation/read receipts are validated but not versioned in this tranche.
- Both Swift managers serialize writes, capture the API/session generation, invalidate pre-write/read-on-dismiss tickets, preserve failed drafts and last confirmed rows, and expose refresh errors. The mounted builder no longer clears its draft before issuing POST or labels every network failure as a quota rejection. Acknowledged create/delete updates are applied without depending on a second successful GET. Autumn styling unchanged.
- Three real-SQLite/evaluated-route regressions cover missing/other-owner credentials, private headers, actual creation/deletion receipts, wrong-owner deletion, invalid drafts and concurrent quota admission. Kotlin/Swift acceptance tests authored for the blank-ID envelope regression, missing lists and exact positive deletion. Full server suite: 262 passed (`/home/user/phrase-contracts.log`), scoped TypeScript, 9 native syntax parses and diff checks passed. Native tests were not locally compiled/executed.
- Open: durable create identity/lost-ACK replay, cross-device list revision/refresh and full UI execution. Headerless web phrase calls intentionally fail closed; web credential rollout remains a dependency. Latest observed game read commit `bd5ba7d`: web/archive/delivery success with other native checks in progress; folder commit `9799e30`: web/delivery success, native build/test/archive in progress. Neither is whole-app runtime evidence.

### Scheduled sends: cancellation/delivery race, pagination and native acknowledgement

- Traced create/list/cancel through both native surfaces and the real dispatch worker. These routes previously trusted supplied owner IDs; all now bind the existing credential. Room membership/announcement/block decisions and creation share a Serializable transaction. Read pages recheck membership in their snapshot; owners may still cancel their own pending rows after leaving the room.
- Cancellation no longer reads `sentAt` outside the delete transaction. A conditional owner+unsent deletion competes with the dispatch write inside Serializable transactions; already-sent rows return an explicit conflict, never a cancellation acknowledgement. Refused rows can still be removed. Responses carry `scheduleVersion: 1`, owner/room and exact action targets; reads are private/no-store/Vary Authorization.
- Replaced the silently truncated 50-row list with stable scheduledAt+ID seek pages. Kotlin/Swift (and the existing web consumer) scan complete-or-error, reject duplicate IDs/repeated or missing cursors/wrong scope, and refuse partial results. Limits are 100 pages of at most 50 rows; Android/web also bound accumulated character payload and Swift bounds content bytes. These are not cross-page immutable snapshots; cross-device changes may require refresh. Backend and clients need coordinated rollout.
- Android's nested `runCatching { Result }` cancellation bug no longer reports success for a rejected cancellation. Primary and mounted managers retire rows only on exact acknowledgement, retain confirmed data on transient read failure, clear private rows on denial, gate stale reads, surface retry and bind actions to the account/lifecycle. The primary manager now scrolls lazily rather than clipping its long list. Scheduling drafts/time pickers remain on refusal; repeated writes are gated; mirrored detached IO jobs are replaced with composition-owned jobs. Local-time confirmation stamps no longer display raw UTC as local.
- Swift managers and scheduled chips use captured API/account generations and read/mutation tickets. Schedule/cancel results cannot overwrite a replacement session or dismissed manager; failed reads no longer fabricate an empty queue. Mirrored schedule creation preserves failed drafts and retires only a matching acknowledged composer body. The "Tomorrow 9:00" preset now means the next local calendar day even before 09:00 and across DST, rather than choosing today.
- Dispatch additionally had two runtime defects: the first 25 slow-mode-waiting rows could starve unrelated ready rooms on every tick, and sender devices were excluded from scheduled-message events despite receiving no synchronous delivery response. The worker now retains a bounded seek cursor across ticks (with overlap/error guards); dispatch advances past waiting candidates and relays committed messages to sender and peers. Dispatch still does not advance Seen/read markers.
- Nine real-SQLite/evaluated-route/ticker regressions pass: credentials, tied-time pagination/deleted cursor, removed membership, create policy/window, sent cancellation refusal/rollback, sender event + no duplicate send + unchanged read marker, waiting-room fairness, concurrent cancel-versus-delivery, and ticker cursor/error/overlap behavior. Full server suite: 271 passed (`/home/user/scheduled-contracts.log`). Scoped TypeScript, two TS/TSX syntax checks, 15 native syntax parses and diff checks passed. Kotlin/Swift acceptance/paging and local-day/DST tests are authored; native compilation/device execution for this tranche remains pending.
- Prior `adfadfe` (quick phrases) checks now report Android build/API30/API34, Swift build-test/archive, web and server delivery successful; publish skipped. This is not verification of the new scheduled-send work.
- Remaining scheduled-send boundaries: durable creation identity/lost-ACK retries, cancellation replay receipt after deletion, immutable cross-page revision, post-commit notification outbox/retry, exact-time latency under large queues, and full device execution. There is no schedule-edit endpoint/UI in this path; no edit capability is claimed. No whole-app completion, merge or deployment claim. Autumn theme unchanged.

### Room automation authority, webhook credentials and native control acknowledgements

- Automation list/create/update/delete and webhook management now bind supplied actors to the existing account credential. Current membership/admin checks, case-insensitive trigger conflict checks and mutations run within Serializable transactions. Busy/conflict refusals are explicit; management snapshots use private/no-store/Vary Authorization. Malformed boolean patches cannot silently apply their other fields.
- Deliberate permission alignment: webhook URLs are bearer posting credentials, not ordinary room metadata. Listing these credentials and creating/revoking hooks now require a current admin, matching native creation controls. Ordinary members may still read automation rules. Primary/mounted Android and Swift webhook sections hide credential rows for non-admins rather than offering copy controls. Existing native/web deployments need coordinated endpoint rollout; headerless web management requests intentionally fail closed.
- External webhook POST remains public capability-token ingress: no account login is imposed on external services. It rechecks token existence, current author membership and TTL inside the message transaction, closing the revoke-before-write race. All current recipients, including the represented sender, receive the committed message event; no read marker is advanced. Existing group-owned capabilities are not automatically revoked merely because their creator leaves.
- Kotlin/Swift reject absent list containers, incomplete automation states, malformed webhook rows, wrong create/update targets and unconfirmed deletions. Delete acknowledgements now require exact owner/target plus positive `ok`/`deleted`. Kotlin repository operations capture account ownership across network work. Android primary create/rename forms close only after acknowledgement, creation fields are frozen while pending, cancellation clears creation/loading busy flags, and failed reads have explicit retry states instead of appearing empty. Swift webhook reads capture account/lifetime cancellation, clear denied rows, and fence stale-account action completion.
- Six evaluated-route/real-SQLite regressions cover all seven credential-bound management handlers, member/admin boundaries, demotion, malformed patches, concurrent duplicate triggers, write rollback/exact deletion, and public token revocation/delivery/no-Seen behavior. Full server suite: **277 passed**, no failures/skips (`/home/user/room-control-contracts.log`). Scoped TypeScript and native syntax checks pass. Kotlin/Swift receipt matrices were authored; local native compilation/device execution was not performed.
- Prior scheduled-send commit `a32e676`: last queried web/server/archive checks successful; Android build/API30/API34 and Swift build-test still in progress. This is not verification of this tranche.
- Still open: durable create/delete replay identities, large-list pagination/revisions, comprehensive integration manager read/mutation/lifetime ordering (especially mounted automation forms and Swift automation actions), post-commit notification outbox/retry and webhook-origin attribution in every native message renderer. Existing webhook sender fallback semantics are unchanged, not newly certified. Credential revocation is checked before, not atomically with, management transactions. No whole-app completion, merge or deployment claim. Autumn theme unchanged.

### Integration manager follow-through: draft retention and response ordering

- Continued beyond the endpoint tranche into live manager code. Swift automation rename used an alert that dismissed before PATCH; failure irretrievably closed the draft. Replaced it with an awaited, busy-frozen sheet that remains open on refusal. Automation toggle/rename/delete are now serialized and update confirmed rows only; stale toggle rollback cannot resurrect a deleted row or overwrite a rename.
- Swift automation and webhook reads now carry account, mount lifetime and read-revision guards. Actions invalidate older reads; owner/role changes and dismissal retire pending callbacks. Both creation sheets capture their API/account/mount, freeze pending fields and dismissal, and suppress stale completion/toasts. Webhook URL copying uses URL resolution instead of concatenating a possibly trailing-slash origin.
- Mounted Android webhook management now uses composition-owned main-thread jobs with IO confined to the repository helper, session/revision checks and finally cleanup. Confirmed create/delete directly updates the known list; it no longer relies on a second GET whose failure could leave a newly created hook missing or a revoked one visible. Old read responses cannot overwrite the acknowledgement. Failed reads clear credential rows and remain visibly retryable. Pending creation cannot edit or dismiss its submitted draft.
- Syntax parsing and diff checks passed for these two native files; no native compile/device execution claim. Endpoint/server behavior is unchanged from the 277-passing-test tranche. At last query, `74838d3` web/server checks passed and native build/instrumentation/Swift/archive were in progress; prior scheduled `a32e676` Android build also passed, with API30/API34 and Swift build-test still pending.
- Open: primary Android and mounted automation read/write ordering still need further work, durable lost-ACK/replay remains absent, unstructured Swift requests may finish server-side after dismissal, and whole-app/device verification is incomplete. Already-copied webhook capabilities are not invalidated by hiding their URLs: deployment should review/rotate previously exposed URLs via admin revoke/recreate where needed. No automatic destructive credential rotation was performed. Autumn theme unchanged.

### Android integration ordering and stale manager actions

- Followed the unresolved primary/mounted Android automation paths. Added a shared main-dispatcher `RoomControlGate`: reads have monotonic tickets, writes acquire a single gate before coroutine dispatch, pending writes reject overlapping reads/writes, account replacement invalidates queued dispatch/late replies, and cleanup can only release its own writer. Manager dismissal invalidates callbacks; the gate is not durable network idempotency.
- Primary automation and webhook managers now accept only current read responses, serialize mutations, apply acknowledged rows/deletions directly, and always release their own pending state on cancellation/refusal. Removed optimistic automation rollback. Mutation actions require the confirmed list's account session, so an old screen cannot issue a stale-row action under a replacement account. Unsuccessful reads are explicit errors, not empty-success lists.
- Mounted Android automation management now shares the gate across periodic reads, create/rename sheets and row toggle/delete. It no longer makes a second GET the condition for displaying successful writes. Pending form input/dismissal is frozen; refusals retain drafts; cancellation is rethrown rather than turned into an ordinary error; older refreshes cannot restore deleted rules. The unrelated member picker is unchanged.
- Found the prior Swift test job failure: the new receipt fixture called nonexistent `patchAutomation` rather than the actual `updateAutomation` API. Corrected that call. At inspection, `4bc7016` Android build/API30/API34, Swift archive, web and server checks passed; Swift build-test failed at that fixture compile error. Those historical checks do not certify these new changes.
- Four domain test cases authored for request/read order, single-writer admission, account replacement, cancellation/dismissal and failed-delete versus stale-read resurrection. Native syntax/diff checks performed; native compilation/device validation of this tranche remains pending. No full-app completion, deployment or merge claim. Autumn theme unchanged.

### Automation execution: live authority, atomic accounting and thread context

- Extended inspection from management into the actual post-send engine. It previously selected rules/TTL outside the message transaction, allowed removed creators to keep authoring replies, and incremented hits only after notification. Extracted `createAutomationReply`: a Serializable transaction now reads the current source and enabled rule, verifies source membership/visibility, evaluates the creator's current membership/block/broadcast/slow-mode posting policy, and commits reply/hit counter/room timestamp/unarchive together.
- A deleted/expired/edited source, machine-authored source, removed member, disabled/deleted rule, or inaccessible thread root no longer produces a late automated reply. Thread-triggered replies remain in their original thread/topic and expire no later than the root, instead of appearing in the room's main stream. The rule creator need not remain admin in ordinary rooms, but must still satisfy current posting policy; this does not silently transfer authorship to another member.
- Notification happens after commit to all current members, including the represented author, and still does not advance Seen. A notification outage cannot roll back a persisted reply or suppress its persisted hit count. An accounting/write failure rolls back the entire automation transaction while the original human send remains successful.
- Fixed ASCII-only trigger boundaries: Bengali/other Unicode letters, combining marks, connector punctuation and join controls count as word constituents. NFC-equivalent text matches consistently; regex-special trigger text stays literal. Six added SQLite/evaluated-source regressions cover execution gates, posting restrictions, source/thread visibility, rollback/notification failure, thread TTL/routing and Unicode matching. Full server suite: **283 passed**, no failures/skips (`/home/user/automation-delivery-contracts.log`); helper scoped strict TypeScript, message-route syntax and diff checks passed.
- Remaining: durable per-source execution receipts/exactly-once replay, post-commit notification retry/outbox, large-rule scans/rate limits and consistent webhook/automation attribution in all native message renderers. AI/deterministic bot execution and webhook capability sender fallback require further separate tracing; this tranche does not certify those engines. No full-app completion, merge or deployment claim. Autumn unchanged.

### AI/command replies: expired context, late writes and blank poll bubbles

- Followed the other automated-message engines. Both now use `bot-delivery.ts` to recheck the current source, source membership, bot posting policy and thread root inside a Serializable message transaction. Removed/blocked bots, announcement/slow-mode refusals, edited/deleted/expired/view-once sources and inaccessible roots cannot produce late replies. Reply/poll/options/order/unarchive commit together; thread/topic and source/root TTL bounds are retained without advancing Seen.
- Command `/poll` previously persisted a text-kind message with empty text plus a Poll relation: native kind-based rendering could show a blank bubble. It now persists `kind: poll`. `/wallet` no longer creates a wallet as a side effect of reading a missing balance. Current recipients, including the bot, receive committed reply events.
- AI prompt construction now excludes expired/deleted/view-once rows, unrelated threads and messages after the triggering source. Incognito rows use their alias rather than the account name. Before an answer is saved, the scoped transcript/roster fingerprint and current posting policy are checked again; changed context or a removed bot discards the late answer. Already-transmitted prompts cannot be recalled, and this is not a claim of provider-side deletion.
- The AI in-flight claim is now acquired before the first await, closing the duplicate-generation race. A 45-second acceptance timeout releases the room even if the SDK hangs; it does not promise cancellation of remote computation. Typing uses the actual bot participant ID/current recipients, and decorative typing failure cannot discard a reply.
- Six new real-SQLite/evaluated-engine tests cover current authority, transcript filtering/anonymity, thread TTL/poll rollback, delayed completion/duplicate admission, timeout cleanup/recipient delivery and actual command entry points. Full server suite: **289 passed** (`/home/user/bot-delivery-contracts.log`). Scoped strict helper TypeScript, engine syntax and diff checks passed. LLM calls were controlled test doubles, not real-provider end-to-end execution.
- Historical `2b81e94` checks: Android build/API34, Swift build-test/archive, web/server passed; API30 instrumentation failed. Its detailed log download returned EOF from the GitHub log-storage host; no root cause is asserted. New native/device verification remains open.
- Still open: bot identity provisioning (display-name lookup versus handle lookup), durable execution receipts/outbox, distributed admission/queued prompts, reply notification retry, large-context/revision limits and webhook-native attribution. The identity migration needs explicit provenance handling; this tranche does not certify mutable names as service identities. No completion, merge or deployment claim. Autumn unchanged.

### Native integration attribution, forwarded provenance and webhook posting policy

- New webhook and bot replies now persist the existing server-controlled `viaAutomation` flag. Kotlin/Swift distinguish `Webhook · name`, `Bot` and `Automation` using that flag plus typed metadata; deleted rows suppress the caption. Old payload-only flags were client-writable, so they are deliberately labelled `Unverified integration`, not retroactively certified. Display names are bounded and stripped of control/directional formatting characters; these captions are not account-identity verification.
- Primary/mounted native room bubbles, thread rendering, reply composers, primary search/pin/saved surfaces and the mounted Swift inbox preview now show integration attribution rather than only a represented human's name/“You”. Existing autumn colors/fonts remain. This is not yet universal provenance coverage: embedded server quote snippets and other inbox/notification/profile surfaces still need tracing.
- Kotlin/Swift forwarding strips bot/webhook metadata before durable enqueue while preserving valid content/effects/location/sticker data. Normal message POST rejects reserved integration payload keys instead of letting clients mint those markers. Older queued integration forwards may receive an explicit refusal and remain saved; coordinated client/backend rollout is required. Nothing is silently deleted or retroactively marked verified.
- Webhook token lookup, current author selection, posting policy, TTL and message write now share one Serializable transaction. Capabilities cannot bypass current DM blocks, announcements or slow mode. Machine callers count automated messages for their own slow-mode admission without changing scheduled/human default behavior. Public capability-token POST remains available to external services; group-owned hooks are not automatically revoked on creator departure.
- Unknown tokens no longer allocate rate-limit buckets. A committed webhook post still returns positive acceptance plus message ID when realtime notification fails, avoiding an unnecessary retry signal; durable lost-ACK idempotency/notification retry is still absent. The fallback sender ID remains a current room member when the bot is absent; captions do not change unread/ownership semantics of that fallback.
- Full server suite: **292 passed** (`/home/user/message-origin-contracts.log`). Added real-route block/broadcast/slow-mode, reserved-marker and post-commit notification-outage regressions; strengthened bot slow-mode coverage for already-automated messages. Scoped strict TypeScript for helper/webhook route, normal-message route syntax, 16 native syntax parses and diff checks passed. New native origin/forward tests are authored but not locally compiled or device-executed.
- Still open: canonical service identity migration, universal origin/quote/notification rendering, fallback sender/unread semantics, durable replay and notification outbox, distributed rate limits and whole-app/native-device verification. No completion, deployment or merge claim.

### Generated text limits: broken surrogate pairs

- Follow-through on generated content found direct UTF-16 `slice` calls that could end on half of an emoji. Such strings can make native JSON decoding fail instead of merely truncating a caption. Added `clipUtf16` and applied it to AI answers/transcript excerpts, command replies/poll questions/options and webhook override names. Existing code-unit size limits are retained; no half-surrogate is introduced from well-formed text.
- Four added server cases exercise boundary sweeps across emoji/Bengali/CJK/combining text, invalid budgets, actual AI/poll persistence and the public webhook name path. Full server suite: **296 passed** (`/home/user/unicode-message-contracts.log`); targeted 25 cases, scoped TypeScript and diff checks passed. This helper is not arbitrary-input Unicode validation or whole-grapheme preservation.
- At last query, `a3a7413` web/server checks passed and Android build/API30/API34 plus Swift archive/build-test were in progress. No native execution verdict is claimed for the new origin tests. Historical API30 failure remains undiagnosed because its detailed log host returned EOF. Whole-app audit, service identity provisioning, quote/notification attribution, fallback sender/unread semantics and durable replay remain open. Autumn unchanged; no merge/deployment.


### Reply quote decoding, anonymous attribution and independent cache lifetime

- Following sender attribution into embedded replies found a deeper wire failure: Android and the shared TypeScript protocol described `replyTo` as a recursive full message, but REST/socket serializers emit `{id, content, senderName, deleted}`. A real reply could fail Android decoding for missing conversation/sender/timestamp fields. Added the actual snippet DTO and replaced invented recursive-message test fixtures with the real shape.
- Server quotes now carry `quoteVersion: 1`, an explicit nullable expiry and view-once flag. Anonymous sources use their alias (or “Anonymous”), not the real account name; machine sources use the same bounded origin semantics as native captions. Legacy client-authored integration markers remain unverified. Deleted, expired, foreign-room, missing-root and invalid/nested-root references disclose neither body nor author; thread quotes inherit the earlier source/root deadline.
- Native quote presentation fails closed for old/missing/invalid bounds while retaining the reply and source navigation ID. Kotlin stores bounds in a separate Room column, not caller-writable rich payload metadata. The additive v13→v14 migration retires only old derived quote author/body fields, preserving messages, drafts and outbox rows. Swift Codable preserves the explicit null lifetime witness across disk restarts and masks legacy quote content/name on decode.
- Kotlin room/thread/saved expiry flows now include independent quote deadlines. Swift main/mounted quote views re-project on a one-second visibility clock. A disappearing quote no longer depends on its containing reply expiring or a new socket/database event. Late same-reply snapshots cannot extend an already-known quote deadline, reopen a known deleted quote or remove its view-once gate. Anonymous optimistic Swift quotes use aliases; unknown/thread-root lifetime quotes remain placeholders until the server supplies bounds. Existing autumn styles are unchanged.
- Validation: **299 full server tests passed** (`/home/user/quote-disclosure-contracts.log`), including a real Prisma include + evaluated production serializer test for anonymous/thread/view-once/deleted-root quotes. Strict TypeScript passed for the actual serializer/disclosure/shared protocol dependency graph (no serializer stub); 22 changed/new native files parsed without syntax errors. Added Kotlin real-snippet mapping, independent-clock/late-snapshot policy and Room upgrade/reopen tests, plus Swift decode/expiry/optimistic-store-reopen tests. No local Kotlin/Swift compiler or device execution is claimed; new GitHub native results are pending publication.
- Previous `d2f8f0f` checks subsequently completed successfully: web/server, Android build/API30/API34 and Swift build-test/archive. This supersedes the earlier pending note, not the lack of whole-app/device-flow clearance.
- Still open: propagating later source edits/deletion to every already-cached non-expiring quote without refetch; complete anonymous/profile/avatar and notification disclosure audit; fallback machine sender unread/notification semantics; canonical bot identity and durable notification/replay delivery. A quote caption fix does not make the full wire sender identity anonymous. Whole-app completion remains **no**; no merge or deployment requested.

### Represented machine unread, notification privacy and lossless cache writes

- Prisma and raw-SQL unread paths now treat persisted `viaAutomation` messages as incoming even for the represented sender ID. Human echoes remain excluded; payload-only legacy integration markers cannot create incoming semantics. Android/iOS inbox classification and unread divider placement follow the same distinction; Swift missed-arrival tracking now counts these real incoming IDs. Ownership/edit/delete/view-once access checks were deliberately not rewritten.
- Foreground alert helpers on Kotlin/Swift reject deleted/expired/foreign-room/local-echo/thread rows, use anonymous aliases and honest integration captions, and replace view-once text with a generic label. iOS now requires a ready foreground session and consults the active account's cached room mute state before sound/haptics/toasts. Expired mute dates no longer remain permanently muted in cached conversation projections. This is cached mute enforcement, not cross-device instantaneous policy synchronization.
- Main/mounted iOS inbox and Android inbox mapping mask anonymous and single-use previews. Android's existing cache lacks those old previews' privacy flags, so the additive v14→v15 migration retires only derived inbox preview fields. Unread state, conversations, messages, draft text and outbox rows survive; previews repopulate on synchronization. Swift full-envelope caches can re-project the flags without another schema migration.
- Normal-send remote push planning now re-reads the committed message, parent availability, current participants, mutual blocks, mute deadlines and recipient preview preferences in one serializable snapshot. Anonymous names and single-use captions no longer leak into the push payload. Deleted/expired/unavailable-thread messages are skipped, text uses Unicode-safe clipping, malformed preference JSON hides the body, and the earliest lifetime is checked again before provider fanout. Push failure still cannot negate an accepted message. This planner is wired to normal sends only; bot/automation/webhook/scheduled remote-push coverage remains open.
- Follow-up to the quote-cache patch found an unnecessary decode/re-encode of unchanged Room fields, which normalized raw ISO timestamps and could drop unknown JSON fields. Restored a lossless unchanged-row path while always applying sanitized quote fields. Added an actual Room regression for legacy quote redaction plus exact timestamp/unknown-field preservation; no privacy bypass was restored.
- Validation: **305 full server tests passed** (`/home/user/attention-contracts.log`); **34 targeted** passed (`/home/user/attention-targeted.log`). New SQLite tests exercise unread equality/expiry and production notification planning with membership removal, blocks, mutes, anonymity, per-recipient preferences, single-use, inaccessible roots, machine classification and provider failure. Strict TypeScript passed for the actual serializer/unread/disclosure/notification dependency graph; normal-message route syntax and diff checks passed. Kotlin/Swift preview/mapping/mute-isolation/Room upgrade regressions are authored; local native execution is unavailable.
- `ae47150` web/server, Android build and Swift build-test/archive completed successfully; both Android instrumentation checks failed and surfaced `RoomMigrationTest::migration5To6PreservesRowsAndAddsDepthColumnsAndTables`. Detailed log retrieval again returned EOF. The raw timestamp round-trip defect above is fixed, but no claim is made that all instrumented failures are cleared before rerunning.
- Still open: durable push/replay outbox, native provider delivery on real devices, socket-online versus per-device background delivery, duplicate foreground attention, fresh cross-device mute/quiet-hour policy, machine-producer remote pushes, cached quote source-mutation propagation, broader anonymity/avatar/profile surfaces and the remaining whole-app tree. Autumn unchanged; whole-app completion remains **no**. No merge or deployment authorization.

### Android jump-pill arrivals versus rendered history rows

- Continued the unread trace into the main Kotlin chat and found its missed-message badge was calculated from `rows.size`, including day separators, older-history pages and own outgoing rows. Any deletion also reset the entire badge. Replaced this with a room/viewer/topic-scoped ID tracker driven only by `MessageReceived` events; cache/history snapshots cannot create arrivals.
- Initial snapshots seed replay protection; subsequent cache emissions can arrive before or after the realtime event without suppressing that new arrival. Pending IDs survive a stale snapshot until observed, then are pruned individually on deletion/removal/expiry. Human outgoing/local echoes, threads and other scopes/topics are excluded; persisted machine sends are incoming. Reading the actual tail clears the pending set; merely tapping a jump button no longer clears it before a potentially cancelled scroll completes.
- Added five Kotlin policy regressions covering history/edit-only snapshots, both event/cache orderings, deletion/TTL, outgoing/thread/topic/account exclusion, machine arrivals and tail/replay behavior. Four Kotlin syntax parses and diff checks passed; native execution remains delegated to GitHub. Server code is unchanged from the **305-pass** notification tranche.
- This is the local navigation pill, not a new server read watermark. Replay memory is bounded to 4096 recent event/seed IDs; it is not durable deduplication across process restarts. Whole-app read gaps/timestamp ties, offline reconciliation and the broader native tree remain open. Autumn styling and Seen/viewport receipt logic were not changed.

### Cached quote revocation and mutation-versus-arrival routing

- Tracing the source-delete route confirmed that the server emits a full tombstone. The native caches previously updated that source only, leaving versioned quotes in other already-cached messages readable until their own refresh. Both caches now tighten dependent quote privacy in the source write transaction: known deletion, earlier source/root expiry, view-once/consumption, invalid room/root links and anonymous attribution. Quotes retain their owner message and navigation source ID; legacy/unversioned snippets are never promoted to trusted content. Cached older source text does not overwrite a newer server quote.
- Android follows indexed reverse dependencies in 200-row pages, after all incoming rows in a batch have merged. This covers direct source references and references to known cached children of a changed thread root. Only quote fields are updated; unrelated raw timestamps and JSON remain intact. Room v15→v16 adds the dependency indexes and repairs old quotes with already-known source/root tombstones. No message/draft/outbox deletion is introduced.
- Swift adds the same reverse repair in GRDB, returns post-repair batch results, and batches cache-context reads below SQLite parameter limits. GRDB v12 indexes and re-projects existing quote rows. Known terminal SQL columns constrain stale legacy envelopes as well. Main/mounted rooms, thread views (including sources outside the displayed thread), pins and search selections re-project quote privacy rather than retaining stale visible copies; mounted refresh now uses committed cache rows instead of the original HTTP inputs. Cache-read failure still applies the privacy information available in the received source event locally.
- The quote trace also exposed an arrival-routing bug beneath the previous jump-pill fix: Android's repository emitted `MessageReceived` for edits/reactions/pins/views/polls/link updates. Added `MessageUpdated`; only `message:new` now reaches arrival/sound/effect subscribers. Updates still refresh storage/inbox. Foreign-room envelope mismatches are rejected before cache admission. Swift generic envelopes now enter the central cache even without an open room, and mutation envelopes no longer replay decorative arrival effects in the primary chat.
- Added Kotlin policy/envelope tests plus real Room transaction rollback, 205-dependent paging, batch order, late-replay, reopen and v15-upgrade regressions. Added Swift pure/cache tests for older-source text preservation, outside-page source deletion, 205 dependents, batch order, root deletion, reopen and v11-upgrade repair. Actual Android migration SQL and reverse-dependency pagination passed a local SQLite execution check; 18 changed/new native files passed syntax parsing. The server contract suite remains **305 passing** (`/home/user/quote-revocation-contracts.log`). Native compilation/execution for these new changes is pending GitHub; syntax checks are not a substitute.
- Official GRDB v6.29.3 `GRDB/Core/Statement.swift` was checked through GitHub for sequence `StatementArguments` and named-argument `+=` behavior used by the bounded cache reads.
- Previous `ada5820` checks completed successfully: web/server, Android build/API30/API34, Swift build-test/archive (publishing skipped). This confirms the prior native checks cleared, not a full-app/device clearance.
- Remaining boundaries: quotes of an **uncached thread child** do not carry their root ID in the legacy snippet, so a root-only deletion cannot identify every such dependency without more wire provenance. Source edit/version reconciliation is still open: deliberately do not replace quote text with an unversioned, potentially older cached source. Broad malformed/epoch-zero expiry decoding, durable notification replay/outbox, account/profile anonymity surfaces and the rest of the feature tree remain under review. Autumn unchanged; whole-app completion remains **no**, with no merge/deployment requested.

### Expiry decoding: malformed does not mean permanent

- The follow-through found two fail-open decoders: Android discarded non-positive/failed expiry parses as null, including when persisting the internal cache metadata; Swift `isAlive` returned true on an unparseable deadline. Only an absent deadline now means permanent. Android preserves real zero/negative epoch values and represents an explicitly unparseable deadline as unavailable; that value survives the Room metadata round trip. Corrupt non-null internal deadline values also fail closed. Message/search/inbox mapping and unread-expiration buckets use the same distinction.
- Swift UI and SQL writes share one expiry conversion. Unparseable non-null deadlines receive a finite, unavailable SQL sentinel instead of SQL NULL, preventing unread/thread aggregates and offline reads from treating them as permanent. GRDB v13 repairs old ambiguous NULL-expiry rows from their retained envelopes in 200-row pages, preserving genuinely permanent messages, drafts and queued operations. Quote privacy is re-projected after repair. Inbox preview and unread-bucket projections also retire unparseable deadlines without clearing unrelated unread or manual-unread state.
- Added Kotlin wire/cache round-trip tests for nil, empty, malformed, epoch zero, negative and future deadlines plus inbox buckets and corrupt cache metadata. Added Swift UI/SQL/thread-count, summary/idempotence and v12-upgrade regressions. Eight changed/new native files passed syntax parsing and diff checks; new native execution remains pending GitHub. Server code is unchanged from the **305-pass** quote-revocation validation.
- Boundary: old Android rows whose invalid expiry was already discarded cannot reconstruct that raw value; a fresh authoritative response is needed. This is not a claim of repairing arbitrary storage corruption or changing server read watermarks. Whole-app completion remains **no**; autumn unchanged.

### Push provider transport, registration retirement and disappearing previews

- Tracing remote delivery found broad FCM 400/404 and APNs 403 pruning: bad payloads or provider credentials could permanently remove healthy device registrations. Retirement now requires the provider-specific unregistered response; APNs also requires a valid invalidation timestamp newer than the registration. Dispatch rechecks the captured binding and deletion uses conditional `deleteMany`, so an in-flight rejection cannot erase a newer refresh/rebind. Unknown platforms are skipped, not erased.
- The APNs path used generic `fetch` despite Apple's HTTP/2 interface and defaulted to `chat.pulse.app`, not the actual `app.pulse.chat` target. Replaced it with bounded Node HTTP/2 requests, timeout/error/close cleanup and a response-body cap. Added explicit sandbox/production selection; unknown settings fail closed. Configuration and the single-environment registry limitation are documented in `PUSH-TRANSPORT-CONFIGURATION.md`.
- Normal-message planning carries the earlier source/root deadline into provider payloads. Web/FCM TTL and APNs expiration are capped and rounded down; late/invalid deadlines cannot become a long-lived provider default. Finite-lifetime previews are generic in the planner and native foreground attention because queue TTL cannot retract a displayed OS notification. Android's custom receive path and iOS foreground presentation suppress an explicit expired/malformed deadline. OS-managed background delivery remains outside those native hooks.
- Strict TypeScript checking now covers the actual transport graph, repairing two pre-existing web-push declaration/interop errors without dropping legacy payload-free pings. The official Node v22 HTTP/2 documentation and web-push source were checked through their GitHub repositories; prior Firebase provider error-classification research remains applicable.
- Validation: **316 full server tests passed** (`/home/user/push-contracts.log`), including real local HTTP/2 success/rejection/timeout/abrupt-close/oversized-response cases, actual SQLite registration refresh/rebind/invalidation guards, payload TTL and source/root preview privacy. The initial full run was blocked by a missing local Socket.IO client dependency; installing the same test dependency as CI cleared that failure. Strict scoped TypeScript and six changed native-file syntax parses passed. New Kotlin/Swift disappearing-preview regressions are authored; their native build/device results are pending publication. Local HTTP/2 is not real APNs device-delivery proof.
- The preceding `4b01f01` native checks completed successfully (Android build/API30/API34, Swift build-test/archive); web and server checks also passed. This does not clear the new changes or whole app.
- Still open: user-wide online suppression/per-device background delivery, socket/push duplicate attention, Android mixed FCM background bypass, machine-producer push coverage, durable outbox/retries, already-visible notification recall, post-plan policy changes and in-flight/same-millisecond registration races. Autumn untouched; full-app completion remains **no**. No merge or deployment authorized.

### Notification tap destinations and iOS cross-tab room handoff

- Following delivery into tap handling found Android's launcher only parsed URI intents. Firebase's OS-rendered mixed notification/data path instead supplies launcher extras, so a background notification tap could open the app without its room. MainActivity now decodes the known message/call/group-call envelopes through a pure core helper; message taps retain their target ID, call taps remain room-only, and malformed extras fail closed. Destination resolution still uses the existing session/room flow, not a provider-supplied URL or new permission bypass. Official Firebase `CommonNotificationBuilder.java` was checked for the data-extras handoff.
- Swift's notification delegate and room deep-link case discarded message IDs. Room links now carry an optional jump through notification decode, cold-launch buffering, Root's cancellable room resolver and `requestOpenRoom`. Locally scheduled/due reminders also carry the authoritative reminder message ID. A warm tap is no longer left in the cold-start slot to reopen on a later Root appearance. Buffer access/routing is main-actor isolated.
- The iOS Profile saved-library route could publish a resolved room while Chats was unmounted, requiring a manual tab switch before anything happened. Root now activates Chats on a pending resolved-room handoff without consuming its conversation/jump; Chats remains the owner of stack insertion and deferred consumption.
- Android's room URI builder now percent-encodes IDs rather than allowing delimiters to change the route. Swift parses the percent-encoded path before one decoding pass, avoiding double-decoding literal escapes and splitting encoded slashes. Both preserve literal plus signs; existing no-jump room links remain supported.
- Added Kotlin launcher-envelope and reserved-character round-trip tests, Swift remote/reminder/cold/warm/jump/encoded-link tests and delayed-subscriber handoff coverage. Ten changed native files passed syntax parsing and diff checks; native compilation/execution remains pending GitHub. Backend code is unchanged from the preceding **316-pass** transport tranche; its web/server checks have passed, native checks were still running when this entry was written.
- Boundaries: these tests do not exercise OS notification taps on physical devices or every animated SwiftUI stack transition. Android activity-recreation replay and iOS multi-scene handoff ownership remain unverified; the message-history jump limit and authoritative membership refresh are unchanged. Expired/missing targets still follow existing unavailable-message handling. Per-device delivery suppression, foreground duplicate attention and durable push retries remain open. Autumn unchanged; whole-app completion remains **no**.

### Quick reply durability and account-bound fallback

- Continuing into notification actions found Swift sent directly before persisting, then handled errors through the mutable session. A switch during the request could put the old reply into a new account's outbox/draft; permanent refusals overwrote the current composer draft. Removed that separate asynchronous sender. Accepted quick replies now commit synchronously to the captured account store, then use the existing account-bound outbox engine for FIFO dispatch, stable operation IDs, cache-before-retirement acknowledgements and retained permanent failures.
- Locked or oversized iOS replies are inserted atomically as non-dispatchable recovery entries, visible in Drafts & outbox, without an intermediate pending state or any composer overwrite. The transaction returns its committed disposition; no fallible post-commit read can falsely report a saved reply as unsaved. No-store/write-failure paths explicitly report no save instead of claiming delivery. Queued means queued, not sent.
- The iOS notification completion handler now runs after main-actor persistence or tap buffering, rather than releasing OS handling before that work starts. Only the default tap opens a room; dismiss/unknown actions are ignored. Android quick-reply decoding no longer silently truncates text at 2000 UTF-16 units or splits a surrogate pair: the complete text reaches the existing send-use-case length validation, which rejects oversized input without sending a partial message.
- Added Swift real-store/outbox tests for commit-before-network, stable-ID network retry, preservation of an existing draft, held replies never dispatching, account-store separation, reopening the durable queue and injected SQLite write failures. Updated Kotlin decoding regression to retain an over-limit emoji boundary intact. Six changed native files passed syntax parsing and diff checks; new native execution remains pending GitHub. Backend remains unchanged from the **316-pass** transport tranche.
- Boundaries: a cold action before an account store is available still cannot safely assign its text to a future identity; it reports no save. No claim is made that old delivered notifications are recipient/account-bound at action time, or that Android's rejected quick-reply text is durably recoverable. iOS background execution may defer a committed queue's actual dispatch; provider/device delivery, notification deduplication and the broader feature tree are not cleared. Autumn unchanged; full-app completion remains **no**.

### Notification recipient ownership at quick-reply dispatch

- Found that delivered notifications carried no recipient authority into Reply: an old banner could send through a different current account. Provider fanout now stamps `recipientUserId` from each checked registry row, overriding any caller value. Android custom notifications only expose Reply when recipient metadata exists; explicit foreign recipients are suppressed in that receive path.
- Android captures recipient + live repository session at action time. A new bound-reply repository capability refuses implementations without ownership enforcement, validates again before the account-scoped transaction, and uses an immutable sender ID. Account-router capture keeps the API/database together across suspension. A refreshed session or switched account cannot turn an old action into a current-account send. Late completion cannot cancel the new account's notification or show stale send feedback. Oversized text remains rejected intact, and cancellation is not converted into an ordinary send failure.
- Swift requires the remote message recipient to match the live API/viewer before any durable write. Local reminder payloads carry their existing gateway/account owner key, and Reply requires that exact key. Legacy/malformed/foreign ownership fails closed without copying the text into the current store. Newly scheduled ownerless local reminders do not advertise a dead Reply action. Read-only tap navigation remains separate from send authorization.
- Added production fanout/SQLite tests for per-device recipient stamping and forged caller metadata, Kotlin owner/epoch/legacy checks plus refusal of an unbound repository fallback, and Swift remote/local/gateway/legacy ownership checks. **317 full server tests passed** (`/home/user/notification-owner-contracts.log`); scoped strict TypeScript and ten changed/new native syntax parses passed. An initial Kotlin syntax parse exposed a catch-clause formatting issue, which was corrected and rechecked. Native execution of these new changes is pending GitHub.
- Previous `ffdecf4` checks now show Swift build-test/archive, Android build/API34, web and server success. API30 failed with an ADB exit error; the detailed run-log download returned EOF, so its root cause is not established or claimed repaired.
- Boundaries: remote recipient IDs do not yet bind an originating gateway when separate deployments reuse the same IDs; local reminders do. OS-managed background banners cannot be withdrawn by these handlers. Cold no-store actions and legacy unowned banners refuse automatic reply, with no claim that their text is recoverably saved. Per-device online suppression/deduplication and the rest of the feature tree remain open. Autumn unchanged; whole-app completion is **no**.

### Reminder originals, cancellable room handoff and destination-owned Android jumps

- Tracing the actual Android shell (not just the retired feature screen) found its reminder rows had resolve/delete controls but no original-message navigation. Upcoming and history rows now expose Open original message / Open chat using an explicit nonblank target, passing through the active room scaffold to the existing account-bound exact-room resolver. Missing room provenance is disabled instead of guessing the current room. Existing autumn colors and layouts are retained, with only the functional link caption added.
- The same trace found the Android transition kept `roomShown` one frame/exit behind `openRoom` while passing a global jump ID to both. An outgoing room could search for the destination's message and clear its anchor. Jumps now carry conversation + message + unique delivery identity, are supplied only to the active destination, and are consumed conditionally. Repeated taps on the same message have distinct effect keys; an older completion cannot clear a newer intent. Ordinary opens/back navigation retire old jumps, including the archived-room path.
- Swift's cross-room reminder used an unowned Task and mutable `session.api` without generation, latest-intent or returned-room checks. Removed that fetch. A structured linked-room delivery now carries the optional anchor through Root's existing cancellable, account/navigation-generation-guarded resolver. Legacy room-only assignments enter the same delivery stream; deferred consumption checks both delivery ID and session generation, preventing an old cleanup from clearing a newer same-room request. Reminders with missing room provenance no longer assume the currently open chat.
- Added Android target/transition/repeated-intent regressions and Swift publisher-reentrant, delayed-cleanup, same-room/new-anchor, legacy assignment and session-reset handoff tests. Nine changed/new native files passed syntax parsing and diff checks; actual new native build/runtime checks are pending GitHub. Backend code remains unchanged from the preceding **317-pass** recipient-ownership tranche.
- Boundaries: this is not a physical-device or every-animation clearance. Existing bounded history search, offline membership freshness, notification delivery/deduplication and other feature-tree issues remain open. Full-app completion remains **no**; no merge/deployment authorized.

### Seen, last-seen, typing and preview privacy under malformed or unavailable policy

- Found privacy decisions reused forgiving UI defaults: malformed/non-object JSON or explicit non-boolean flags could enable read receipts, last-seen timestamps and notification previews. Added a shared strict disclosure decoder. Known SQL NULL/unset keys retain documented product defaults; unknown/unloaded policy and malformed values refuse disclosure. Read fanout still synchronizes the reader's own devices, while REST member serialization preserves their own unread watermark. Omitting viewer context no longer bypasses another member's hidden-receipt setting.
- Applied the same policy to user/profile-stat serialization, last-active updates, notification preview planning and the internal presence/typing snapshot. Settings GET reflects the conservative values; PATCH preserves them during unrelated appearance edits, requires explicit boolean privacy changes, and rejects non-object request bodies instead of crashing on null. Existing theme values are not redesigned.
- The socket relay previously failed open during API outages and reused expired cached flags. It now hides unknown/expired/malformed policy, uses short negative caching for failures, serializes concurrent refreshes, ignores unsolicited IDs and batches all requested users instead of permanently starving those beyond 500. A non-overlapping periodic sweep publishes changed presence snapshots even when connected users are idle; changing privacy no longer depends indefinitely on somebody joining or typing. This remains TTL/poll-based revocation, not instantaneous policy propagation.
- Added strict-policy matrices, actual serializer/route/planner checks, actual relay-function tests for unknown/expired/error responses, concurrent refreshes, 1100-user batching and idle-client revocation, plus settings repair/explicit-opt-in checks. **326 full server tests passed** (`/home/user/privacy-contracts.log`); strict TypeScript passed for the serializer/read/notification/provider graph and the full socket relay. Changed route sources passed TypeScript parsing. The initial serializer test fixture lacked Node's `path` binding; that harness error was corrected before the passing run.
- Boundaries: previously disclosed data cannot be recalled; cache freshness and the periodic cadence still delay revocation. Presence privacy is not delivery authority: the existing push transport still uses a user-wide visible-online snapshot, so per-device suppression and duplicate attention remain unresolved. Read timestamp ties/manual-unread races and whole-app/device verification remain open. Autumn unchanged; full-app completion remains **no**.

### Manual unread receipts and older viewport boundaries (2026-10-24)

- **Behavior repaired:** an older displayed-message receipt previously preserved the newer read watermark but unconditionally cleared a subsequently placed manual unread marker. Older boundaries now preserve that marker. Deliberate mark-all still clears it, including after the latest message is removed.
- **Backend:** manual-unread membership and mutation share a Serializable transaction. The endpoint returns a versioned acknowledgement bound to the committed owner, room, and boolean, with retryable contention reported as 409.
- **Android + iOS:** manual-unread API calls reject incomplete, legacy, wrong-owner, wrong-room, wrong-version, false-success, and opposite-state replies before the existing guarded callers change badges. Android uses a strict decoder for this receipt instead of the lenient legacy-message decoder. No fallback to generic HTTP success.
- **Evidence:** actual SQLite helper regressions and actual route execution, both native API contract matrices; local full server suite 329 passing, scoped strict TypeScript graph passing, six changed Kotlin/Swift files syntax-parsed. Native compile/device execution is not established by syntax checks.
- **Still open:** equal/newer delayed receipts crossing manual intent, timestamp ties, thread gaps, and full cross-device/offline intent fencing. The receipt version describes the response contract, **not** a state revision; it cannot order competing actions. No full-app clearance. Autumn theme unchanged.

### Live group-call ring admission, expiry and answer ownership (2026-10-24)

- **Behavior repaired:** room membership alone could ring an ended/nonexistent call, replayed HTTP requests could ring again, and both native apps restarted a 45-second timeout when a delayed ring arrived. Callers now submit their admitted call ID. The relay checks the exact live call, kind and caller membership immediately before emitting, admits one ring per in-memory call, stamps the original call-start +45s deadline, and returns a bound receipt. Push runs only after a valid first admission; refusal, malformed ACK and lost ACK never guess success. Mute filtering still precedes both channels.
- **Android + iOS:** ring identities/deadlines travel through the wire/domain/engine paths; missing/expired rings and expired answer taps are rejected. A live incoming answer surface cannot silently switch to another caller. Matching ended signals dismiss idle rings; unrelated call ends cannot clear the current ring. The relay now notifies its remembered invitees when the last member leaves. iOS also suppresses group rings while a one-to-one call is busy.
- **iOS answer ordering:** entering joining now precedes clearing the ring. The synchronous CallKit observer therefore does not interpret an answer as an unanswered dismissal; a publisher-order regression covers this actual engine sequence.
- **Push:** Android call pushes are data-only, so foreground/background custom receipt paths both enforce current-account and source-deadline guards. Call notification timeout uses the remaining deadline, capped at 45s. APNs/FCM/Web payloads carry call IDs; provider TTL/expiration inherits the fixed deadline. Message push presentation is unchanged. Firebase SDK `FirebaseMessagingService.dispatchMessage` source confirms notification payloads can be consumed by OS display before `onMessageReceived`, while data reaches the service.
- **Shared web compatibility:** its ring request now waits for roster admission and includes the call ID; received rings honor the deadline and exact ended identity. No visual/theme redesign.
- **Evidence:** real socket/HTTP relay integration verifies one admission, exact identity, replay refusal, last-member-ended delivery and replaced-call refusal. Actual relay-handler deadline and Next route tests cover expiry/ownership/mute/push gates. Native deadline/API/engine regressions and data-only provider-payload checks added. Full server suite **335 passing**; strict route/helper/push/relay TypeScript graph passes using Next's published declarations; 17 changed Kotlin/Swift files and TSX syntax pass. Native compilation/device behavior remains unverified locally.
- **Limits:** in-memory single-admission is not a durable exactly-once notification outbox. A lost relay ACK can omit push; a call may end after admission while a provider send is in flight. Already displayed OS alerts cannot be recalled. Per-device push suppression/dedup, gateway provenance, stale invitation join binding after call replacement, cross-process/multi-node delivery, and broad native call lifecycle/device checks remain open. Legacy ring requests without an admitted call ID are deliberately refused, requiring coordinated backend/relay/client rollout. No full-app completion claim; autumn unchanged.

### Invitation/ongoing joins bound to the observed call (2026-10-24)

- **Behavior repaired:** accepting an old invitation or an outdated ongoing-call banner used the same create-or-join operation as starting a new call. If the original call ended, the action could silently create a fresh call; if the room already had a replacement call, it could join that different call. Both native paths now preserve the observed call ID as `expectedCallId` through preparation, repository/wire transport and relay admission. Deliberate starts remain distinct and omit the expectation.
- **Relay:** an explicit expectation, including malformed/empty values, never downgrades to create. Missing/replaced targets and another already-admitted device of the same account are refused before any existing membership is removed. A valid expected call still admits normally. Last-member end or refusal names the exact requested call.
- **Android + iOS:** ongoing banners require an identifiable call; pending invitation joins retain room/call ownership through permissions. Matching end/refusal cancels preparation and releases media; unrelated ends and replacement rosters cannot complete that join. Ongoing-banner termination is now call-ID-bound as well. Swift's real-engine tests cover end-during-permission and observed-call roster acceptance.
- **Shared web compatibility:** invitation/probe joins carry the same expectation. Permission results are generation/owner checked before installing streams or joining; stale results release their own tracks. The camera fallback sends its resolved kind. Actual extracted production callback execution verifies bound payload, cancellation and owner replacement.
- **Evidence:** live relay test verifies refusal preserves the joining user's existing call, does not evict the host's other device, does not create a call after end, and cannot join a replacement. Native payload/engine tests added. Full server suite **337 passing**, full relay/backend scoped strict TypeScript passing, ten changed native sources/tests syntax-parsed. Native builds/device clearance remain unobserved; no full-app correctness claim.
- **Limits:** requires coordinated relay/client rollout; old clients still omit the new expectation. Pending new-call cancellation and delayed same-room leave intent need stronger attempt fencing; room-admission authorization, per-device delivery, lost-ACK recovery and wider native call lifecycle/device behavior remain under review. These changes do not solve the remaining read-state intent/tie/gap races. Autumn theme unchanged.

### Android unread receipt fixture correction (2026-10-24)

- GitHub Android `build` checks for 5854c5f (job 120822703784) and 609dad3 (120824087128) failed at `ReadStateApiTest.manualUnreadRequiresExactOwnerRoomVersionAndDesiredBoolean`: the supposedly valid response was rejected. Inspection confirmed its manually concatenated raw string omitted the closing quote of `userId`; production strict parsing correctly refused it.
- Replaced that fixture with a JSON object builder for every valid/malformed-field variant and included the actual result in assertion diagnostics. Production decoding/validation is unchanged. Corrected native execution is still pending; this is not a successful-build claim.

### Android receipt token-type enforcement (2026-10-24)

- Corrected-fixture job 120825230842 revealed a real decoder behavior: the string-boolean variant was accepted even with `isLenient=false`. The earlier assumption that strict decoding alone enforced boolean token types was wrong.
- Manual-unread acceptance now validates the JSON object tokens before DTO decoding: `ok`/`manualUnread` must be unquoted booleans, version must be the unquoted supported number, and room/owner must be strings. Added quoted-success, quoted-version and numeric-owner refusal cases alongside the existing string/null/omitted state matrix. Production badge confirmation remains fail-closed.
- Two changed Kotlin files syntax-parse; corrected Android execution remains pending. Backend behavior/theme unchanged.

### Per-join ownership for delayed group-call leave/cancel (2026-10-24)

- **Behavior repaired:** a delayed leave used only room/user/device identity, so a newer join on the same socket in the same room could be removed by the old action. Android, iOS and the shared web caller now generate a fresh join-attempt ID, carry it in JOIN, and capture it in LEAVE before teardown. Admitted leaves additionally carry the known call ID. Cancellation before the roster arrives still has an exact attempt identity; it does not need to guess a call ID.
- **Relay:** stores attempt ownership on the admitted device/member, updates it for a deliberate same-device rejoin, and refuses old/unbound/mismatched leaves before both socket-room departure and roster mutation. A legacy replay cannot downgrade an attempt-bound admission. Existing legacy admissions retain their prior leave behavior; upgraded clients require the upgraded relay for this protection.
- **Evidence:** real relay tests cover same-call/new-attempt rejoin, delayed old leave, wrong call, unbound leave, legacy downgrade replay, preserved roster subscription, replacement call and immediate pre-ACK cancellation. Native builder tests and the actual Swift engine verify capture-before-teardown and fresh IDs on subsequent joins. Full server suite **338 passing**, strict relay TypeScript passing, ten Kotlin/Swift files and shared TSX syntax pass.
- **Protocol boundary:** official Socket.IO delivery-guarantees documentation confirms ordered arrival but default at-most-once delivery. This repair binds intent; it does not turn dropped events into durable delivery or fence deliberately replayed JOIN packets across server restarts. Admission acknowledgements, reconnect/device execution and room-membership authority remain under review. Native checks still pending; autumn unchanged; whole app incomplete.

### Current room membership at group-call admission (2026-10-24)

- **Behavior repaired:** the group-call socket handler checked the registered user identity but not whether that user belonged to the conversation. Added a shared-key internal membership endpoint and actual database predicate requiring an existing conversation with that participant. The relay requires a fresh, exact room/user/version/boolean verdict before creating/joining a group call or replacing existing membership. Missing configuration, malformed verdict, denial and backend outage refuse admission; positive permission is not cached.
- **Asynchronous ownership:** pending membership checks are abortable and per-socket/attempt. Cancellation, superseding joins, disconnect and registered-user changes invalidate late approvals. An old leave cannot cancel a newer pending check. Legacy JOIN replay cannot downgrade a modern pending/admitted intent. Refusal leaves an unrelated current call intact.
- **Android + iOS:** admission refusal and full-call responses carry the attempted join ID. Only the matching joining screen releases its media and shows refusal; delayed replies from an older attempt cannot cancel the new attempt. The shared web consumer uses the same gate. Existing matched-call termination behavior remains separate.
- **Evidence:** actual SQLite membership tests, actual internal route execution, malformed/error/missing-key receipt matrix, and real relay/HTTP tests cover denied access, no cached admission, cancellation, supersession, wrong-attempt leave, identity change and disconnect. Full server suite **342 passing**; strengthened real-relay tests also pass separately; strict route/helper/relay TypeScript and eight changed Kotlin/Swift files plus TSX syntax pass. Native execution remains pending.
- **Deployment:** Next and the relay must share the existing nonempty `CRON_SECRET`, and the relay's `PULSE_ORIGIN` must reach Next. This endpoint must be rolled out with the relay; there is deliberately no outage fallback that admits without a membership verdict.
- **Limits:** this is admission-time room membership, not instantaneous revocation of an ongoing call or a redesign of deferred sign-in/session migration. Existing tokenless/optional session-registration semantics, public call-state probes, other voice/stage/space admission surfaces, internal relay exposure and post-admission revocation remain under review. A distributed membership read cannot guarantee atomicity with a later membership removal. Autumn unchanged; whole app incomplete.

### Keep admission replies separate from call termination (2026-10-24)

- Follow-up review caught a fall-through case: an old attempt's refusal carrying the same observed call ID could miss the attempt gate and then be interpreted as a call-wide end, cancelling a newer pending or active join.
- Android, iOS and the shared web handler now treat any attempt-bearing end as an admission reply exclusively. A nonmatching reply returns without reaching expected-call, active-call or ring termination. Ordinary relay call-end events still use their exact call identity.
- Added actual Swift engine regressions for same-call old-attempt refusal before and after admission, plus execution of the actual web handler. Full server suite **343 passing**; three changed native files syntax-parse. Native runtime verification remains pending; no broader completion claim.

### Swift regression-test cleanup correction (2026-10-24)

- Native build-test jobs 120827334177 / 120827799808 reported that new tests called nonexistent `PulseGroupCallEngine.stop()`. This was an introduced test-compilation error; syntax parsing did not establish symbol correctness.
- Test cleanup now uses the engine's existing `handleSocketDisconnected()` plus `dismissRing()` lifecycle paths, rather than adding an artificial production API. Runtime assertions are unchanged. Corrected Swift build/test execution remains pending; existing concurrency/deprecation warnings are not claimed resolved.

### Revalidate membership throughout active group calls (2026-10-24)

- **Behavior repaired:** admission-time membership was never rechecked while a call stayed active. The relay now gives each admitted device a 30-second access lease, rechecks quiet calls on a five-second sweep, and schedules a refresh on conversation updates. A bound explicit negative verdict removes that participant immediately; remaining peers receive the revised roster, and the final removal ends the call.
- **Outages:** malformed/missing/failed authority responses do not masquerade as membership revocation and do not extend a lease. If verification cannot recover before expiry, signaling/rings stop accepting the member immediately at the lease boundary; the sweep removes the member and notifies clients. Permission deadlines use request-start time, not delayed response arrival. Calls may end during sustained authority outages rather than retain access indefinitely.
- **Race/load safety:** rejoining replaces the admission object. Late permission results check both call and admission object identity, so an old refusal cannot remove a replacement call or newer attempt on the same socket. Refreshes coalesce by room, processing at most eight member checks concurrently. Expiry continues independently of a slow refresh queue.
- **Android + iOS:** existing matched-call teardown releases local media; new messages distinguish lost membership from unavailable verification. Actual Swift-engine tests cover current-call-only termination, microphone release, surviving-call continuity after peer removal and rejection of late SDP from that peer. Shared web text follows the same distinction; no theme changes.
- **Evidence:** real relay/HTTP revocation test verifies removed sender refusal, surviving roster and final-member teardown; actual lease functions are tested for outages, deadlines, stale results, periodic coverage and bounded concurrency. Full server suite **350 passing**, scoped strict TypeScript passing, three changed native files syntax-parsed. Native builds/device checks remain pending.
- **Limits:** not instantaneous or cryptographic P2P revocation. A missed update is recovered by periodic checking; packet delivery, event-loop delay, clock correctness, authority capacity and client cooperation still matter. Already disclosed media cannot be recalled; malicious peers can ignore signaling outside the app. Public call-state probes, other voice/stage/space surfaces and deferred session migration still need work. Autumn unchanged; whole app incomplete.

### Bind ongoing-call lookups to the requested viewer and room (2026-10-24)

- **Behavior repaired:** the public call-state route returned rosters solely by conversation ID, and translated relay failure/malformed responses into a successful empty roster. It now requires the viewer ID, checks current membership before and after relay I/O, validates a presented session credential using the existing migration policy, and returns unavailability rather than inventing a no-call result. Responses are private/no-store.
- **Relay boundary:** direct `/gcall` requests require the configured shared key. The Next route forwards that key and validates exact room/call/kind/timestamp/roster shape, strips internal fields and rejects duplicate members. Only a verified missing/empty live call produces an empty result.
- **Both native apps:** send the viewer and require a versioned owner/room receipt with a complete, valid roster before populating or clearing the ongoing-call state. Kotlin uses strict primitive checks rather than coercing a quoted version. Swift uses a required Decodable envelope rather than the tolerant socket-snapshot decoder. The web probe also rejects incomplete/mismatched/superseded responses and clears a stale target only on confirmed empty state.
- **Evidence:** full server suite **354 passing**, scoped strict TypeScript passing, six changed/new native files syntax-parsed. Tests exercise actual route/relay/web probe logic and add Ktor/URLProtocol matrices for both native APIs. Native test execution for this revision remains pending, not claimed green.
- **Limits:** optional-token identity migration is unchanged; a claimed viewer without a token is still not strong identity proof. This is membership/receipt isolation, not complete authentication hardening. Other relay HTTP endpoints and voice/stage/space permission paths remain open review work. Old clients need the coordinated probe contract update; the historical manual R8 fake-room script is not an admission-authority test. Theme unchanged; app-wide clearance remains open.

### Close public HTTP event injection into native socket streams (2026-10-24)

- **Behavior repaired:** `/notify`, `/typing` and `/online` had no internal authorization boundary, despite being server-to-server endpoints. An exposed relay could accept fabricated native-facing message/call events or online lookups. The shared-key gate now covers these routes and `/gcall` before body parsing, privacy lookups or fanout. Missing configuration, missing/wrong keys and duplicate header values fail closed. Socket.IO connection handling and health remain separate.
- **Backend integration:** general socket notifications, bot typing, live group rings and push online suppression now send the server-only key. Missing configuration performs no outbound relay request; rings report unavailability, decorative/best-effort events retain their existing no-op behavior, and online suppression conservatively falls back to push. Startup warns when the key is absent without printing it.
- **Evidence:** real relay tests reject anonymous/wrong-key event, typing and presence requests, verify no forged event reaches the socket, then verify authorized event delivery and health/Socket.IO continuity. Actual backend caller functions are exercised for key propagation and no-key behavior. Full server suite **357 passing**; expanded scoped strict TypeScript includes the bot graph with the repository-version SDK declarations. No native protocol/theme/database change in this tranche.
- **Limits:** deploy matching server configuration and Next/relay code together. An unconfigured key suppresses best-effort live updates; this does not supply a durable notification outbox or solve deferred optional-token socket identity. Native device execution and the broader feature-tree review remain unfinished.

### Scheduled-send draft ownership and retryable cancellation — Swift + Kotlin (2026-10-24)

- **iOS production:** the Mirror scheduling callback previously compared only the draft's text. A delayed confirmation could erase a newer edit-away/edit-back draft. Both Mirror and the main Swift chat composer now capture a journal-owned receipt before scheduling and check its journal identity, account store, room/thread and storage revision before clearing. Pending writes invalidate receipts synchronously; a new journal/window cannot reuse an old receipt. Scheduling failure preserves the draft.
- **Android production:** the Compose draft now tracks edit revision and composer identity, scoped by viewer/room. Schedule confirmation requires its exact receipt and the still-current sheet presentation, not only matching text. Dismiss/reopen cannot let an old confirmation close the replacement sheet or erase its draft.
- **Both platforms, backend cancellation:** deleting a scheduled row made a lost successful DELETE response unrecoverable: retry returned 404 while the UI still showed pending cancellation. Cancellation now retains an owner-bound tombstone, erases discarded content, excludes that tombstone from list/dispatch and returns the same receipt on retry. Already-sent rows still refuse cancellation; another owner cannot use the tombstone. No schema migration required.
- **Evidence:** Swift journal tests exercise edited-back, pending-write, reopened-window, cross-store, thread and external-write refusal. Kotlin draft tests cover edit-back and replacement-composer ownership. Both native HTTP suites exercise failure followed by a confirmed cancellation retry. Real SQLite tests cover repeat cancellation, ownership, no dispatch, erased content, failed-write rollback and cancellation/delivery contention. Full server suite **358 passing**, scoped strict TS passing; nine native files syntax-parsed. Native compilation/device execution remains pending.
- **Limits:** create/schedule lost-ACK deduplication remains separate work; this tranche does not make creation idempotent. Cancellation receipts persist while their row/conversation exists; tombstone retention adds metadata storage. No offline scheduling queue or whole-app clearance claimed. Autumn unchanged.

### Quick-phrase refresh ownership — iOS rail + Android loader (2026-10-24)

- **iOS defect:** the main chat's phrase rail used `try? ... ?? []`, so a temporary failure erased confirmed phrases. It also lacked request ordering and captured the mutable session API inside a deferred task. It now captures the API/session before launch, checks the room's session generation and shared read ticket before publishing, cancels superseded work and invalidates reads on disappearance. Errors preserve confirmed chips; the manager sheet retains explicit retry/error feedback.
- **Android parity:** existing response ordering remains; reads now capture an account-bound repository before launch, recheck the session before dispatch and cancel earlier loads on refresh/add/delete. A queued old-room read cannot start against a newly attached account. Existing revision checks still reject a cancellation-ignoring response.
- **Evidence:** added Swift regression for cancellation-ignoring older reads and room-closure invalidation through the production ordering gate. Three native files syntax-parsed; source inventory refreshed. This is not an end-to-end rendered rail test. Earlier corrected revision `4fc0bc4` now has a successful iOS build-test result; the newly published draft and phrase revisions still need their own native execution. Server baseline remains 358 passing; no server change in this tranche.
- **Limits:** phrase creation lost-ACK deduplication and cross-device list revisions are not solved. Full-app/device clearance remains open. Autumn unchanged.

### Durable scheduled-create retry identity — iOS + Android (2026-10-24)

- **Behavior repaired:** retrying a schedule after a lost POST response created another row. Both native API/repository paths now persist a gateway/account/instruction-scoped request ID before network I/O using their existing durable operation journals. Equivalent UTC timestamp spellings share an intent; different room/text/time/owner instructions do not. Failure, malformed receipt, cancellation or restart retains the ID.
- **Server:** scheduled row and owner-bound `ScheduledCreationOperation` receipt commit in one Serializable transaction. Same-ID/same-instruction retries return the original pending row, including after its initial 30-second admission window has passed; changed instructions conflict. Current membership is checked on replay. Concurrent requests and receipt-write rollback are covered. Cancelled, sent or removed rows return an explicit terminal refusal; their retry key never recreates them. Receipts have no cascading FK and contain a request hash, not the plaintext message.
- **Native acceptance:** Swift and Kotlin require the echoed ID plus a typed replay flag and the existing owner/room/content/time validation. Main and Mirror composers on both platforms retire the journal entry only after accepting the returned item and invoking their draft-consumption path. Stale acknowledgements use compare-and-remove and cannot consume a newer identical intent. Terminal refusal does not silently release the retry key; use a different time for a deliberate new send.
- **Rollout safety:** new clients use `/api/conversations/[id]/scheduled/create-v2`, never fallback to the legacy create path. An old server therefore refuses instead of silently ignoring the ID and duplicating rows. Back up the database, apply `prisma/patches/20261024_scheduled_creation_operations.sql`, deploy the API, then ship clients. The table is also declared in Prisma schema; generated vendor artifacts were not rebuilt. No deployment performed.
- **Evidence:** full server suite **364 passing** and scoped strict TypeScript passing; 15 native files syntax-parsed. Added Swift URLProtocol restart/lost-response/malformed/terminal/scope/stale-ack tests, Kotlin strict creation/no-fallback tests and real DataStore restart/canonical-identity tests. Native compilation/execution for this revision remains pending.
- **Limits:** not an offline scheduling queue or cross-device semantic deduplication. Changing text/time is a different instruction and does not cancel an earlier uncertain one. Legacy web/old-native no-ID creates remain non-idempotent. Picker expiry/reselection behavior, crash boundaries around UI acceptance/draft persistence and later dispatch-event outbox delivery remain separate review work. Local and server operation metadata are intentionally retained for uncertain/terminal requests. Autumn unchanged; whole app incomplete.

### Scheduled-message offline push — both native transports (2026-10-24)

- **Behavior repaired:** scheduled dispatch only emitted a live socket event; it never called the offline push planner. Committed scheduled messages now invoke the same current-policy notification planner/fanout as ordinary sends, reaching configured APNs/FCM transports with the actual message/conversation identity. Sender exclusion follows the existing ordinary-message planner; socket sync still includes the sender's devices.
- **Privacy/expiry:** the planner re-reads the committed message and current participants, block/mute policies and preview preferences. Disappearing messages use generic previews and absolute expiry. No scheduled-content shortcut bypasses those rules.
- **Failure isolation:** notification work starts only after the message transaction commits and does not block the dispatcher on providers. A failed socket notification cannot prevent the push attempt. `dispatched` now counts durable commits even when a post-commit transport fails. Repeat ticks do not recreate or re-notify already-dispatched rows.
- **Evidence:** actual dispatch route plus real SQLite and the production notification planner verify message identity, no repeat push, mute/block suppression, hidden/disappearing previews, provider/socket failure isolation and truthful dispatch counts. Full server suite **367 passing**, expanded scoped strict TypeScript passing. This does not verify OS delivery on devices.
- **Limits:** push remains best-effort, not a durable notification outbox. Process termination or a lost provider response can still lose an alert; no exactly-once push claim. Existing online suppression races and self-chat/sender notification policy remain unchanged. Provider credentials and the earlier shared-key relay configuration are deployment prerequisites. Theme unchanged; whole-app clearance remains open.

### Preserve the selected schedule through a retry — Swift/Kotlin pickers (2026-10-24)

- **Follow-through defect:** request IDs alone did not prevent duplicates when the UI recomputed “In 1 hour” at each retry: the new timestamp became a new instruction. Main pickers now resolve presets once per presentation. Android Mirror presets also retain their original instants and timezone instead of rebuilding on recomposition.
- **iOS confirmation:** the main Swift sheet no longer dismisses immediately after launching an unowned task. It awaits the room's async result through the existing sheet-operation gate, stays open on failure and suppresses overlapping submissions. Disappearance/account/room replacement invalidates the task's UI ownership. A confirmed server schedule is not falsely described as unscheduled if only local receipt cleanup fails.
- **Expired-window recovery:** both Mirror composers allow the exact already-submitted text/time to query its retry receipt after the new-send time window closes; changing instructions does not bypass that window. The Swift date picker's lower bound is frozen too, so a refresh cannot clamp an old selection to a new time. The server still decides whether an original schedule exists and is pending.
- **Evidence:** nine changed/new native files syntax-parsed. Added Kotlin calendar/DST/window/identity tests and Swift preset/window/gate tests. Native execution remains pending; unchanged server baseline is 367 passing.
- **Limits:** stable selection is per presentation. Closing/reopening or restarting still requires selecting the original exact instruction or reviewing Scheduled messages; this is not a recoverable pending-schedule UI. Changing text/time does not cancel an uncertain earlier request. Autumn unchanged; full-app/device review remains open.

### Photo import: real bounds-decode refusal, bounded Swift decode and shared orientation handling (2026-10-24)

- **Android root cause:** both independent chat/camera/channel-photo and story image helpers used the result of `BitmapFactory.decodeStream(... inJustDecodeBounds=true)` as the read-success signal. That result is deliberately **null** even for valid images, so the Elvis throw rejected them before pixel decoding. Confirmed against AOSP `BitmapFactory.Options.inJustDecodeBounds`: https://github.com/aosp-mirror/platform_frameworks_base/blob/master/graphics/java/android/graphics/BitmapFactory.java .
- Both helpers now call one shared Android image preparer. It reads the content provider once into a private, bounded temporary source, checks dimensions rather than the null bounds result, samples before decoding, and handles all eight EXIF rotations/reflections. It checks compression success, propagates cancellation and cleans bitmaps/source files in `finally`. The single source snapshot also avoids headers/EXIF/pixels coming from different provider reads.
- **Swift:** gallery import in main chat, Mirror and stories now uses a file transfer representation, prepares the small JPEG within the temporary file's access lifetime, and does not first load the original image into a `Data`/full-resolution `UIImage`. ImageIO thumbnails honor orientation before producing the ≤1280px JPEG; the camera's existing Data entry point uses the same decoder. Extremely thin images no longer pass zero-sized dimensions to a UIKit renderer.
- Both platforms bound original sources at 64 MiB and explicitly flatten transparency onto white before JPEG encoding. This does not change the autumn UI or upload endpoints. Images over the source limit are refused, not silently truncated.
- Added actual Android decoder instrumentation for both feature entry points, all eight EXIF corner mappings, dimensions, transparent/thin/corrupt sources and temporary cleanup; Swift tests cover file/Data output, rotated/thin/corrupt input, alpha and oversized-file refusal. Nine edited/new native source/test files syntax-parsed; native execution remains pending. No emulator/device behavior or memory profile claimed here.
- **Still open:** source-provider compatibility across real photo/cloud libraries, peak memory measurement on older devices, story upload/publication lifetime ordering, server upload size/policy parity, and broader attachment delivery. FileRepresentation preparation addresses gallery decoding, not the system camera's full-size capture allocation.

### Story publication and replacement ownership — mounted Android Mirror + Swift (2026-10-24)

- **Mounted Android defect:** `MirrorStoryComposer` ignored the repository `Result`, then always closed as if sharing succeeded, from a detached IO coroutine. It now snapshots the submitted text/background and account, checks acceptance, retains text with a visible error on failure, prevents overlapping/edit-in-flight submits, and uses presentation-owned main-dispatcher work. Retired account/presentation responses cannot close the next sheet.
- **Mounted Swift defect:** photo imports/uploads were unowned tasks; an older selection could replace a newer one or force Photo mode back after switching to Text. The composer now uses its sheet-operation gate, cancels superseded preparation/uploads and close/mode changes, captures the API before suspension, and checks session/cancellation before dispatch and application. Replacement failure retains the previous confirmed image. Post snapshots content, only closes on acceptance, and does not wait for a separate feed reload to count publication as accepted. Retired `StoriesSessionModel` instances cannot issue calls using a replacement session; their polling terminates, and refresh also expires already-visible stories locally.
- Android's mounted root now scopes the post-success feed reload instead of detaching it, orders it against polling, binds each poll chain to its account, and retains confirmed story/folder/mention state on failed reads instead of converting failures to empty results. Viewer-keyed caches do not carry into another viewer. The standalone Android photo composer/VM received matching coroutine, account and feed-ordering repairs, **but it is not currently mounted by MirrorRoot**; Mirror's composer remains text-only. That parity gap is not marked complete.
- Caption limits now match the server's 280 UTF-16 units on both platforms without cutting a surrogate pair. Swift no longer admits 280 emoji that the server rejects, and the counter uses the same units. Mirror's former unrelated 160-unit cap now uses the shared limit. Autumn unchanged.
- **Evidence:** 12 edited/new native files syntax-parsed. Added Kotlin delayed publication/upload/feed and owner-retirement tests; Swift replacement/retired-model gate and paired Unicode boundary tests. Native execution is still queued/in progress; these are not mounted UI/device or build-success claims.
- **Limits:** story creation still has no durable lost-ACK identity; cancelling a request does not prove the server did not publish it. Viewer/delete operations, backend audience/block privacy, real cloud-photo providers, and whole-app behavior remain under review.

### Story audience, real native deletion, and atomic viewer removal (2026-10-24)

- **Backend defects:** the feed ignored both directions of blocking; any claimed requester could mark a known story viewed without sharing its audience; viewer rows could be deleted before a later failed story delete. Feed, create, viewer read/mark and deletion now bind the existing credential to the claimed owner. Feed/view mutation share an owner-or-current-unblocked-conversation-peer SQL predicate. Expired/non-audience view attempts return no story/count and create no receipt; owner self-views remain unrecorded and repeated permitted marks stay idempotent.
- Viewer names and aggregate counts omit currently blocked identities and orphaned legacy view records. Owner viewer reads stop at expiry. Story/view deletion is one serializable transaction, including ownership check; a failed second delete rolls back the first. Personalized responses are private/no-store and vary on Authorization.
- The audience is a fixed-parameter SQL subquery, retaining the author/expiry index opportunity rather than materializing every member into an `IN` bind list. Counts aggregate inside SQLite instead of loading every view into application memory; author/viewer names use joins instead of another unbounded parameter list. This is not feed/viewer pagination.
- **Paired transport trace found more real failures:** Swift story deletion sent requesterId only in the JSON body, although the route reads the query; it now sends the encoded owner query. Android's hand-written story DELETE bypassed its auth-header helper and fabricated success for malformed 2xx responses; it now uses the authenticated DELETE helper. Both require a genuine `{ok:true}` response before confirming deletion.
- **Evidence:** eight actual-route SQLite regressions cover credentials, both block directions, membership removal, strangers/expiry, idempotent/self views, blocked/orphan counts, rollback after partial deletion failure, UTF-16 publication and a 33,000-member audience. Full server suite **375 passing** (`/home/user/story-access-contracts.log`); expanded scoped strict TypeScript passes. Four native API/test files syntax-parsed; added Swift request/ack and Kotlin auth/negative-ack tests await native execution. No device/deployment claim.
- **Rollout:** no schema change. Updated native clients must precede the stronger story API gate: older Android story DELETE omitted its available bearer token, and older Swift deletion used the wrong owner location. Header-less legacy/web callers are now refused; no claimed-ID bypass was retained. Sign-in/recovery UI was not changed and nothing was deployed.
- **Open limits:** durable story publication lost-ACK identity, feed/viewer pagination, previously cached content and known public media URL revocation, story-specific read-privacy policy, and broad viewer-operation/device review. This does not certify all status or app behavior.

### Open story viewers follow current feed and confirmed deletion (2026-10-24)

- Android Mirror previously passed the frozen group selected at opening to its viewer forever, even while the root fetched a newer feed. It now resolves that author from the current feed and closes if no live group remains; selection is viewer-keyed. View marks capture the account and reject late/cancelled replies before touching the viewer machine.
- Swift's view-mark `onChange` launched detached tasks and published a viewed state even on failure. It now uses a presentation/lifecycle-keyed task, guards the original session before and after transport, and keeps failures in retry bookkeeping instead of treating them as receipts.
- Swift deletion no longer optimistically empties the group (and auto-closes the viewer) before the server acknowledges deletion. The viewer gates the request, pauses playback, displays an unconfirmed-deletion error and retains the story on failure. Confirmed removal invalidates older feed reads and persists the reduced cache so an offline restart does not immediately restore that deleted story. Closing cancels sheet-owned deletion work; cancellation still cannot prove whether a server committed.
- Both pure viewer machines clear the pending mark when a fresh feed removes all stories, including while playback is paused. Added paired regressions for this transition. Eight native files syntax-parsed; native execution remains pending. Backend baseline unchanged at 375 passing.
- Limits remain: this is fresh-feed propagation, not instantaneous block/media-cache revocation. Android Mirror still lacks the standalone viewer's owner delete/viewer-list controls; the standalone Android viewer/VM action lifecycle needs further work. These gaps are not hidden as completed parity. Autumn unchanged.

### Mounted status-owner controls and ordered private snapshots — both platforms (2026-10-24)

- Android Mirror now mounts owner-only **Viewers** and **Delete** controls on its real status viewer. Deletion confirms the selected ID, pauses playback, uses the presentation/account-owned coroutine, stays visible with an error on refusal, and updates the root feed only after the server acknowledgement. Late root reads are invalidated. Viewer-list polling is lifecycle-owned, lazy-rendered, and distinguishes loading/empty/error; access refusal clears old identities instead of leaving a stale private list.
- Android story snapshots now have a shared cache-commit fence and per-account deletion tombstones. Network requests are not serialized behind one another. A late feed cannot overwrite the newest snapshot or restore a confirmed deleted row; persisted removal survives a normal restart. Typed story failures preserve auth/forbidden/not-found distinctions, and explicit access denial does not fall back to a private offline snapshot. Disk-write failure keeps the fresh in-memory result; failed deletion-cache persistence is logged without falsely denying an already-confirmed server deletion.
- Swift viewers now show failures rather than “No views yet,” preserve a confirmed list only for transient failures, and discard rows on auth/access/not-found refusals. The list resets when its story changes, checks session and read ordering, and polls only while active. Both API clients reject absent/malformed viewer arrays, empty identities and duplicate IDs instead of inventing an empty-success response.
- Both mounted viewers now run expiry checks independently of playback. Opening a viewer/delete modal no longer freezes a story's privacy deadline.
- Evidence: added Kotlin cache tests for delayed feeds, latest-wins, normal restart, account separation, disk failures and access denial; paired native wire validation tests and Swift error-policy tests. Eleven edited/new native files syntax-parsed. Native execution for this revision remains pending; older Android image-preparation commit `40f9248` build passed, not a validation of this revision. Unchanged server baseline: 375 passing. Autumn unchanged.
- Remaining limits: persisted cleanup can still fail on a broken/full disk, process loss between server acceptance and local cleanup remains possible, publication lost-ACK recovery and server pagination remain open, and the standalone Android viewer action lifecycle is still being repaired. No deployment or whole-app/device clearance.

### Status acknowledgements, access refusals and home-side queries (2026-10-24)

- Both native story-feed decoders now require an explicit groups array and unique/nonblank author/story identities; Kotlin also requires explicit nested story arrays. Both view-mark clients reject absent, negative, null, quoted and Boolean counts instead of accepting a made-up zero as a successful seen receipt.
- Android's standalone feature-stories viewer no longer launches detached VM callbacks for marking/deleting/polling. It binds the operation to its presentation/account, waits for deletion acknowledgement, preserves refused stories, displays deletion/list failures, and keeps expiry running through paused modals. Its late feed reads cannot overwrite an acknowledged action. The mounted Mirror remains separate and uses the repository cache fence.
- Android confirmed view receipts now persist the seen bit and invalidate older cache reads, while later server counts remain authoritative (they can decrease after blocking). A pending newer request cannot override an actual access denial; a newer *accepted* authorized snapshot can. Swift confirmed marks now invalidate older feed responses and persist the updated shared snapshot. Both VMs clear stale loading flags when confirmed mutations invalidate a refresh.
- Swift's story session consumes a throwing feed API: authorization/access/not-found failure clears private groups and viewers and replaces its cached snapshot with an empty one. Transient failure retains confirmed content with a visible error rather than pretending the feed is empty. The home tray's side queries are cancellable and generation/account fenced, preserve confirmed values on transient failures, and expire stories locally. Folder reloads no longer erase the rail/active filter just because a request failed; they reject out-of-order responses and clear deleted selection only on a confirmed snapshot (or explicit access refusal).
- Added paired wire tests for malformed feeds/mark receipts, Kotlin delayed-read/restart/denial cache cases, standalone VM refused-delete/mark and late-account-result cases. Twelve changed/new native files syntax-parsed, diff clean; native execution remains queued/pending and is not claimed. No server changes in this tranche; autumn retained.
- Still open: end-to-end/device execution, process-loss and failed-persistence boundaries, expiry of cached/reachable media URLs, mounted Android photo creation, pagination, and the wider audit. These changes do not prove whole-app correctness.

### Actual photo composer and sheet/picker interaction (2026-10-24)

- The mounted Android Mirror composer is no longer text-only: it now uses shared `ComposerState`, the bounded/EXIF-aware preparation path, account-bound upload, image preview, optional caption, replace/remove controls and confirmed publication. Upload/post operations belong to the presentation; replacement failure preserves the earlier photo/caption. A picker result checks the owner captured when the picker opened. The UI retains the autumn sheet/palette and uses the existing scrollable body.
- Both Android composer previews now use the exact compressed upload bytes, not a second read of the original provider URI that could have changed or lost its temporary permission. Coil's published 2.7 `ByteArrayMapper` was checked as the supported preview transport.
- Swift's Photo action can replace an existing photo without first deleting it, so replacement failure can genuinely preserve that photo. Picker launch ownership is checked before importing/uploading; account change closes and invalidates the picker. Blank upload paths are rejected and cannot enable an unpostable photo draft.
- Android shared Mirror sheets no longer put the entire panel under a clickable dismiss ancestor: the outside scrim is a sibling, blank panel taps do not discard drafts, Back closes the sheet, and the panel respects keyboard insets. Added real Compose instrumentation cases for blank-panel versus scrim taps and Back handling; these are authored, not device-executed here. Added paired photo replacement/state cases and a Swift whitespace-upload transport case.
- Nine native source/test files syntax-parsed; diff clean. Added the standard Compose UI test dependencies under the existing BOM for the mounted-sheet instrumentation. Native build/device execution remains pending, not inferred from syntax. No theme replacement, deployment or whole-app clearance. Publication lost-ACK identity, durable/process-death drafts and uploaded-media revocation remain unresolved.

### Shared Swift URL builder — executed-test finding (2026-10-24)

- The completed Swift `build-test` job **120843110057**, run **40083999647**, for older commit `bfb2f49` failed the real URLProtocol deletion case: the expected owner `me&peer` was not received and the mock returned 400. Inspection found a shared transport defect, not just a deletion fixture: caller-escaped query values were assigned to `URLComponents.query`, which escapes `%` again (`%26` → `%2526`). Encoded path segments were similarly assigned to `.path`.
- The shared builder now preserves valid existing escapes, safely escapes raw Unicode/spaces/malformed percent sequences for legacy callers, joins a gateway base path without duplicate slashes, and clears inherited base query/fragment state. It assigns the resulting encoded components through `percentEncodedPath`/`percentEncodedQuery`, rather than decoding/re-encoding values or unsafe unchecked assignment.
- Added direct tests for encoded IDs/search values, literal encoded percent sequences, mixed raw Unicode/malformed percent input and base-prefix/query isolation. The existing real deletion transport test remains in place; it was not weakened. Source/tests syntax parsed; the corrected native suite has not executed yet. The successful compilation of that older Swift revision is not clearance for newer unpublished/published work.
- Reference checked: official swift-foundation `Sources/FoundationEssentials/URL/URLComponents.swift`, property documentation for `.path`/`.query` explicitly describes encoding-on-set. Full job-log retrieval failed at GitHub's results-receiver host (EOF); the failure above comes from the check's annotations, not an invented full log.

### External shares and room attachment intake — both platforms (2026-10-24)

- Android's mounted share sheet formerly disabled only the row currently sending, launched detached VM sends, and routed each later upload/send through the mutable account router. It now owns a single presentation-scoped sender, disables concurrent destination taps, validates shared text without truncating it, binds preparation/upload/queue work to the original account, and checks cancellation at each boundary. Failed image attempts retain prepared/uploaded results and reuse a per-destination client ID for queue retry; progress no longer leaks into the next share sheet.
- Incoming shares have distinct presentation IDs, including identical payloads delivered twice. An old completion/dismissal cannot consume a newer share or navigate it to the old destination. The short-lived Android share activity now forwards the image URI in ClipData with read-grant flags before finishing; the exported custom MainActivity action refuses file URIs and streams not matching the delegated ClipData. Added an instrumentation configuration test for the real forward/parse functions; this is not proof of cross-app provider grant execution.
- Swift room library/document/camera callbacks now validate picker-launch ownership and the room model's original account/room before staging; staged sends also enforce that original account. Camera permission callbacks are request/owner fenced. An unavailable camera no longer proceeds to an invalid UIImagePicker source, and capture renders directly to bounded JPEG output instead of allocating a second full-resolution JPEG and decoding it again.
- Swift document import moves file-provider I/O/base64 work off the main actor, keeps security-scoped access alive for the read, propagates cancellation to that worker, and checks ownership/revision before replacing the staged attachment. Failures keep the previous attachment. Stream reads check cancellation between chunks; a provider's already-blocking OS read is not claimed to be instantly interruptible.
- Added four production-sender Kotlin cases for account change, concurrent destinations/retry identity, cancellation-ignoring upload and oversized text; added Swift camera-scale/unavailable-source cases and Android handoff validation instrumentation. Ten edited/new native sources/tests syntax parsed, diff clean; native execution still pending. Older `e99a145` Swift job compiled and ran tests but failed the same URL-encoding test already addressed in `f0f82bc`; no clean-current-native claim.
- Limits: external shared photos still need preparation/upload before the media outbox commits (unlike the room's raw-byte journal); process-death restoration/URI lifecycle, a full iOS system share-extension flow, and end-to-end provider/device coverage remain open. No theme change or deployment.

### Shared-photo upload moves behind the durable queue; bounded document readers (2026-10-24)

- Closed the previous tranche's upload-before-queue gap for Android external images. `queueSharedImage` validates and saves raw bytes in the original account's existing immutable attachment-file journal, then commits the pending message/file reference in a Room transaction **before** network upload. It does not overwrite the destination room's separate unsent attachment draft. The existing account outbox now owns upload/retry after the sheet closes. Pending-ID replay reuses its row instead of replacing it; a failed/foreign operation is not silently revived or redirected. No schema version change (one DAO lookup added).
- The presentation sender now calls that durable raw-image entry point; its tests exercise preparation/account cancellation and stable retry IDs without a pre-queue upload. Normal process restart after a committed queue can use the persisted bytes; the separate crash/cancellation boundary between commit and UI consumption and persistent share-intent replay identity are not claimed solved.
- Android document/audio readers now check cancellation between chunks, rethrow cancellation rather than wrapping it as an ordinary upload failure, and reject a broken provider returning zero progress instead of spinning forever. Empty inputs produce explicit errors. Swift's document reader rejects empty/non-file input and checks cancellation before returning bytes, matching its bounded off-main import pipeline.
- Added JVM bounded-stream zero-progress/cancellation/exact-limit tests and Swift real-file empty/size/scheme/cancellation cases. Eleven edited/new native sources/tests syntax parsed; execution pending. Inspection traced the queued `uploadKey` through `attemptOutboxSend`, which reloads the full entry and reads the account-private file; list projections deliberately hide that reference.
- Executed Android job **120845991886** / run **40084993100** at older `c38ed49` found the new successful-feed test omitted mandatory `SenderDto.name`. Fixed that fixture (including duplicate-ID fixtures so they test the intended condition); production validation was not relaxed. Older `bfb2f49` JVM-test step passed; current release/device status is not inferred from it. Swift `c38ed49` repeated the already-addressed URL-encoding failure.
- Autumn preserved. Provider reads already blocked inside the OS, complete share restoration/receipt identity, app-wide/device clearance and broader audit remain open.

### Upload acknowledgements must mean durable bytes — shared native backend (2026-10-24)

- `/api/uploads` previously acknowledged a disk-only upload even when its database write failed. Both native outboxes can retire their upload bytes after that acknowledgement, so an ephemeral-server filesystem loss could permanently break a supposedly successful attachment. The route now commits `UploadedFile` first and returns retryable 503 without a file receipt when durable storage fails. Disk is an optional cache, atomically published by rename; cache failure does not deny an already-durable upload.
- Upload JSON is bounded before parsing/base64 decoding, including chunked bodies. The reader has a body deadline, rejects sustained zero progress, and uses bounded backing storage rather than accumulating arbitrarily many tiny chunk objects. At most two uploads per server process occupy this path; excess requests receive 429/Retry-After. Canonical padded and valid unpadded base64 are accepted; malformed padding/trailing bits are refused rather than silently decoded into truncated bytes.
- Stored-media responses no longer advertise a one-year public immutable cache: new responses are private/no-store with nosniff. A missing disk cache plus a database outage returns retryable 503, not a misleading permanent 404. Database fallback framing uses the actual byte length.
- Executed evidence: eight real route/SQLite/stream cases (durable failure, cache failure/restart fallback, malformed base64, bounded/stalled bodies, concurrency, outage classification, framing/traversal); **383 server tests PASS**, scoped strict TypeScript PASS. Log: `/home/user/upload-durability-contracts.log`. Workflow path filters cover the helper/routes.
- **Not an authorization/revocation fix:** known media URLs remain accessible under the existing endpoint policy, already-cached copies cannot be remotely recalled, and public-upload provenance/abuse controls still require broader work. The concurrency bound is per process, not a distributed rate limiter. A provider/disk/database operation already blocked below the bounded body reader is not claimed instantly interruptible. UploadedFile schema/write availability is now required, not an optional best-effort fallback; deployment has not occurred.

### Mounted room photo/document handlers, paired iOS replacement semantics (2026-10-24)

The previous shared-reader and external-share repairs did **not** cover the actual MirrorRoom photo/document handlers. Those handlers still had their own unbounded document read and unoriented image decoder; photo preparation/send used detached scopes, and both handlers uploaded before queueing. This tranche replaces those mounted handlers, rather than treating the standalone feature-chat path as proof of the mounted UI.

- Android mounted room uses the shared bounded image/document readers, a room-owned cancellable job and picker-launch session/intent snapshots. The root keys the room composition to account/session as well as conversation so old remembered captions/replies/jobs cannot become the new account's composer. External picker results require content-provider URIs; interrupted/recreated picker ownership is refused rather than guessed.
- Photos and documents go through a review sheet (existing autumn palette), with a real prepared-byte photo preview, filename/size, original destination, caption, anonymity label and image view-once control. Documents no longer send just because the picker returned a file. Unsupported/overlong document names are rejected during preparation on both Android and iOS.
- A generalized prepared-attachment enqueue writes immutable private bytes and the optimistic message/outbox transaction before upload. It preserves caption/topic/reply/anonymity/view-once/file metadata and does not overwrite an unrelated room attachment draft. The document's source text clears only if its captured composer revision still matches. Caption/metadata freeze at the first Send; retry in that presentation keeps the same operation ID instead of changing the operation after an uncertain result.
- iOS successful photo/document replacement no longer erases a caption edited during preparation. Replacing a view-once image keeps that setting; explicitly turning it off still works, and documents do not acquire an unsupported view-once flag. The real RoomViewModel installation path has a regression test, with automatic network/subscription work disabled for that test instance.
- Added Android metadata/freeze regressions and a Swift model regression. Eleven changed native files parse successfully; native compilation/execution is **not yet confirmed**. Last b48cc76 native build/build-test checks queried were still running. Inventory remains 369 production sources; Room 16 / GRDB v13 unchanged. Backend evidence remains the separately executed 383 tests at f09e8f4.
- Still open: persistent selection/consumption receipts across recreation/process death, unsent mounted-sheet rehydration, actual Room queue transaction/device-provider execution, interrupted OS-blocked reads, upload lost-ack deduplication, authenticated media revocation and further mounted actions (including older detached rich-message flows). These fixes do not certify the whole app or strong view-once media confidentiality.

### Mounted profile/group photos, honest profile saves, and profile-write authority (2026-10-24)

- **Actual Android profile surfaces:** the editor, cover panel and handle dialog used detached jobs and sometimes treated `Result.failure` inside a successful `runCatching` as a confirmed save. The editor could close/show success without a successful PATCH. They now unwrap the actual result, retain errors/drafts, snapshot submitted fields before suspension, send only changed fields, and retain newer edits after an acknowledgement. Photo acknowledgements change only photo metadata, not the text baseline. The profile preview now actually receives the uploaded avatar path. Back/dismiss refreshes the mounted profile after confirmed edits.
- **Android photo ownership:** mounted profile/group pickers reserve the original account/session before launch, use the bounded EXIF-aware shared reader, cancel surface-owned jobs and check ownership before progressing upload into PATCH. Root profile/editor/group-info composition is keyed to account/session with bound repositories. Removed the duplicate, unbounded/unoriented profile/group decoders. Repository profile PATCH also checks its captured owner/session and rejects a mismatched returned user.
- **Swift profile editor:** hydrate the original owner's server profile before exposing editable fields; serialize photo/remove/save operations from the first suspension; capture the API client; retire tasks on dismissal/session change. Removed optimistic whole-viewer rollback, which could restore an earlier account or erase newer fields. Media replies no longer call `adopt` on the editable form. Text writes compare an immutable baseline and keep edits made while the save was in flight. Empty bio/handle changes are real explicit clears.
- **Swift media:** profile, group-info and channel photo imports now use bounded file transfers instead of loading arbitrary-size Data first. Avatar/cover rendering uses bounded oriented pixels; cover editing now produces the actual 2:1 banner rather than a square avatar. Managed upload filenames are normalized before profile/group PATCH (the previously bare iOS profile filename was rejected by the backend). Native name/bio/status clipping respects the gateway's UTF-16 budget without splitting a character at the limit.
- **Profile mirrors:** iOS bio/status cache now uses the injected defaults and an account + gateway namespace; unowned legacy cache entries are not attributed to a new account. Opening the editor hydrates owned fields from the server. Both mounted profile stat panels stop rendering failed/missing values as zero; iOS stats/wallet refreshes have owner/generation fencing and the profile view resets on session replacement.
- **Backend:** PATCH `/api/users/[id]` now binds the existing bearer credential to that target owner before parsing/mutating the profile. Wrong-typed fields cannot silently become clears. Empty bio is allowed intentionally. The database uniqueness race for simultaneous handle claims returns an actionable 409 with a suggestion instead of an unclassified failure. This is not media upload ownership/revocation or a profile revision/lost-ack protocol.
- **Executed:** six real SQLite/route cases cover target authorization, credential rotation/legacy acceptance, managed paths, field types/UTF-16 limits, clears and an explicitly synchronized competing-handle race. **389 server tests PASS**, scoped strict TypeScript graph PASS. Added native ownership/cache/image/baseline/clipping regressions; native execution for this tranche is not yet established. Production inventory 370; Room 16 / GRDB v13 unchanged.
- **Native evidence/correction:** dda51b1 iOS check 120852995919 succeeded. Its Android check exposed cross-module nullable `replyToId` narrowing; 9fb7161 fixes that by snapshotting the property. 9fb7161 Android check 120863148580 then failed in the JVM/build step with only exit 1, without compiler/JUnit annotations; root cause is not established. The diagnostic fallback now includes bounded Gradle/dependency/daemon summaries rather than guessing. No assertion that current native builds are green.
- **Remaining:** standalone legacy feature-settings profile screen (including its unjournaled local-cover claim), durable unsaved-profile drafts, multi-scene/device revision ordering, lost-write acknowledgements, full device picker/lifecycle proof, and upload/media audience revocation. Indexing or a repaired mounted path is not whole-app completion. Autumn styling remains intact.

### Legacy profile editor and home-profile cover lifecycle (2026-10-24)

- Android's separately compiled `feature-settings/ProfileEditScreen` still decoded full-size images on the UI thread and wrote a global `pulse_cover.jpg`, claiming an offline cover would upload on reconnect without any retry job. Removed that path and promise. Picker work now uses the shared bounded, EXIF-aware reader, the original bound account/session, and one upload/PATCH transaction; neither an upload receipt nor a failed PATCH is a confirmed profile change. The previous photo remains on failure. This screen has only an unused MainActivity import in the inspected call tree; the already-repaired Mirror editor remains the mounted path.
- The legacy editor now loads the owned server profile before editing, seeds the actual cover, offers failed-load retry, submits frozen changed-only fields (including intentional bio/handle clears), and preserves edits made during a save. Handle lookups cancel and fence stale responses. No-op saves do not issue empty writes. Retired callbacks cannot patch a replacement account and discard the old visible state when ownership is checked.
- iOS home-profile cover operations now reserve the existing `ProfileMutationGate` synchronously before scheduling their Task. The original mounted owner is retained through picker callbacks, so neither a delayed Task nor a late picker result can select a new session. Dismissal cancels/invalidates the operation; late cleanup cannot unlock a newer ticket. Only confirmed cover metadata is adopted, as before. The autumn view styling is unchanged.
- Authored six Android VM regressions (no-op write, failed upload, upload-vs-PATCH acknowledgement, owner replacement, edits during save, stale handle check) and one additional Swift gate regression. Four changed native source/test files syntax-parse successfully; native compilation/execution of these edits is not yet established. Earlier full server suite remains 389 PASS; no backend changes in this tranche.
- Still open: Android session display-name/color cache propagation; server revisions/lost acknowledgements; durable profile drafts; real-device picker presentation/rotation and multi-scene behavior. The global legacy file is no longer read or falsely advertised as queued; no destructive device-file migration was added. No full-app completion, merge or deployment claim.

### Confirmed display metadata and independent profile panels (2026-10-24)

- Mounted Android profile text acknowledgements now feed only the submitted name/color fields into the session's display cache, including when newer editor text remains unsaved. This is a metadata-only DataStore update, not `chooseViewer`: no credential clear, repository restart, outbox replacement, or whole-profile rollback. The original session is captured at the mounted editor and the active owner/session is checked inside the serialized storage transaction. The call/voice identity bindings now receive the persisted color instead of always passing null. Selecting an identity with no color also no longer inherits the previous identity's color.
- Refactored the prefs implementation to accept its DataStore internally while retaining its injected Context constructor. Authored three real file-backed DataStore regressions: metadata survives reopen while unrelated keys remain untouched; foreign/retired/signed-out updates are refused; a queued write rechecks the session after acquiring the transaction. These tests are authored, not yet executed in the current native build.
- Android profile, statistics and wallet reads now start independently under the original owner. Previously a failed stats request skipped the wallet entirely. iOS stats/wallet Tasks now receive the mounted owner before scheduling, including wallet retry; they run independently instead of serially waiting behind the wallet request. Late starts as well as late replies lose authority on session replacement.
- Ten changed/new native files syntax-parse; `git diff --check` passes. The 07cad5b Android check reached `:app:compileDebugKotlin` and failed; the surfaced summary does not identify the compiler cause. Broadened the diagnostic filter to include unlocated, indented and ANSI-colored `e:` lines, with its actual production generator checked against synthetic examples. This is diagnosis support, not a claimed compile repair. Current native compilation/execution remains open.
- Display-cache propagation currently covers the mounted editor's confirmed local changes, not cross-device profile revisions or a global user-update stream. The encrypted identity vault is deliberately untouched; recovery/cache reconciliation, lost acknowledgements, durable profile drafts and device picker lifecycle proof remain open. No whole-app completion or deployment claim; autumn styling remains unchanged.

### Settings statistics ownership and captured profile origins (2026-10-24)

- Android Settings' profile observer now cancels/fences obsolete account reads rather than assigning a late row before noticing the replacement identity. The footprint loader is a small controller used by the real settings VM: it captures the displayed owner and bound session before dispatch, cancels/supersedes older refreshes, and never converts failures into zero counts. Account changes clear old profile/stat state. The registered ConnectivityManager callback is retained only for the VM lifetime and unregistered on clearing.
- iOS Settings previously launched unowned footprint Tasks and assigned their results directly. Refresh now reserves the shared owner/ticket gate synchronously, captures the API, cancels old work, resets stale data, and validates both success and failure. Session replacement triggers a fresh owned load; dismissal invalidates the request and stale cleanup cannot stop a newer spinner.
- The compiled legacy iOS Mirror profile loader constructed a request with the global gateway URL and a separately captured client's bearer. Replaced that combination with the captured API client's typed user request, including HTTP and returned-owner validation. The legacy mirror balance also remains unknown instead of manufacturing zero. No current `MirrorRootView` invocation was found in the inspected production tree; this is a compiled legacy path, not a claim that it is the current shell.
- Authored four Android loader races/failure tests and two Swift URLProtocol tests against the actual Mirror profile-fetch helper (captured origin/credential, foreign receipt and HTTP failure). Six changed/new native files syntax-parse; diff check passes. These new tests have not yet executed. iOS production archive for a679ab9 passed; Android a679ab9 remained in its JVM/build step at inspection. Earlier 8487b66 Android failed; no exact compiler cause has yet been surfaced. Production inventory now 371; schema versions unchanged.
- Remaining: profile revisions and cross-device cache coherence, legacy mirror polling/lifecycle behavior, real-device picker/navigation testing, older Android API-level network-state behavior, and the broader feature backlog. No full-app completion, merge or deployment claim; autumn styling is preserved.

### Preference-request ownership and the Android room bytecode limit (2026-10-24)

- The mounted Android settings writer no longer creates detached IO scopes or ignores a nested `Result.failure`. It hands the original owner/session to the session VM, so closing the settings surface does not retarget the request. Failures produce an honest notice, explicitly not an automatic retry promise. The alternate settings VM also captures its bound receiver before launching; repository settings reads/writes check their original epoch after suspension.
- Swift settings PATCH captures the API and owner synchronously before its Task and fences both success and authentication/error callbacks. Startup settings GET receives the original session predicate, fences its read ticket/gateway/viewer, and refuses to overwrite local preference edits made after the read began. Added three URLProtocol-backed tests for retired reads, an intervening privacy edit, and the unchanged owned success path.
- **Important remaining defect:** same-session concurrent settings PATCH ordering and whole-snapshot acknowledgements are not yet repaired by these ownership guards. An unrelated successful write can also clear a prior failure hint. Durable preference retry/revisions and Android dotted FX-key decoding need follow-up. Do not equate ownership fencing with full privacy/settings convergence.
- **Concrete Android build diagnosis:** a679ab9 check 120868349682 failed generating `MirrorRoomKt.MirrorRoom`: **Method too large** (JVM 64 KiB method limit), followed by compiler internal-error termination. This is not evidence of OOM. Added three deliberately non-inline composable content boundaries for the room layout, attachment tray and conversation menu. Their contents are unchanged except indentation; no layout node, theme change, or moved remember/effect ownership was introduced. Compilation must verify the resulting method sizes; no successful build claimed yet.
- The installed third-party Kotlin parser segfaults on the full existing 5,000-line MirrorRoom file. Its three edited boundary fragments parse successfully; ordinary changed sources/tests are checked separately. This parser limitation and source checks are not substitutes for native compilation. The previous a679ab9 iOS archive passed; newest checks remain pending. No merge/deployment or whole-app completion claim.

### Per-field preference intent and ordered writes — both native apps (2026-10-24)

- Android now stages local values and per-field UUID receipts in one account-scoped DataStore edit. Separate local-preparation and HTTP lanes allow the next privacy choice to apply while an earlier request waits. Cancelling a UI waiter does not cancel or overtake the repository-owned write. Retry reads the current retained ticket inside the preparation lane; it does not re-stage an obsolete snapshot.
- Swift now records pending values/UUIDs under the gateway and viewer before dispatch, and the session owns a cancellable FIFO HTTP queue. Both platforms accept only requested, returned, still-current fields from a response. An old ABA receipt, unrelated successful update, or full server reply cannot clear another pending change. Incomplete receipts remain unconfirmed. Unknown/corrupt pending records are not silently treated as synchronized.
- Both platforms invalidate old GETs when an acknowledgement retires intent, closing the race where a read begun during a write could arrive after its pending mask disappeared. Android additionally fences overlapping read generations. Explicit Retry is mounted on both apps. No automatic/background retry is claimed. Android's alternate settings surface derives network-pending status from the shared journal, while failed local writes receive a separate field-retained notice instead of a sticky network flag.
- Android preserves acknowledgement field presence rather than manufacturing defaults, and accepts canonical `fx.webglMode` plus legacy persisted `fxWebglMode`. Backend GET/PATCH now bind credentials to the settings owner; PATCH compares the exact preferences snapshot before saving and re-merges disjoint concurrent changes on contention. Bounded contention returns an actionable 409 rather than success. Invalid stored privacy remains conservative until explicitly changed.
- Validation: 393 server assertions passed (381 top-level tests), including actual settings handlers, real SQLite contention and real owner authorization. Strict TypeScript checking passed for the settings dependency graph. Twenty changed/new native source/test files syntax-parse; authored Android queue/store/protocol tests and Swift journal/queue/read tests still require native execution. The earlier **9e8e931** Android build/API30/API34 and Swift archive/tests all passed; those results are baseline evidence, not verification of this new tranche.
- Still open: Swift's general confirmed-settings cache is not yet gateway/account scoped; cross-device same-field revisions and requests that commit after a client timeout; Android local-only room-theme persistence; wider feature backlog and physical-device interaction proof. No theme redesign, schema bump, deployment, merge or whole-app completion claim.

### Account-local privacy caches and strict acknowledgements (2026-10-24)

- Continuing beyond the previous tranche: Swift's server-backed settings snapshot, conversation theme map and personal screen-veil cache now have gateway/viewer provenance. A replacement cache is restored synchronously **before** publishing its viewer, and startup, retry, setters and settings fetches activate the current gateway scope. Pending values overlay the matching snapshot immediately on reopen, without waiting for a successful GET. Old global keys are not adopted as account policy. Unreadable pending records cannot let a cached opt-in outrank an unknown privacy refusal.
- Android's existing account/gateway DataStore isolation is retained. New/unknown/corrupt local privacy fields and account-transition placeholders now fail closed, separately from canonical server default resolution. An unrelated first local edit does not manufacture privacy opt-ins. Foreground attention reads the bound account's settings for each arrival and revalidates its epoch before showing a toast on Main; it no longer depends on an asynchronously mirrored global preview/sound/vibration preference from another account. Event provenance across every navigation/transport boundary is still separate open work.
- The c288e8d Android run caught a real decoder behavior: kotlinx.serialization accepted the string `"false"` as a Boolean, contrary to the acknowledgement contract. Raw settings booleans are now explicitly validated; quoted booleans cannot confirm a change. The older API test that demanded fabricated defaults from a partial response is updated to require field absence. Its canonical defaults resolver tests remain separate.
- Added Swift reopen/account/gateway/publication-order/corrupt-journal tests and Android real-store isolation plus malformed-policy tests. Fifteen changed/new native files syntax-parse; no execution claim for this follow-up. c288e8d Swift production archive passed, its Android build stopped on the quoted-boolean regression, and other native checks remained pending at inspection. Backend unchanged from the 393 passing server assertions. Autumn/device-wide design language and appearance are untouched; no schema bump or deployment. Broad feature review remains unfinished.

### Follow-through into DM room controls and captured origins (2026-10-24)

- The mounted Swift **DM** `RoomInfoSheet` had not inherited the group manager's ownership safeguards: it launched independent Tasks for reads/TTL/mute/veils, read a mutable session client, and could restore obsolete mute state after failure. It now captures the API/viewer/epoch, claims a single-flight mutation before launching, invalidates old reads, validates returned conversation identity, cancels work on dismissal, and closes on session replacement. TTL notices use the returned value, successful details update the open room, and uncertain outcomes reload instead of rolling back an old snapshot. Existing styling is preserved. Screen-privacy acknowledgement additionally requires `ok: true`, not just a matching Boolean.
- Android's mounted room-info personal/room-wide screen-security writers now capture a bound repository and share an ownership gate. They recheck before network dispatch and before notes/state/callbacks; stale cleanup cannot release a successor. Metadata refreshes also capture and validate the original repository/viewer/epoch instead of allowing a suspended read to retarget itself.
- Follow-up gateway check: Swift preference tickets now validate the **captured API origin** as well as the active prefs scope. A newly selected gateway cannot send its pending settings through a session client that still targets the previous server. Added an origin regression to the journal-owner test and negative screen-privacy receipt cases to the actual API fixture.
- Native compilation surfaced a module-boundary issue in 77121d7: feature-settings could not access the JsonObject return type used only to get field names. Added a protocol helper returning plain `Set<String>` rather than importing serialization into the UI module. 77121d7 Swift archive passed; Android failed at that boundary; current follow-up remains uncompiled. Syntax/diff checks are not native execution or real-device lifecycle proof.
- Remaining room-control work includes per-room privacy intent/read revisions, durable retry of personal-veil changes, stale metadata versus newer Seen updates, the other mounted metadata controls, and generic mute/pin acknowledgement depth. These focused ownership corrections do not clear the entire room/settings tree or the full app. Backend unchanged; no merge/deployment/theme redesign.

### Mounted screen-security enforcement, not just settings switches (2026-10-24)

- Found a concrete mounted-path gap: Android's alternate `ChatRoomScreen` applied FLAG_SECURE, but the actual Mirror room exposed the same switches without applying that protection. The Mirror scaffold now feeds validated owned metadata to a dedicated security boundary. Unknown policy remains protected while loading; covering/reopening the room invalidates the previous policy, preventing a room-info privacy change from briefly reopening capture. Focused-room metadata refresh is bounded to 20 seconds and has read/owner fences; remote changes are not instantaneous.
- The mounted boundary masks drawing and accessibility content while a protected room is unfocused or not resumed. A shared window lease helper now serves both Android room implementations: one closing room cannot clear another's secure flag, and protection already present before the first lease is preserved. Core JVM tests cover overlapping/duplicate/stale releases and independent windows. Room composition also includes the attachment epoch, rather than remembering an old same-ID gateway binding across recomposition.
- iOS now requires both personal and room policy to be explicitly off before removing the inactive veil. Unknown details fail closed. The inactive cover uses an **opaque existing ember color**, not translucent material, and hides accessibility content while engaged. A pure policy test covers all known/unknown flag combinations and active/inactive state. This is not an iOS screenshot-prevention guarantee or physical-device/app-switcher proof.
- Nine changed/new native files syntax-parse; current changes still require compilation/device checks. ce3220f Swift archive passed; its Android/native test jobs were still pending at inspection. Production inventory now 377; schema versions unchanged, autumn palette retained. Wider attachment/dialog/PiP capture surfaces and per-room pending privacy revisions remain open. No full-app completion, merge or deployment claim.

### Room-info metadata versus live Seen (2026-10-24)

- Followed the room-info callback into the message view model. Swift rebuilt reader watermarks from the entire returned metadata snapshot, so a late info response could rewind a newer live Seen stamp. It now preserves the newer timestamp only for readers whose incoming timestamp is still present and valid; missing/hidden readers are removed, preserving conservative privacy revocation. Initial cached reader state also initializes the published last-read value. Foreign-room details are refused at the view-model boundary.
- Added a real `RoomViewModel` regression (automatic network work disabled) for older metadata, foreign-room metadata, and a hidden reader. Two changed/new Swift files syntax-parse; execution remains pending. The corresponding Android trace already invalidates in-flight conversation snapshots on `Signal.Read` before fetching/caching detail, so that existing ownership/read-generation path was not rewritten. This does not resolve all read/unread ties, offline boundaries, privacy propagation or physical navigation cases.

### Personal screen-security intent, read races and room-detail authority (2026-10-24)

- Continued on both platforms after 3e2d60d passed Android build/API30/API34 and Swift archive/tests. Those checks validate the previous revision, not the changes below. Reconciled stale local Git metadata only after hashing every published tracked blob against the workspace (zero file differences); no source files were replaced.
- Android now persists each personal room-privacy value, revision and pending UUID together in the account/gateway DataStore. Repository-owned local/HTTP lanes retain intent when a sheet closes, coalesce superseded writes, and retry the existing ticket rather than re-staging an old choice. Reads before staging or before ACK retirement cannot undo the choice. Group metadata overlays the pending personal value; both room implementations observe the local policy directly, so protection need not wait for the next metadata poll. Mirror and alternate group controls show explicit Retry. Corrupt values fail closed and are not discarded.
- Swift now has a separate scoped per-room privacy journal, rather than treating a failed personal-veil PATCH as a disposable comfort preference. Session-owned dispatch outlives the presenting sheet, validates the captured API origin, and applies only the matching ticket. Room-open and DM/group-info GETs capture revision/activation tickets; they cannot overwrite pending or newly acknowledged values. Relaunch restores pending protection immediately. The DM/group controls expose retained-state/error notices and explicit Retry; no automatic background replay is claimed.
- The Swift room-wide policy read also now uses the existing read-generation controller: a late initial GET cannot undo a newer detail delivered by room controls, a newer GET supersedes an older one, and room dismissal retires callbacks. A real RoomViewModel regression covers these transitions.
- Swift conversation-detail transport now validates the requested room and viewer membership before returning data; the caller must match the captured API owner. Identifier encoding now uses RFC-unreserved characters rather than allowing query/path delimiters through `q`. Added actual URLProtocol checks for foreign/missing-member payloads and reserved-character IDs.
- Personal screen-privacy PATCH now requires its owner's bearer even when the header is absent. Actual-handler/real SQLite checks cover missing/foreign credentials, repeated desired-state writes, own-recipient-only notices, and departed membership. The full server suite passes **394 assertions / 382 top-level tests**; strict TypeScript checks pass for the route dependency graph. Twenty-two changed/new native source/test files syntax-parse, with new file-backed Android journal and Swift journal/model/transport regressions awaiting native execution.
- Remaining: ambiguous late remote commits/cross-device revisions, room-wide privacy delivery guarantees, legacy cache intent provenance, other metadata controls and capture surfaces, broader read/unread/offline/navigation work. Standard UserDefaults persistence is not a synchronous disk-flush guarantee. Autumn styling and DB schemas remain unchanged; no deployment, merge or whole-app clearance.

### Room-control authority and typed metadata follow-through (2026-10-24)

- Published personal-intent repair as 90b62b5. Its Swift archive, web and server delivery checks passed; native runtime/unit completion was still pending. Android compiler found the missing Flow `first` import (corrected in f590b11), then the mounted Retry button's missing Material `TextButton` import (corrected here). Parser success was not compiler clearance.
- Continued both clients' actual detail/mutation paths. Android detail GET now escapes the room path and rejects mismatched rooms or absent requester membership before caching. Swift/Kotlin metadata and TTL responses likewise must retain the requesting member; group-only changes must return group identity, names cannot disappear, and an absent photo field is not an explicit photo clear. Swift also rejects a caller ID inconsistent with its captured API before dispatch.
- Shared Android conversation decoding no longer coerces quoted Boolean/integer policy values. Room security, personal security, block/broadcast/self/manual-unread flags and relevant counts/durations retain explicit false/zero/null/missing distinctions. This applies to reads and other DTO consumers, not only a single mutation response. Swift Codable already rejects quoted policy booleans; paired transport tests cover both platforms.
- Deliberately retained the existing newer-server-truth behavior: a validated newer TTL or room-detail snapshot can supersede the requested value. These responses are snapshots, not proof of a server revision or exactly-once write; no invented local success value replaces them.
- Room detail, shared metadata, TTL and slow-mode routes now require the participant's credential even when a bearer header is absent; private detail GET is `private, no-store`. Real-handler/SQLite/credential tests cover denial before reads/writes/fanout, successful authorized changes and membership departure. Full server suite **395 tests / 383 top-level tests PASS**; strict TypeScript route graph passes. Native regressions added; new native execution remains pending.
- Official kotlinx.serialization 1.7.3 source was consulted for the transforming serializer's nonnullable type bound; nullable room policy delegates through the library's nullable adapter. Headerless web/legacy clients must migrate before deployment. Broad feature-tree, persistence-failure handling and cross-device ordering remain open.

### Unreadable preference recovery without resetting stored choices (2026-10-24)

- Traced another Android crash path: the preference flows collected by mounted rooms/settings let DataStore read errors escape. Added a collector-lifetime retry boundary (five-second delay, cancellation propagated, no storage rewrite). Global disclosure preferences fall back closed while retaining appearance values; theme/navigation/conversation-theme/search flows retain their last observed values; unreadable pending-settings state cannot become a false "synchronized" report. A healthy reread restores the stored values. This is read recovery, not a new background worker or mutation retry policy.
- Personal room state now distinguishes unreadable storage, keeps protection on, and exposes the problem on both Android info surfaces. Mounted personal-policy collectors are remembered and keyed by repository/room/attachment epoch instead of being recreated on every recomposition or retaining a prior owner's collected value during a key change. Other lifecycle/epoch propagation paths remain under review.
- Swift already handled initial corruption, but a journal becoming unreadable after a cached Off could leave that Off published. Read/retry/dispatch/ACK error paths now retire that cached policy to unknown/protected. Added an explicit "Reload saved security choices" action: it rereads without resetting data or inventing a remote write; persisted pending identity still requires its own Retry.
- Added real-store Android disclosure/appearance/recovery/cancellation regressions and Swift live-corruption/reload/legacy-cache migration regressions. Eleven native files syntax-parse; compiler/unit execution is pending. Backend remains at the previously checked 395 tests; no backend source changed here. Storage write failures, disk-flush guarantees, device lifecycle validation and the broader feature tree are not cleared.

### Pin/mute/archive confirmation and server timestamps (2026-10-24)

- Followed the next real flow on both clients: pin/mute/archive used body-blind 2xx helpers, and mute/pin/archive caches could manufacture timestamps from device time after a slow response. Both transports now require typed, versioned receipts for the requested account/room/value, including an explicit timestamp or explicit null. Empty/mismatched/quoted/missing receipts cannot confirm a local flag change.
- Android repository projection now uses the validated server timestamp, including the exact mute expiry. Swift single-row and batch actions use the same saved timestamp rather than restarting the duration on receipt; info sheets still refresh current server detail. The Swift mirror-room menu no longer silently ignores mute failure or resolves a different session API inside a delayed task: it captures the client/epoch, uses the existing room-operation gate, and surfaces failure/confirmed expiry.
- Server mute/archive authority and the participant update now share a Serializable transaction with an affected-row guard; contention returns 409. Mute omission is no longer interpreted as unmute, and prototype-property presets such as `constructor`/`__proto__` are rejected instead of constructing invalid dates. Archive replay preserves an existing archive timestamp. All three flag routes require the owner's credential and notify only that owner's devices.
- Added actual handler/SQLite/credential tests and paired native transport cases for false/null, exact stored timestamps, wrong owner/room/value, absent/malformed timestamps and quoted header fields. Full backend suite **398 tests / 386 top-level tests PASS**; strict TypeScript route graph PASS; eight changed/new native files syntax-parse. New native compilation/tests remain pending. The preceding a8ef032 Swift archive/build-tests passed; Android checks were still running.
- These receipts confirm one authorized mutation, not end-to-end ordering across devices. Mute retries can still renew a relative duration, ambiguous late commits/newer activity require further ordering work, and storage failures/device clock skew are not cleared. Coordinated server/native/web rollout remains required; autumn theme unchanged.

### iOS inactive protection beyond message rows (2026-10-24)

- Continued the capture-surface trace: the shipping Swift chat's opaque cover protected the message list, but drafts and AI recap content sat outside it. Added an owner-leased UIKit window shield, driven synchronously by app/scene deactivation notifications rather than scheduling a later task. It evaluates the live room/personal policy and session ownership at deactivation, so a just-staged privacy change does not wait for the next SwiftUI render.
- The shield covers the existing room window, including composer/recap and already-presented content, with opaque autumn chrome and modal accessibility semantics. Independent leases cannot release each other. Temporary presenting-view detachment retains the lease for a full-screen child; reattachment or SwiftUI teardown releases it. Window references are weak; activation removes the cover.
- Added UIKit lease/window-size/activation/separate-window/temporary-detachment/current-policy regressions; four Swift files syntax-parse. Native build/device validation is pending. This is inactive snapshot mitigation, **not iOS screenshot prevention**. Late presentations, system-owned windows, actual app-switcher capture and multi-scene device timing remain to verify; no all-capture-surface clearance.

### Bulk-action retry selection and tap-owned dispatch (2026-10-24)

- Found a concrete alternate Android ChatsScreen inversion: partial mark-read removed failed IDs and kept successful ones. Archive also exited selection before requests finished. All three bulk actions now remove only confirmed targets, retain failures/newly selected rooms, and fence callbacks against a reopened selection session.
- Android row and batch pin/mute/archive/unread/read actions capture the bound repository plus attachment epoch synchronously at the tap. Per-room tickets block overlapping writes, deduplicate targets, stop a batch at owner retirement, and prevent an old completion from clearing a successor's claim. Ordinary failures are surfaced; cancellation-ignoring replies are checked again before callbacks. The mute toast no longer invents a device-time deadline.
- Swift archive/mute/read batches now acknowledge selection through a generation-bound helper used by the actual ChatsViewModel. Successful rows leave the retry set, failed/new rows remain, and an old batch cannot clear a reopened selection containing the same room. Partial counts no longer imply every selected chat succeeded.
- Added actual Android ViewModel deferred-repository tests for overlap, partial failure, pre-dispatch/mid-batch owner changes, successor cleanup and cancellation; real Swift ChatsViewModel tests cover partial/reopened selection. Five native files syntax-parse; compiler/unit checks remain pending. Backend is unchanged from the 398-test pass. This does not clear other account-bound async surfaces or bulk server idempotency.

### Peer block receipts and honest private lists, both platforms (2026-10-24)

- Block-state reads, desired-state writes and private blocked lists previously trusted a public actor ID; list ownership was only query/path equality. Pair operations now require the existing owner credential and use one Serializable pair snapshot. Repeated desired writes preserve one row; self-writes/missing peers fail. Typed versioned receipts bind actor, target and exact state; private lists carry owner/version, deterministic ordering and no-store. DM invalidation goes only to the actor, not the blocked account.
- Kotlin and Swift reject missing/default/foreign/mismatched/ill-typed block receipts and malformed/duplicate/self/undated list rows. Android repository reads/writes additionally reject account retirement after transport. This is not a rollout-compatibility claim: headerless web/legacy clients need coordinated migration.
- Shipping Android MirrorUserPage had nested `runCatching { Result } .isSuccess` false positives for both blocking and reporting. It now unwraps the real result, captures the receiver/inputs, uses composition-owned main tasks, suppresses repeat block taps, and shows errors. Unknown initial block state is a retry, not an invented Unblock/Block decision. Its DM navigation is now similarly structured and owner-bound. Alternate UserPage/Contacts safety actions also bind before dispatch and expose returned failures.
- Shipping Swift profile now distinguishes unknown block state, validates configured ownership and fences profile/target ABA responses and busy cleanup. Swift blocked-list reads/actions capture API, surface epoch and request tickets; old reads cannot restore an unblocked row, old cleanup cannot release a successor, and disappearing retires tasks. Android shipping and alternate settings lists retain confirmed rows on errors, show retry instead of false emptiness, invalidate pre-write reads, gate actions synchronously and remove only acknowledged rows. Autumn surfaces remain unchanged.
- Actual SQLite/handler/credential tests cover forged/private reads, missing/self targets, repeated desired writes, receipts and actor-only fanout. Full backend **401 tests / 389 top-level PASS**, strict TypeScript expanded route graph PASS. Paired native API regressions and actual Android blocked-list ViewModel deferred-reply tests added; **13 changed/new native files syntax-parse**, native compiler/unit/device results pending for this tranche.
- Still open: lost receipts versus later/cross-device opposite writes, durable offline intents and pagination/large-list load; report endpoint authority/receipt semantics need their own follow-through. These targeted fixes are not a full-app or safety-feature clearance.

### Private reporting end-to-end follow-through (2026-10-24)

- Continued from blocking into reports, not just UI notifications. Report GET/POST previously accepted someone else's public reporter ID; writes also split existence/read/create into separate operations. Both require the existing owner credential now. Serializable pair validation/upsert returns versioned reporter/target/reason/exact-evidence/server-time receipts; repeat reason updates one row. History is owner-bound/versioned/private-no-store, with deterministic order and no notification to the reported user.
- Both native transports now reject empty/false/mismatched/foreign/undated report acknowledgements and malformed/foreign/duplicate history. Kotlin repository rejects retired-owner replies. History failures no longer become successful empty history on Swift.
- Shipping Swift report sheet captures client/epoch/target/reason/evidence before launching, claims pending synchronously, retires work on disappearance, preserves the draft on failure and only dismisses/notifies after a matching acknowledgement. History errors retain known rows and expose retry. UTF-16 counting now matches the server/Kotlin limit; the old grapheme-count + prefix truncation could reject emoji-heavy reports or silently alter evidence. The server preserves submitted text exactly and rejects non-text/over-limit evidence.
- Android shipping report input is disabled during its captured request. Alternate profile/contacts panels no longer close at submit before a verdict; failure keeps the form, errors remain visible even with populated contacts, and duplicate dispatch/old-owner callbacks cannot dismiss a successor. A report form opened for an older owner cannot submit under a replacement account. Existing autumn appearance is retained.
- Backend **404 tests / 392 top-level PASS**; expanded strict TypeScript graph PASS. Added paired native transport regressions and actual ContactsViewModel deferred-report/owner-retirement tests. Eleven changed/new native files syntax-parse; native compile/unit/device confirmation is still pending for this tranche. Earlier 1e518c0 Swift archive/build-test passed; newer checks remain pending, not completion evidence.
- This does not settle moderation operations, durable offline reports, cross-device evidence revisions/lost-receipt replay, or all contacts loading/navigation races. Whole-app clearance remains **no**.

### Tap-owned inbox leases and contact-request races (2026-10-24)

- Continued the Swift inbox work beyond partial selection. Plain pending-room sets survived owner changes and old cleanup could subtract newer claims; independent same-kind batches also shared an unowned Boolean. Actual ChatsViewModel now claims UUID/epoch/room-set tickets synchronously at taps, captures the API before launching, tracks cancellable tasks, fences every completion and releases only its ticket. Batch indicators derive from all live claims, so one finishing batch cannot enable controls while another is pending. Stop/owner retirement invalidates tasks and reopening cannot be cleaned up by an older completion. Shipping row/archive/batch callbacks now call the synchronous capture methods directly.
- Android alternate ContactsViewModel directory reads now capture the receiver, cancel/supersede older searches, retain real rows on failed refresh, reset retired-owner rows and reject late results. DM creation has a separate synchronous single-flight claim, owner-bound transport, cancellation checks and ticket-owned cleanup; an old reply cannot publish navigation or clear another owner's spinner. Directory loading no longer shares one mutable pending Boolean with DM creation. Populated contact lists now expose refresh errors/retry.
- Added real Swift ChatsViewModel ticket/overlap/owner/reopen/batch regressions and actual Android ContactsViewModel deferred-directory/DM tests. Six changed/new native files syntax-parse; runtime/compiler tests for this tranche pending. Backend remains at the preceding 404-test pass (unchanged here).
- Follow-up native evidence: 9859ce2 Swift archive passed. Its Android build job failed in three **legacy Wave6SocialApiTest fixtures** that still supplied unversioned block/list replies. Updated those route-shape success fixtures to the new strict receipts, including report fixtures for cb9e47d, without relaxing production validation or the malformed-receipt regressions. Replacement native checks are required; no claim of all-native pass.
- Ticket ownership does not prevent a previously dispatched request committing late on the server. Cross-device ordering, ambiguous opposite writes, broad contact directory pagination and unrelated navigation/capture surfaces remain open.

### Owner-private comparison markers, not encryption proofs (2026-10-24)

- Traced the actual safety-number implementation: it hashes public account IDs and a public domain string, not device identity keys. Removed the Android room's false claim that this confirms an end-to-end encryption identity; both native comparison sheets now explain the limit, and backend source comments no longer describe a cryptographic key proof. Autumn styling is unchanged. This is an honest account-comparison marker, not an E2E implementation.
- Verification GET/POST/DELETE previously accepted public owner IDs without a credential. They now require the existing owner's credential, validate the pair and read/write one Serializable snapshot, reject missing/self mutation targets, and return private-no-store full versioned owner/peer/state/code/timestamp receipts. Both native APIs reject unowned/legacy/default/opposite/malformed marks, incomplete codes and invalid/missing timestamps. Success fixtures and new paired native receipt cases cover the current contract.
- Kotlin repository comparison requests now use the owner fence and reread after a confirmed mutation; returned state retains the actual 60 digits rather than replacing them with an empty DTO field. Both mounted Android sheets capture the receiver before dispatch, use structured main-scope work, retain state/show failures and guard owner/peer outcomes. The info sheet cannot let an older read overwrite a pending write.
- Swift shared badge cache is epoch-scoped at **every** read/write call site; another login/gateway cannot inherit a peer checkmark. DM cache hydration captures client/epoch and rejects retired replies. SafetyBadgeModel resets on owner/target change, fences reads/actions/cleanup, cancels mutations on disappearance, retries initial failures and only offers marking after real state loaded. Unknown digits render placeholders, not invented zero codes; the sheet checks configured ownership before displaying an old marker.
- Full backend **405 tests / 393 top-level PASS**, expanded strict TypeScript graph PASS, eleven changed/new native syntax parses PASS. Added paired API rejection regressions, actual Swift cache ownership regressions and SQLite/handler/private-direction tests. Native compilation/unit/device acceptance for this tranche remains pending. cb9e47d Android failure was confirmed as the missing `jsonPrimitive` import already corrected in 478e275; replacement checks remain pending.
- Still open: actual E2E/device-key identity protocol (not supplied by this number), cross-device/late-opposite-write ordering, real-device sheet lifecycle acceptance and noninstant badge refresh. Mandatory credentials/versioned responses require coordinated server/native/web migration. Full-app completion remains **no**.

### Notebook authority and opening ownership (2026-10-25)

- Followed Note to Self from the tap into the backend. The route only checked credentials when a token was present, allowing a headerless request naming another public user ID to create/open that user's private notebook summary. The existing owner credential is now mandatory. Responses are private/no-store and carry a versioned owner receipt; both native APIs validate that the returned chat is a non-group self-chat with exactly the requesting owner before caching/navigation.
- Swift Note to Self now captures API, owner epoch, cache and navigation intent synchronously before launching. UUID-owned pending state can be cancelled/reopened without an older cleanup releasing the new request or permanently holding the button busy. The active task is retired with inbox/navigation lifecycle; successful navigation releases its claim before the inbox tears down.
- Android alternate inbox notebook actions now capture the bound repository at the tap, reject duplicates, support cancellation, validate offline self identity and fence old callbacks/cleanup. The mounted MirrorRoot personal/deep-link resolver now captures and verifies account activation as well as user ID, rechecks after awaits, and resolves links from the captured repository cache rather than taking a potentially older Compose-list shortcut.
- Added actual handler/SQLite credential/read/create/replay tests, paired malformed/foreign/shared notebook receipt tests, actual Swift model pending-lease tests and actual Android model delayed/cancelled/retired-owner navigation tests. Full backend **406 tests / 394 top-level PASS**; strict TypeScript affected route graph PASS; nine changed/new native files syntax-parse. Native compile/unit/device checks for this change are pending.
- Baseline 39300ba native results are now available: Android build/API30/API34 and Swift archive/build-test succeeded. Those results do not certify this new change or the full app. Coordinated legacy/web credential migration and late server commits after cancelled navigation remain open; no deployment or whole-app clearance.

### Composer integrity and reminder parsing (2026-10-25)

- Traced the mounted Android slash-send path and both platforms' reminder input. Android's duplicate slash parser called `toInt()` on unbounded duration digits; the shared Kotlin parser could wrap duration multiplication/addition, and Swift's multiplication could trap. Both kernels now reject zero, overflow and unrepresentable dates, handle uppercase units and use next-calendar-day semantics for `tomorrow` even before 09:00 (including DST). Android's mounted composer uses that same parser instead of its divergent copy.
- Found that the two-field reminder sheets passed their standalone When text into a combined note-and-time parser. Their own examples (`in 30m`, `tomorrow`) were rejected; a prefixed note in that field could replace the Note field. Added a time-only parser on both platforms and connected the sheets without substituting the note. Android's alternate sheet now awaits acknowledgement, suppresses duplicate submission and keeps note/time on failure rather than clearing them at dispatch; cancellation propagates.
- Removed silent `/me` truncation in both Kotlin surfaces and Swift. Transform results are accepted whole or rejected using the server's UTF-16 limit, leaving the submitted draft intact. Mounted Android no longer takes a 2000-unit prefix for effect/anonymous text and atomically refuses an oversized text-field insertion rather than cutting a paste through an emoji. Its delayed send/reminder/edit operations use a tap-captured repository instead of re-resolving the live account after scheduling.
- Added paired regressions for arithmetic extremes, zero durations, uppercase/seconds, pre-09:00 tomorrow/DST, standalone time-field examples and full Unicode transform integrity; mounted Android input/parser regressions cover its actual helpers. **Twelve native syntax parses and diff check PASS; native compiler/test execution pending.** Backend is unchanged from the 406-test notebook run; no test-count claim substitutes for device behavior.
- Notebook archive compilation exposed a Swift local-function actor isolation error; a41b180 explicitly marks the ownership predicate `@MainActor`. Rebuild results are pending, not inferred from syntax parses.
- Still open: lost-ACK/idempotency for reminder creation, absolute-date input parity, broader overlength local/server draft persistence and mounted account-transition lifecycle coverage. Existing autumn styling is unchanged. This is continued repair, not whole-app completion.

### Explicit iOS account-surface lifetime (2026-10-25)

- Traced the previously flagged one-shot inbox store binding back to the actual root. `PulseSession.start` retires the old account and reopens its database, but the main tab tree relied on a rendered `ready == false` branch to destroy its StateObjects. A fast restart can coalesce ready transitions, leaving the old inbox model/store attachment and contacts state alive. The whole iOS tab subtree now has explicit session-generation identity, including its child navigation/sheets; it does not depend on observing an intermediate loading frame. Android already keys this main surface by viewer and configured gateway (`MainActivity`), and its in-flight personal/link resolution was epoch-fenced in ca77347.
- This prevents retained child-model state; it is not a claim that every root-level sheet or multi-scene lifecycle is covered. The old one-shot cache binding is no longer treated as an independently proven normal-start failure: session/store replacement and root lifetime must be considered together. Changed Swift source parses; simulator lifecycle evidence is still pending.

### Reminder credential and live-context boundary (2026-10-25)

- Following the same reminder flow into the backend found all four reminder routes trusted the supplied public user ID without checking its credential. GET/POST/PATCH/DELETE now require the existing owner credential before reading, creating, firing or cancelling a private reminder; successful responses are private/no-store. Both native transports already attach their account bearer token. This is authorization of an existing feature, not sign-in/recovery work.
- Reminder context now checks current chat membership before revealing the chat label or anchored text. The shared anchor lookup excludes deleted/expired messages and replies whose canonical same-room root is absent/deleted/expired; POST also refuses those unavailable anchors. The user's own explicitly authored reminder note is retained after access/source revocation, but the fetched source preview is not. Snippet clipping no longer cuts UTF-16 surrogate pairs.
- Actual handler + SQLite regressions cover tokenless/invalid/foreign credentials on all four methods, unchanged data after denied writes, authenticated create/resolve/delete replay, expiry/deletion, lost membership, canonical thread roots and Unicode clipping. Targeted **27 PASS**; full backend **409 tests / 397 top-level / no failures or skips**; affected-route strict TypeScript and diff check PASS.
- Native f795927 Swift archive succeeded; the other native executions/newer-root checks are still pending at this checkpoint. Reminder creation lost-ACK/revision contracts, client-retained historical snippets, source-write races after the read snapshot, pagination/load costs and legacy web credential compatibility remain open. No deployment or whole-app clearance.
