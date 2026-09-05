// ─────────────────────────────────────────────────────────────
// /api/channels/[id]/subscribe — join / leave a broadcast channel
// (R30-c, leave succession reworked R33-b). Joining lands a role:"member"
// participant row; an admin may leave only while another admin remains —
// the last admin must promote a successor first.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { notifySocket, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Shared guards → { conv } or a ready NextResponse error. */
async function guardChannel(id: string) {
  const conv = await db.conversation.findUnique({
    where: { id },
    select: { id: true, isGroup: true, broadcastMode: true },
  })
  if (!conv) {
    return { error: NextResponse.json({ error: 'Channel not found.' }, { status: 404 }) }
  }
  if (!conv.isGroup || !conv.broadcastMode) {
    return {
      error: NextResponse.json(
        { error: 'This conversation is not a broadcast channel.' },
        { status: 400 },
      ),
    }
  }
  return { conv }
}

/**
 * POST /api/channels/[id]/subscribe  body { userId }
 * Join the channel as role:"member" (lastReadAt = now → fresh unread state).
 * Already a participant → 200 { already: true, memberCount } (no-op, never
 * downgrades an admin). Relays conversation:updated to all participants.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const guarded = await guardChannel(id)
  if ('error' in guarded) return guarded.error

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'Unknown user.' }, { status: 404 })
  }

  const existing = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true },
  })
  if (existing) {
    const memberCount = await db.conversationParticipant.count({ where: { conversationId: id } })
    return NextResponse.json({ already: true, memberCount })
  }

  await db.$transaction([
    db.conversationParticipant.create({
      data: { userId, conversationId: id, role: 'member', lastReadAt: new Date() },
    }),
    // bump so the channel floats to the top of the joiner's lists
    db.conversation.update({ where: { id }, data: { updatedAt: new Date() } }),
  ])

  const recipients = (
    await db.conversationParticipant.findMany({ where: { conversationId: id }, select: { userId: true } })
  ).map((p) => p.userId)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })

  return NextResponse.json({ already: false, memberCount: recipients.length })
}

/**
 * DELETE /api/channels/[id]/subscribe  body { userId }
 * Leave the channel (participant row removed). R33-b honest succession:
 * an admin may leave ONLY while at least one OTHER admin remains (counted
 * live from participant roles); the LAST admin is blocked with 403 until
 * they promote someone via "Make admin" on the channel info page.
 * Members leave freely.
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const guarded = await guardChannel(id)
  if ('error' in guarded) return guarded.error

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true, role: true },
  })
  if (!participant) {
    return NextResponse.json({ error: 'You are not subscribed to this channel.' }, { status: 404 })
  }
  if (participant.role === 'admin') {
    // Real succession check — leave is fine when another admin stays behind.
    const otherAdmins = await db.conversationParticipant.count({
      where: { conversationId: id, role: 'admin', NOT: { userId } },
    })
    if (otherAdmins === 0) {
      return NextResponse.json(
        {
          error:
            'You are the last admin of this channel — promote another admin ("Make admin" on the channel info) before leaving.',
        },
        { status: 403 },
      )
    }
  }

  await db.conversationParticipant.delete({ where: { id: participant.id } })

  const recipients = (
    await db.conversationParticipant.findMany({ where: { conversationId: id }, select: { userId: true } })
  ).map((p) => p.userId)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })

  return NextResponse.json({ ok: true, memberCount: recipients.length })
}
