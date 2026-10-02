// Pulse stamp picker. Five packs of designed glyph tiles on gradient
// cards. Tapping a tile posts a REAL message: kind 'sticker', payload
// { emoji: <stamp id>, pack }. "Recent stamps" is a local UI
// preference (localStorage); the sent data itself is chat data.
// Every tile is a vector glyph resolved from the stamp registry in
// '@/lib/icon-ids' - no raw values are ever rendered.
'use client'

import { useEffect, useMemo, useState } from 'react'
import { History } from 'lucide-react'
import {
  PulseBolt,
  PulseHeart,
  PulsePaw,
  PulseSmiley,
  PulseThumb,
  STAMP_ICON_GLYPHS,
  type PulseGlyph,
} from '@/components/ui/icons'
import { STAMP_LABELS, type StampId } from '@/lib/icon-ids'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

export interface StampPick {
  stamp: StampId
  pack: string
}

interface StampPack {
  name: string
  badge: PulseGlyph
  /** tailwind gradient classes for the pack tiles */
  gradient: string
  items: StampId[]
}

export const STAMP_PACKS: readonly StampPack[] = [
  {
    name: 'Signal',
    badge: PulseBolt,
    gradient: 'from-amber-400 to-orange-500',
    items: ['bolt', 'flame', 'sparkles', 'rocket', 'target', 'star'],
  },
  {
    name: 'Celebrate',
    badge: PulseHeart,
    gradient: 'from-rose-400 to-pink-500',
    items: ['trophy', 'crown', 'gift', 'cake', 'music', 'heart'],
  },
  {
    name: 'Create',
    badge: PulseSmiley,
    gradient: 'from-amber-400 to-orange-500',
    items: ['palette', 'camera', 'mic', 'gamepad', 'brain', 'drama'],
  },
  {
    name: 'Nature',
    badge: PulsePaw,
    gradient: 'from-lime-400 to-green-500',
    items: ['leaf', 'moon', 'drop', 'planet', 'coffee', 'paw'],
  },
  {
    name: 'Marks',
    badge: PulseThumb,
    gradient: 'from-violet-400 to-fuchsia-500',
    items: ['smile', 'pin', 'sun', 'shield', 'key', 'thumbsup', 'thumbsdown'],
  },
]

/** Resolve the gradient for a stamp's pack (unknown packs -> neutral emerald). */
export function stickerGradient(pack: string): string {
  return STAMP_PACKS.find((p) => p.name === pack)?.gradient ?? 'from-amber-400 to-orange-500'
}

const RECENTS_KEY = 'pulse.sticker-recents.v2'
const RECENTS_MAX = 12

function loadRecents(): StampPick[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(RECENTS_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter(
        (item): item is StampPick =>
          typeof item === 'object' &&
          item !== null &&
          typeof (item as StampPick).stamp === 'string' &&
          typeof (item as StampPick).pack === 'string',
      )
      .slice(0, RECENTS_MAX)
  } catch {
    return []
  }
}

function persistRecents(list: StampPick[]): void {
  try {
    window.localStorage.setItem(RECENTS_KEY, JSON.stringify(list))
  } catch {
    // storage full/blocked - recents are a nicety, never a failure
  }
}

function StampTile({
  stamp,
  pack,
  size = 'md',
  onPick,
}: {
  stamp: StampId
  pack: string
  size?: 'md' | 'sm'
  onPick: (pick: StampPick) => void
}) {
  const Glyph = STAMP_ICON_GLYPHS[stamp]
  return (
    <button
      type="button"
      aria-label={`Send the ${STAMP_LABELS[stamp]} stamp from ${pack}`}
      onClick={() => {
        haptic(12)
        onPick({ stamp, pack })
      }}
      className={cn(
        'flex items-center justify-center rounded-2xl bg-gradient-to-br shadow-md outline-none transition-transform duration-150 will-change-transform hover:scale-[1.04] hover:shadow-lg active:scale-90',
        stickerGradient(pack),
        size === 'md' ? 'aspect-square' : 'size-11',
      )}
    >
      <Glyph className={cn('text-white drop-shadow-sm', size === 'md' ? 'size-9' : 'size-5')} aria-hidden />
    </button>
  )
}

export function StickerPicker({
  open,
  onOpenChange,
  onPick,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onPick: (pick: StampPick) => void
}) {
  const [recents, setRecents] = useState<StampPick[]>([])
  const [pack, setPack] = useState<string>(STAMP_PACKS[0].name)

  // hydrate recents once per open session
  useEffect(() => {
    if (!open) return
    const kick = setTimeout(() => setRecents(loadRecents()), 0)
    return () => clearTimeout(kick)
  }, [open])

  const activePack = useMemo(
    () => STAMP_PACKS.find((p) => p.name === pack) ?? STAMP_PACKS[0],
    [pack],
  )

  const handlePick = (pick: StampPick) => {
    setRecents((prev) => {
      const next = [pick, ...prev.filter((r) => !(r.stamp === pick.stamp && r.pack === pick.pack))].slice(0, RECENTS_MAX)
      persistRecents(next)
      return next
    })
    onPick(pick)
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <DrawerTitle className="sr-only">Stamps</DrawerTitle>
        <DrawerDescription className="sr-only">Pick a stamp to send it instantly</DrawerDescription>

        <div className="pb-2">
          {recents.length > 0 ? (
            <div className="mb-2.5">
              <p className="mb-1.5 flex items-center gap-1 px-1 text-[10px] font-bold uppercase tracking-widest text-zinc-400 dark:text-zinc-500">
                <History className="size-3" aria-hidden />
                Recent
              </p>
              <div className="pulse-scroll flex gap-2 overflow-x-auto pb-1">
                {recents.map((r) => (
                  <StampTile key={`${r.pack}-${r.stamp}`} stamp={r.stamp} pack={r.pack} size="sm" onPick={handlePick} />
                ))}
              </div>
            </div>
          ) : null}

          <Tabs value={pack} onValueChange={setPack}>
            <TabsList className="mb-2 flex h-9 w-full justify-between gap-1 rounded-2xl bg-zinc-100 p-1 dark:bg-zinc-800">
              {STAMP_PACKS.map((p) => (
                <TabsTrigger
                  key={p.name}
                  value={p.name}
                  aria-label={`${p.name} pack`}
                  className="h-7 flex-1 rounded-xl px-1 text-base data-[state=active]:bg-white data-[state=active]:shadow-sm dark:data-[state=active]:bg-zinc-700"
                >
                  <p.badge className="size-4" aria-hidden />
                </TabsTrigger>
              ))}
            </TabsList>

            <TabsContent value={activePack.name} className="mt-0">
              <div className="pulse-scroll grid max-h-[38dvh] grid-cols-3 gap-2 overflow-y-auto p-0.5 pb-1">
                {activePack.items.map((stamp) => (
                  <StampTile key={stamp} stamp={stamp} pack={activePack.name} onPick={handlePick} />
                ))}
              </div>
              <p className="mt-1.5 text-center text-[10.5px] font-medium text-zinc-400 dark:text-zinc-500">
                {activePack.name} pack · tap to send - stays open for combos
              </p>
            </TabsContent>
          </Tabs>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
