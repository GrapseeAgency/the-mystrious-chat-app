// ─────────────────────────────────────────────────────────────
// /api/conversations/[id] — conversation detail (meta + members)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { buildConversationDetail, CONVERSATION_FULL_INCLUDE } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/conversations/[id]?userId=X
 * → { conversation: ConversationDetail } | 404
 * userId (the viewer) is required by contract; members carry lastReadAt.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({
    where: { id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  return NextResponse.json({ conversation: buildConversationDetail(conv) })
}
