import type { PrismaClient } from '../../prisma/generated-client'

export class ConversationPersonalSettingsError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
const MUTE_MS = { '8h': 8 * 60 * 60 * 1000, '1w': 7 * 24 * 60 * 60 * 1000, always: 50 * 365 * 24 * 60 * 60 * 1000 }
type PersonalPatch = { mutedUntil?: Date | null; archivedAt?: Date | null }

async function updatePersonal<T>(db: PrismaClient, room: string, owner: string,
  resolve: (current: { archivedAt: Date | null }) => { data: PersonalPatch; receipt: T }) {
  try {
    return await db.$transaction(async tx => {
      if (!await tx.conversation.findUnique({ where: { id: room }, select: { id: true } }))
        throw new ConversationPersonalSettingsError(404, 'Conversation not found.')
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } }, select: { id: true, archivedAt: true },
      })
      if (!participant) throw new ConversationPersonalSettingsError(403, 'You are not a participant of this conversation.')
      const result = resolve(participant)
      const changed = await tx.conversationParticipant.updateMany({
        where: { id: participant.id, userId: owner, conversationId: room }, data: result.data,
      })
      if (changed.count !== 1) throw new ConversationPersonalSettingsError(403, 'Conversation membership changed.')
      return { ok: true, conversationPreferenceVersion: 1, conversationId: room, userId: owner, ...result.receipt }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      throw new ConversationPersonalSettingsError(409, 'The conversation setting is busy. Refresh before retrying.')
    throw error
  }
}

export async function setConversationMute(db: PrismaClient, room: string, owner: string, value: unknown) {
  const preset = value === null || value === 'off' ? null : typeof value === 'string' ? value.trim() : undefined
  if (preset === undefined || (preset !== null && !Object.hasOwn(MUTE_MS, preset)))
    throw new ConversationPersonalSettingsError(400, "until must be '8h', '1w', 'always', 'off' or explicit null.")
  return updatePersonal(db, room, owner, () => {
    const mutedUntil = preset === null ? null : new Date(Date.now() + MUTE_MS[preset as keyof typeof MUTE_MS])
    return { data: { mutedUntil }, receipt: { mutePreset: preset, mutedUntil: mutedUntil?.toISOString() ?? null } }
  })
}
export async function setConversationArchive(db: PrismaClient, room: string, owner: string, archived: unknown) {
  if (typeof archived !== 'boolean') throw new ConversationPersonalSettingsError(400, 'archived must be a boolean.')
  return updatePersonal(db, room, owner, current => {
    const archivedAt = archived ? current.archivedAt ?? new Date() : null
    return { data: { archivedAt }, receipt: { archived, archivedAt: archivedAt?.toISOString() ?? null } }
  })
}
