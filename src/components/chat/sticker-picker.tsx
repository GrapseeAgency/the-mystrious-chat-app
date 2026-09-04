// ─────────────────────────────────────────────────────────────
// Pulse — sticker picker (LINE/Kakao-grade packs).
// 5 packs of emoji-art tiles on gradient cards. Tapping a tile
// posts a REAL message: kind:'sticker', payload {emoji, pack}.
// "Recent stickers" is a local UI preference (localStorage) —
// the sent data itself is always real chat data.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState } from 'react'
import { History } from 'lucide-react'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

export interface StickerPick {
  emoji: string
  pack: string
}

interface StickerPack {
  name: string
  badge: string
  /** tailwind gradient classes for the pack tiles */
  gradient: string
  items: string[]
}

export const STICKER_PACKS: readonly StickerPack[] = [
  {
    name: 'Pulse',
    badge: '⚡️',
    gradient: 'from-emerald-400 to-teal-500',
    items: ['⚡️', '🔥', '💥', '🎉', '✨', '🌟', '💫', '🚀', '🎯', '🏆'],
  },
  {
    name: 'Faces',
    badge: '😄',
    gradient: 'from-amber-400 to-orange-500',
    items: ['😂', '😍', '😎', '🤯', '😭', '😡', '🥳', '😴', '🤔', '🫠'],
  },
  {
    name: 'Reactions',
    badge: '👍',
    gradient: 'from-violet-400 to-fuchsia-500',
    items: ['👍', '👎', '🙏', '👏', '💪', '🤝', '😅', '🫡', '🤌', '🤗'],
  },
  {
    name: 'Love',
    badge: '❤️',
    gradient: 'from-rose-400 to-pink-500',
    items: ['❤️', '🧡', '💛', '💚', '💜', '🖤', '💖', '💘', '💞', '🫶'],
  },
  {
    name: 'Critters',
    badge: '🐾',
    gradient: 'from-lime-400 to-green-500',
    items: ['🐶', '🐱', '🐼', '🦊', '🐸', '🐵', '🦄', '🐙', '🦋', '🐢'],
  },
]

/** Resolve the gradient for a sticker's pack (unknown packs → neutral emerald). */
export function stickerGradient(pack: string): string {
  return STICKER_PACKS.find((p) => p.name === pack)?.gradient ?? 'from-emerald-400 to-teal-500'
}

const RECENTS_KEY = 'pulse.sticker-recents.v1'
const RECENTS_MAX = 12

function loadRecents(): StickerPick[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(RECENTS_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter(
        (item): item is StickerPick =>
          typeof item === 'object' &&
          item !== null &&
          typeof (item as StickerPick).emoji === 'string' &&
          typeof (item as StickerPick).pack === 'string',
      )
      .slice(0, RECENTS_MAX)
  } catch {
    return []
  }
}

function persistRecents(list: StickerPick[]): void {
  try {
    window.localStorage.setItem(RECENTS_KEY, JSON.stringify(list))
  } catch {
    // storage full/blocked — recents are a nicety, never a failure
  }
}

function StickerTile({
  emoji,
  pack,
  size = 'md',
  onPick,
}: {
  emoji: string
  pack: string
  size?: 'md' | 'sm'
  onPick: (pick: StickerPick) => void
}) {
  return (
    <button
      type="button"
      aria-label={`Send ${emoji} sticker from ${pack}`}
      onClick={() => {
        haptic(12)
        onPick({ emoji, pack })
      }}
      className={cn(
        'flex items-center justify-center rounded-2xl bg-gradient-to-br shadow-md outline-none transition-transform duration-150 will-change-transform hover:scale-[1.04] hover:shadow-lg active:scale-90',
        stickerGradient(pack),
        size === 'md' ? 'aspect-square text-[44px]' : 'size-11 text-2xl',
      )}
    >
      <span className="drop-shadow-sm">{emoji}</span>
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
  onPick: (pick: StickerPick) => void
}) {
  const [recents, setRecents] = useState<StickerPick[]>([])
  const [pack, setPack] = useState<string>(STICKER_PACKS[0].name)

  // hydrate recents once per open session
  useEffect(() => {
    if (!open) return
    const kick = setTimeout(() => setRecents(loadRecents()), 0)
    return () => clearTimeout(kick)
  }, [open])

  const activePack = useMemo(
    () => STICKER_PACKS.find((p) => p.name === pack) ?? STICKER_PACKS[0],
    [pack],
  )

  const handlePick = (pick: StickerPick) => {
    setRecents((prev) => {
      const next = [pick, ...prev.filter((r) => !(r.emoji === pick.emoji && r.pack === pick.pack))].slice(0, RECENTS_MAX)
      persistRecents(next)
      return next
    })
    onPick(pick)
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <DrawerTitle className="sr-only">Stickers</DrawerTitle>
        <DrawerDescription className="sr-only">Pick a sticker to send it instantly</DrawerDescription>

        <div className="pb-2">
          {recents.length > 0 ? (
            <div className="mb-2.5">
              <p className="mb-1.5 flex items-center gap-1 px-1 text-[10px] font-bold uppercase tracking-widest text-zinc-400 dark:text-zinc-500">
                <History className="size-3" aria-hidden />
                Recent
              </p>
              <div className="pulse-scroll flex gap-2 overflow-x-auto pb-1">
                {recents.map((r) => (
                  <StickerTile key={`${r.pack}-${r.emoji}`} emoji={r.emoji} pack={r.pack} size="sm" onPick={handlePick} />
                ))}
              </div>
            </div>
          ) : null}

          <Tabs value={pack} onValueChange={setPack}>
            <TabsList className="mb-2 flex h-9 w-full justify-between gap-1 rounded-2xl bg-zinc-100 p-1 dark:bg-zinc-800">
              {STICKER_PACKS.map((p) => (
                <TabsTrigger
                  key={p.name}
                  value={p.name}
                  aria-label={`${p.name} pack`}
                  className="h-7 flex-1 rounded-xl px-1 text-base data-[state=active]:bg-white data-[state=active]:shadow-sm dark:data-[state=active]:bg-zinc-700"
                >
                  <span aria-hidden>{p.badge}</span>
                </TabsTrigger>
              ))}
            </TabsList>

            <TabsContent value={activePack.name} className="mt-0">
              <div className="pulse-scroll grid max-h-[38dvh] grid-cols-3 gap-2 overflow-y-auto p-0.5 pb-1">
                {activePack.items.map((emoji) => (
                  <StickerTile key={emoji} emoji={emoji} pack={activePack.name} onPick={handlePick} />
                ))}
              </div>
              <p className="mt-1.5 text-center text-[10.5px] font-medium text-zinc-400 dark:text-zinc-500">
                {activePack.name} pack · tap to send — stays open for combos
              </p>
            </TabsContent>
          </Tabs>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
