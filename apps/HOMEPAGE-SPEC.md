# Pulse Home Page — Web → Native Specification (N10)

Source of truth (web, frozen): `src/components/chat/chats-tab.tsx`, `chats-row.tsx`,
`chats-skeleton.tsx`, `user-avatar.tsx`, `nav-router.tsx` (default `capsule`), `main-shell.tsx`,
`src/lib/pulse-utils.ts`, `src/app/globals.css` (glass recipes).
This file is the binding translation contract for Android (`apps/android`) and iOS (`apps/ios`).
Golden rule: **no mock data, no fake states** — every data area renders real repository data,
or the honest loading / empty / error state.

---

## 1. What the home page IS on the web

After onboarding, the app boots into `MainShell` with tab `chats` selected. The home page =
the **Chats tab** (`ChatsTab`) + the **default Floating Capsule nav dock** + tab-panel
transition chrome. Four tabs exist: `chats · hub · contacts · profile` (order matters).

Tab-panel transition: outgoing/incoming panels crossfade 220ms with a ±24px horizontal slide,
direction derived from tab index delta (`chats<hub<contacts<profile`). Reduced-motion → fade only.

---

## 2. Root layout & background

- Root: full-bleed column; background **translucent** (`bg-white/30` light, `dark:bg-zinc-950/20`)
  so the ambient aurora wash shows through behind rows/pills (this is what makes glass read).
- Vertical stack: Header → Filter chips → Stories row → Folder rail → scrollable list →
  (floating) multi-select bar + capsule dock.

---

## 3. Header (two modes)

### 3a. Normal mode (not searching)
Row `px-3 py-2.5`, gap-2, `border-b border-zinc-200 dark:border-zinc-800`, top safe-area padding.
Left→right:
1. **My avatar button** (36dp) — opens the Profile tab. Press scale 0.92 spring.
2. **Title** `Pulse` — `text-xl font-bold tracking-tight`, zinc-900 / dark zinc-50, followed by
   a **6dp emerald dot** with `bg-gradient-to-br from-emerald-400 to-emerald-600` (`ml-1.5 pl-1`).
   Title pushes the rest to the trailing edge (`mr-auto`).
3. **Phone icon button** (40dp circle, ghost) — opens the Calls sub-page (see §9).
   Icon tint zinc-500, hover/active emerald.
4. **Compose icon button** (40dp circle, ghost, `SquarePen` icon) — opens New Chat flow.
   (Native builds without a New Chat sheet yet → honest snackbar "New chat composer isn't in this native build yet." — do NOT fake it.)
5. **Theme toggle button** (40dp circle) — cycles system → light → dark (read
   `src/components/chat/theme-toggle.tsx` for exact iconography: Sun/Moon/Monitor semantics).
   Android: writes `SessionViewModel.darkOverride` ("system"|"light"|"dark"). iOS: `PulsePrefs.appearance`.

### 3b. Search mode (replaces header content)
- Glass pill (h-10, `rounded-full bg-zinc-100/80 ring-1 ring-zinc-200/70 backdrop-blur-xl`,
  dark `bg-zinc-900/60 ring-white/10`) containing a Search glyph (16dp, zinc-400) +
  borderless input, placeholder **"Search chats and messages…"**.
- When focused AND query non-empty: emerald focus halo (`ring-2 ring-emerald-500/50`,
  `shadow-[0_0_20px_rgba(16,185,129,0.25)]`) fades in around the pill; close button turns emerald.
- Clear button: 24dp circle `bg-zinc-300/70` with X, springs in when query non-empty.
- Close (X) ghost button 40dp at the end exits search mode.
- Entering search exits multi-select mode.

While searching: chips row, stories row and folder rail are all hidden.

---

## 4. Filter chips (All / Unread / Groups)

