import { reactionId } from './icon-ids.ts'

/** The displayed reaction is the same logical key used by mutations. Legacy
 * aliases and canonical IDs must not count the same member more than once. */
export function normalizedReactionGroups(rows: readonly { emoji: string; userId: string }[]) {
  const groups = new Map<string, Set<string>>()
  for (const row of rows) {
    const emoji = reactionId(row.emoji)
    const users = groups.get(emoji) ?? new Set<string>()
    users.add(row.userId)
    groups.set(emoji, users)
  }
  return [...groups].map(([emoji, users]) => ({ emoji, userIds: [...users], count: users.size }))
}
