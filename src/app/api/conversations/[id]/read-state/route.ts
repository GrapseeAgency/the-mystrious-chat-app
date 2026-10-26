// Native read acceptance distinguishes actual visibility from deliberate mark-all.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { unreadSnapshot, threadRootAvailable } from '@/lib/message-visibility'
import { memberIdsOf, notifySocket, safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { advanceReadReceipt, ReadReceiptError, receiptRecipients } from '@/lib/read-receipt'

export const dynamic = 'force-dynamic'
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req), userId = strField(body.userId)
  const mode = body.mode
  const readIntent = typeof body.readIntent === 'string' && body.readIntent.length <= 128 ? body.readIntent.trim() : ''
  const messageId = typeof body.messageId === 'string' ? body.messageId.trim() : null
  if (!readIntent || !userId || (mode !== 'visible' && mode !== 'all') ||
      (mode === 'visible' && !messageId) || (mode === 'all' && body.messageId != null)) {
    return NextResponse.json({ error: 'Specify visible with a messageId, or an explicit all read action.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'read receipt')
  if (denied) return denied
  try {
    const at = await advanceReadReceipt(db, id, userId, mode === 'visible' ? messageId! : undefined, readIntent)
    const owner = await db.user.findUnique({ where: { id: userId }, select: { preferences: true } })
    const recipients = receiptRecipients(userId, await memberIdsOf(id), owner?.preferences)
    await notifySocket('message:read', recipients, { conversationId: id, userId, lastReadAt: at.toISOString(), readerMembershipId: readIntent.split('.').at(-1), messageIds: messageId ? [messageId] : [], all: mode === 'all' })
    return NextResponse.json({ ok: true, readReceiptVersion: 3, readIntent, conversationId: id, userId, mode,
      messageId: mode === 'visible' ? messageId : null, lastReadAt: at.toISOString() }, { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

/** Independent unread projection for a room, canonical thread, or topic. */
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params, query = new URL(req.url).searchParams
  const userId = strField(query.get('userId'))
  const threadId = query.get('threadId'), topicId = query.get('topicId')
  if (!userId || (threadId !== null && (!threadId.trim() || threadId.length > 128)) ||
      (topicId !== null && (!topicId.trim() || topicId.length > 128)) || (threadId !== null && topicId !== null))
    return NextResponse.json({ error: 'Choose one read scope.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'read state')
  if (denied) return denied
  try {
    const snapshot = await db.$transaction(async tx => {
      const member = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId, conversationId: id } },
        select: { id: true, lastReadAt: true, manualUnread: true },
      })
      if (!member) throw new ReadReceiptError(403, 'You are not a participant of this conversation.')
      const now = new Date()
      if (threadId !== null) {
        const root = await tx.message.findUnique({ where: { id: threadId },
          select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } })
        if (!threadRootAvailable(root, id, now)) throw new ReadReceiptError(410, 'This thread is no longer available.')
      }
      if (topicId !== null && !await tx.topic.findFirst({ where: { id: topicId, conversationId: id }, select: { id: true } }))
        throw new ReadReceiptError(404, 'Topic not found in this conversation.')
      return { ...await unreadSnapshot(tx, id, userId, member.lastReadAt, now,
        { threadId: threadId ?? undefined, topicId: topicId ?? undefined }), manualUnread: member.manualUnread }
    }, { isolationLevel: 'Serializable' })
    return NextResponse.json({ readStateVersion: 1, conversationId: id, userId, threadId, topicId, ...snapshot },
      { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}