Horizontal scroll row `px-3 py-1.5`, gap-1.5. Persisted preference (`ChatsListFilter`).
- Chip: h-7, `rounded-full px-3`, `text-[12px] font-semibold`.
- Inactive: `bg-zinc-100 text-zinc-500` (dark `bg-zinc-800 text-zinc-300`).
- Active: **emerald-500 fill pill** (white text, shadow `shadow-emerald-600/25`), pill slides
  between chips (shared-element layout animation; native: animate the fill position or crossfade).
- The **Unread** chip (when inactive and total unread > 0) shows a mini count chip:
  h-15dp min-w-15dp `bg-emerald-500/20 text-[9px] font-bold text-emerald-600 (dark:emerald-400)`,
  cap "99+".
- Filter semantics: `unread` → unreadCount > 0 · `groups` → isGroup · `all` → everything.
- "Groups" empty copy: **"No groups yet — start one from Contacts."** ·
  "Unread" empty copy: **"No unread chats — you are all caught up."** (see §11).

---

## 5. Stories row (24h status)

Visible when not searching. `border-b border-zinc-100 dark:border-zinc-800/70`, `pb-2 pt-1`.
Horizontal snap scroll of **56dp ring cells** (cell width 64, gap 12, edge fade mask):
- Ring geometry: outer ring 56dp; inner avatar inset 2.5dp (≈51dp) clipped to circle.
- `unseen` → animated **conic gradient ring** `from #34d399 → #14b8a6 → #6ee7b7 → #10b981`,
  rotating 360° every 6s linear (disabled under reduced motion).
- `seen` → static `bg-zinc-300` (dark `bg-zinc-600`) ring. `none` → `bg-zinc-200` (dark `zinc-700`).
- **"My status"** cell: ring `none` + emerald **plus badge** (20dp, `bg-emerald-500`, 2dp white
  ring, Plus 12dp, bottom-right) when I have no live story; ring `unseen` when I do.
- Other users' cells: `unseen` unless `allSeen`.
- Label under each cell: 11dp medium zinc-600 (dark zinc-400), truncate center, name.
- Entrance: staggered rise+fade (30ms per cell).
- Press: my status → open my story, else open composer; others → viewer.
  **Native**: the stories API/composer/viewer are not built yet → render the row (My status
  cell only when the stories store has no groups), and a press shows the honest toast
  **"Stories aren't available in this native build yet."** (haptic + no navigation).

---

## 6. Folder rail (Signal-style)

Visible when not searching. Horizontal scroll `px-3 py-1`, gap-1.5:
- **"All" pill**: h-11 rounded-full px-4, 13dp semibold; active = emerald fill (shared pill
  animation); inactive = glass `bg-white/70 ring-1 ring-zinc-200/70 backdrop-blur-xl`
  (dark `bg-zinc-900/60 ring-white/10`).
- **Folder chips**: emoji + name (max-w 96 truncate) + count badge (15dp round chip,
  emerald-500/20 emerald text; white/25 white when active). Tap toggles active folder.
- **Manage button**: 44dp circle glass with `FolderPlus` (18dp); opens the folder manager.
  **Native**: folders API not built → rail renders All + manage; manage press → honest toast
  **"Chat folders aren't available in this native build yet."**
- Selecting a folder re-runs the list entrance (fade + 10dp rise, soft spring).
- Folder empty copy: **"This folder is empty — tap the folder button on the rail to add chats."**

---

## 7. The list (scroll area)

`overflow-y-auto`, bottom padding for dock clearance. States in priority order:

1. **Loading (no cache)** → 6 × `RowSkeleton`: row px-4 py-3, 48dp circle shimmer +
   bars `h-3.5 w-1/3` and `h-3 w-2/3` shimmer.
2. **Searching** → §10.
3. **No rows at all** → Empty state (§12).
4. **Normal list** → vertical composition:
   a. **Note to Self card** (§7.1) — always first, never in the regular list.
   b. **Mentions pill** (§7.2).
   c. **Channels pill** (§7.2).
   d. **Archived pill** (§7.2).
   e. Grouped rows: if any pinned rows → `PINNED` section header + pinned rows, then
      `ALL CHATS` header + unpinned rows (§7.3). If none pinned → just rows.
   f. Filter-empty copy (§4/§6) when active rows exist but none visible.
   g. "Every chat is archived." + "New messages bring chats back here." when all archived.

