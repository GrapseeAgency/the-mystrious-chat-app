// ─────────────────────────────────────────────────────────────
// Pulse — "Neo" icon system (R35).
// ONE icon voice for the whole product: Phosphor, duotone-first.
//
//   duo    → chrome + destinations (nav, section tiles, headers)
//   bold   → primary actions (compose, confirm)
//   fill   → brand marks (verified seal, send)
//   line   → inline meta (rows, timestamps, quiet affordances)
//
// Zero emojis in UI chrome — anything legacy that persisted an
// emoji VALUE renders through the glyph maps below as a real
// icon, so the data contract stays intact while nothing raw
// ever reaches the screen.
// ─────────────────────────────────────────────────────────────
'use client'

import type { ComponentType } from 'react'
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
  Star,
  Sun,
  Sword,
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

// ── destinations (duotone — the nav voice) ────────────────────
export const PulseChats = duo(ChatCircleDots)
export const PulseHub = duo(Planet)
export const PulseContacts = duo(UsersThree)
export const PulseProfile = duo(UserCircle)

// ── chrome actions ───────────────────────────────────────────
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

// ── media + comms ────────────────────────────────────────────
export const PulsePhoto = line(ImageSquare)
export const PulseMic = line(Microphone)
export const PulseSend = solid(PaperPlaneTilt)
export const PulsePhone = line(PhoneCall)
export const PulseVideo = line(VideoCamera)
export const PulsePlay = line(PlayCircle)
export const PulseCompose = bold(PencilSimple)
export const PulseFolderPlus = bold(FolderPlus)

// ── settings section tiles ───────────────────────────────────
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

// ── marks + decorative ───────────────────────────────────────
export const PulseSeal = solid(SealCheck)
export const PulseStar = duo(Star)
export const PulseCoins = duo(Coins)
export const PulseKanban = duo(Kanban)
export const PulseGift = duo(Gift)
export const PulseSword = duo(Sword)
export const PulseFolder = duo(FolderSimple)

// ── legacy persisted-value glyph maps (emoji in, icon out) ───
export const FOLDER_GLYPHS: Record<string, PulseGlyph> = {
  '📂': duo(FolderSimple),
  '💼': duo(Briefcase),
  '🎮': duo(GameController),
  '❤️': duo(HeartStraight),
  '🔥': duo(Fire),
  '🎯': duo(Crosshair),
  '🎵': duo(MusicNotes),
  '🧠': duo(Brain),
}
export const TOPIC_GLYPHS: Record<string, PulseGlyph> = {
  '💬': duo(ChatCircle),
  '🎨': duo(Palette),
  '🚀': duo(Rocket),
  '🧠': duo(Brain),
  '🎉': duo(Confetti),
  '🛠️': line(Wrench),
  '📌': duo(PushPin),
  '☕': duo(Coffee),
}

/** Resolve a persisted legacy value to its icon (never returns an emoji). */
export function glyphFor(map: Record<string, PulseGlyph>, value: string | null | undefined, fallback?: PulseGlyph): PulseGlyph {
  return (value && map[value]) || fallback || duo(FolderSimple)
}
