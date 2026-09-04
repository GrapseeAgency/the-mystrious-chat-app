// ─────────────────────────────────────────────────────────────
// Pulse bot engine — REAL deterministic command bot (server-only).
// The bot is a genuine User row (username "pulseai" — the same row
// the LLM companion uses) that joins conversations like anyone else.
// It replies only when a message is a supported slash command, optionally
// addressed through its handle ("@pulseai /roll", "pulseai, /flip") or a
// bare handle mention ("@pulseai"). Everything it answers is computed
// server-side or read from Prisma — zero external APIs, zero mocks.
// Never throws: callers can safely await it inside the send pipeline.
// ─────────────────────────────────────────────────────────────
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  MESSAGE_FULL_INCLUDE,
  MESSAGE_MAX,
  notifySocket,
} from '@/lib/serializers'

/** The bot's real @handle — a User row with this username must be a participant. */
export const BOT_USERNAME = 'pulseai'

/** Deterministic payload marker on bot-authored messages (kind stays "text"). */
const BOT_PAYLOAD = JSON.stringify({ bot: true })

const POLL_QUESTION_MAX = 140
const POLL_OPTION_MAX = 80
const ROLL_MAX = 10_000

const KNOWN_COMMANDS = new Set([
  'help',
  'roll',
  'flip',
  '8ball',
  'math',
  'rps',
  'dice',
  'time',
  'wallet',
  'poll',
])

const HELP_TEXT = [
  '🤖 Pulse bot — commands',
  '/roll [N] · random 1..N (default 100)',
  '/flip · coin toss',
  '/8ball <question> · magic 8-ball',
  '/math <expression> · e.g. (2+3)*4/5',
  '/rps rock|paper|scissors · play a round',
  '/dice · roll 2d6',
  '/time · server time (UTC)',
  '/wallet · your real PC/GEM balance + streak',
  '/poll <question> | <opt> | <opt> · live poll',
  'Tip: "@pulseai" or "pulseai," also works before any command.',
].join('\n')

const GREETING_TEXT =
  "👋 I'm the Pulse bot. Send /help to see everything I can do."

const POLL_USAGE =
  'Usage: /poll What should we play? | Chess | Maze | Marbles'

const EIGHT_BALL_ANSWERS = [
  'It is certain.',
  'It is decidedly so.',
  'Without a doubt.',
  'Yes — definitely.',
  'You may rely on it.',
  'Most likely.',
  'Outlook good.',
  'Signs point to yes.',
  'Reply hazy, try again.',
  'Concentrate and ask again.',
  'My reply is no.',
  'Very doubtful.',
]

// ── Trigger parsing ──────────────────────────────────────────

const MENTION_RE = new RegExp(`@${BOT_USERNAME}(?![a-z0-9_])`, 'i')
const HANDLE_PREFIX_RE = new RegExp(`^${BOT_USERNAME}\\s*[,:!]\\s*`, 'i')
const COMMAND_RE = /^([a-z0-9_]+)(?:[\s,:]+([\s\S]*))?$/

interface Trigger {
  /** triggered via the bot's own handle forms (@pulseai / pulseai,) */
  viaHandle: boolean
  /** normalized known command (null = none found) */
  cmd: string | null
  arg: string
}

/**
 * Pure trigger parser. Returns null when the message cannot possibly
 * reach the bot (no leading slash, no handle mention/prefix).
 */