### 7.1 Note to Self card
`mx-2 mb-1 mt-0.5`, **deep glass** (`glass-deep glass-sheen` → blur 36 saturate 1.75,
translucent panel bg + hairline border + specular rim + diagonal gloss band; native: closest
layered glass = translucent white/near-black panel + hairline ring + top-edge highlight),
rounded-2xl, px-3 py-2.5, gap-3:
- Glyph tile 36dp rounded-xl `bg-gradient-to-br from-emerald-400 to-teal-600`, white
  `NotebookPen` 17dp, radial white highlight overlay at top.
- Title **"Note to Self"** 14dp semibold zinc-900 (dark zinc-50); subtitle
  **"Your private space — notes, links, ideas"** 11.5dp zinc-500 (dark zinc-400).
- Trailing: while creating → 16dp emerald spinner; if chat exists → text **"Open"** +
  ChevronRight (emerald, 11dp bold); else filled chip **"Create"** (emerald bg, white).
- Press → if exists open it, else `POST /api/conversations/self {userId}` then open.
  Failure toast: **"Could not open Note to Self"**. Haptic 6.
- Rows with `isSelf` never render as normal rows.

### 7.2 Entry pills (Mentions / Channels / Archived)
`mx-2 my-1`, h-11, `w-[calc(100%-16px)]`, **glass pill** (blur 22, translucent panel,
hairline, specular top edge), px-3.5, gap-2.5:
- **Mentions**: `AtSign` 18dp emerald; label 13dp semibold zinc-700 (dark zinc-200);
  live count badge (17dp, emerald-500 bg, white 10dp bold, "99+" cap) when > 0; trailing
  "1 mention" / "N mentions" 12dp zinc-400 + ChevronRight 14dp.
- **Channels**: `Radio` 18dp emerald; trailing "1 channel" / "N channels" (count = my
  subscriptions: `isGroup && broadcastMode`).
- **Archived**: `Archive` 18dp emerald; unread badge (same badge style) when archived
  unread > 0; trailing "1 chat" / "N chats".
- Press: archived → Archived sub-page (§9); mentions → mentions sub-page;
  channels → channels sub-page. **Native**: mentions/channels pages not built →
  honest toast **"Mentions aren't available in this native build yet."** /
  **"Channels aren't available in this native build yet."**. Archived page IS built (§9).

### 7.3 Section headers (also used by search)
`px-3 pt-3 pb-0.5`: label 11dp semibold uppercase tracking-wider zinc-400 (dark zinc-500);
count chip h-4 min-w-4 `bg-emerald-500/15` 10dp bold emerald-600 (dark emerald-400),
"99+" cap; then a 1dp hairline `bg-zinc-100` (dark `zinc-800`) filling the rest.
Labels: **"Pinned"**, **"All chats"**, **"Chats"**, **"Messages"**.

### 7.4 Conversation row (the core atom) — `chats-row.tsx`
Outer: px-2, entrance stagger ONLY on first load (opacity 0 → 1, y 14 → 0, 320ms,
delay = index × 28ms capped at 12 items), rounded-2xl.
Body: `bg-white/80 dark:bg-zinc-900/70`, **inset hairline** `ring-white/40` (dark
`ring-white/[0.06]`), px-2 py-2.5, gap-3. Pressed overlay `bg-zinc-900/[0.04]`
(dark `white/5`). Pinned wash `bg-emerald-500/[0.045]` (dark `[0.06]`).

**Avatar block (48dp)**:
- DM: circular `UserAvatar` — gradient by member color (§13 map), initials white semibold
  (fontSize ≈ 0.36×size), glass rim (top inset white highlight), photo if present.
  Presence dot bottom-right: size max(9, 0.26×48), emerald-500 when online else
  zinc-300 (dark zinc-600), 2dp bg-colored ring.
