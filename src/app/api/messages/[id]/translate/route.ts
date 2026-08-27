// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/translate — on-demand LLM translation
// (Webex-style caption translation; result persisted per language)
// ─────────────────────────────────────────────────────────────
import ZAI from 'z-ai-web-dev-sdk'
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  safeJson,
  strField,
  MESSAGE_FULL_INCLUDE,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Supported target languages (v1). */
const LANGS: Record<string, string> = {
  en: 'English',
  es: 'Spanish',
  fr: 'French',
  de: 'German',
  hi: 'Hindi',
  bn: 'Bengali',
  ar: 'Arabic',
  zh: 'Simplified Chinese',
}

/**
 * POST /api/messages/[id]/translate  body { userId, lang? } (default "en")
 * → 200 { message: ChatMessage } · relays translation:added to other members.
 * Cached: a second request for the same language returns instantly.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const lang = strField(body.lang) in LANGS ? strField(body.lang) : 'en'

  const message = await db.message.findUnique({
    where: { id },
    select: { conversationId: true, content: true, deletedAt: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.deletedAt) {
    return NextResponse.json({ error: 'Deleted messages cannot be translated.' }, { status: 400 })
  }
  const source = message.content.trim()
  if (source.length === 0) {
    return NextResponse.json({ error: 'Only text messages can be translated.' }, { status: 400 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: message.conversationId } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  // Cache hit → no LLM call.
  const cached = await db.messageTranslation.findUnique({
    where: { messageId_lang: { messageId: id, lang } },
    select: { text: true },
  })

  if (!cached) {
    try {
      const zai = await ZAI.create()
      const completion = await zai.chat.completions.create({
        messages: [
          {
            role: 'assistant',
            content:
              `You are a precise chat-message translator. Translate the user's message into ${LANGS[lang]}. ` +
              'Reply with ONLY the translation — no quotes, no explanations, no language name. ' +
              'Preserve emoji and casual tone.',
          },
          { role: 'user', content: source.slice(0, 1200) },
        ],
        thinking: { type: 'disabled' },
      })
      const translated = (completion.choices[0]?.message?.content ?? '').trim()
      if (translated.length === 0) {
        return NextResponse.json({ error: 'Translation service returned nothing.' }, { status: 502 })
      }
      await db.messageTranslation.upsert({
        where: { messageId_lang: { messageId: id, lang } },
        create: { messageId: id, lang, text: translated.slice(0, 4000) },
        update: {},
      })
    } catch (error) {
      console.error('[translate] llm failed:', error instanceof Error ? error.message : error)
      return NextResponse.json({ error: 'Translation is unavailable right now.' }, { status: 502 })
    }
  }

  const fresh = await db.message.findUnique({ where: { id }, include: MESSAGE_FULL_INCLUDE })
  if (!fresh) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }

  const mapped = mapMessage(fresh, userId)
  const recipients = (await memberIdsOf(message.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  await notifySocket('translation:added', recipients, {
    type: 'translation:added',
    message: mapped,
    recipientIds: recipients,
    conversationId: message.conversationId,
  })

  return NextResponse.json({ message: mapped })
}
