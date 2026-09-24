// GET /api/group-call-state?conversationId=<id>
// Proxy to the socket service's group-call probe so clients can learn about
// an ONGOING group call without a live ring (late room open, page reload).
// Socket down / no call → { members: [] } (honest empty, not an error).
import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

const SOCKET_URL = 'http://localhost:3003'

export async function GET(req: Request) {
  const conversationId = (new URL(req.url).searchParams.get('conversationId') ?? '').trim().slice(0, 128)
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }
  try {
    const res = await fetch(`${SOCKET_URL}/gcall?conversationId=${encodeURIComponent(conversationId)}`, {
      signal: AbortSignal.timeout(2000),
    })
    if (!res.ok) {
      return NextResponse.json({ members: [], kind: 'voice' })
    }
    const body = (await res.json()) as {
      state?: { members?: unknown; kind?: unknown; callId?: unknown; startedAt?: unknown } | null
    }
    const state = body.state ?? null
    if (!state || !Array.isArray(state.members)) {
      return NextResponse.json({ members: [], kind: 'voice' })
    }
    return NextResponse.json({
      callId: typeof state.callId === 'string' ? state.callId : null,
      kind: state.kind === 'video' ? 'video' : 'voice',
      startedAt: typeof state.startedAt === 'number' ? state.startedAt : null,
      members: state.members,
    })
  } catch {
    return NextResponse.json({ members: [], kind: 'voice' })
  }
}