function parseTrigger(raw: string): Trigger | null {
  const mention = MENTION_RE.exec(raw)
  let viaHandle = false
  let body: string

  if (mention) {
    viaHandle = true
    body = raw.slice(mention.index + mention[0].length).replace(/^[\s,:!]+/, '')
  } else if (HANDLE_PREFIX_RE.test(raw)) {
    viaHandle = true
    body = raw.replace(HANDLE_PREFIX_RE, '')
  } else if (raw.startsWith('/')) {
    body = raw.slice(1)
  } else {
    return null
  }

  // Normalize: drop one leading slash so "@pulseai /flip" and "pulseai, /flip"
  // parse the same way as a bare "/flip" command.
  const trimmed = body.trim().replace(/^\//, '').trim()
  if (trimmed.length === 0) return { viaHandle, cmd: null, arg: '' }
  const m = COMMAND_RE.exec(trimmed)
  if (!m) return { viaHandle, cmd: null, arg: '' }
  const cmd = m[1].toLowerCase()
  return {
    viaHandle,
    cmd: KNOWN_COMMANDS.has(cmd) ? cmd : null,
    arg: (m[2] ?? '').trim(),
  }
}

/**
 * Synchronous predicate for the messages route: true when THIS message
 * will produce a bot reply, so the LLM companion can stand down and the
 * user never receives two answers to one message.
 */
export function botWillRespond(content: string): boolean {
  const raw = typeof content === 'string' ? content.trim() : ''
  if (!raw) return false
  const trigger = parseTrigger(raw)
  if (!trigger) return false
  return trigger.cmd !== null || trigger.viaHandle
}

// ── Reply-once guard (belt & braces — engine runs once per send) ──

const REPLIED_CACHE_MAX = 500
const repliedFor = new Set<string>()

function claimOnce(messageId: string): boolean {
  if (repliedFor.has(messageId)) return false
  if (repliedFor.size >= REPLIED_CACHE_MAX) {
    const oldest = repliedFor.values().next().value
    if (oldest) repliedFor.delete(oldest)
  }
  repliedFor.add(messageId)
  return true
}

// ── Safe math — tiny recursive-descent parser (NO eval / Function) ──

type MathTok = { t: 'num'; v: number } | { t: 'op'; v: string }

function tokenizeMath(input: string): MathTok[] | null {
  const toks: MathTok[] = []
  let i = 0
  while (i < input.length) {
    const ch = input[i]
    if (ch === ' ') {
      i += 1
      continue
    }
    if ((ch >= '0' && ch <= '9') || ch === '.') {
      let j = i
      let dots = 0
      while (j < input.length && ((input[j] >= '0' && input[j] <= '9') || input[j] === '.')) {
        if (input[j] === '.') dots += 1
        j += 1
      }
      const numStr = input.slice(i, j)
      if (dots > 1) return null
      const v = Number(numStr)
      if (!Number.isFinite(v)) return null
      toks.push({ t: 'num', v })
      i = j
      continue
    }
    if (ch === '*' && input[i + 1] === '*') {
      toks.push({ t: 'op', v: '**' })
      i += 2
      continue
    }
    if ('+-*/%()'.includes(ch)) {
      toks.push({ t: 'op', v: ch })
      i += 1
      continue
    }
    return null // illegal character
  }
  return toks
}

/** Returns the result, or null when the expression is invalid/unsafe. */
function evalMath(expr: string): number | null {
  if (expr.length === 0 || expr.length > 200) return null
  const parsed = tokenizeMath(expr)
  if (!parsed || parsed.length === 0) return null
  const toks: MathTok[] = parsed

  let pos = 0
  const eatOp = (v: string): boolean => {
    const t = toks[pos]
    if (t && t.t === 'op' && t.v === v) {
      pos += 1
      return true
    }
    return false
  }

  function parseExpr(): number | null {
    let left = parseTerm()
    if (left === null) return null
    for (;;) {
      if (eatOp('+')) {
        const r = parseTerm()
        if (r === null) return null
        left += r
      } else if (eatOp('-')) {
        const r = parseTerm()
        if (r === null) return null
        left -= r
      } else {
        return left
      }
    }
  }

  function parseTerm(): number | null {
    let left = parseUnary()
    if (left === null) return null
    for (;;) {
      if (eatOp('*')) {
        const r = parseUnary()
        if (r === null) return null
        left *= r
      } else if (eatOp('/')) {
        const r = parseUnary()
        if (r === null) return null
        if (r === 0) return null
        left /= r
      } else if (eatOp('%')) {
        const r = parseUnary()
        if (r === null) return null
        if (r === 0) return null
        left %= r
      } else {
        return left
      }
    }
  }

  function parseUnary(): number | null {
    if (eatOp('-')) {
      const v = parseUnary()
      return v === null ? null : -v
    }
    if (eatOp('+')) return parseUnary()
    return parsePower()
  }

  function parsePower(): number | null {
    const base = parseAtom()
    if (base === null) return null
    if (eatOp('**')) {
      const exp = parseUnary()
      if (exp === null) return null
      const v = Math.pow(base, exp)
      return Number.isFinite(v) ? v : null
    }
    return base
  }

  function parseAtom(): number | null {
    const t = toks[pos]
    if (!t) return null
    if (t.t === 'num') {
      pos += 1
      return t.v
    }
    if (t.t === 'op' && t.v === '(') {
      pos += 1
      const v = parseExpr()
      if (v === null) return null
      if (!eatOp(')')) return null
      return v
    }
    return null
  }

  const result = parseExpr()
  if (result === null || pos !== toks.length || !Number.isFinite(result)) return null
  return result
}

function formatMathResult(v: number): string {
  return Number.isInteger(v) ? String(v) : String(Number(v.toPrecision(12)))
}

// ── Bot message insertion (mirrors the real send pipeline) ────

interface BotMessageData {
  content: string
  payload?: string
  poll?: { question: string; options: string[] }
}

async function insertBotMessage(
  conversationId: string,
  botId: string,
  data: BotMessageData,
): Promise<void> {
  const now = new Date()
  const conv = await db.conversation.findUnique({
    where: { id: conversationId },
    select: { ttlSeconds: true },
  })
  const expiresAt =
    conv && conv.ttlSeconds > 0 ? new Date(now.getTime() + conv.ttlSeconds * 1000) : null

  const created = await db.$transaction(async (tx) => {
    const msg = await tx.message.create({
      data: {
        conversationId,
        senderId: botId,
        content: data.content.slice(0, MESSAGE_MAX),
        kind: 'text',
        ...(data.payload ? { payload: data.payload } : {}),
        ...(expiresAt ? { expiresAt } : {}),
        ...(data.poll
          ? {
              poll: {
                create: {
                  question: data.poll.question,
                  options: {
                    createMany: {
                      data: data.poll.options.map((text, position) => ({ text, position })),
                    },
                  },
                },
              },
            }
          : {}),
      },
      include: MESSAGE_FULL_INCLUDE,
    })
    // Bump list ordering (@updatedAt) + pull the chat out of other members' archives.
    await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
    await tx.conversationParticipant.updateMany({
      where: { conversationId, userId: { not: botId }, archivedAt: { not: null } },
      data: { archivedAt: null },
    })
    return msg
  })

  const recipients = (await memberIdsOf(conversationId)).filter((id) => id !== botId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapMessage(created),
    recipientIds: recipients,
    conversationId,
  })
}

