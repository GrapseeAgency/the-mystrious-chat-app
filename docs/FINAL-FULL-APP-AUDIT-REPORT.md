# PULSE — FINAL FULL-APP FORENSIC AUDIT REPORT

**Audit type:** FINAL FULL-APP FORENSIC AUDIT — NO NEW FEATURE DEVELOPMENT (record-only; no findings were fixed during this audit)
**Audit executed:** 2026-09-23 (sandbox session)
**Auditor:** Z.ai Code (orchestrator) + 6 read-only evidence agents (AUDIT-E1…E6, worklog entries appended)
**Discipline:** every status below is backed by current-HEAD source reads (file:line), re-executed tests, or live E2E probes run in this session. No inherited PASS. No "looks implemented". Hardware claims are never made from emulator/CI.

---

## 1. EXECUTIVE SUMMARY

The app is a real, working cross-platform chat product with a verified backend, verified realtime relay, verified offline/outbox core, verified auth-token hardening, and green CI on all three platforms — but **native parity is materially incomplete**: of the master specification's 175 feature rows, **41 rows have features missing on BOTH natives**, **1 row is missing on all three surfaces (web included)**, and **1 live-confirmed security risk exists in a default internal key**. Android additionally carries a demonstrably broken send-cap (client 4000 vs server 2000 → user-facing 400s) and two dead-code paths (poll stop, kanban prefill). Video calling is web-only. Group administration (info/members/leave/invite-create) is web-only. PiP panes are web-only. Formatting (markdown/spoilers), jumbo emoji, incognito, scheduled messages, disappearing TTL, slow-mode lockout UI, slash-command palette, and sticker picker are **all missing on both natives**.

There is no evidence of regression: **293/293 live E2E checks across 9 suites passed** against a freshly started backend, **301/301 Android JVM tests passed**, the release APK was byte-verified (sha256) and badged (versionCode 20), and all Room/GRDB migration chains are intact. Wave 3-HW and Wave 5-HW remain OPEN and are recorded as hardware-blocked, not passed.

**Status tally across 175 rows (per surface; a row can differ per surface):**

| Surface | CODE/TEST/RELAY VERIFIED | PARTIAL | NOT FOUND | EXTERNAL BLOCKED |
|---|---|---|---|---|
| Android | 134 | 19 | 22 | — (Tier-2 push rows EB by absence of accounts) |
| iOS | 138 | 15 | 22 | 2 (App Store channel; push) |
| Web (ground truth) | 174 | — | 1 (F-MS-29 quick phrases) | — |
| Backend API | all routes present | 2 deviations (draft GET absent; `since=` delta absent — spec-optional) | 0 | 2 (push sender; AASA) |

Bottom line: **do not ship "parity complete"** — the natives are strong on conversations, messaging core CRUD, offline, realtime, voice/stage/space, stories, hub, and settings, but 23% of spec rows have zero native implementation, concentrated in messaging polish, media translation/location, AI surfaces, PiP, group admin, and video.

---

## 2. EXACT RELEASE / COMMIT AUDITED

| Item | Value | Evidence (this session) |
|---|---|---|
| Release tag | `v0.10.0-native` → commit `6692313` | `git rev-list -n1 v0.10.0-native` |
| origin/main (release state per directive) | `87bd7a3` ("wave8: completion report + CDN v0.10.0-native…") | `git log origin/main -1` |
| Local HEAD | `196852d` (origin/main + 1 unpushed audit-tooling commit) | `git status -sb` → `main...origin/main [ahead 1]` |
| versionCode / versionName | **20 / 0.10.0-native** (gradle default) | `apps/android/app/build.gradle.kts:11-12` |
| Release APK badging | `package: name='app.pulse.chat' versionCode='20' versionName='0.10.0-native' targetSdkVersion:'35'` | aapt2 dump badging on re-downloaded asset (build-tools 35.0.0) |
| Release asset | `Pulse-v0.10.0-native.apk` 23,973,927 B, sha256 `5540c8bb29e0b96093ed79a51ec173d5310c07a744cb99ecff22c59ee080b2ec` — re-downloaded this session, hash recomputed, **identical** to GitHub digest and CDN manifest pin | GitHub releases API + `sha256sum /tmp/pulse-audit.apk` |
| CDN manifest | versionCode 20, same sha256, `gateway:""` and `socket:""` **still empty** (bridge pin open) | `download/update-manifest.json` |

**Local-commit disclosure:** HEAD commit `196852d` (message = UUID) contains ONLY: `apps/qa/final-audit/security-probe.js` (isolated audit area, per directive), a Prisma generated-client regeneration (brings generated client in line with the already-shipped source schema field `sessionTokenHash` — verified present in `prisma/schema.prisma:25` since Wave 8), and growth of tracked dev DB `db/custom.db` (+86 KB, from audit probe runs). No production behavior change. It is unpushed; origin/main remains `87bd7a3`.

---

## 3. MASTER-SPEC COVERAGE SUMMARY

- Authoritative source: `docs/NATIVE-PARITY-MASTER-SPECIFICATION.md` §2 (lines 78–373) — **175 unique feature rows** verified by extraction (`apps/qa/final-audit/data/features.tsv`, 175 lines).
- Families (row counts): Identity F-ID×7 · Conversations F-CL×19 · Messaging F-MS×30 · Media F-MD×9 · Rich objects F-RO×10 · Voice rooms F-VR×4 · Stage F-ST×4 · Space F-SP×3 · Calls F-CA×6 · Stories F-SR×5 · Channels F-CH×4 · Contacts/Profile/Safety F-CP×9 · Search/Mentions F-SM×4 · Folders F-FD×3 · PiP F-PI×3 · Settings F-SE×9 · Hub F-HB×10 · Groups F-GR×6 · Offline F-OF×5 · Realtime F-RT×8 · Theme/FX F-FX×7 · Platform services F-PS×5 · AI F-AI×5.
- Every row was independently checked this session against current source by agents E1–E6 (worklog: AUDIT-E1…E6) and, where applicable, against live E2E suites re-run in §18.
- Wave reports (docs/WAVE-0…8) were treated as **history, not proof**; every claim re-verified that was re-verifiable in this sandbox (CI via API, tests re-run, release re-hashed). Wave-8 claims matched reality in all checked instances.

