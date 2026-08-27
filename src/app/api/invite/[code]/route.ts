// ─────────────────────────────────────────────────────────────
// /api/invite/[code] — public preview of a group invite code
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { normalizeInviteCode } from '@/lib/serializers'
import type { InvitePreview } from '@/lib/types'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ code: string }>
}

/**
 * GET /api/invite/[code]?userId=X
 * → 200 { invite: InvitePreview } | 404 unknown code
 * Safe to hit before login/creation — leaks nothing beyond the group
 * name and member count. userId is optional and only feeds alreadyMember.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { code: raw } = await params
  const code = normalizeInviteCode(raw)
  if (code.length === 0) {
    return NextResponse.json({ error: 'Invite code is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({
    where: { inviteCode: code },
    include: { participants: { select: { userId: true } } },
  })
  if (!conv || !conv.isGroup) {
    return NextResponse.json({ error: 'This invite link is no longer valid.' }, { status: 404 })
  }

  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')

  const invite: InvitePreview = {
    code,
    conversationId: conv.id,
    isGroup: conv.isGroup,
    name: conv.name,
    memberCount: conv.participants.length,
    alreadyMember: userId ? conv.participants.some((p) => p.userId === userId) : false,
  }
  return NextResponse.json({ invite })
}
