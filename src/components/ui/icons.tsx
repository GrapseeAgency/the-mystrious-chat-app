// Pulse icon system. One voice for the whole product: Phosphor,
// duotone-first. Weights: duo for chrome and destinations, bold for
// primary actions, fill for brand marks, line for inline meta.
// Pickable glyphs (folders, topics, status) are resolved from the
// id registries in '@/lib/icon-ids'; stored values are ids and are
// never rendered raw.
'use client'

import type { ComponentType } from 'react'
import {
  type FolderIconId,
  type ReactionId,
  type StampId,
  type TopicIconId,
  folderIconId,
  reactionId,
  stampId,
  topicIconId,
} from '@/lib/icon-ids'
import {
  Aperture,
  ArrowLeft,
  At,
  BellRinging,
  BookmarkSimple,
  Brain,
  Briefcase,
  Broadcast,
  Camera,
  CaretLeft,
  CaretRight,
  ChatCenteredDots,
  ChatCircle,
  ChatCircleDots,
  Check,
  CircleNotch,
  Coffee,
  Coins,
  Command,
  Confetti,
  CopySimple,
  Crosshair,
  Crown,
  Database,
  DotsSixVertical,
  DotsThree,
  Drop,
  Eyes,
  Fire,
  FolderPlus,
  FolderSimple,
  Fingerprint,
  GameController,
  GearSix,
  Gift,
  HeartStraight,
  ImageSquare,
  Info,
  Kanban,
  Key,
  Leaf,
  Lightning,
  Lock,
  MagnifyingGlass,
  MaskHappy,
  MaskSad,
  Microphone,
  Monitor,
  Moon,
  MoonStars,
  MusicNotes,
  PaperPlaneTilt,
  PawPrint,
  Palette,
  PencilSimple,
  PersonArmsSpread,
  PhoneCall,
  Planet,
  PlayCircle,
  Plus,
  PushPin,
  Rocket,
  SealCheck,
  ShareNetwork,
  Shield,
  ShieldCheck,
  SignOut,
  Smiley,
  SmileySad,
  Sparkle,
  Star,
  Sun,
  Sword,
  Cake,
  ThumbsDown,
  ThumbsUp,
  Trash,
  Trophy,
  UserCircle,
  UsersThree,
  VideoCamera,
  Wrench,
  X,
  type IconProps,
} from '@phosphor-icons/react'

/** Anything renderable as `<Icon className="size-4" aria-hidden />`. */
export type PulseGlyph = ComponentType<{
  className?: string
  'aria-hidden'?: boolean | 'true' | 'false'
}>

type Ph = ComponentType<IconProps>
type Simple = { className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }

const duo = (Icon: Ph): PulseGlyph => {
  const C = (p: Simple) => <Icon weight="duotone" aria-hidden {...p} />
  C.displayName = 'PulseDuo'
  return C
}
const bold = (Icon: Ph): PulseGlyph => {
  const C = (p: Simple) => <Icon weight="bold" aria-hidden {...p} />
  C.displayName = 'PulseBold'
  return C
}
const line = (Icon: Ph): PulseGlyph => {
  const C = (p: Simple) => <Icon weight="regular" aria-hidden {...p} />
  C.displayName = 'PulseLine'
  return C
}
const solid = (Icon: Ph): PulseGlyph => {
  const C = (p: Simple) => <Icon weight="fill" aria-hidden {...p} />
  C.displayName = 'PulseFill'
  return C
}

// Destinations (duotone, the nav voice)
export const PulseChats = duo(ChatCircleDots)
export const PulseHub = duo(Planet)
export const PulseContacts = duo(UsersThree)
export const PulseProfile = duo(UserCircle)

// Chrome actions
export const PulseSearch = bold(MagnifyingGlass)
export const PulsePlus = bold(Plus)
export const PulseSettings = duo(GearSix)
export const PulseSaved = duo(BookmarkSimple)
export const PulseStories = duo(Aperture)
export const PulseMore = bold(DotsThree)
export const PulseGrip = bold(DotsSixVertical)
export const PulseCommand = line(Command)
export const PulseBack = line(ArrowLeft)
export const PulseCaret = line(CaretRight)
export const PulseCaretLeft = line(CaretLeft)
export const PulseClose = line(X)
export const PulseCheck = bold(Check)
export const PulseAt = line(At)
export const PulseEdit = line(PencilSimple)
export const PulseShare = line(ShareNetwork)
export const PulseCopy = line(CopySimple)
export const PulseSignOut = line(SignOut)
export const PulseFingerprint = line(Fingerprint)
export const PulseTrash = line(Trash)
export const PulseLoader = line(CircleNotch)
export const PulseLock = line(Lock)
export const PulseBolt = duo(Lightning)