- DM online → **presence halo**: pulsing ring behind avatar (-3dp inset, ring-2
  emerald-400/60 + emerald glow shadow), scale 1→1.14 & opacity .65→.18 reversing loop
  (reduced motion: static ring).
- DM live streak (myStreak.count ≥ 2, not at-risk) → **heat ring** around avatar:
  conic gradient `amber(0.9) → rose(0.85) → amber(0.35) 70% → transparent 85%`, ring padding
  2/2.5/3dp and inset -3/-4/-5dp for heat 1 (2-4) / 2 (5-9) / 3 (10+).
- Group/Channel: **squircle mask** (28% fallback radius; superellipse path) wrapping
  `GroupAvatar` — violet-family gradient picked by `hash(id) % 4` (§13), initials white,
  radius max(10, 0.28×48), photo (circular) overrides when present.

**Title line** (baseline row, gap-2):
- `Pin` glyph 12dp filled emerald-500 when pinned.
- Name 15dp tracking-tight truncate; **unread → font-semibold + zinc-900 (dark zinc-50)**,
  else font-medium zinc-900 (dark zinc-100).
- Trailing cluster (gap-1.5): streak chips then time.
  - `ends tonight` chip (at-risk streak): 10dp bold amber-600 (dark amber-400),
    `bg-amber-500/10` rounded-full px-2 py-0.5 ring-1 amber-500/25, `Hourglass` 12dp.
    Content: **"ends tonight"**.
  - Live streak chip: same shape (`ring-amber-500/20`), `Flame` 12dp + count.
  - Lost streak chip: `bg-rose-500/[0.07] ring-rose-500/20 text-rose-400`, `Flame` 12dp +
    **"streak lost"**. Priority: at-risk > live > lost; never two at once.
  - Time (`formatListStamp`: today → "14:05" · yesterday → "Yesterday" · else "3 Aug"),
    11dp; **unread → semibold emerald-600 (dark emerald-400)**, else zinc-400 (dark zinc-500).

**Preview line** (mt-0.5, justify-between gap-2), priority order:
1. **Typing**: 3 bouncing 3.5dp emerald dots (y 0→-2.5, 900ms loop, 150ms stagger) +
   italic **"typing…"** 13dp medium emerald-600 (dark emerald-400).
2. **Draft** (local draft overrides; server `myDraft` fills cross-device): `PencilLine` 12dp
   amber-500 + **"Draft:"** amber-600 semibold + italic draft text zinc-500 truncate.
3. **Preview**: prefix (zinc-400): `You: ` when my message · `<Sender>: ` in groups ·
   `↩ ` prefix when reply. Text 13dp; unread → medium zinc-600 (dark zinc-300), else
   zinc-500 (dark zinc-400). Special texts: deleted → **"🚫 message deleted"** (italic);
   image-only → **"📷 Photo"**; audio-only → **"🎤 Voice message"**; file →
   **"Document — <fileName>"**. (These strings match the web exactly, emoji included.)

**Trailing status** (18dp height zone):
- Unread badge (unreadCount > 0, not muted): h-18dp min-w-18dp emerald-500 pill, white 10dp
  bold, shadow emerald-600/40, **2dp white ring** (dark zinc-900), springs in on value
  change, "99+" cap.
- Manual-unread dot (myManualUnread, count 0): 12dp emerald-500 dot, white ring.
- Muted: `BellOff` chip — when unread: `bg-zinc-300` (dark `bg-zinc-700`) zinc-600 text
  with count; when read: transparent, zinc-400, text **"Muted"**.
- Muted state check: `mutedUntil` in the future (server truth), not merely non-null.

