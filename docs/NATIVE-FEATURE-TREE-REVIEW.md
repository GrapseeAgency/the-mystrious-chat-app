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