---

## 4. FULL 175-FEATURE MATRIX

Legend per cell: **CV** code verified (source read at HEAD) · **TV** test verified (named test) · **RV** relay-verified live this session · **P** partial (dimension named) · **NF** not found · **HW** hardware-gated unverified · **EB** external blocked · **—** no backend/relay surface required.

### 4.A Identity & Session (F-ID)
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-ID-01 | Create identity | CV (OnboardingViewModel.kt:184, SWATCHES:117) | CV (OnboardingView.swift nameMax:18, confetti:742) | CV users POST | web onboarding-screen.tsx:87-128 |
| F-ID-02 | Handle claim | CV (350ms debounce, registry fallback repo:582) | CV (OnboardingView.swift:21,134) | CV check-username + handles.json | 60 s registry backoff timing not re-verified (minor) |
| F-ID-03 | Reclaim via login | CV (loginInstead:160) + TV Wave8ApiTest | CV (confirmReclaim:164) | CV login route | — |
| F-ID-04 | Session token model | CV (SessionTokenStore, join token PulseSocketClient.kt:184) · Keystore cipher = HW/CI-androidTest | CV (PulseSession.start(as:):159, Keychain:21) | RV (W8 E2E 19/19 this session) | reconnect re-join verified; distinct foreground trigger absorbed by reconnect |
| F-ID-05 | Multi-identity switcher | CV (chooseViewer:112 clears token, re-join) | CV (IdentityPickerSheet.swift:119) | CV | — |
| F-ID-06 | Forget viewer | CV (forgetViewer:122 + UI:362) | **NF** — `PulsePrefs.setViewer(nil)` zero callers; picker offers pick/create only | — | spec requires explicit forget on iOS |
| F-ID-07 | 8-color palette | CV (SWATCHES emerald…cyan) | CV (SerializerPalette:185) | CV normalizeColor | — |

### 4.B Conversations list (F-CL)
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-CL-01 | Room-backed list + previews | CV (ChatsScreen.kt:261, Room PulseDatabase:50) | CV (GRDB reads wired; upsert:2687) | CV conversations GET | iOS spec TODO ("wire GRDB reads") is DONE |
| F-CL-02 | Unread + dock badge 99+ | CV (UnreadBadge:1608, DockTab:176) | CV (RootView.swift:274,335) | CV + `message:read` relay | — |
| F-CL-03 | Pin optimistic | CV (repo.togglePin:1111) | CV (togglePin:2805 rollback) | CV pin route | — |
| F-CL-04 | Mute 8h/1w/always | CV (sheet:1904, epochs:1126) | CV (setMuted:2820) | CV mute route | — |
| F-CL-05 | Archive + page | CV (archived page:637) | CV (fullScreenCover:144) | CV archive route | — |
| F-CL-06 | Mark unread | CV (ChatsViewModel:273) | CV (:2077,2868) | CV mark-unread | — |
| F-CL-07 | Filter chips persisted | CV (prefs.chatsListFilter:67) | CV (prefs bind fixed:2587) | — | spec's iOS stale-wiring defect is FIXED |
| F-CL-08 | Note to Self | CV (:1224) | CV (:334) | CV self route | — |
| F-CL-09 | Streaks + heat ring | CV (StreakHeatRing:1746) | CV (heatLevel:1089) | CV streak fields | — |
| F-CL-10 | Drafts + server mirror | CV (DraftDao + mirror:926,1026) | CV (draft table:80, TV PulseStoreMigrationTests) | **P** — route implements PATCH only (no GET); reads via summary `myDraft` (functional, spec-deviant) | |
| F-CL-11 | Presence dots | CV (observePresence:110) | CV (session:403) | RV (W0: presence snapshot privacy-filtered) | — |
| F-CL-12 | Typing preview 4 s | CV (TypingState:138) | CV (typers:2463, TypingDots:1369) | RV (W0 typing relay) | — |
| F-CL-13 | Swipe reveal chips | CV (SwipeChip:1424) | CV (DragGesture:1158) | — | — |
| F-CL-14 | Multi-select batch | CV (batch*:295 + partial-fail) | CV (MultiSelectBar:312) | batch = sequential | — |
| F-CL-15 | Export .txt + share | **P** — writes cacheDir .txt + toast; ACTION_SEND chooser never fired (MediaSupport helper unused:218) | CV (shareURL:2108) | CV paged history | Android share step unmet |
| F-CL-16 | Clear my messages | CV (repo.clearMyMessages:351) | CV (confirm dialog:1897) | CV DELETE + `message:deleted` | — |
| F-CL-17 | Skeletons/empty/error | CV (EmptyStateCard:371) | CV (skeleton:291, retry:1804) | — | — |
| F-CL-18 | Foreground 6 s poll | **P** — 6 s poll exists but `stopPolling()` (:160) has **no call site** → polls while backgrounded (battery dimension unmet) | CV (isActive start/stop:159) | — | iOS spec defect FIXED |
| F-CL-19 | Action sheet | CV (:584-596) | CV (ChatActionSheet:1849) | — | — |

