import type { PrismaClient } from '../../prisma/generated-client'

/** Room membership is independent of being registered on the signaling socket. */
export async function canJoinConversationCall(db: PrismaClient, conversationId: string, userId: string): Promise<boolean> {
  return (await db.conversation.findFirst({
    where: { id: conversationId, participants: { some: { userId } } }, select: { id: true },
  })) !== null
}

export interface GroupCallProbeSnapshot {
  callId: string
  kind: 'voice' | 'video'
  startedAt: number | null
  members: Array<{ id: string; name: string; color: string; avatar: string | null }>
}

/** null means an invalid relay response, not an empty call. */
export function readGroupCallProbe(raw: unknown, conversationId: string): GroupCallProbeSnapshot | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const body = raw as Record<string, unknown>
  if (body.ok !== true) return null
  const empty: GroupCallProbeSnapshot = { callId: '', kind: 'voice', startedAt: null, members: [] }
  if (body.state === null) return empty
  if (!body.state || typeof body.state !== 'object' || Array.isArray(body.state)) return null
  const state = body.state as Record<string, unknown>
  if (state.conversationId !== conversationId || typeof state.callId !== 'string' || !state.callId.trim() ||
      (state.kind !== 'voice' && state.kind !== 'video') || typeof state.startedAt !== 'number' ||
      !Number.isSafeInteger(state.startedAt) || state.startedAt < 0 || !Array.isArray(state.members) || state.members.length > 8) return null
  const members: GroupCallProbeSnapshot['members'] = [], ids = new Set<string>()
  for (const item of state.members) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return null
    const member = item as Record<string, unknown>
    if (typeof member.id !== 'string' || !member.id.trim() || ids.has(member.id) ||
        typeof member.name !== 'string' || typeof member.color !== 'string' ||
        (member.avatar !== null && typeof member.avatar !== 'string')) return null
    ids.add(member.id)
    members.push({ id: member.id, name: member.name, color: member.color, avatar: member.avatar })
  }
  return members.length ? { callId: state.callId, kind: state.kind, startedAt: state.startedAt, members } : empty
}
