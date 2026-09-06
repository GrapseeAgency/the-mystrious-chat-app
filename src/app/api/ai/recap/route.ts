// ─────────────────────────────────────────────────────────────
// /api/ai/recap — Zoom AI-Companion-style conversation recap.
// POST { userId, conversationId } → loads the last ~30 messages
// (server-side, soft-deletes respected), asks the Z-AI LLM for a
// ≤5-bullet plain-English summary (decisions / questions / action
// items) and returns { recap, basedOn, cached }.
// Zero mocks: a missing/failed LLM is an honest 502, never fake text.
// Results are cached in-memory per conversation for ~5 minutes and
// keyed on the newest message id + count, so a fresh message yields
// a fresh recap while repeat taps are instant.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import ZAI from 'z-ai-web-dev-sdk'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Messages fed to the model (newest window). */
const RECAP_WINDOW = 30
/** Minimum real (non-deleted) messages a conversation needs to be recapable. */
const RECAP_MIN_MESSAGES = 5
/** In-memory cache lifetime. */
const CACHE_TTL_MS = 5 * 60 * 1000

interface CacheEntry {
  recap: string
  basedOn: number
  /** cache key the entry was computed for (conversation : newestMessageId : count) */
  key: string
  at: number
}

/** conversationId → latest cached recap. Server memory only, resets on restart. */
const recapCache = new Map<string, CacheEntry>()

const RECAP_SYSTEM_PROMPT = [
  'You are the AI recap companion inside the Pulse mobile chat app.',
  'Summarize the conversation transcript you receive in AT MOST 5 short bullet lines.',
  'Focus on decisions made, open questions, and action items (who does what).',
  'If there are none, summarize what the conversation was about.',
  'Plain English, one line per bullet, each line starting with "• ".',
  'Never use emojis. Never use markdown headings, bold or code blocks. Never invent facts.',
].join(' ')

/** Defensive sweep: the recap contract is emoji-free even if the model drifts. */
function stripEmoji(text: string): string {
  return text
    .replace(
      /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{1F1E6}-\u{1F1FF}\u{FE0F}\u{2190}-\u{21FF}]/gu,
      '',
    )
    .replace(/[ \t]{2,}/g, ' ')
}

/** Normalize model output to the "• " bullet contract, capped at 5 lines. */
function normalizeRecap(raw: string): string {
  const lines = raw
    .split('\n')
    .map((line) => line.replace(/^\s*(?:[-*•·]|\d+[.)])\s*/, '• ').trim())
    .filter((line) => line.length > 1)
  return lines.slice(0, 5).join('\n')
}

/** Human-readable transcript line for one message row. */
function transcriptLine(row: {
  content: string
  kind: string
  imagePath: string | null
  audioPath: string | null
  senderName: string
}): string {
  const what =
    row.content.trim().length > 0
      ? row.content.trim().slice(0, 300)
      : row.kind === 'image' || row.imagePath
        ? '[sent a photo]'
        : row.kind === 'audio' || row.audioPath
          ? '[sent a voice note]'
          : `[sent ${row.kind === 'text' ? 'a message' : row.kind}]`
  return `${row.senderName}: ${what}`
}

/**
 * POST /api/ai/recap  body { userId, conversationId }
 * → 200 { recap: string, basedOn: number, cached: boolean }
 * 400 missing fields · 404 unknown conversation · 403 non-participant
 * 409 fewer than 5 messages · 502 LLM unavailable (honest error path).
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  if (!userId || !conversationId) {
    return NextResponse.json(
      { error: 'userId and conversationId are required.' },
      { status: 400 },
    )
  }

  const conversation = await db.conversation.findUnique({
    where: { id: conversationId },
    select: { id: true },
  })
  if (!conversation) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  // Recency + volume facts drive both the gate and the cache key.
  const [totalCount, newest] = await Promise.all([
    db.message.count({ where: { conversationId, deletedAt: null } }),
    db.message.findFirst({
      where: { conversationId, deletedAt: null },
      orderBy: { createdAt: 'desc' },
      select: { id: true },
    }),
  ])
  if (totalCount < RECAP_MIN_MESSAGES) {
    return NextResponse.json(
      { error: `Recap needs at least ${RECAP_MIN_MESSAGES} messages in this chat.` },
      { status: 409 },
    )
  }

  const cacheKey = `${conversationId}:${newest?.id ?? 'none'}:${totalCount}`
  const cached = recapCache.get(conversationId)
  if (cached && cached.key === cacheKey && Date.now() - cached.at < CACHE_TTL_MS) {
    return NextResponse.json({ recap: cached.recap, basedOn: cached.basedOn, cached: true })
  }

  const recent = await db.message.findMany({
    where: { conversationId, deletedAt: null },
    orderBy: { createdAt: 'desc' },
    take: RECAP_WINDOW,
    select: {
      content: true,
      kind: true,
      imagePath: true,
      audioPath: true,
      sender: { select: { name: true } },
    },
  })
  recent.reverse()

  const transcript = recent
    .map((m) => transcriptLine({ ...m, senderName: m.sender.name }))
    .join('\n')

  let recap = ''
  try {
    const zai = await ZAI.create()
    const completion = await zai.chat.completions.create({
      messages: [
        { role: 'assistant', content: RECAP_SYSTEM_PROMPT },
        { role: 'user', content: `Conversation transcript (oldest first):\n${transcript}` },
      ],
      thinking: { type: 'disabled' },
    })
    recap = normalizeRecap(stripEmoji(completion.choices[0]?.message?.content ?? ''))
  } catch (error) {
    console.error('[ai-recap] llm failed:', error instanceof Error ? error.message : error)
    return NextResponse.json(
      { error: 'The AI service could not summarize this chat right now.' },
      { status: 502 },
    )
  }

  if (recap.length === 0) {
    return NextResponse.json(
      { error: 'The AI service returned an empty recap. Try again in a moment.' },
      { status: 502 },
    )
  }

  // Bounded map — one entry per conversation is plenty at app scale.
  if (recapCache.size > 256) {
    for (const [key, entry] of recapCache) {
      if (Date.now() - entry.at >= CACHE_TTL_MS) recapCache.delete(key)
    }
  }
  recapCache.set(conversationId, { recap, basedOn: recent.length, key: cacheKey, at: Date.now() })

  return NextResponse.json({ recap, basedOn: recent.length, cached: false })
}