### 4.C Messaging core (F-MS)
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-MS-01 | Send ≤2000 | **P→FAIL** — SendMessageUseCase MAX_LENGTH=**4000** (spec: align 2000); server 400s 2001–4000 → user-facing failures | CV (no clamp; server enforces) | CV cap 2000 (serializers.ts:26) | see §15 D1 |
| F-MS-02 | Markdown/spoiler parser | **NF** (zero parser hits) | **NF** | — | natives render raw text; web chat-room.tsx:6580-6745 |
| F-MS-03 | Jumbo emoji | **NF** | **NF** | — | web pulse-utils.ts:155 |
| F-MS-04 | Edit own message | CV (VM:374 + PATCH API:441) | CV (:265,2385) | CV + `message:edited` | — |
| F-MS-05 | Delete → tombstone | CV (TombstoneBubble:2127) | CV (:185) | CV soft-delete | — |
| F-MS-06 | Reply quote | CV (VM:360) | CV (deleted-variant:1663) | CV replyToId | — |
| F-MS-07 | Threads | CV (ThreadScreen.kt + API:460) | CV (ThreadView + API:290) | CV thread GET | — |
| F-MS-08 | Reactions 6+24 + who-reacted | **P** — 6 quick only; no 24-picker; who-reacted only inside MessageInfoSheet | **P** — same (6 palette:1701) | CV react toggle + `message:react` | |
| F-MS-09 | Copy text | CV (:998) | CV (UIPasteboard:165) | — | — |
| F-MS-10 | Forward multi-target | **P** — ForwardSheet POSTs directly; not outbox-queued when offline (spec OFFLINE cell) | **P** — same | CV re-POST | |
| F-MS-11 | Save/star + library | CV (SavedLibraryScreen + API:448,699) | CV (SavedLibraryView + API:277) | CV save/saved routes | — |
| F-MS-12 | Pins + sheet | CV (pins sheet:483) | CV (banner+list:645) | CV pin/pinned + `message:pinned` | — |
| F-MS-13 | Convert → task | **P(FAIL-minor)** — Android prefill dead code `kanbanSourceMessage?.let { null }` (ChatRoomScreen.kt:910); server derives title so flow completes | CV (Wave7KanbanSheet:418) | CV kanban POST | §15 D2 |
| F-MS-14 | Remind me | CV (RemindersSheet + ReminderNotifier WorkManager) | CV (UNCalendarNotificationTrigger) | CV reminders | delivery on device = HW |
| F-MS-15 | Message info | CV (MessageInfoSheet:357) | CV (:1134) | composed | — |
| F-MS-16 | View-once | CV (ViewOnceGateBubble:297 + TV PulseWave2LogicTest) | CV (gated/burned:1254) | CV viewed + `message:viewed` | — |
| F-MS-17 | Incognito alias | **NF** (no anon fields anywhere) | **NF** (no anon param) | CV (server contract route:504) | |
| F-MS-18 | Scheduled messages | **NF** (zero scheduled endpoints in native API layers) | **NF** | CV GET/POST/DELETE + dispatch cron | |
| F-MS-19 | Disappearing TTL | **NF** (DTO field present, unused) | **NF** | CV disappearing PATCH | |
| F-MS-20 | Slow-mode 429 lockout | **NF** (no retryAfter handling) | **NF** | CV 429+retryAfter (route:484) — **live-verified in probe this session** | |
| F-MS-21 | Broadcast lock | CV (composerLocked:209) | CV (:247) | CV broadcastMode | — |
| F-MS-22 | Slash commands palette | **NF** | **NF** | commands-as-text | |
| F-MS-23 | Message effects | **P** — burst kinds on send/react only; incoming effect payloads (lasers/echo/sparkles) never decoded | **P** — same (ParticleBus) | CV effect contract | |
| F-MS-24 | Sticker picker | **NF** (kind whitelist only) | **NF** | CV kind+payload | |
| F-MS-25 | Offline outbox ≤50 FIFO | CV (Room outbox + FlushOutboxUseCase + OutboxWorker + TV) | CV (GRDB outbox + PulseOutboxEngine + TV PulseOutboxTests) | RV (W1 OFFLINE→FLUSH pass) | core offline works |
| F-MS-26 | Delivery ticks | CV (clock→✓→Seen:1872) | CV (Seen:1307) | CV read + `message:read` | — |
| F-MS-27 | Typing emit 1.2 s | CV (idle stop VM:339) | CV (typingStopTask:2162) | RV (W0) | — |
| F-MS-28 | Paging + day separators | CV (loadOlder:516, dayChip) | CV (:2002) | CV before cursor | — |
| F-MS-29 | Quick phrases rail | **NF** | **NF** | route exists, **zero clients** — **web ground truth itself absent** → only row where all three lack the feature | |
| F-MS-30 | Topic rail | CV (TopicBar:371 + self-heal:284) | CV (:290) | CV topics routes | web lacks topic delete (endpoint unused on web) |

### 4.D Media (F-MD)
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-MD-01 | Photo pipeline ≤1280/0.82 | **P** — compress pipeline CV (MediaSupport.kt:79-123) + Photo Picker; **camera capture NF** despite CAMERA permission | **P** — picker CV (:15-32); no AVFoundation capture | CV uploads 4.5 MB | capture UX = HW |
| F-MD-02 | Documents ≤10 MB | CV (gate:143, FileBubble:428) | CV (fileImporter:526) | CV 10 MB cap | — |
| F-MD-03 | Voice notes | **P** — record/playback/rates CV (VoicePlayer 1×/1.5×/2×); **waveform still deterministic bars**; hold-to-record gesture NF (tap-to-start) | **P** — same shape (VoiceNotes.swift:48) | CV uploads audio | mic = HW |
| F-MD-04 | Transcription strip | CV (strip:2275 + Room cols) | CV (:2712) | CV transcribe | — |
| F-MD-05 | Link unfurl | CV (auto-trigger:1493) | CV (TV Wave2UITests UnfurlTrigger) | CV unfurl + `link:preview` RV | — |
| F-MD-06 | Translation | **NF** | **NF** | CV translate + `translation:added` | backend ready, zero native client |
| F-MD-07 | Location share | **NF** (no LOCATION kind) | **NF** | kind accepted in whitelist | |
| F-MD-08 | Lightbox | **P** — dialog + tap-dismiss only; no pinch-zoom/swipe | CV (pinch+double-tap:117) | CV uploads GET | Android gesture dim missing |
| F-MD-09 | Upload durability (BLOB fallback) | — | — | CV (uploads/[file]/route.ts:42 R41 fallback) | server-side by spec |

### 4.E Rich objects (F-RO)
All 10 rows CV on both natives + BE (Wave 7): polls (TV PollPickTest), red packets (atomic debit route; 20 s poll both natives), whiteboard (900 ms delta poll; **P-minor: local draft strokes not persisted**), kanban (native move-controls substitute for web DnD), events (4 s poll), reminders (5 s due poll), games tic-tac-toe (TV Wave7LogicTest both), tournaments (TV), leaderboard, XP display. Relay: REST-polling transport is spec-mandated parity. Notes: Android ReminderNotifier does not survive reboot (no BOOT receiver) — F-RO-06 P-minor.