**Swipe left actions** (disabled in select mode): drag constrained to -112..0dp, snap open at
56dp, spring snappy; reveal two 48dp glass chips (`bg-white/70 ring-1 ring-white/10
backdrop-blur-xl`, dark `bg-zinc-900/60`): Pin/Unpin (`Pin` emerald / `PinOff` amber) and
Archive/Unarchive (`Archive` zinc / `ArchiveRestore` amber), 9dp semibold zinc-500 labels.
Chips call the SAME endpoints as the sheet.

**Long-press 450ms** → enters multi-select (main list) or opens the action sheet (archived page).
**Select mode**: check-circle overlay 24dp over the avatar (emerald-500 fill + white check
when selected; dark translucent + white/60 ring when not), row scale 0.985, tap toggles,
swipe disabled; empty selection exits the mode; entering search exits it.

**Divider**: 1dp `ml-[64px]` `bg-zinc-100` (dark `zinc-800`).

### 7.5 Multi-select floating bar
`absolute bottom-[86px]` centered, glass-deep pill p-1.5 ring-white/40, entrance scale .92→1
rise 28→0 spring snappy:
- "N selected" 12dp bold tabular zinc-600 (dark zinc-300).
- Actions (40dp circles, staggered 45ms): **Archive** (`Archive`; exits mode on success),
  **Mute 8h** (`BellOff`; keeps mode), **Mark read** (`CheckCheck`; keeps mode unless partial
  failure). Spinner replaces the icon while pending.
- Exit `X` (40dp, zinc-400).
- Toasts: "Archived N chat(s)" · "Muted N chat(s) for 8 hours" ·
  "N chat(s) marked as read" · partial: "N chat(s) could not be marked read — try again".

---

## 8. Row action sheet (long-press / ⋮)

Bottom sheet with the avatar, title, and contextual subtitle (typing… / Draft: … / N members),
then actions — all PATCH/POST with **`{userId: me.id}`** bodies:
1. **Pin/Unpin** — `PATCH /api/conversations/{id}/pin` body `{userId}` (server toggles).
2. **Archive/Unarchive** — `PATCH /api/conversations/{id}/archive` body `{userId, archived}`.
3. **Mark as unread/read** — `PATCH /api/conversations/{id}/mark-unread` body `{userId, on}`.
4. **Mute strip** — `PATCH /api/conversations/{id}/mute` body `{userId, until}` with presets
   `8h` / `1w` / `always` (offsets 8h · 7d · +50y) and **Unmute** (`until: null`).
   Optimistic flip; toast "Notifications unmuted" / "Muted — always" /
   "Muted until <stamp>".
5. **Export** (.txt) and **Clear chat** (soft-delete my own messages) — keep the buttons and
   web copies; on transport failure show the web error toast ("Could not export this chat" /
   "Could not clear this chat"). Success copies: "Chat exported — Saved <file>" /
   "Cleared N message(s)" / "Nothing to clear — none of your messages are left in this chat."
All destructive/optimistic ops roll back on failure with the web's error toasts
("Could not update the pin/mute/unread flag/archive").

---

## 9. Archived sub-page (`chats-archived-page.tsx`)

Full-screen page (push) with back button, titled **"Archived"**, listing archived rows using
the SAME row component (long-press opens the sheet; swipe Pin works; Archive chip reads
**"Unarchive"**), skeleton while loading, and the count in the title area. Opening a row
works exactly like the main list. Native: Android = `archived` NavHost route; iOS = full
screen cover/push.

---

## 10. Search results layout

- Local matches over rows (name, draft, last non-deleted message content) → section
  **"Chats"** with count, rows rendered by the same ConversationRow (no long-press select).
- Server message search (debounced 250ms, query ≥ 2 chars) → section **"Messages"** with
  rows: sender avatar 40dp (+16dp group badge), conversation name 13dp semibold, time stamp
  11dp zinc-400, snippet 13dp zinc-500 with the match **highlighted** (`emerald-500/20`
  rounded mark, semibold emerald text), ≤64-char clip window with "…" clippings; deleted →
  italic "Deleted message"; image → "📷 Photo"; document caption miss → "Document — <file>".
