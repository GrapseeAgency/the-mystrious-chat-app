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
export const TTL_PRESETS = [0, 86_400, 604_800, 2_592_000] as const
export const SLOW_MODE_PRESETS = [0, 5, 10, 30, 60, 300] as const

export type ConversationMetadataPatch = {
  ttlSeconds?: number
  slowModeSeconds?: number
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
  if (patch.ttlSeconds !== undefined) requirePreset(patch.ttlSeconds, TTL_PRESETS, 'ttlSeconds')
  if (patch.slowModeSeconds !== undefined)
    requirePreset(patch.slowModeSeconds, SLOW_MODE_PRESETS, 'seconds')
  try {
    return await db.$transaction(
      async (tx) => {
        const conversation = await tx.conversation.findUnique({
          where: { id: room },
          select: { id: true, isGroup: true },
        })
        if (!conversation) throw new ConversationMetadataError(404, 'Conversation not found.')
        const adminAction =
          patch.name !== undefined ||
          patch.broadcastMode !== undefined ||
          patch.photo !== undefined ||
          patch.slowModeSeconds !== undefined
        if (!conversation.isGroup && adminAction)
          throw new ConversationMetadataError(
            400,
            'Name, photo, announcement mode and slow mode changes are groups-only.',
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
            'Only current group admins can change group name, photo, announcement mode or slow mode.',
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
    rethrowMetadataError(error)
  }
}

export function requirePreset(
  value: unknown,
  presets: readonly number[],
  field: string,
): asserts value is number {
  if (typeof value !== 'number' || !Number.isInteger(value) || !presets.includes(value)) {
    throw new ConversationMetadataError(400, `${field} must be one of: ${presets.join(', ')}.`)
  }
}

/** The membership predicate belongs to the personal-setting write itself. */
export async function patchMyScreenPrivacy(
  db: PrismaClient,
  room: string,
  actor: string,
  on: boolean,
) {
  if (typeof on !== 'boolean') throw new ConversationMetadataError(400, 'on must be a boolean.')
  try {
    await db.$transaction(
      async (tx) => {
        const roomExists = await tx.conversation.findUnique({
          where: { id: room },
          select: { id: true },
        })
        if (!roomExists) throw new ConversationMetadataError(404, 'Conversation not found.')
        const updated = await tx.conversationParticipant.updateMany({
          where: { conversationId: room, userId: actor },
          data: { screenPrivacy: on },
        })
        if (updated.count !== 1)
          throw new ConversationMetadataError(
            403,
            'You are not a participant of this conversation.',
          )
      },
      { isolationLevel: 'Serializable' },
    )
  } catch (error) {
    rethrowMetadataError(error)
  }
}

function rethrowMetadataError(error: unknown): never {
  if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
    throw new ConversationMetadataError(
      409,
      'Group settings or membership changed. Refresh before trying again.',
    )
  }
  throw error
}
