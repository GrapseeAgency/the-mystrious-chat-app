import type { SearchResultMessage } from './types'

/** Search is a retained preview, never another way to open one-time media.
 * Anonymous hits must not disclose the underlying author in search chrome. */
export function visibleSearchHit(message: SearchResultMessage, query: string, at = new Date()): SearchResultMessage | null {
  if (message.deletedAt || message.viewOnce) return null
  if (message.expiresAt && !(Date.parse(message.expiresAt) > at.getTime())) return null
  const needle = query.trim().toLowerCase()
  if (!needle || !(message.content.toLowerCase().includes(needle) || (message.fileName ?? '').toLowerCase().includes(needle))) return null
  if (!message.anon) return message
  return { ...message, senderId: '', sender: { id: '', name: message.anonAlias || 'Anonymous', username: null, color: 'orange', avatar: null } }
}