- Query length 1: hint **"Keep typing to search inside messages…"** (centered 12dp zinc-400).
- No matches anywhere: Search glyph 32dp zinc-300, **"No matches"** 13dp medium zinc-500,
  **"Nothing here for "<q>"."** 12dp zinc-400.
- **Native**: the server-search call may fail (no live gateway) → omit the Messages section
  silently (local Chats section still works — real data, real behavior).

---

## 11. Empty state (no conversations at all)

Centered deep-glass card (`rounded-[28px]`, max-w 300, px-6 py-8) with a soft emerald radial
glow blooming behind (40 blur), the **`empty-chats.png` illustration** (144dp, rounded-3xl,
light ring + shadow — COPY the asset from `public/empty-chats.png` into both apps),
title **"No conversations yet"** (16dp semibold zinc-800/dark zinc-100), body
**"Your next great chat is one tap away. Find someone and break the ice."** (13dp zinc-500),
glass outline pill button **"Say hi to someone"** + `ArrowRight` (emerald text, ring
emerald-500/40) → opens the Contacts tab.
Error state (refresh failed, no cache): same card anatomy, honest copy:
**"Could not reach the gateway"** + failure message + **"Retry"** pill.

---

## 12. Capsule dock (default nav — part of the home chrome)

Floating glass capsule: `inset-x-3` (12dp side margins), bottom offset =
safe-area + 10dp, **rounded-28**, p-6dp, gap-4dp, glass panel (`border-zinc-200/70
bg-white/70 backdrop-blur-2xl saturate-150`, dark `border-white/10 bg-zinc-900/65`,
shadow 0 8 32 rgba(0,0,0,.14) + inset top white highlight).
Entrance: rise 64dp + fade. Hidden while a chat room owns the screen.

Layout order: **[Chats, Hub] · compose · [Contacts, Profile] · More**.
- **Tab**: min-h 52dp, flex-1, rounded-22, column icon(22dp)+label(10dp, gap 3dp).
  - Active fill pill: `bg-gradient-to-b from-emerald-500/20 to-emerald-500/[0.06]`,
    inset ring `ring-emerald-500/30`, shadow `0 6 20 -6 rgba(16,185,129,.55)`
    (dark: from emerald-400/[0.16] to [0.05], ring /25). Slides between tabs (shared layout).
  - Active icon/label: emerald-600 (dark emerald-400), icon stroke 2.2; inactive zinc-500
    (dark zinc-400) stroke 1.8. Active icon nudges scale 1.08 / y -1.
  - Press: scale .88 spring + **wobble** rotate [0, -8, 6, 0]° 350ms; haptic.
- **Unread badge** on Chats: absolute -right-2.5 -top-1.5, h-20dp min-w-20dp,
  `bg-gradient-to-br from-emerald-500 to-teal-500` white 10dp bold, ring-2 white
  (dark zinc-900), shadow emerald glow, pops (scale .4→1) on change, "99+" cap.
- **Compose**: 46dp circle `bg-gradient-to-br from-emerald-500 to-teal-600`, white Plus 20dp,
  shadow emerald glow, press spring, haptic 14 → New Chat action (honest toast in native).
- **More**: 40dp circle, `Ellipsis` 20dp zinc-500, haptic 10 → compact glass menu
  (**Settings · Search · Saved · Stories**, 232dp wide, veil-tap dismiss).
  Native mapping: Search → open the chats search mode; Saved/Stories/Settings → honest
  toast "<X> isn't available in this native build yet." (Real actions, no fakes.)
- Icons (Lucide): `MessageCircle` Chats · `Flame` Hub · `Users` Contacts ·
  `CircleUserRound` Profile. Native equivalents:
  Android `Icons.Outlined.ChatBubbleOutline` · `Icons.Outlined.Whatshot` ·
  `Icons.Outlined.Group` · `Icons.Outlined.AccountCircle` · `Icons.Filled.Add` ·
  `Icons.Filled.MoreHoriz`. iOS SF Symbols: `bubble.left.and.bubble.right` · `flame` ·
  `person.2` · `person.crop.circle` · `plus` · `ellipsis`.

