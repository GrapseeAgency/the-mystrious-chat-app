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
  type TopicIconId,
  folderIconId,
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
  Database,
  DotsSixVertical,
  DotsThree,
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
  Lightning,
  Lock,
  MagnifyingGlass,
  Microphone,
  Monitor,
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
  ShieldCheck,
  SignOut,
  Smiley,
  Star,
  Sun,
  Sword,
  ThumbsUp,
  Trash,
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