### 4.F–4.K Voice / Stage / Space / Calls / Stories / Channels
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-VR-01..04 | PTT rooms: join/roster, PTT chunks, captions, speaking glow | CV + RV (W5 20/20 this session: join, chunk-to-peer-not-sender, identity gate, transcript server-stamp, ptt echo + forced-off) | CV + RV (same suite) | voice/transcribe route | mic/audio/ASR runtime = HW |
| F-ST-01..04 | Stage roles/hands/host controls/audio | CV + RV (W5: first-joiner host server-truth, FIFO hands, host-only approve, mute demote, stage:ended teardown) | CV + RV | — | — |
| F-SP-01..03 | Space map/move/render | CV + RV (W5: spawn 0.5/0.5, clamp, 80 ms throttle, prune) | CV + RV | — | **P-minor**: last-position cache not stored natively |
| F-CA-01 | 1:1 call signaling | CV (CallEngine org.webrtc UNIFIED_PLAN) + RV (W3 17/17 this session: SDP round-trip, ICE, timeout, busy, identity gate) | CV (PulseCallEngine + TV CallSignalingRoundTripTests) + RV | — | peer-connection **media leg** = UNVERIFIED (no WebRTC runtime/network here) |
| F-CA-02 | **Video** call | **NF** — zero video track/capturer code on Android | **P** — accepts video offer, answers **audio-only** (PulseRTCMediaProvider.swift:8-9) | — | web is the only complete video implementation |
| F-CA-03 | Ring/accept/decline | CV (CallOverlay:64 + ForegroundService) | CV (CallView:191) | RV (W3 reject/cancel/timeout paths) | ringtone/vibration = HW |
| F-CA-04 | Mute/speaker/hangup | CV (:139-170) | CV (:11,116) | — | speaker routing = HW |
| F-CA-05 | Call history | CV (CallsView + API:753,764) | CV (CallsHistoryView + TV CallLogMapperTests) | CV /api/calls (RV W3 #12) | — |
| F-CA-06 | Mini-call/PiP surface | **P** — engine hoisted above NavHost; no mini surface | **P** — session hoisted (:244,439); mini-call NF | — | spec lists native PiP as improvement |
| F-SR-01..05 | Stories (rail rings, composer, viewer 24 h, viewers list, delete) | CV (StoryComposer + StoryViewer + state machines, TV ComposerStateTest/StoryViewerStateMachineTest) | CV (StoriesView viewers:81, delete:172) | CV stories routes (TTL hard filter) | iOS family has **zero unit tests**; photo/camera = HW |
| F-CH-01..04 | Channels directory/create/subscribe/locked composer | CV (Wave6Surfaces + API:988-1016) | CV (ChannelsView:100,441) | CV channels routes + RV (W6 40/40 this session) | — |

### 4.L–4.R Contacts / Search / Folders / PiP / Settings / Groups / Hub
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-CP-01..07,09 | Contacts, add, user page, profile edit, block manager, report, safety verification, status | CV (ContactsScreen:80, AddContact, UserPage, ProfileEdit:100, BlockedList, ReportPanel, safety sheet + sha256-pair lib) | CV (contacts/add/user/profile/blocked — unblock DELETE contract fixed; ReportPanelView; SafetySheetView) | CV + **server-side block enforcement live-verified** (probe: blocked DM create → 403) | — |
| F-CP-08 | Verified badge display | **P** — endpoints + sheet CV; badge on roster/user rows not found | **P** — same | CV | |
| F-SM-01..04 | Spotlight, room search, mentions feed, @-composer | CV (debounced search:207, MentionsScreen:79, suggester:643) | CV (searchMessages:626, MentionsView, pickMention:559) | CV search/mentions routes | — |
| F-FD-01..03 | Folders CRUD + assignment | CV (rail:276, manage VM:544, TV Wave6SocialApiTest) | CV (FoldersManageSheet + TV Wave6WireTests) | CV folders routes | — |
| F-PI-01..03 | PiP panes (3-pane, pill stack, open-dispatch) | **NF** (zero "pip" hits) | **NF** | — | web pip-store/pip-stack/pip-chat complete |
| F-SE-01..09 | Settings 9 sections | CV (SettingsRootScreen 9 sections + honor-OS animations + versioned channels + prefs mirror); **P** F-SE-02: UI-themes + 13 nav styles not ported (spec rule P2-sanctioned) | CV (SettingsView 9 cards + Reduce Motion + live probe) | CV settings GET/PATCH merge+clamp (W8 E2E live) | push delivery Tier-2 = EB |
| F-GR-01 | Group create | **P** — data/domain ready+TV; chats composer is honest toast (MainActivity:733) → no group-create UI | CV (NewChatSheet dm/group:317,334) | CV conversations POST (DM dedupe, block refusal RV) | |
| F-GR-02 | Group info/rename/photo | **NF** | **NF** | CV conversations PATCH admin-only | |
| F-GR-03 | Members add/promote/kick | **NF** (zero /members calls) | **NF** | CV (admin-only, sole-admin refuse, kick-admin 403) | server governance complete |
| F-GR-04 | Leave group | **NF** | **NF** | CV self-removal + LAST-ADMIN SUCCESSION (:135-157) — probe live-verified honest 400 pointer | |
| F-GR-05 | Invite create/regenerate | **P** — redeem CV (JoinInvite + pulse:// deep link); create NF | **P** — redeem CV (:507); create NF | CV invite mint/regenerate + join | downstream of F-GR-02 absence |
| F-GR-06 | conversation:updated fan | CV (SocketContracts:55 + handler) | CV (:145) | RV (W6) | — |
| F-HB-01..10 | Hub wallet/checkin/transfer/swap/tasks/market/logs/apps/community/installs | CV (HubScreen + wave7_cache; TV Wave7ApiTest) — **P** F-HB-02 (no reward FX), F-HB-07 (manual load only, no 12 s live) | CV (HubView + TV Wave7WireTests) — **P** F-HB-02 (no FX), F-HB-05 (**tasks not cached in wave7Cache**), F-HB-07 | CV hub routes (RV W7 93/93 this session incl. honest 402 market-buy) | — |

### 4.S–4.W Offline / Realtime / FX / Platform / AI
| Row | Feature | Android | iOS | Backend | Evidence / gap |
|---|---|---|---|---|---|
| F-OF-01..03 | Offline read caches, install channel, outbox | CV (Room v9 shell + LiveUpdater integrity chain TV HomeGatewayGuardTest) | CV (GRDB + outbox TV) | — | — |
| F-OF-04 | Flush triggers | CV (start/reconnect/foreground/worker:1004) | **P** — scenePhase + BGTask + 60 s heal; **no NWPathMonitor** network-up trigger | — | |
| F-OF-05 | Delta sync `since=` | **NF** (spec-optional [BACKEND ADD]) | **NF** | NF | honest full-refresh v1 |
| F-RT-01..08 | Join auth, typing, 9-event envelopes, fan-out, voice/stage/space pipes, call signaling, backoff+re-join, privacy filter | CV + RV (W0 8/8 + W8 relay-gate 3/3 this session; TV SocketRoundTripTest reconnect re-join) | CV + RV (same suites; reconnectWait 1→5) | CV /notify whitelist (11 events) + verify/privacy internal routes | multi-device presence OK (Set<socketId>); privacy cache 30 s fail-open |
| F-FX-01..07 | Ambient shader, particles, themes, nav styles, **conversation themes**, dark mode, motion springs | CV (AGSL+fallback, ParticleBurstHost, Theme.kt, Motion.kt); F-FX-03/04 NF **by spec rule P2** (intentional); **F-FX-05 NF** (conv themes — spec says Build) | CV (Metal kernels, ParticleBus, springs); F-FX-03/04 NF by P2; **F-FX-05 NF** | prefs blob | F-FX-05 is a real gap on both |
| F-PS-01 | Install/update channel | CV (LiveUpdater + REQUEST_INSTALL_PACKAGES + FileProvider + ManifestEndpoints) | **EB** — App Store/TestFlight requires Apple Developer account (no code by spec) | CV update-manifest | — |
| F-PS-02 | Scheduled dispatch cron | — | — | CV maintenance/dispatch (flush ≤25 due) | default-key risk → §8 |
| F-PS-03 | Notifications Tier 1/2 | CV Tier-1 (ReminderNotifier, POST_NOTIFICATIONS, versioned channels) | CV Tier-1 (UNUserNotificationCenter) | **NF backend token registry** | **Tier-2 (FCM/APNs) EB end-to-end** |
| F-PS-04 | Deep links | CV (manifest intent-filter + PulseDeepLink TV + onNewIntent) | CV (CFBundleURLSchemes pulse + onOpenURL:142) | CV invite preview/join | AASA universal links = later/EB |
| F-PS-05 | Diagnostics/honest surfaces | CV (offline copy, no reconnect spam) | CV (gateway probe + Live/Reconnecting/Offline row:496) | RV (socket / health JSON) | — |
| F-AI-01 | @Pulse AI bot | CV (viaAutomation tag render:2111 + Room col) | **P** — WireChatMessage lacks viaAutomation field → bot tag chip absent (decoder tolerates) | CV ai-bot.ts | |
| F-AI-02 | Slash-command bots | **P** — typed commands work via send; no palette | **P** — same | CV bot-engine | |
| F-AI-03..05 | Automations CRUD, recap, webhooks | **NF** (all three) | **NF** | CV routes all exist (webhooks participant-gated; ingest token public) | |

---

## 5. ANDROID vs iOS PARITY MATRIX (family-level classification)

| Family | Verdict | Classification of differences |
|---|---|---|
| Identity | Near-parity | iOS missing explicit forget (F-ID-06) — **incomplete**, not intentional |
| Conversations | Near-parity | Android: export share + poll-lifecycle dims missing — **defects**; iOS stale-pref defect fixed |
| Messaging core | Parity in absence | Both natives miss the same 8 features + 2 partials — **spec-incomplete, symmetric** |
| Media | Near-parity | Android lightbox gestures missing (defect); both missing camera capture/hold-to-record |
| Rich objects | Parity | Wave-7 symmetric; Android reboot-survival missing (defect) |
| Voice/Stage/Space | Parity | Symmetric CV+RV; hardware dims both |
| Calls | **Divergent** | Video: Android NF vs iOS audio-only degradation — **incomplete on both, differently** |
| Stories | Parity in code, **asymmetric tests** | iOS has zero story unit tests — **test gap** |
| Channels | Parity | — |
| Contacts/Safety | Parity | Android+iOS both lack verified-badge rendering |
| Search/Mentions/Folders | Parity | — |
| PiP | Parity in absence | Both NF (web-only) |
| Settings | Near-parity | Android Appearance narrower (P2-sanctioned — **intentional** platform-scoping) |
| Groups | Parity in absence | Both natives lack group admin UI — **spec-incomplete**; Android additionally lacks group-create UI |
| Hub | Near-parity | iOS tasks not cached (defect); Android logs no live poll (minor) |
| Offline | Near-parity | iOS flush-trigger set narrower (no NWPathMonitor) — **incomplete** |
| Realtime | Parity | Both CV+RV |
| FX | Parity in absence | Conv themes missing both; themes/nav-styles intentionally scoped out (P2) |
| Platform | Divergent by design | Android LiveUpdater vs iOS store channel — **intentional**; Tier-2 push EB both |
| AI | Near-parity | iOS missing viaAutomation chip (defect) |

No artificial parity forced; differences classified per directive.

---

## 6. BACKEND / API / RELAY COVERAGE

- All API routes required by audited families exist in `src/app/api/**` and were exercised live (§18) or code-verified. Route-by-route evidence embedded in §4 matrix (BE column).
- Relay (`mini-services/pulse-socket/index.ts`): join token gate (verify cache 60 s, fail-open, invalid → join:error + disconnect) — live-verified 3/3; 11-event NOTIFY whitelist; voice/stage/space/call handlers — live-verified via W5/W3; privacy filter (typing/presence/readReceipts) — live-verified via probe.
- Deviations: (1) draft route PATCH-only (GET absent — functional via summary, spec-deviant); (2) `since=` delta sync absent (spec-optional); (3) `users/[id]/phrases` route orphaned (no client anywhere — web first); (4) hub market buy honest 402 (verified W7) is correct behavior, not a defect.

## 7. OFFLINE / PERSISTENCE COVERAGE

- **Room (Android):** schemas 4–9 checked in; production chain `MIGRATION_3_4 … MIGRATION_8_9` present (PulseDatabase.kt:815-921, version=9); **all six androidTest builder chains carry MIGRATION_8_9** (RoomMigrationTest.kt:279,354,449,631-746) — the Wave-7 CI fix is intact.
- **GRDB (iOS):** v1→v7 migrator registered, additive only (PulseStore.swift:40-176); outbox/draft tables from v2; wave7Cache present.
- Outbox: FIFO ≤50, stop-at-first-failure both platforms (TV both); flush triggers Android complete, iOS missing NWPathMonitor; W1 OFFLINE→FLUSH live pass.
- Drafts: local + server mirror both platforms; iOS TV (PulseStoreMigrationTests).
- Reconnect reconciliation: re-join on connect both; multi-device presence correct server-side.
- Cache invalidation: message:new/edited/deleted/react/pinned/viewed/read/typing/poll/link/translation/conversation:updated all handled both natives (SocketContracts.kt:93-96 / PulseSocketClient.swift:135-145).
- Honest gaps: delta sync absent; whiteboard draft strokes not persisted; iOS hub tasks uncached; Android reminders not reboot-proof; no BOOT receiver.

## 8. SECURITY FINDINGS (checklist per directive)

| # | Check | Result | Evidence |
|---|---|---|---|
| S1 | Invalid/expired bearer → 401 | **PASS live** | probe: `Bearer deadbeef…` → 401; W8: invalid Bearer 401 |
| S2 | Missing token | **PASS (documented migration semantics)** | headerless accepted (optional-verify proxy; migration window) — W8 E2E intentional check |
| S3 | Token rotation | **PASS live** | login rotates; old token → 401 (probe + W8) |
| S4 | Internal verify boundary | **PASS live** | `/api/internal/verify` valid→{valid:true}, invalid→{valid:false}, no key→401 |
| S5 | Internal privacy/maintenance key gate | **RISK — live-confirmed** | `/api/maintenance/dispatch` accepts default constant `x-pulse-key: pulse-dispatch-key` (server fallback when CRON_SECRET unset). Probe: `DOCUMENTED RISK … got 200`. Verify/privacy routes refused without key (401) — the risk is specific to the dispatch default. |
| S6 | Socket join authentication | **PASS live** | W8 relay: valid join → joined; invalid → join:error + disconnect; tokenless accepted (migration window, documented) |
| S7 | Blocked-user enforcement | **PASS live** | probe: blocked DM create → 403; enforcement server-side (conversations/route.ts:137, messages:436) |
| S8 | Privacy enforcement | **PASS live** | readReceipts=false → lastReadAt epoch for viewers (probe); relay privacy cache fail-open |
| S9 | Report/block/unblock routes | **PASS live** | report idempotent 201/200; unblock DELETE contract |
| S10 | Role enforcement | **PASS live** | non-admin rename/remove → 403; remove-admin → 403; demote-sole-admin → 400; honest leave pointer 400 (probe) |
| S11 | Admin-only actions | **PASS live** | invite mint admin-only; kanban/members admin checks (W6/W7 suites) |
| S12 | Last-admin protection | **PASS live** | probe demote 400; server succession logic (members DELETE :135-157) |
| S13 | Client vs server authority | **PASS** | message cap, slow mode, edit/delete ownership all server-enforced (probe 2001-char → 400; foreign edit/delete → 403/400) |
| S14 | Sensitive data leakage | **PASS** | registry invite preview leaks name/count only; presence privacy-filtered; no token storage server-side (sha256 hash only — schema comment verified) |
| S15 | Upload caps | **PASS live** | 4.6 MB media → 413 |

## 9. REALTIME FINDINGS

Connect/backoff 800→5000 ms both platforms + web; join re-emitted on reconnect (TV SocketRoundTripTest:202); 9 message-envelope events + conversation:updated + typing + presence handled; relay fan-out whitelist verified; call 30 s ring timeout server-side; voice/stage/space state machines server-authoritative. **No defects found this session** in the realtime chain (W0/W3/W5/W8 all green against freshly started relay).

## 10. DEEP-LINK / NAVIGATION FINDINGS

- Android: `pulse://` intent-filter (AndroidManifest) + PulseDeepLink (invite/user/room, no-authority + percent-decode) + TV PulseDeepLinkTest + onNewIntent routing (:286-289, :406-414).
- iOS: CFBundleURLSchemes ["pulse"] (project.yml:52-54) + onOpenURL (RootView:142) + PulseDeepLink.swift.
- Web: `?join=CODE` + link builders. Cross-surface invite join live-verified (W6: preview → join → idempotent re-join).
- Gaps: no universal links/AASA (spec: later, needs domain — EB); iOS cold-start routing animation dimension unverified.

## 11. ACCESSIBILITY FINDINGS

- Settings honor-OS: Android "Remove animations" reads Settings.Global.ANIMATOR_DURATION_SCALE (SettingsRootScreen); iOS Reduce Motion gates FX (verified in SettingsView + FX code paths).
- Web parity for reducedMotion pref blob; versioned notification channels; 44px touch targets not measurable here (HW).
- Gaps: no dedicated audit of TalkBack/VoiceOver labels (would require device — recorded UNVERIFIED, not PASS); no contentScale/dynamic-type verification.

## 12. PERFORMANCE FINDINGS

Master-spec §3.14 targets (launch, list scroll, socket reconnect latency, R8 size) are **UNVERIFIED**: no profiling harness, no physical device, no emulator in this session (emulator runs are CI-side). What IS measurable and verified: release APK size 23,973,927 B (R8 on, vs 35.6 MB pre-R8 — CI + badging verified); 6 s foreground poll interval code-verified (web 6 s parity); relay latency structurally bounded by suites but not stopwatched. **No performance claim is made.** Android F-CL-18 (background polling) is the one measurable battery defect found.

## 13. HARDWARE-BLOCKED FINDINGS (Wave 3-HW + Wave 5-HW remain OPEN)

Mic capture/playback (PTT + voice notes), camera capture UX, speaker/Bluetooth routing, haptics fidelity, badge pop physics, ringtone loop + vibration, real notification delivery (WorkManager/UNUserNotificationCenter OS behavior incl. quiet hours), WebRTC peer-connection media leg across real networks (STUN-only by design; no TURN), Keystore/Keystore-cipher androidTest + Keychain backup/restore behaviors, on-device R8 release smoke, stylus, TalkBack/VoiceOver, on-device ASR runtime. **None of these may be claimed PASS from this audit.**

## 14. REGRESSION FINDINGS (later waves vs earlier functionality)

Re-verified live this session: auth/session (W8 19/19), messaging+offline flush (W1 27/27), depth features (W2 40/40), calls signaling (W3 17/17), voice/stage/space (W5 20/20), social/folders/invites (W6 40/40), collaboration/hub (W7 93/93), realtime base (W0 8/8), migration chains (Room test chains intact + GRDB v7), release artifact (byte + badging). **No regressions found.** Web CI: last run SUCCESS at `2fc2803`; release commits did not re-trigger it (path filters) — current state is green, not stale-red.

## 15. EVERY FAIL / PARTIAL / NOT FOUND (with evidence)

**FAIL (demonstrably broken):**
- D1 · F-MS-01 Android send cap 4000 vs server 2000 — SendMessageUseCase.kt:24; sending 2001–4000 chars → server 400 (cap live-verified). User-facing break web-natives divergent.
- D2 · F-MS-13 Android dead prefill — ChatRoomScreen.kt:910 `kanbanSourceMessage?.let { null }` always nil (flow completes via server title derivation).

**NOT FOUND on both natives (zero native implementation; backend/web exist unless noted):**
- D3 · F-MS-02 formatting/spoiler parser · D4 · F-MS-03 jumbo emoji · D5 · F-MS-17 incognito · D6 · F-MS-18 scheduled messages · D7 · F-MS-19 disappearing TTL · D8 · F-MS-20 slow-mode lockout UI · D9 · F-MS-22 slash palette · D10 · F-MS-24 sticker picker · D11 · F-MD-06 translation · D12 · F-MD-07 location share · D13 · F-AI-03 automations · D14 · F-AI-04 recap · D15 · F-AI-05 webhooks · D16 · F-PI-01..03 PiP panes (web-only) · D17 · F-GR-02 group info page · D18 · F-GR-03 member management · D19 · F-GR-04 leave group · D20 · F-FX-05 conversation themes · D21 · F-CA-02 video on **Android** (iOS degrades to audio) · D22 · **F-MS-29 quick phrases — absent on web too** (orphan route; only row unimplemented on all three surfaces).

**NOT FOUND single-platform:** D23 · F-ID-06 iOS forget-viewer (PulsePrefs.setViewer(nil) zero callers).

**PARTIAL (dimension named):**
- D24 · F-CL-15 Android export lacks share sheet (MediaSupport.kt:218 unused) · D25 · F-CL-18 Android stopPolling() dead → background polling (ChatsViewModel.kt:160) · D26 · F-CL-10 draft route PATCH-only, spec lists GET+PATCH · D27 · F-MS-08 both natives no 24-picker; Android who-reacted only via info sheet · D28 · F-MS-10 forward bypasses outbox offline (both) · D29 · F-MS-23 effect-payload decode absent (both) · D30 · F-MD-01 camera capture absent (both) · D31 · F-MD-03 waveform deterministic bars + no hold-to-record gesture (both) · D32 · F-MD-08 Android lightbox no pinch-zoom · D33 · F-CA-06 no mini-call/PiP surface (both) · D34 · F-CP-08 verified-badge rendering absent (both) · D35 · F-GR-01 Android group-create UI toast-only (MainActivity:733) · D36 · F-GR-05 invite create/regenerate absent (both; redeem exists) · D37 · F-SE-02 Android UI-themes/nav-styles not ported (spec P2-sanctioned — not a defect) · D38 · F-HB-02 reward FX unwired (both) · D39 · F-HB-05 iOS hub tasks not cached (wave7Cache unused for tasks) · D40 · F-HB-07 logs no live 12 s poll (both) · D41 · F-OF-04 iOS no NWPathMonitor trigger · D42 · F-AI-01 iOS viaAutomation chip absent (WireDtos.swift:146) · D43 · F-AI-02 no slash palette (both; typed commands still work) · D44 · F-RO-03 whiteboard local draft strokes not persisted · D45 · F-RO-06 Android reminders not reboot-proof (no BOOT receiver) · D46 · F-SP-01 last-position cache absent (both) · D47 · F-OF-05 delta sync absent (spec-optional).

## 16. EVERY BLOCKED (exact external dependency)

| Item | Blocked on |
|---|---|
| Push Tier-2 end-to-end (F-PS-03) | Firebase project (FCM) + Apple Developer account (APNs) + backend token registry/sender |
| iOS store update channel (F-PS-01 iOS) | Apple Developer account / TestFlight |
| Universal links / AASA (F-PS-04 extension) | Owned domain + AASA hosting |
| Real-device verification set (§13) | Physical Android + iOS devices, real networks (incl. cross-network TURN decision), haptics/camera/mic |
| Bridge gateway/socket manifest pin | Operator-level sandbox/public URL decision (`gateway:""`/`socket:""` still in manifest) |
| WebRTC media-leg verification | WebRTC-capable runtime + network (sandbox has neither) |

## 17. TEST RESULTS / COUNTS (this session)

| Suite | Count | Result | Where |
|---|---|---|---|
| Android JVM (`:protocol`, `:data`, `:domain`, `:feature-voice`) | 122+69+45+65 = **301** | 0 failures / 0 errors | re-run locally, exit 0 |
| Android `:app:compileDebugKotlin` | — | SUCCESS | re-run locally |
| Android emulator migration suite (6 chains incl. 8→9) | — | CI VERIFIED (Android CI success at 6692313) | GitHub Actions |
| iOS XCTest (incl. Wave-8 +20) | 245+20 | CI VERIFIED (iOS CI success at 6692313) | GitHub Actions — **not runnable locally (Linux, no Xcode)** |
| `bun run lint` (web) | — | clean | local |

## 18. LIVE E2E RESULTS (all re-run this session against freshly started backend+relay)

| Suite | Result |
|---|---|
| wave0-e2e-realtime (relay base) | **8/8 PASS** |
| wave1-messaging-gate (send/offline-flush/reconnect) | **27/27 PASS** |
| wave2-depth-gate (threads/polls/topics/etc.) | **40/40 PASS** |
| wave3-call-gate (signaling + history) | **17/17 PASS** |
| wave5-runtime-e2e (voice/stage/space relay) | **20/20 PASS** |
| wave6-runtime-e2e (channels/folders/invites) | **40/40 PASS** |
| wave7-runtime-e2e (collaboration + hub) | **93/93 PASS** |
| wave8-runtime-e2e (tokens/settings/relay gate) | **19/19 PASS** |
| final-audit security-probe | **29/29 PASS** (1 live-confirmed documented risk, §8 S5) |
| **Total** | **293/293 PASS / 0 FAIL** |

## 19. CI / RELEASE VERIFICATION

- Android CI **success** at 6692313 (both main and tag runs); iOS CI **success** at 6692313 (both); iOS rounds r1–r4 history (failures at 527e02a→95ce9c0, resolved cbd86a3) — all verified via GitHub API this session.
- Web CI **success** at 2fc2803; not re-triggered by release commits (path filters) — green, not stale.
- Release `v0.10.0-native`: asset re-downloaded, sha256 recomputed **5540c8bb…b2ec** == GitHub digest == CDN pin; badging versionCode 20 / 0.10.0-native / targetSdk 35; R8 ON (23.97 MB vs 35.65 MB pre-R8).
- Local HEAD `196852d` = audit tooling only (§2 disclosure); origin/main == 87bd7a3 == audited release state.

## 20. REMEDIATION BACKLOG (ordered by technical severity)

1. **[SECURITY] Replace default `pulse-dispatch-key` fallback** on `/api/maintenance/dispatch` (and audit any other `?? 'pulse-dispatch-key'` defaults) — require explicit env secret; refuse boot without it. Live-confirmed accepted (S5).
2. **[FUNCTIONAL-BREAK] Android send cap 4000 → 2000** (SendMessageUseCase.kt:24) — currently produces user-facing 400s for 2001–4000 chars.
3. **[PRODUCT GAP] Native group administration** (F-GR-02/03/04 + F-GR-05 create + F-GR-01 Android create UI) — users cannot manage groups at all from either native.
4. **[PRODUCT GAP] Video calling** — implement Android (NF) + iOS real video track (currently audio-only answer).
5. **[SPEC BATCH] Messaging polish family on both natives** — D3–D10 (formatting, jumbo, incognito, scheduled, TTL, slow-mode UI, slash palette, stickers) + D27/D28/D29.
6. **[SPEC BATCH] Media translation + location share** (D11–D12) — backend ready.
7. **[SPEC BATCH] AI surfaces** (D13–D15 + D42 iOS chip).
8. **[SPEC BATCH] PiP panes** (D16) — web-only today.
9. **[SPEC] Conversation themes** (D20) — both natives.
10. **[DEFECTS] Android:** export share sheet (D24), stopPolling dead (D25), lightbox gestures (D32), kanban dead prefill (D2), BOOT receiver for reminders (D45).
11. **[DEFECTS] iOS:** forget-viewer (D23), hub-task cache (D39), NWPathMonitor (D41), viaAutomation chip (D42).
12. **[CONSISTENCY] Shared:** verified-badge rendering (D34), reward FX (D38), hub logs polling (D40), whiteboard draft strokes (D44), space last-position cache (D46), story/channel iOS test coverage.
13. **[OPTIONAL] Delta sync `since=`** (D47) — spec-optional.
14. **[HARDWARE GATES] Wave 3-HW + Wave 5-HW** — remain open until real-device verification (§13); not remediable in sandbox.
15. **[EXTERNAL] Push Tier-2 + TestFlight + AASA + bridge manifest pin** — §16 dependencies.

## 21. EXACT RECOMMENDED NEXT ACTION PER FINDING

- S5 → set real `CRON_SECRET` in the deployment env AND remove the constant fallback in `src/app/api/maintenance/dispatch/route.ts` (one-line guard) — then re-run security probe.
- D1 → change `MAX_LENGTH` to 2000 in `SendMessageUseCase.kt` + add JVM test for 2001-char rejection.
- D3–D22/D16/D17–D20 → schedule as the next implementation wave(s) per backlog order (each is a build, not a fix; requires user approval per standing wave discipline).
- D23/D24/D25/D32/D39/D41/D42/D2/D45 → small per-platform defect fixes (each ≤1 file + test).
- D26/D28/D29/D34/D38/D40/D44/D46 → shared consistency tickets across web+Android+iOS.
- D47 → decide accept-as-v1 (documented) or schedule `[BACKEND ADD] since=`.
- Hardware items → physical-device verification session (Wave 3-HW/5-HW closure procedure already defined in wave specs).
- Bridge pin → operator decision on public gateway URL, then fill `download/update-manifest.json` `gateway`/`socket`.

---

**NEXT STEP (exact):** Review this report and the §20 backlog; the single first action on approval is remediation item #1 (remove the default `pulse-dispatch-key` fallback and set `CRON_SECRET`), followed by #2 (Android send cap 2000). No implementation has been performed during this audit, per directive.
