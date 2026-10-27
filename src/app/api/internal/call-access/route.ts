import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { canJoinConversationCall } from '@/lib/group-call-access'

export const dynamic = 'force-dynamic'

/** Internal membership verdict only; the relay binds this to its registered socket user. */
export async function GET(req: Request) {
  const secret = process.env.CRON_SECRET
  if (!secret || req.headers.get('x-pulse-key') !== secret) {
    return NextResponse.json({ error: 'Unauthorized.' }, { status: 401 })
  }
  const url = new URL(req.url)
  const conversationId = (url.searchParams.get('conversationId') ?? '').trim()
  const userId = (url.searchParams.get('userId') ?? '').trim()
  if (!userId || userId.length > 64 || !conversationId || conversationId.length > 128) {
    return NextResponse.json({ error: 'Valid conversationId and userId are required.' }, { status: 400 })
  }
  const allowed = await canJoinConversationCall(db, conversationId, userId)
  return NextResponse.json({ callAccessVersion: 1, conversationId, userId, allowed })
}
