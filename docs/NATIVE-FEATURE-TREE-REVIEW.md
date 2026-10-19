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
- Added **5 Swift regression methods** (three PTT policy, one closed-surface delayed
  permission, one Space switch). Together with the Android continuation this is
  **22 new native regression methods**, not a declaration of their execution result.
- Native compilation caught incorrect Java UUID construction in the new focus code;
  corrected to `UUID.randomUUID()` in both routers. No prior failed build is counted
  as a pass, and syntax parsing alone is not compilation proof.
