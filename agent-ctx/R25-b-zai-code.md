# R25-b — Profile experience overhaul (work record)

## Files owned & changed
- REWRITTEN src/components/chat/profile-tab.tsx (~984 lines)
- REWRITTEN src/components/chat/user-profile-sheet.tsx (~401 lines)
- NEW src/components/profile/status-glyph.tsx (legacy statusEmoji -> Lucide icon layer)
- NEW src/components/profile/profile-primitives.tsx (ProfileSection / ChevronRow / SwitchRow / CountUp / StatTile)
- NEW src/components/profile/handle-editor.tsx (moved verbatim from old profile-tab)
- NEW src/components/profile/ui-language-picker.tsx (5 UI_THEMES, live setTheme)
- NEW src/components/profile/nav-style-picker.tsx (13 NAV_STYLE_META, zone filters, live setStyle)

## Contracts verified
- ProfileTab props unchanged: { me: AppUser; onOpenSavedMessage?: (conversationId, messageId) => void } (main-shell.tsx:333 call site OK)
- UserProfileSheet props unchanged: { user: AppUser | null; open; onOpenChange; onMessage? } — contacts-tab passes onMessage, group-info-sheet + chat-room do not (fallback DM creation added for those)
- Real data sources used: GET /api/users/[id]/stats, GET /api/hub/wallet, PATCH /api/users/[id], GET /api/users/check-username, GET /api/users/[id]/saved, POST /api/conversations (DM), promptPwaInstall/usePulsePwa, navigator.storage.estimate + caches.keys, usePulseRealtime().onlineIds (real presence)
- Query keys shared with other crews: ['user-stats', id], ['hub-wallet', id], ['conversations', id], ['saved', id], ['me', id], ['users'], ['username-check', handle]

## Verification
- bunx tsc --noEmit | grep "^src/" -> empty
- bun run lint -> 0 errors (1 pre-existing warning in main-shell.tsx, not mine)
- Dev server on :3000 was DOWN the entire session (platform-managed; dev.log stale, gateway 502) -> no runtime smoke test; noted in worklog

## Notes for next crews
- statusEmoji DB values stay emoji strings (cross-surface contract); render via StatusGlyph in R25 surfaces
- If a shell "open conversation" event appears, wire it into UserProfileSheet's DM fallback to auto-open the chat
- About section shows real package.json version (imported at build time); no build-id source exists client-side
