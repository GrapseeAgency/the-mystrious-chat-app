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

// Reactions: message reactions carry one of these stable ids on the
// wire (the Reaction.emoji column keeps its historical name, the
// value domain is ids). Each surface maps the id to its own glyph.
export const REACTION_IDS = [
  'heart',
  'fire',
  'laugh',
  'wow',
  'sad',
  'celebrate',
  'thumbsup',
] as const

export type ReactionId = (typeof REACTION_IDS)[number]
export const REACTION_DEFAULT: ReactionId = 'heart'

export const REACTION_LABELS: Record<ReactionId, string> = {
  heart: 'Heart',
  fire: 'Fire',
  laugh: 'Laugh',
  wow: 'Wow',
  sad: 'Sad',
  celebrate: 'Celebrate',
  thumbsup: 'Thumbs up',
}

/** Legacy emoji values (pre-id era) resolve to ids for rendering. */
export const REACTION_LEGACY: Record<string, ReactionId> = {
  '\u{2764}': 'heart',
  '\u{2665}': 'heart',
  '\u{1FA77}': 'heart',
  '\u{1FA75}': 'heart',
  '\u{1F90D}': 'heart',
  '\u{1F90E}': 'heart',
  '\u{1F496}': 'heart',
  '\u{1F49D}': 'heart',
  '\u{1F498}': 'heart',
  '\u{1F497}': 'heart',
  '\u{1F493}': 'heart',
  '\u{1F49E}': 'heart',
  '\u{1F495}': 'heart',
  '\u{1F49F}': 'heart',
  '\u{1F9E1}': 'heart',
  '\u{1F49B}': 'heart',
  '\u{1F49A}': 'heart',
  '\u{1F499}': 'heart',
  '\u{1F5A4}': 'heart',
  '\u{1F525}': 'fire',
  '\u{1F602}': 'laugh',
  '\u{1F923}': 'laugh',
  '\u{1F606}': 'laugh',
  '\u{1F604}': 'laugh',
  '\u{1F603}': 'laugh',
  '\u{1F600}': 'laugh',
  '\u{1F642}': 'laugh',
  '\u{1F62E}': 'wow',
  '\u{1F632}': 'wow',
  '\u{1F92F}': 'wow',
  '\u{1F622}': 'sad',
  '\u{1F62D}': 'sad',
  '\u{2639}': 'sad',
  '\u{1F641}': 'sad',
  '\u{1F61E}': 'sad',
  '\u{1F614}': 'sad',
  '\u{1F389}': 'celebrate',
  '\u{1F38A}': 'celebrate',
  '\u{1F973}': 'celebrate',
  '\u{1F44D}': 'thumbsup',
  '\u{1F44F}': 'thumbsup',
  '\u{1FAF6}': 'thumbsup',
  '\u{1F91D}': 'thumbsup',
  '\u{1F64F}': 'thumbsup',
}

/**
 * Resolve a wire reaction value to a reaction id. Ids pass through,
 * legacy emoji values map to their id, anything else falls back to
 * the default so nothing unknown is ever rendered.
 */
export function reactionId(value: string | null | undefined): ReactionId {
  const v = value ?? ''
  if ((REACTION_IDS as readonly string[]).includes(v)) return v as ReactionId
  return REACTION_LEGACY[v] ?? REACTION_DEFAULT
}

// Stamps: the sticker surface. A sticker message carries a stamp id
// in its payload (the payload key keeps its historical name). Each
// surface maps the id to its own large glyph.
export const STAMP_IDS = [
  'bolt',
  'flame',
  'sparkles',
  'rocket',
  'target',
  'star',
  'trophy',
  'crown',
  'gift',
  'cake',
  'music',
  'heart',
  'palette',
  'camera',
  'mic',
  'gamepad',
  'brain',
  'drama',
  'smile',
  'pin',
  'sun',
  'shield',
  'key',
  'thumbsup',
  'thumbsdown',
  'leaf',
  'moon',
  'drop',
  'planet',
  'coffee',
  'paw',
] as const

export type StampId = (typeof STAMP_IDS)[number]
export const STAMP_DEFAULT: StampId = 'sparkles'