// ── Command handlers (pure text builders) ─────────────────────

function buildRoll(callerHandle: string, arg: string): string | null {
  if (arg.length === 0) {
    const n = 1 + Math.floor(Math.random() * 100)
    return `🎲 ${callerHandle} rolled ${n} (1-100)`
  }
  if (!/^\d+$/.test(arg)) {
    return `Give me a whole number from 1 to ${ROLL_MAX}, like /roll 20.`
  }
  const max = Number.parseInt(arg, 10)
  if (max < 1 || max > ROLL_MAX) {
    return `Give me a whole number from 1 to ${ROLL_MAX}, like /roll 20.`
  }
  const n = 1 + Math.floor(Math.random() * max)
  return `🎲 ${callerHandle} rolled ${n} (1-${max})`
}

function buildRps(arg: string): string | null {
  const RPS_EMOJI = { rock: '🪨', paper: '📄', scissors: '✂️' } as const
  type Rps = keyof typeof RPS_EMOJI
  const ALIASES: Record<string, Rps> = {
    rock: 'rock',
    r: 'rock',
    paper: 'paper',
    p: 'paper',
    scissors: 'scissors',
    scissor: 'scissors',
    s: 'scissors',
  }
  const BEATS: Record<Rps, Rps> = { rock: 'scissors', paper: 'rock', scissors: 'paper' }
  const pick = ALIASES[arg.toLowerCase()]
  if (!pick) return 'Play with /rps rock, /rps paper or /rps scissors.'
  const botPick = (['rock', 'paper', 'scissors'] as Rps[])[Math.floor(Math.random() * 3)]
  const mine = `You: ${RPS_EMOJI[pick]} ${pick}`
  const bots = `Me: ${RPS_EMOJI[botPick]} ${botPick}`
  if (pick === botPick) return `${mine} — ${bots} → It's a draw 🤝`
  if (BEATS[pick] === botPick) return `${mine} — ${bots} → You win! 🎉`
  return `${mine} — ${bots} → I win! 🤖`
}

// ── Main entry point ─────────────────────────────────────────

/**
 * Evaluate a freshly-created user message for bot commands and answer
 * with real Message rows. Awaits are cheap; every failure is swallowed
 * so message sending can never break because of the bot.
 */
