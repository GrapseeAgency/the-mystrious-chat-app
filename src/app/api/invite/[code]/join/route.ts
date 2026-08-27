// ─────────────────────────────────────────────────────────────
// /api/invite/[code]/join — redeem an invite code (join group)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { notifySocket, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ code: string }>
}

const CODE_RE = /^[A-Z0-9]{4,16}$/

/**
 * POST /api/invite/[code]/join  body { userId }
 * → 200 { conversationId, alreadyMember } | 404 unknown/invalid code
 * Idempotent: joining twice returns the conversation with alreadyMember:true.
 * New joiner lands as role:"member" with lastReadAt=now (fresh unread state).
 * Relays conversation:updated to ALL participants (incl. the joiner) so every
 * open client refreshes its lists immediately.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { code: raw } = await params
  const code = raw.toUpperCase()
  if (!CODE_RE.test(code)) {
    return NextResponse.json({ error: 'Invalid invite code.' }, { status: 400 })
  }

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({
    where: { inviteCode: code },
    include: { participants: { select: { userId: true } } },
  })
  if (!conv || !conv.isGroup) {
    return NextResponse.json({ error: 'This invite link is no longer valid.' }, { status: 404 })
  }

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'Unknown user.' }, { status: 404 })
  }

  const alreadyMember = conv.participants.some((p) => p.userId === userId)
  if (!alreadyMember) {
    await db.conversationParticipant.create({
      data: { userId, conversationId: conv.id, role: 'member', lastReadAt: new Date() },
    })
    // bump the group so it sorts to the top of everyone's list
    await db.conversation.update({ where: { id: conv.id }, data: { updatedAt: new Date() } })
  }

  const recipients = Array.from(new Set([...conv.participants.map((p) => p.userId), userId]))
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: conv.id,
    recipientIds: recipients,
  })

  return NextResponse.json({ conversationId: conv.id, alreadyMember })
}
