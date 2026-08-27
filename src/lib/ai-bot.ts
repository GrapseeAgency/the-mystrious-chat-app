// ─────────────────────────────────────────────────────────────
// Pulse AI bot — server-only LLM companion (Discord-bot parity).
// A REAL user row in the DB ("Pulse AI"), invitable to DMs and
// groups like anyone else. Replies when mentioned in groups, or
// to every message in a direct conversation. Zero canned lines —
// every answer comes from the z-ai-web-dev-sdk chat completion.
// ─────────────────────────────────────────────────────────────
import ZAI from 'z-ai-web-dev-sdk'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  MESSAGE_FULL_INCLUDE,
  MESSAGE_MAX,
  notifySocket,
} from '@/lib/serializers'
import type { ChatMessage } from '@/lib/types'

export const BOT_NAME = 'Pulse AI'

/** Conversations currently generating an answer (prevents reply storms). */
const inFlight = new Set<string>()

/** Find (or lazily create) the bot's real User row → its id. */
export async function ensurePulseBot(): Promise<string> {
  const found = await db.user.findFirst({
    where: { name: BOT_NAME },
    select: { id: true },
  })
  if (found) return found.id
  const created = await db.user.create({
    data: {
      name: BOT_NAME,
      color: 'violet',
      about: 'Your AI companion · DM me or @mention me in groups ✨',
      statusEmoji: '✨',
      statusText: 'happy to help with anything',
    },
    select: { id: true },
  })
  return created.id
}

/** Best-effort typing indicator broadcast for the bot. */
async function emitBotTyping(
  conversationId: string,
  recipients: string[],
  isTyping: boolean,
): Promise<void> {
  if (recipients.length === 0) return
  try {
    await fetch('http://localhost:3003/typing', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        recipients,
        conversationId,
        userId: 'pulse-ai-bot', // deterministic pseudo-id; UI only reads the name
        userName: BOT_NAME,
        isTyping,
      }),
      signal: AbortSignal.timeout(1500),
    })
  } catch {
    // typing is decorative — ignore failures
  }
}

/**
 * Fire-and-forget AI reply evaluation.
 * Trigger rules: bot participates AND (DM conversation OR message mentions "@Pulse AI").
 * Called WITHOUT await from the message POST route; never throws.
 */
export function maybeAiReply(conversationId: string, trigger: ChatMessage): void {
  void runReply(conversationId, trigger).catch((error) => {
    console.error('[ai-bot] reply failed:', error instanceof Error ? error.message : error)
  })
}

async function runReply(conversationId: string, trigger: ChatMessage): Promise<void> {
  const conv = await db.conversation.findUnique({
    where: { id: conversationId },
    select: { id: true, isGroup: true },
  })
  if (!conv || inFlight.has(conversationId)) return

  const botId = await ensurePulseBot()
  const participantIds = (
    await db.conversationParticipant.findMany({
      where: { conversationId },
      select: { userId: true },
    })
  ).map((p) => p.userId)
  if (!participantIds.includes(botId)) return
  if (trigger.senderId === botId) return

  const mentioned = trigger.content.toLowerCase().includes(`@${BOT_NAME.toLowerCase()}`)
  if (conv.isGroup && !mentioned) return // groups: only speak when addressed

  inFlight.add(conversationId)
  try {
    // Recent transcript (top-level messages; names make it readable for the model)
    const recent = await db.message.findMany({
      where: { conversationId, deletedAt: null, parentId: null },
      orderBy: { createdAt: 'desc' },
      take: 16,
      include: { sender: { select: { id: true, name: true } } },
    })
    recent.reverse()
    const transcript = recent
      .map((m) => {
        const who = m.sender.name
        const what =
          m.content.trim().length > 0
            ? m.content.slice(0, 500)
            : m.imagePath
              ? '[sent a photo]'
              : '[sent a voice note]'
        return `${who}: ${what}`
      })
      .join('\n')

    const humanUser = trigger.sender.name
    const system = [
      `You are ${BOT_NAME}, the friendly AI member of the Pulse mobile chat app.`,
      `You are chatting inside a ${conv.isGroup ? 'group conversation' : 'direct one-on-one conversation'}.`,
      'Style: warm, concise (usually under 80 words), natural messenger tone.',
      'Plain text only — no markdown headings or bullet lists; emoji sparingly (max two per answer).',
      'You may use WhatsApp-style formatting: *bold* or _italic_.',
      `If someone addresses you, answer their actual question or request. Never invent facts about people you cannot see.`,
      `The latest message is from ${humanUser}. Respond to that message (and keep earlier context in mind).`,
    ].join(' ')

    const stopTyping = () => emitBotTyping(conversationId, participantIds.filter((id) => id !== botId), false)
    await emitBotTyping(conversationId, participantIds.filter((id) => id !== botId), true)

    let answer = ''
    try {
      const zai = await ZAI.create()
      const completion = await zai.chat.completions.create({
        messages: [
          { role: 'assistant', content: system },
          {
            role: 'user',
            content:
              transcript.length > 0
                ? `Transcript so far:\n${transcript}`
                : `${humanUser} just started this conversation.`,
          },
        ],
        thinking: { type: 'disabled' },
      })
      answer = (completion.choices[0]?.message?.content ?? '').trim()
    } finally {
      await stopTyping()
    }

    if (answer.length === 0) return
    if (answer.length > MESSAGE_MAX) answer = `${answer.slice(0, MESSAGE_MAX - 1)}…`

    const now = new Date()
    const created = await db.message.create({
      data: { conversationId, senderId: botId, content: answer },
      include: MESSAGE_FULL_INCLUDE,
    })
    await db.$transaction([
      db.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } }),
      db.conversationParticipant.updateMany({
        where: { conversationId, userId: { not: botId }, archivedAt: { not: null } },
        data: { archivedAt: null },
      }),
    ])

    const recipients = participantIds.filter((id) => id !== botId)
    await notifySocket('message:new', recipients, {
      type: 'message:new',
      message: mapMessage(created),
      recipientIds: recipients,
      conversationId,
    })
  } finally {
    inFlight.delete(conversationId)
  }
}