export async function maybeBotReply(
  conversationId: string,
  userMessage: { id: string; senderId: string; content: string },
): Promise<void> {
  try {
    const raw = typeof userMessage?.content === 'string' ? userMessage.content.trim() : ''
    if (!raw) return
    const trigger = parseTrigger(raw)
    if (!trigger) return
    // Unknown bare slash commands stay silent (the composer's client-side
    // slash palette owns those — /effects, /topic …). Handle mentions get
    // a deterministic pointer to /help.
    if (trigger.cmd === null && !trigger.viaHandle) return
    if (!claimOnce(userMessage.id)) return

    // The bot must be a real participant of THIS conversation.
    const botMember = await db.conversationParticipant.findFirst({
      where: { conversationId, user: { username: BOT_USERNAME } },
      select: { userId: true, role: true },
    })
    if (!botMember) return
    const botId = botMember.userId
    if (userMessage.senderId === botId) return

    const caller = await db.user.findUnique({
      where: { id: userMessage.senderId },
      select: { id: true, name: true, username: true },
    })
    if (!caller) return
    const callerHandle = caller.username ? `@${caller.username}` : caller.name
    const send = (content: string) =>
      insertBotMessage(conversationId, botId, { content, payload: BOT_PAYLOAD })

    switch (trigger.cmd) {
      case 'help':
        await send(HELP_TEXT)
        return

      case 'roll': {
        const reply = buildRoll(callerHandle, trigger.arg)
        if (reply) await send(reply)
        return
      }

      case 'flip':
        await send(`🪙 ${Math.random() < 0.5 ? 'Heads' : 'Tails'}`)
        return

      case '8ball': {
        if (trigger.arg.length === 0) {
          await send('Ask me something: /8ball will it work?')
          return
        }
        const answer = EIGHT_BALL_ANSWERS[Math.floor(Math.random() * EIGHT_BALL_ANSWERS.length)]
        await send(`🔮 “${trigger.arg}” — ${answer}`)
        return
      }

      case 'math': {
        const result = evalMath(trigger.arg)
        await send(result === null ? 'Invalid expression' : `🧮 ${trigger.arg} = ${formatMathResult(result)}`)
        return
      }

      case 'rps': {
        const reply = buildRps(trigger.arg)
        if (reply) await send(reply)
        return
      }

      case 'dice': {
        const a = 1 + Math.floor(Math.random() * 6)
        const b = 1 + Math.floor(Math.random() * 6)
        await send(`🎲 2d6 → [${a} + ${b}] = ${a + b}`)
        return
      }

      case 'time': {
        const formatted = new Intl.DateTimeFormat('en-US', {
          timeZone: 'UTC',
          weekday: 'short',
          year: 'numeric',
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
          hourCycle: 'h23',
        }).format(new Date())
        await send(`🕒 ${formatted} (UTC)`)
        return
      }

      case 'wallet': {
        // Real balances for the CALLER — zeros when they never checked in.
        const wallet = await db.userWallet.upsert({
          where: { userId: caller.id },
          create: { userId: caller.id },
          update: {},
        })
        await send(
          `💼 ${callerHandle} — your Pulse wallet\nPC ${wallet.coins} · GEM ${wallet.gems}\nCheck-in streak: ${wallet.streak} day(s)`,
        )
        return
      }

      case 'poll': {
        const parts = trigger.arg
          .split('|')
          .map((p) => p.trim())
          .filter((p) => p.length > 0)
        const question = (parts[0] ?? '').slice(0, POLL_QUESTION_MAX)
        const options = parts.slice(1, 7).map((p) => p.slice(0, POLL_OPTION_MAX))
        if (question.length === 0 || options.length < 2) {
          await send(POLL_USAGE)
          return
        }
        // Announcement mode gates the bot like any other poster.
        const conv = await db.conversation.findUnique({
          where: { id: conversationId },
          select: { isGroup: true, broadcastMode: true },
        })
        if (conv?.isGroup && conv.broadcastMode && botMember.role !== 'admin') {
          await send("I can't post a poll while announcement mode is on.")
          return
        }
        // Real Poll + PollOptions attached to a bot-authored message —
        // the exact creation pattern used by POST /api/conversations/[id]/poll.
        await insertBotMessage(conversationId, botId, {
          content: '',
          payload: BOT_PAYLOAD,
          poll: { question, options },
        })
        return
      }

      default:
        // Addressed by handle without a command → friendly pointer.
        await send(GREETING_TEXT)
        return
    }
  } catch (error) {
    console.error(
      '[bot-engine] reply failed:',
      error instanceof Error ? error.message : error,
    )
  }
}
