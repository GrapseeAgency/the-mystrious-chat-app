import type { ChatMessage } from './types'

/** A soft-deleted database row is a tombstone on the wire, not a hidden copy
 * of the original attachment/text that exports or another renderer can expose. */
export function redactDeletedMessage(message: ChatMessage): ChatMessage {
  if (!message.deletedAt) return message
  return { ...message, content: '', payload: null, replyTo: null, reactions: [],
    imagePath: null, audioPath: null, durationMs: null, filePath: null, fileName: null, fileSize: null,
    transcript: null, transcribedAt: null, pinnedAt: null, pinnedBy: null,
    linkUrl: null, linkPreview: null, poll: null, translations: [] }
}

export function quoteDisclosure(parent: { content: string; deletedAt: Date | null; expiresAt: Date | null; viewOnce: boolean },
  now: Date = new Date()): { content: string; deleted: boolean } {
  const deleted = parent.deletedAt !== null || (parent.expiresAt !== null && parent.expiresAt <= now)
  return { content: deleted ? '' : parent.viewOnce ? 'View-once message' : parent.content, deleted }
}
