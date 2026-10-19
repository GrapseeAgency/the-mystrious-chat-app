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

`NATIVE-SOURCE-INVENTORY.csv` enumerates **300 production files: 186 Kotlin and 114 Swift**.
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
