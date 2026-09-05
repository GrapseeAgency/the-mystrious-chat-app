import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { MATRIX } from '@/lib/hub-catalog'
import { buildConversationDetail, CONVERSATION_FULL_INCLUDE, safeJson } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ appId: string }>
}

function resolveApp(appId: string) {
  const n = Number(appId)
  if (!Number.isInteger(n)) return null
  return MATRIX.find((a) => a.n === n) ?? null
}

/**
 * GET /api/hub/apps/[appId]/community?userId=<id>
 * Finds the app's community group chat (Conversation.appKey).
 * Membership is EXPLICIT — only callers who POST become participants.
 * → 200 { conversation: ConversationDetail | null, memberCount, joined }
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) {
    return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  }
  const url = new URL(req.url)
  const userId = (url.searchParams.get('userId') || '').trim()

  const existing = await db.conversation.findUnique({
    where: { appKey: appId },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!existing) {
    return NextResponse.json({ conversation: null, memberCount: 0, joined: false })
  }
  const detail = await buildConversationDetail(existing, userId || undefined)
  return NextResponse.json({
    conversation: detail,
    memberCount: detail.members.length,
    joined: Boolean(userId && detail.members.some((m) => m.id === userId)),
  })
}

/**
 * POST /api/hub/apps/[appId]/community  body { userId }
 * Join the app community: auto-provisions the group on first join
 * (founder becomes admin), then adds the participant as member.
 * → 200 { conversation: ConversationDetail, joined: true }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) {
    return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  }
  const body = await safeJson(req)
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const convo = await db.$transaction(async (tx) => {
    const found = await tx.conversation.findUnique({ where: { appKey: appId } })
    if (found) return found
    return tx.conversation.create({
      data: {
        isGroup: true,
        name: `#${String(app.n).padStart(3, '0')} · ${app.name} community`,
        appKey: appId,
        participants: {
          create: { userId, role: 'admin', joinedAt: new Date() },
        },
      },
    })
  })

  await db.conversationParticipant.upsert({
    where: { userId_conversationId: { userId, conversationId: convo.id } },
    create: { userId, conversationId: convo.id, role: 'member', joinedAt: new Date() },
    update: {},
  })

  const fresh = await db.conversation.findUnique({
    where: { id: convo.id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!fresh) {
    return NextResponse.json({ error: 'Conversation vanished.' }, { status: 500 })
  }
  return NextResponse.json({
    conversation: await buildConversationDetail(fresh, userId),
    joined: true,
  })
}
