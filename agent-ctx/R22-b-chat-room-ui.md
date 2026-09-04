# Task R22-b — Chat room premium UI overhaul (composer + bubbles + list motion)

Agent: Z.ai Code · Exclusive file: `src/components/chat/chat-room.tsx` (~270KB → read in chunks with Read/Grep before every surgical edit) · English UI · zero mocks · no build, :3000 untouched.

## What shipped (for the lead's browser pass)

### 1. Composer (the "fucked up" input area)
- **Floating glass capsule**: `rounded-[26px] backdrop-blur-2xl bg-white/80 dark:bg-zinc-900/70 ring-1 ring-inset shadow-[0_8px_32px]`; strip behind = `bg-zinc-100/80 dark:bg-zinc-950/60` (dock-clearance div color-matched).
- **Focus ring**: emerald hairline (`ring-emerald-500/40`) overlay, spring.soft opacity/scale via `onFocusCapture/onBlurCapture`.
- **Keyboard lift**: `visualViewport` resize/scroll + focusin/focusout (rAF-throttled). Overlap = `innerHeight − vv.height − vv.offsetTop`, fires only >90px while a text field holds focus, cap 420px → wrapper `motion.div animate={{ y: −lift }}` spring.soft, `will-change: transform` on the wrapper only.
- **Layout**: `[+ tray] [textarea] [😊 popover] [mic/send]` — replaced the 4-leading-button crush; sticker/location/poll/schedule/photo moved to the tray. Textarea: borderless inside capsule, grows to 120px (~5 lines) then internal scroll, `transition-[height] 200ms`.

### 2. Send button morph
- zinc **dot** (disabled/armed-empty) → **plane** slides in x16→0 rotate−35→0 spring.bouncy on emerald-400→600 gradient → **spinner** while pending (rotate-in spring.snappy) → **check** in edit mode. Success pop: `wasSendingRef` watcher bumps `sendPop` → mic remounts with scale [1,1.18,1]. `whileTap 0.88`.

### 3. Attachments tray
- `+` rotates 45°→✕ (spring.snappy). Tray springs open ABOVE the capsule (AnimatePresence height/opacity spring.soft) with 8 staggered (30ms, spring.bouncy, whileTap 0.92) glass tiles wired to EXISTING handlers: **Photo** (compress→upload), **Sticker**, **Location**, **Poll**, **Schedule** (empty-draft toast guard), **Board** (`whiteboard.setOpen`), **Effects** (inline sub-row arms confetti/lasers/echo/sparkles via `setPendingEffect` + existing toast), **Commands** (help dialog).
- Opening clamps textarea to one line (`trayOpenRef` → autosize cap 48px), dismisses slash palette; closes on send / room switch / recording start; broadcastLocked disables.

### 4. Recording state
- Status pill slides in (x−14→0 spring.snappy); **pulsing red radar ring** around the record dot (scale 1→2 / opacity .85→0 loop, reduced-motion gated); timer chip pops (spring.bouncy); cancel/stop kept.

### 5. Bubbles
- **Entrance (new-only)**: `justArrived` = temp-optimistic OR `createdAt > mountMsRef` (frozen when `historyLoaded` commits) AND NOT in `landedIdsRef` (real ids that replaced temps → no double animation). Own: squash-stretch scaleX1.06/scaleY0.94 → 1 (origin bottom-right); incoming: scale .85/y8 → 1; spring.bouncy. History + "Load older" render static (`initial={false}`). `rowsEqual` extended (justArrived, reducedMotion, + onOpenProfile).
- **Press**: `whileTap 0.97` spring.snappy (long-press options dialog untouched).
- **Swipe-to-reply**: constraints ±64, elastic 0.12, directionLock (scroll safe), release threshold 52→**28px**, emerald Reply arrow fills at 4→28px, scales to 1.1 by 44px; dragMovedRef still kills the post-swipe click.
- **Dividers**: day chips + UNREAD pill ease.out fade/slide (glass kept, unread gains blur).

### 6. Scroll-to-bottom FAB
- Spring pop (480/20 + whileTap) + white **count badge** of messages landed while scrolled away (ref-mirrored, resets on jump/scroll/room switch); `will-change: transform`.

### 7. Reaction bursts + effects
- Adding a reaction → `fireParticles({kind:'hearts'})` from the chip/dialog-button/double-tap rect (add-guarded so removing never bursts); count digit pops on change.
- Effect sends now ALSO fire the app-wide particle layer: confetti→confetti, sparkles→stars, lasers/echo→**burst** at (0.5, 0.85) — inside `triggerEffectFor`, so own sends + incoming both covered, reduced-motion short-circuits first.
- Typing: dots already framer-physics (kept); container upgraded to spring.snappy.

## Regression sweep (all verified intact by code review — no handlers changed)
voice notes · /whiteboard · bot pass-through · stickers · location · effects (slash/chip/canvas) · PiP · threads · polls · scheduled · view-once · translations · replies/quote-jump · reactions (toggle/info sheet) · read receipts · @mentions · slash palette · outbox/drafts. Removed only the old plus-Popover (its actions became tray tiles).

## Gates
- `npx tsc --noEmit 2>&1 | grep "^src/"` → **0**
- `npx eslint src/components/chat/chat-room.tsx` → **0 problems** (`bun run lint` repo-wide shows only a concurrent crew's in-flight `contacts-tab.tsx` error — not this task's file)
- GET / → 200 · dev.log clean · no build · :3000 never restarted
