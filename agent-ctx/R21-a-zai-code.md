# Task R21-a — Bot Engine + Discord-style Webhooks (work record)

Agent: Z.ai Code (bot-engine & webhooks crew)
Status: COMPLETE — all verification green. Full detail in worklog.md (`Task ID: R21-a` section).

## Files changed (exclusive ownership respected)
| File | Change |
|---|---|
| `src/lib/bot-engine.ts` | NEW — deterministic command bot: `maybeBotReply()` + `botWillRespond()` + safe math parser |
| `src/app/api/webhooks/route.ts` | NEW — POST create / GET list (participant-gated) |
| `src/app/api/webhooks/[token]/route.ts` | NEW — public CORS ingest (20/min/token → 429) + admin-only DELETE |
| `src/app/api/conversations/[id]/messages/route.ts` | HOOKED — `await maybeBotReply(...)` after 201 pipeline; ai-bot suppressed only when the engine claims the message |
| `src/components/chat/group-info-sheet.tsx` | ADDED — WebhooksSection card (list/copy-URL/create/delete, admin-gated, spring stagger) |

## API contracts
- `POST /api/webhooks {conversationId, name(1-32), requesterId}` → `201 WebhookDTO {id,name,token,avatarColor,url,createdAt,createdBy}` (participant required)
- `GET /api/webhooks?conversationId=&requesterId=` → `{webhooks: WebhookDTO[]}` (participant required)
- `POST /api/webhooks/[token]` PUBLIC `{content(1..2000), username?}` → `{success:true}`; 400/404/429; CORS `*`
- `DELETE /api/webhooks/[token]?requesterId=` → `{ok:true}` (participant-ADMIN only)
- `maybeBotReply(conversationId, {id, senderId, content})` — awaited inside messages POST, never throws

## Bot trigger grammar & commands
Trigger: leading `/` · `@pulseai` (word-boundary) · `pulseai,`/`pulseai:`/`pulseai!` prefix; optional `/` after the handle is normalized. Unknown bare slash = silent; handle-mention w/o command = "/help" pointer. Commands: `/help /roll [N] /flip /8ball <q> /math <expr> /rps <pick> /dice /time /wallet /poll q | o1 | o2 [| …]`. Payloads: bot replies `{"bot":true}`; webhook posts `{"webhookName","webhookColor","webhook":true}` — kind stays `text` so all renderers work.

## Proof highlights (curl, against :3000)
- `/math (2+3)*4-6/3` → `🧮 (2+3)*4-6/3 = 18`; `2**10%7` → `= 2`; `2+abc` → `Invalid expression`
- `/wallet` → real balances `PC 10 · GEM 6 · streak 1` (Alice's actual UserWallet)
- `/poll Favorite Pulse color? | Emerald | Violet | Amber` → real Poll + 3 PollOptions on a bot message
- `@pulseai /roll 20` → `🎲 @alicechen rolled 6 (1-20)`; `pulseai, /flip` → `🪙 Heads`
- Webhook ingest `{"content":"Build #42 passed ✅","username":"CI Robot"}` → `{success:true}` + message payload on the wire
- 21-post burst → `200×19 429 429` (20/min/token incl. earlier post)
- Member delete → 403; deleted token → 404; non-participant list → 403
- Fixture DM (Alice↔Bob, bot absent): `/math 1+1` → no reply (participant gate)
- pulse-socket log: `notify event=message:new delivered=2/2` — live relay confirmed

## Status
- `npx tsc --noEmit` → 0 errors in my files (pre-existing errors in other crews' files only)
- `bun run lint` → clean for my files (pre-existing stories/story-composer/voice-room issues untouched)
- No `bun run build`, no dev-server restarts, nothing committed (lead commits)

## Left in DB for lead E2E
- Group **"Bot & Webhook QA"** `cmtn1844z0000nhhcxv5i1f8p` (Alice admin / Bob / pulseai) with live command+webhook history
- Webhook **"CI Deploy"** `cmtn1cu84002jnhhclhwjpaud` in that group (rate-limit burst messages included — labeled `burst N`)

## Handoff notes
- One identity, two brains: `pulseai` User row serves the LLM companion AND this engine; `botWillRespond()` gate in the messages route guarantees exactly one reply per user message.
- Bubble-level "via <webhookName>" rendering is a one-file change in chat-room (payload already on the wire).
