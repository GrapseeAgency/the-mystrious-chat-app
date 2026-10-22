/** Both native clients omit an optional photo when creating a plain channel.
 * null is a valid no-photo value; undefined is reserved for invalid input. */
export function normalizeChannelPhoto(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string') return undefined
  const path = value.trim()
  if (path === '') return null
  return /^\/api\/uploads\/([A-Za-z0-9-]+\.(?:jpg|jpeg|png|webp))$/.test(path) ? path : undefined
}

/** A channel is the broadcast conversation itself, not a separate resource. */
export function channelIdentity(id: string): { id: string; conversationId: string } {
  return { id, conversationId: id }
}
