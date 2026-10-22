import type { PrismaClient } from '../../prisma/generated-client'
import { normalizeChannelPhoto } from './channel-contract.ts'

export class ConversationMetadataError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
  }
}
export type ConversationMetadataPatch = {
  name?: string
  broadcastMode?: boolean
  photo?: string | null
  screenPrivacy?: boolean
}

/** Reject malformed fields before applying any of a mixed settings request. */
export function conversationMetadataPatch(
  body: Record<string, unknown>,
  nameMax: number,
): ConversationMetadataPatch {
  const patch: ConversationMetadataPatch = {}
  if (body.name !== undefined) {
    if (typeof body.name !== 'string' || !body.name.trim() || body.name.trim().length > nameMax) {
      throw new ConversationMetadataError(400, `Group name must be 1-${nameMax} characters.`)
    }
    patch.name = body.name.trim()
  }
  for (const key of ['broadcast', 'screenPrivacy'] as const) {
    if (body[key] !== undefined && typeof body[key] !== 'boolean') {
      throw new ConversationMetadataError(400, `${key} must be a boolean.`)
    }
  }
  if (typeof body.broadcast === 'boolean') patch.broadcastMode = body.broadcast
  if (typeof body.screenPrivacy === 'boolean') patch.screenPrivacy = body.screenPrivacy
  if (body.photo !== undefined) {
    const photo = normalizeChannelPhoto(body.photo)
    if (photo === undefined)
      throw new ConversationMetadataError(
        400,
        'photo must be an uploaded image path ("/api/uploads/<file>").',
      )
    patch.photo = photo
  }
  if (!Object.keys(patch).length)
    throw new ConversationMetadataError(
      400,
      'Nothing to update - provide name, broadcast, photo and/or screenPrivacy.',
    )
  return patch
}

/** Admin/participant authority is evaluated in the same snapshot as the write. */
export async function patchConversationMetadata(
  db: PrismaClient,
  room: string,
  actor: string,
  patch: ConversationMetadataPatch,
) {
  try {
    return await db.$transaction(
      async (tx) => {
        const conversation = await tx.conversation.findUnique({
          where: { id: room },
          select: { id: true, isGroup: true },
        })
        if (!conversation) throw new ConversationMetadataError(404, 'Conversation not found.')
        const adminAction =
          patch.name !== undefined || patch.broadcastMode !== undefined || patch.photo !== undefined
        if (!conversation.isGroup && adminAction)
          throw new ConversationMetadataError(
            400,
            'Name, photo and announcement mode changes are groups-only.',
          )
        const member = await tx.conversationParticipant.findUnique({
          where: { userId_conversationId: { userId: actor, conversationId: room } },
          select: { role: true },
        })
        if (!member)
          throw new ConversationMetadataError(
            403,
            'You are not a participant of this conversation.',
          )
        if (adminAction && member.role !== 'admin')
          throw new ConversationMetadataError(
            403,
            'Only current group admins can change group name, photo or announcement mode.',
          )
        await tx.conversation.update({ where: { id: room }, data: patch, select: { id: true } })
        return (
          await tx.conversationParticipant.findMany({
            where: { conversationId: room },
            select: { userId: true },
          })
        ).map((row) => row.userId)
      },
      { isolationLevel: 'Serializable' },
    )
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      throw new ConversationMetadataError(
        409,
        'Group settings or membership changed. Refresh before trying again.',
      )
    }
    throw error
  }
}