export const STAMP_LABELS: Record<StampId, string> = {
  bolt: 'Bolt',
  flame: 'Flame',
  sparkles: 'Sparkles',
  rocket: 'Rocket',
  target: 'Target',
  star: 'Star',
  trophy: 'Trophy',
  crown: 'Crown',
  gift: 'Gift',
  cake: 'Cake',
  music: 'Music',
  heart: 'Heart',
  palette: 'Palette',
  camera: 'Camera',
  mic: 'Microphone',
  gamepad: 'Gamepad',
  brain: 'Brain',
  drama: 'Drama',
  smile: 'Smile',
  pin: 'Pin',
  sun: 'Sun',
  shield: 'Shield',
  key: 'Key',
  thumbsup: 'Thumbs up',
  thumbsdown: 'Thumbs down',
  leaf: 'Leaf',
  moon: 'Moon',
  drop: 'Drop',
  planet: 'Planet',
  coffee: 'Coffee',
  paw: 'Paw',
}

/** Legacy emoji sticker values (pre-id era) resolve to stamp ids. */
export const STAMP_LEGACY: Record<string, StampId> = {
  '\u{26A1}': 'bolt',
  '\u{26A1}\u{FE0F}': 'bolt',
  '\u{1F4A5}': 'bolt',
  '\u{1F525}': 'flame',
  '\u{2728}': 'sparkles',
  '\u{1F31F}': 'sparkles',
  '\u{1F4AB}': 'sparkles',
  '\u{2B50}': 'sparkles',
  '\u{1F680}': 'rocket',
  '\u{1F3AF}': 'target',
  '\u{1F3C6}': 'trophy',
  '\u{1F451}': 'crown',
  '\u{1F381}': 'gift',
  '\u{1F382}': 'cake',
  '\u{1F370}': 'cake',
  '\u{1F355}': 'cake',
  '\u{1F3B5}': 'music',
  '\u{1F3B6}': 'music',
  '\u{2764}': 'heart',
  '\u{2764}\u{FE0F}': 'heart',
  '\u{1F9E1}': 'heart',
  '\u{1F49B}': 'heart',
  '\u{1F49A}': 'heart',
  '\u{1F499}': 'heart',
  '\u{1F49C}': 'heart',
  '\u{1F5A4}': 'heart',
  '\u{1F496}': 'heart',
  '\u{1F498}': 'heart',
  '\u{1F49E}': 'heart',
  '\u{1FAF6}': 'heart',
  '\u{1F3A8}': 'palette',
  '\u{1F4F7}': 'camera',
  '\u{1F4F8}': 'camera',
  '\u{1F3A4}': 'mic',
  '\u{1F399}': 'mic',
  '\u{1F3AE}': 'gamepad',
  '\u{1F9E0}': 'brain',
  '\u{1F3AD}': 'drama',
  '\u{1F600}': 'smile',
  '\u{1F602}': 'smile',
  '\u{1F979}': 'smile',
  '\u{1F60D}': 'smile',
  '\u{1F60E}': 'smile',
  '\u{1F914}': 'smile',
  '\u{1F634}': 'moon',
  '\u{1F973}': 'smile',
  '\u{1F64C}': 'smile',
  '\u{1F44B}': 'smile',
  '\u{1F44D}': 'thumbsup',
  '\u{1F44E}': 'thumbsdown',
  '\u{1F64F}': 'smile',
  '\u{1F44F}': 'smile',
  '\u{1F4AA}': 'bolt',
  '\u{1F91D}': 'smile',
  '\u{1F605}': 'smile',
  '\u{1FAE1}': 'smile',
  '\u{1F90C}': 'smile',
  '\u{1F917}': 'smile',
  '\u{2600}': 'sun',
  '\u{2600}\u{FE0F}': 'sun',
  '\u{1F308}': 'leaf',
  '\u{1F319}': 'moon',
  '\u{2615}': 'coffee',
  '\u{26BD}': 'target',
  '\u{1F43E}': 'paw',
  '\u{1F436}': 'paw',
  '\u{1F431}': 'paw',
  '\u{1F43C}': 'paw',
  '\u{1F98A}': 'paw',
  '\u{1F438}': 'paw',
  '\u{1F435}': 'paw',
  '\u{1F984}': 'paw',
  '\u{1F419}': 'paw',
  '\u{1F98B}': 'paw',
  '\u{1F422}': 'paw',
  '\u{1F30D}': 'planet',
  '\u{1F30E}': 'planet',
}

/** Resolve a wire stamp value. Ids pass through, legacy emoji values
 * map to their stamp, anything else falls back to the default. */
export function stampId(value: string | null | undefined): StampId {
  const v = value ?? ''
  if ((STAMP_IDS as readonly string[]).includes(v)) return v as StampId
  return STAMP_LEGACY[v] ?? STAMP_DEFAULT
}

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
