// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/unfurl — attach a real Open-Graph preview.
// Fetches the live web page (with timeout), caches metadata in the
// LinkPreview table, links it to the message and relays it out.
// ─────────────────────────────────────────────────────────────
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

const FETCH_TIMEOUT_MS = 4500
const MAX_HTML_BYTES = 250_000

/** First http(s) URL inside arbitrary chat text. */
export function extractFirstUrl(text: string): string | null {
  const m = /https?:\/\/[^\s<>"')\]]+/i.exec(text)
  if (m) {
    return m[0].replace(/[.,;:!?)\]]+$/g, '')
  }
  const bare = /(^|[\s])((?:www\.)[^\s<>"')\]]+)/i.exec(text)
  if (bare) {
    return `https://${bare[2].replace(/[.,;:!?)\]]+$/g, '')}`
  }
  return null
}

function metaContent(html: string, keys: string[]): string | null {
  for (const key of keys) {
    const patterns = [
      new RegExp(`<meta[^>]+property=["']${key}["'][^>]*content=["']([^"']*)["']`, 'i'),
      new RegExp(`<meta[^>]+content=["']([^"']*)["'][^>]*property=["']${key}["']`, 'i'),
      new RegExp(`<meta[^>]+name=["']${key}["'][^>]*content=["']([^"']*)["']`, 'i'),
    ]
    for (const re of patterns) {
      const m = re.exec(html)
      if (m && m[1].trim().length > 0) return decodeEntities(m[1].trim())
    }
  }
  return null
}

function decodeEntities(value: string): string {
  return value
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#x27;/gi, "'")
    .replace(/&nbsp;/g, ' ')
}

function tagContent(html: string, tag: string): string | null {
  const m = new RegExp(`<${tag}[^>]*>([\\s\\S]{1,300}?)</${tag}>`, 'i').exec(html)
  if (!m) return null
  const inner = m[1].replace(/<[^>]*>/g, '').trim()
  return inner.length > 0 ? decodeEntities(inner) : null
}

/**
 * POST /api/messages/[id]/unfurl  body { userId, url? }
 * → 200 { message: ChatMessage | null } (null = nothing unfurled).
 * Idempotent + cache-first; safe to call fire-and-forget after sends.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const message = await db.message.findUnique({
    where: { id },
    select: { conversationId: true, content: true, deletedAt: true, linkUrl: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.deletedAt || message.linkUrl !== null) {
    // already consumed or nothing to do — answer with current state
    const current = await db.message.findUnique({ where: { id }, include: MESSAGE_FULL_INCLUDE })
    return NextResponse.json({ message: current ? mapMessage(current, userId) : null })
  }

  const targetRaw = strField(body.url).length > 0 ? strField(body.url) : extractFirstUrl(message.content)
  if (!targetRaw) {
    return NextResponse.json({ message: null })
  }
  let target: URL
  try {
    target = new URL(/^https?:\/\//i.test(targetRaw) ? targetRaw : `https://${targetRaw}`)
  } catch {
    return NextResponse.json({ message: null })
  }
  if (target.protocol !== 'http:' && target.protocol !== 'https:') {
    return NextResponse.json({ message: null })
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

  let html = ''
  try {
    const res = await fetch(target.toString(), {
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; PulseChatLinkBot/1.0; +link-preview)',
        Accept: 'text/html,application/xhtml+xml',
      },
      redirect: 'follow',
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    })
    if (res.ok) {
      const type = res.headers.get('content-type') ?? ''
      if (type.includes('text/html') || type === '') {
        const raw = await res.text()
        html = raw.slice(0, MAX_HTML_BYTES)
      }
    }
  } catch {
    // unreachable / slow host → fall through with empty html
  }

  const title =
    metaContent(html, ['og:title', 'twitter:title']) ??
    tagContent(html, 'title') ??
    target.hostname
  const description = metaContent(html, ['og:description', 'description', 'twitter:description'])
  let imageUrl = metaContent(html, ['og:image', 'og:image:secure_url', 'twitter:image'])
  if (imageUrl) {
    try {
      imageUrl = new URL(imageUrl, target).toString()
    } catch {
      imageUrl = null
    }
  }
  const siteName = metaContent(html, ['og:site_name']) ?? target.hostname

  const preview = await db.linkPreview.upsert({
    where: { url: target.toString() },
    create: {
      url: target.toString(),
      title: title.slice(0, 200),
      description: description ? description.slice(0, 300) : null,
      imageUrl,
      siteName: siteName.slice(0, 100),
    },
    update: {},
  })

  await db.message.update({ where: { id }, data: { linkUrl: preview.url } })

  const fresh = await db.message.findUnique({ where: { id }, include: MESSAGE_FULL_INCLUDE })
  if (!fresh) {
    return NextResponse.json({ message: null })
  }
  const mapped = mapMessage(fresh, userId)

  const recipients = (await memberIdsOf(message.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  await notifySocket('link:preview', recipients, {
    type: 'link:preview',
    message: mapped,
    recipientIds: recipients,
    conversationId: message.conversationId,
  })

  return NextResponse.json({ message: mapped })
}