// Media + comms
export const PulsePhoto = line(ImageSquare)
export const PulseMic = line(Microphone)
export const PulseSend = solid(PaperPlaneTilt)
export const PulsePhone = line(PhoneCall)
export const PulseVideo = line(VideoCamera)
export const PulsePlay = line(PlayCircle)
export const PulseCompose = bold(PencilSimple)
export const PulseFolderPlus = bold(FolderPlus)

// Settings section tiles
export const PulseUser = duo(UserCircle)
export const PulsePalette = duo(Palette)
export const PulseChat = duo(ChatCenteredDots)
export const PulseBell = duo(BellRinging)
export const PulseShield = duo(ShieldCheck)
export const PulseBroadcast = duo(Broadcast)
export const PulseAccess = duo(PersonArmsSpread)
export const PulseDatabase = duo(Database)
export const PulseInfo = line(Info)
export const PulseSun = line(Sun)
export const PulseMoon = line(MoonStars)
export const PulseMonitor = line(Monitor)

// Marks + decorative
export const PulseSeal = solid(SealCheck)
export const PulseStar = duo(Star)
export const PulseCoins = duo(Coins)
export const PulseKanban = duo(Kanban)
export const PulseGift = duo(Gift)
export const PulseSword = duo(Sword)
export const PulseFolder = duo(FolderSimple)

export const PulseSmiley = duo(Smiley)
export const PulseThumb = duo(ThumbsUp)
export const PulseHeart = duo(HeartStraight)
export const PulsePaw = duo(PawPrint)

// Pickable glyph registries, keyed by the ids persisted on the wire.
export const FOLDER_ICON_GLYPHS: Record<FolderIconId, PulseGlyph> = {
  folder: duo(FolderSimple),
  briefcase: duo(Briefcase),
  game: duo(GameController),
  heart: duo(HeartStraight),
  flame: duo(Fire),
  target: duo(Crosshair),
  music: duo(MusicNotes),
  brain: duo(Brain),
}

export const TOPIC_ICON_GLYPHS: Record<TopicIconId, PulseGlyph> = {
  chat: duo(ChatCircle),
  palette: duo(Palette),
  rocket: duo(Rocket),
  brain: duo(Brain),
  confetti: duo(Confetti),
  wrench: line(Wrench),
  pin: duo(PushPin),
  coffee: duo(Coffee),
}

export function folderGlyphFor(value: string | null | undefined): PulseGlyph {
  return FOLDER_ICON_GLYPHS[folderIconId(value)]
}

export function topicGlyphFor(value: string | null | undefined): PulseGlyph {
  return TOPIC_ICON_GLYPHS[topicIconId(value)]
}

// Reaction glyphs, keyed by the reaction id persisted on the wire.
export const REACTION_ICON_GLYPHS: Record<ReactionId, PulseGlyph> = {
  heart: solid(HeartStraight),
  fire: solid(Fire),
  laugh: solid(MaskHappy),
  wow: solid(Eyes),
  sad: solid(SmileySad),
  celebrate: solid(Confetti),
  thumbsup: solid(ThumbsUp),
}

/** Solid fill inside chips and palettes; resolved through the id registry. */
export function reactionGlyphFor(value: string | null | undefined): PulseGlyph {
  return REACTION_ICON_GLYPHS[reactionId(value)]
}

// Stamp glyphs (the sticker surface), keyed by the stamp id persisted
// in the sticker message payload.
export const STAMP_ICON_GLYPHS: Record<StampId, PulseGlyph> = {
  bolt: duo(Lightning),
  flame: duo(Fire),
  sparkles: duo(Sparkle),
  rocket: duo(Rocket),
  target: duo(Crosshair),
  star: duo(Star),
  trophy: duo(Trophy),
  crown: duo(Crown),
  gift: duo(Gift),
  cake: duo(Cake),
  music: duo(MusicNotes),
  heart: duo(HeartStraight),
  palette: duo(Palette),
  camera: duo(Camera),
  mic: duo(Microphone),
  gamepad: duo(GameController),
  brain: duo(Brain),
  drama: duo(MaskSad),
  smile: duo(Smiley),
  pin: duo(PushPin),
  sun: duo(Sun),
  shield: duo(Shield),
  key: duo(Key),
  thumbsup: duo(ThumbsUp),
  thumbsdown: duo(ThumbsDown),
  leaf: duo(Leaf),
  moon: duo(Moon),
  drop: duo(Drop),
  planet: duo(Planet),
  coffee: duo(Coffee),
  paw: duo(PawPrint),
}

/** Large duotone glyph for a stamp value (legacy emoji values included). */
export function stampGlyphFor(value: string | null | undefined): PulseGlyph {
  return STAMP_ICON_GLYPHS[stampId(value)]
}
