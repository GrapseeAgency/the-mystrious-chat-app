// Viewer-scoped ongoing-call probe. Authority/relay failure is not proof that a call ended.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { canJoinConversationCall, readGroupCallProbe } from '@/lib/group-call-access'

export const dynamic = 'force-dynamic'
const SOCKET_URL = 'http://localhost:3003'
const reply = (body: unknown, status = 200) => NextResponse.json(body, { status, headers: { 'Cache-Control': 'private, no-store' } })

export async function GET(req: Request) {
  const url = new URL(req.url)
  const conversationId = (url.searchParams.get('conversationId') ?? '').trim()
  const userId = (url.searchParams.get('userId') ?? '').trim()
  if (!conversationId || conversationId.length > 128 || !userId || userId.length > 64) {
    return reply({ error: 'Valid conversationId and userId are required.' }, 400)
  }
  // Preserve the existing migration policy, but never accept another owner's presented credential.
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'call state')
    if (denied) return denied
  }
  if (!await canJoinConversationCall(db, conversationId, userId)) return reply({ error: 'Conversation is unavailable.' }, 403)
  const secret = process.env.CRON_SECRET
  if (!secret) return reply({ error: 'Call state is unavailable.' }, 503)
  try {
    const response = await fetch(`${SOCKET_URL}/gcall?conversationId=${encodeURIComponent(conversationId)}`, {
      headers: { 'x-pulse-key': secret }, signal: AbortSignal.timeout(2000), cache: 'no-store',
    })
    if (!response.ok) return reply({ error: 'Call state is unavailable.' }, 503)
    const snapshot = readGroupCallProbe(await response.json(), conversationId)
    if (!snapshot) return reply({ error: 'Call state could not be verified.' }, 503)
    // A leave/removal while the relay request was in flight must not disclose its roster.
    if (!await canJoinConversationCall(db, conversationId, userId)) return reply({ error: 'Conversation is unavailable.' }, 403)
    return reply({ callStateVersion: 1, conversationId, userId, ...snapshot })
  } catch {
    return reply({ error: 'Call state is unavailable.' }, 503)
  }
}
