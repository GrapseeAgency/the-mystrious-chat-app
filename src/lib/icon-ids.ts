// Stable identifiers for every user-pickable icon in Pulse (folders,
// topics, profile status). The wire value is the id string; each
// surface maps the id to its own designed glyph. Stored values are
// never rendered raw, so unknown or stale values resolve to the
// registry default.

export const FOLDER_ICON_IDS = [
  'folder',
  'briefcase',
  'game',
  'heart',
  'flame',
  'target',
  'music',
  'brain',
] as const

export type FolderIconId = (typeof FOLDER_ICON_IDS)[number]
export const FOLDER_ICON_DEFAULT: FolderIconId = 'folder'

export const TOPIC_ICON_IDS = [
  'chat',
  'palette',
  'rocket',
  'brain',
  'confetti',
  'wrench',
  'pin',
  'coffee',
] as const

export type TopicIconId = (typeof TOPIC_ICON_IDS)[number]
export const TOPIC_ICON_DEFAULT: TopicIconId = 'chat'

export const STATUS_ICON_IDS = [
  'flame',
  'sparkles',
  'target',
  'coffee',
  'headphones',
  'moon',
  'bulb',
  'rocket',
  'sleep',
  'food',
  'vacation',
] as const

export type StatusIconId = (typeof STATUS_ICON_IDS)[number]
export const STATUS_ICON_DEFAULT: StatusIconId = 'sparkles'

function pick<T extends string>(ids: readonly T[], value: string | null | undefined, fallback: T): T {
  return (ids as readonly string[]).includes(value ?? '') ? (value as T) : fallback
}

export function folderIconId(value: string | null | undefined): FolderIconId {
  return pick(FOLDER_ICON_IDS, value, FOLDER_ICON_DEFAULT)
}

export function topicIconId(value: string | null | undefined): TopicIconId {
  return pick(TOPIC_ICON_IDS, value, TOPIC_ICON_DEFAULT)
}

/** Unknown status values resolve to null so stale rows render as "no status". */
export function statusIconId(value: string | null | undefined): StatusIconId | null {
  if (!value) return null
  return pick(STATUS_ICON_IDS, value, STATUS_ICON_DEFAULT)
}