Both platforms REPLACE their current tab bars (Material `NavigationBar` / SwiftUI `TabView`)
with this dock. Tab content keeps the direction-aware slide+fade (§1).

---

## 13. Color tokens (light / dark)

- Emerald accent `#10B981` (`emerald-500`); deep `#047857`; gradient partner `teal-600 #0D9488`.
- Neutrals: zinc scale; page translucency `white/30` / `zinc-950/20`; row `white/80` /
  `zinc-900/70`; hairlines `zinc-200`, `zinc-100`, dark `white/10`, `white/[0.06]`.
- Amber streak `#F59E0B`; rose lost `#FB7185`.
- Avatar gradients (user colors): emerald `400→600`, rose, amber, violet, teal, orange,
  pink, cyan (from-{c}-400 to-{c}-600).
- Group gradients by hash(id)%4: violet-400→purple-600 · fuchsia-400→purple-600 ·
  purple-400→violet-700 · fuchsia-500→violet-700.
- Initials: split name on whitespace; 1 word → first 2 chars; else first+last char; uppercase.

---

## 14. Data & backend contract (the "behind the scenes")

REST (identical routes as the web, gateway = `PulseEndpoints.http` / `PulseAPIClient`):
- `GET  /api/conversations?userId=` — the list. **Poll every 6s** while home is visible
  (web `refetchInterval: 6_000`). Render from local cache instantly; refresh in background.
- `POST /api/conversations/self {userId}` — Note to Self create.
- `PATCH /api/conversations/{id}/pin` `{userId}` (toggle).
- `PATCH /api/conversations/{id}/mute` `{userId, until: '8h'|'1w'|'always'|null}`.
- `PATCH /api/conversations/{id}/archive` `{userId, archived}`.
- `PATCH /api/conversations/{id}/mark-unread` `{userId, on}`.
- `POST /api/conversations/{id}/read` `{userId}` — **userId is REQUIRED** (400 otherwise).
- `GET /api/search?userId=&q=` — server message search (≥ 2 chars, 250ms debounce).
- `GET /api/stories?requesterId=` · `GET /api/folders?userId=` · `GET /api/mentions` —
  native builds call them where wired; unreachable → the feature degrades to its honest
  empty state (never crashes, never fakes).

**Transport fixes required in this wave (verified against the routes):**
- Android `PulseApi` has only GET/POST → add a real **PATCH** verb and switch pin / mute /
  archive / mark-unread to it with the exact bodies above; fix `read` to POST `{userId}`.
- iOS `PulseAPIClient` uses POST for pin/mute/archive → switch to **PATCH** with
  `{userId, …}` bodies; add `userId` to `read`.
- `mutedUntil` muting = **future timestamp check**, not non-null.
- iOS `WireConversationSummary` must gain `myManualUnread: Bool?`.
- Android domain `Conversation` must carry (mapper update): `myManualUnread`,
  `streakAtRiskCount` (deadStreak.count), `streakLost` (lostStreak), `lastMessageMine`,
  `lastMessageDeleted`, `lastMessageIsReply`, `lastMessageIsImage/Audio/File + fileName`,
  `mutedUntil` epoch, `otherUserId` (DM presence), `isChannel` (broadcastMode),
  group photo already rides `avatar`.

Live layer: socket is intentionally off until a public relay exists (N9). The home must be
fully usable from the 6s REST poll + local cache; typing/presence render when the socket
layer feeds them (it already does on both platforms when a socket URL is baked).

## 15. Release engineering for this wave

- Android `versionCode` → **6**, `versionName` → **"0.1.5-native"** (defaults in
  `app/build.gradle.kts`).
- One push → one CI round (Android `:app:assembleRelease` + iOS build). Publish + manifest
  update happens only after green CI (existing N8/N9 pipeline).
