# PULSE — WAVE 6 COMPLETION REPORT
## Native Social Graph, Discovery & Identity Safety (Android + iOS)

Date: 2026-09-13 · Family: master-spec §4.L / §4.M / §4.N / §4.K (+ `pulse://` deep links)
Behavioural truth: the repo-root web app (audits 6-a/6-b in `worklog.md`, file:line-cited) — no full re-audit, wave-level evidence only.
Spec: `docs/NATIVE-PARITY-MASTER-SPECIFICATION.md` · Waves 0–5 closed · **Wave 3-HW and Wave 5-HW remain OPEN** · Wave 6 hardware gate joins them.

---

## 1. Android implementation (Kotlin + Compose)
- **Data (from the pre-existing 6-c foundation, repaired + verified)** — `PulseApi` +268 lines (users GET/PATCH, stats, safety GET/POST/DELETE, block/unblock/blocks, report POST/GET, uploads, folders CRUD + PUT membership, channels directory/create/subscribe/unsubscribe, invite preview/join), 22 new `PulseRepository` members, `Wave6Dtos.kt` + `PulseDeepLink` (core/link) + `Wave6LogicTest` (20) + `Wave6SocialApiTest` (14).
- **Surfaces (this session)** — `UserPageScreen.kt` (stats grid, rooms-in-common ≤3 "+N more", block/report/safety actions), `AddContactScreen.kt`, `ProfileEditScreen.kt` (name/bio/11 status glyphs/8 colours/handle editor 350 ms debounce + 409 suggestion/avatar pipeline square ≤512 JPEG q0.85 → uploads → PATCH, optimistic "Profile updated"), `BlockedListScreen.kt`, `Wave6Surfaces.kt` (Mentions page, Channels page incl. last-admin 403 verbatim + optimistic subscribe + create sheet, FoldersManageSheet membership PUT, JoinInviteSheet), ChatsScreen pills un-stubbed + rail→sheet, ChatRoomScreen (@suggester, composer lock "Only admins can post" driven by `myRole`, DM Shield → SafetyNumberSheetHost 3×4 grid), ContactsScreen report dialog → 6-reason ReportPanel (fixes the old free-text "unspecified" 400), MainActivity routes + `pulse://` deep-link capture.
- **Gate**: 175 JVM tests / 0 failed; `:app:compileDebugKotlin` SUCCESS; release APK versionCode 18 / 0.8.0-native, apksigner v1+v2+v3 (CN=Pulse Live Update).

## 2. iOS implementation (Swift + SwiftUI)
- `PulseAPIClient` +`WireWave6Dtos.swift` (tolerant DTOs; **unblock-route defect fixed**: was POST `/api/users/{id}/unblock` — a route that does not exist — now `DELETE /api/users/{id}/block?userId=`), `UserPageView` (public `UserRoute`), `ProfileEditView` (identity/status/handle/avatar sections), `AddContactView`, `ReportPanelView`, `SafetySheetView`, `BlockedListView`, `MentionsView`, `ChannelsView`, `FoldersManageSheet`, `JoinInviteSheet`, `PulseDeepLink` + `RootView.onOpenURL` + `project.yml` `CFBundleURLTypes` scheme "pulse", `PulseWave6Logic` (mention token/insertion/firstMatch, folder/channel rules, `pulseStatusGlyphDisplay`, `pulseValidHandle`), composer lock (`broadcastMode` + role), @suggester, DM Shield entry.
- **Gate**: iOS CI **SUCCESS** (build-test incl. Wave6WireTests + Wave6LogicTests, and xcarchive) after 6 fix rounds (r1 UserRoute public · r2 static→instance + ForEach id-pin · r3 shared glyph helper · r4 let-struct re-clone / maxLength bindings / pure handle validator / UserDefaults.standard / frame split / createForm expression split · r5 tuple label · r6 test labels).

## 3. Realtime/API verification (RELAY VERIFIED)
`apps/qa/wave6-runtime-e2e.js` vs the live backend — **40 PASS / 0 FAIL** (profile PATCH validation 400s, stats, safety determinism+symmetry+verify/reset, block boundary + blocks-list 403, report idempotency, folders CRUD + full-replace reorder, mentions 14d, search, channels create/subscribe/last-admin-403 verbatim/leave, invite create/preview/join). Two web-contract facts encoded: blocked re-DM dedupes to the EXISTING conversation (200); invite create is `{requesterId}` → 200 `{inviteCode}`.

## 4. Release
- Tag `v0.8.0-native` → tag CI Android + iOS **SUCCESS**; release published with `Pulse-v0.8.0-native.apk` (35,361,317 B; API digest sha256 `e30119d5…e96542` verified by download == CDN pin).
- CDN channel re-pinned (commit `380c91c`): versionCode 18 / 0.8.0-native / apkUrl / sha256 e30119d5…; `download/Pulse.apk` mirror swapped. `gateway`/`socket` unchanged (blank — the public-bridge question stays as it was).

## 5. Honest verification levels
| Level | Claim |
|---|---|
| CODE VERIFIED | 175 Android JVM tests · iOS XCTest suite (Wave6Wire+Logic) green in CI |
| CI VERIFIED | Android main+tag SUCCESS · iOS main+tag SUCCESS (build-test + xcarchive) |
| RELAY VERIFIED | 40/40 E2E vs the live REST surface the natives call |
| HARDWARE REQUIRED | avatar photo-picker UX, haptics, keyboard/composer behaviour on devices, deep-link opens from other apps (NFC/QR) — joins the open hardware gate |

## 6. Remaining (next wave)
- **Spec §7 Wave 6 (Collaboration & Hub)**: Kanban · events+RSVP+checkin · reminders (+local notifications Tier 1) · whiteboard · red packets · games+tournaments+leaderboard · Hub wallet/swap/transfer/tasks/market/logs/apps/communities — NOT STARTED (genuine blocker: session context exhausted; continuation point is a fresh session reading this worklog).
- PiP panes (spec §2.O, F-PI-01…03) — deferred with Wave 5, still REMAINING.
- Group management depth (F-GR-02…04: info edit, members add/remove/promote, leave) — not in Wave 6 scope (only invite join shipped).
- Wave 3-HW + Wave 5-HW + Wave 6-HW remain OPEN until physical devices execute their kits.

**STOP — no Wave 7 started.** Waves remain independently gated per the continuous-build rule; the next session resumes at spec §7 Wave 6 (Collaboration & Hub) with no re-audit.
