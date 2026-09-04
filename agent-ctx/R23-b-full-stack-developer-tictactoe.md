# Task R23-b — In-chat tic-tac-toe games — work record

Agent: full-stack-developer (tic-tac-toe) · Date: 2026-09-04 · Status: DONE (API + UI + E2E proofs)

## Files owned (all NEW — zero existing files edited)
1. `src/app/api/games/route.ts` — POST create (+ GET ?conversationId= list)
2. `src/app/api/games/[id]/route.ts` — GET detail + resolved players
3. `src/app/api/games/[id]/move/route.ts` — POST move (win/draw/turn/cell guards, race-safe)
4. `src/app/api/games/[id]/join/route.ts` — POST claim open O seat (race-safe)
5. `src/components/chat/game-tictactoe-card.tsx` — self-polling glass game card

## API contracts (wire shapes)
- `POST /api/games {userId, conversationId, game?='tictactoe', opponentId?}`
  → `201 { match, message }` — message identical to messages-API POST shape (mapMessage),
    kind `'game'`, content `'⚔️ Tic-tac-toe challenge'` / `'⚔️ Tic-tac-toe — open challenge'`,
    payload `{"matchId":"…","game":"tictactoe"}`.
  Guards: 404 conv · 403 caller not participant · 400 opponent==self · 400 opponent not participant.
- `GET /api/games?conversationId=` → `{ matches: Match[] }` newest-first take 25 (400/404 guarded).
- `GET /api/games/[id]` → `{ match, playerX:{id,name,color}, playerO:{id,name,color}|null }` (404).
- `POST /api/games/[id]/move {userId, cell 0..8}` → `{ match, playerX, playerO }`.
  Statuses: 400 bad input · 404 · **409** not active / **403** not a player / **409** 'Not your turn.' / **409** 'Cell taken.' / 409 'Board changed — try again.' (lost optimistic race).
  Win → `status x_won|o_won`, `winnerId`, `winLine` (JSON, parsed to number[] on the wire); 9 moves → `draw`; else turn flips.
- `POST /api/games/[id]/join {userId}` → `{ match, playerX, playerO }`.
  Only when active && playerOId null && userId!==playerXId && caller is room participant (403 else);
  409 'no longer open' / 'already own' / 'Someone else already took the O seat.' (guarded write).
- Wire `Match`: `{ id, conversationId, game, playerXId, playerOId|null, board (9-char string), turn 'X'|'O', status, winnerId|null, winLine number[]|null, moveCount, createdAt, updatedAt }`.

## Events
- Dispatches socket `message:new` (via notifySocket) when an invite message is created.
- Client dispatches `window` CustomEvent **`pulse:external-message`** (exported const `GAME_EXTERNAL_MESSAGE_EVENT`) — `detail` = fresh `ChatMessage` after a rematch invite is created.
- Polls `GET /api/games/[id]` every 1500ms while `status==='active'` only (unmount-cleared, hidden-tab paused via `refetchIntervalInBackground:false`).

## E2E proof transcript (curl, :3000, real Prisma rows)
- Ids: Alice `cmtawq3h4001ktcwn674m1frp` · Bob `cmtawqfwh001ltcwnsalt6659` · bot `cmtbdcvxd0000tcuzdqql8mim` · DM `cmtawqnzg001mtcwn0h4uwzhr` · QA `cmtn1844z0000nhhcxv5i1f8p`.
- DM challenge → 201: matchId `cmtn7q92o0001nhyntaveyz1e`, messageId `cmtn7q92r0003nhynjn9quw24`; verified via messages API (`kind:"game"`, payload `{"matchId":"cmtn7q92o0001nhyntaveyz1e","game":"tictactoe"}`); detail resolves Alice Chen (rose) / Bob (emerald).
- Scripted game: X0 O3 X1 O4 X2 → `x_won`, winnerId Alice, `winLine [0,1,2]`, moveCount 5, board `XXXOO    ` (turn flips verified at every step).
- Guards: finished-match move → **409**; cell 9 → **400**; bot (QA participant, not a player) move on ACTIVE match → **403** 'You are not a player in this match.'
- QA open challenge: create (no opponentId) → matchId `cmtn7rs420005nhyn7tu9q7hj`, messageId `cmtn7rs440007nhynced81011`, playerOId null · Bob join → playerOId=Bob · re-join → **409** · turn flip X0→turn O · out-of-turn → **409** 'Not your turn.' · cell-taken → **409** 'Cell taken.' · Bob wins → `o_won` winnerId Bob `winLine [1,4,7]` (board `XO XO  OX`) · finished move → **409**.
- `GET /api/games?conversationId=` lists both rooms; missing param → 400.
- Left as demo data: DM match (x_won by Alice) + QA match (o_won by Bob) + both invite messages.

## Gates
`bun run lint` → 0 problems · `npx tsc --noEmit` filtered to my 5 files → 0 errors · GET / → 200 · no build, dev server untouched.

## Integration notes for lead (chat-room.tsx / slash-palette.tsx — lead-owned)
1. **Render cards**: message `kind==='game'` → `JSON.parse(payload)` → `<GameTicTacToeCard matchId={payload.matchId} meId={me.id} />` instead of the text bubble (fallback text = `message.content`).
2. **`pulse:external-message` listener**: append `detail` (ChatMessage) to the live list when `detail.conversationId === current room` — required for the rematch SENDER (others already get socket `message:new`).
3. **Composer entry (optional)**: `/game` slash command or tray tile → `POST /api/games {userId: me.id, conversationId}` (omit opponentId = open challenge) → dispatch `pulse:external-message` with `res.message`.
4. **Gotcha**: messages POST route whitelists kind (`MESSAGE_KINDS`) and will 400 `'game'` — invites must be created via `POST /api/games`; messages GET returns `kind:'game'` untouched.
5. Chats-list preview shows the ⚔️ content text — acceptable as-is.

## Backlog
Socket push on move/join (drop poll latency) · tournaments on GameMatch · bot opponent for O · connect-four on the same endpoints.
