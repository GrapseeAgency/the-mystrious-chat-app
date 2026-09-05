// ─────────────────────────────────────────────────────────────
// Pulse — chat folders manager sheet (Task R24-a, Signal-style)
//
// Bottom sheet to manage chat folders: create (8-emoji preset row +
// 24-char name), rename inline, delete with two-tap confirm, and a
// membership editor that lists every real chat with checkboxes and
// PUTs the full ordered membership to /api/folders/[id]/conversations.
//
// Data: folders via TanStack Query key ['folders', userId]; chats via
// the caller's GET /api/conversations rows (real data, passed as
// `conversations`). Every mutation invalidates ['folders', userId].
//
// Visual language follows redpacket-sheet.tsx: vaul Drawer, glass
// zinc-950 panel, spring entrance, sr-only title, safe-area padding,
// motion tokens exclusively from '@/lib/motion'.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronLeft, ChevronRight, LoaderCircle, Pencil, Plus, Trash2, X } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson, conversationDisplayName } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import type { AppUser, ConversationSummary, FolderSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { UserAvatar } from '@/components/chat/user-avatar'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'

const FOLDER_NAME_MAX = 24
const EMOJI_PRESETS = ['📂', '💼', '🎮', '❤️', '🔥', '🎯', '🎵', '🧠'] as const

interface FoldersResponse {
  folders: FolderSummary[]
}

/** staggered list entrance variants (respects reduced motion at call sites) */
const listVariants = {
  hidden: {},
  shown: { transition: { staggerChildren: 0.04 } },
}
const rowVariants = {
  hidden: { opacity: 0, y: 12 },
  shown: { opacity: 1, y: 0, transition: spring.soft },
}

export function FoldersSheet({
  open,
  onClose,
  me,
  conversations,
}: {
  open: boolean
  onClose: () => void
  me: AppUser
  /** real GET /api/conversations rows (parent's query cache) */
  conversations: ConversationSummary[]
}) {
  const reducedMotion = useReducedMotion()
  const queryClient = useQueryClient()

  // ── data ───────────────────────────────────────────────────
  const foldersQ = useQuery({
    queryKey: ['folders', me.id],
    queryFn: async (): Promise<FolderSummary[]> => {
      const res = await apiJson<FoldersResponse>(
        `/api/folders?userId=${encodeURIComponent(me.id)}`,
      )
      return res.folders
    },
    enabled: open,
    staleTime: 10_000,
  })
  const folders = foldersQ.data ?? []

  // ── create form ────────────────────────────────────────────
  const [newName, setNewName] = useState('')
  const [newEmoji, setNewEmoji] = useState<string>('📂')

  // ── sheet modes: list | membership editor ──────────────────
  const [mode, setMode] = useState<'list' | 'members'>('list')
  const [editingFolder, setEditingFolder] = useState<FolderSummary | null>(null)
  const [checkedIds, setCheckedIds] = useState<Set<string>>(new Set())

  // ── inline rename + two-tap delete ─────────────────────────
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)

  // fresh manager every time the sheet opens — render-time state reset
  // (React's "adjusting state when a prop changes" pattern, no effect needed)
  const [prevOpen, setPrevOpen] = useState(open)
  if (open !== prevOpen) {
    setPrevOpen(open)
    if (open) {
      setNewName('')
      setNewEmoji('📂')
      setMode('list')
      setEditingFolder(null)
      setRenamingId(null)
      setConfirmDeleteId(null)
    }
  }

  useEffect(() => {
    if (confirmDeleteId === null) return
    const t = setTimeout(() => setConfirmDeleteId(null), 2600)
    return () => clearTimeout(t)
  }, [confirmDeleteId])

  const invalidateFolders = () =>
    queryClient.invalidateQueries({ queryKey: ['folders', me.id] })

  // ── mutations ──────────────────────────────────────────────
  const createM = useMutation({
    mutationFn: async () =>
      apiJson<{ folder: FolderSummary }>('/api/folders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, name: newName.trim(), emoji: newEmoji }),
      }),
    onSuccess: (res) => {
      haptic(10)
      void invalidateFolders()
      setNewName('')
      setNewEmoji('📂')
      toast.success(`${res.folder.emoji} Folder “${res.folder.name}” created`)
    },
    onError: (err) => {
      toast.error(err instanceof Error ? err.message : 'Could not create the folder.')
    },
  })

  const renameM = useMutation({
    mutationFn: async ({ folderId, name }: { folderId: string; name: string }) =>
      apiJson<{ folder: FolderSummary }>(`/api/folders/${encodeURIComponent(folderId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      }),
    onSuccess: (res) => {
      haptic(6)
      void invalidateFolders()
      setRenamingId(null)
      toast.success(`Renamed to “${res.folder.name}”`)
    },
    onError: (err) => {
      toast.error(err instanceof Error ? err.message : 'Could not rename the folder.')
    },
  })

  const deleteM = useMutation({
    mutationFn: async (folderId: string) =>
      apiJson<{ ok: boolean }>(`/api/folders/${encodeURIComponent(folderId)}`, {
        method: 'DELETE',
      }),
    onSuccess: () => {
      haptic(14)
      void invalidateFolders()
      setConfirmDeleteId(null)
      toast.success('Folder deleted — chats stay in your list')
    },
    onError: (err) => {
      toast.error(err instanceof Error ? err.message : 'Could not delete the folder.')
    },
  })

  const saveM = useMutation({
    mutationFn: async ({ folderId, conversationIds }: { folderId: string; conversationIds: string[] }) =>
      apiJson<{ folder: FolderSummary }>(
        `/api/folders/${encodeURIComponent(folderId)}/conversations`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ conversationIds }),
        },
      ),
    onSuccess: (res) => {
      haptic(10)
      void invalidateFolders()
      setMode('list')
      setEditingFolder(null)
      toast.success(
        res.folder.conversationIds.length === 1
          ? '1 chat saved to the folder'
          : `${res.folder.conversationIds.length} chats saved to the folder`,
      )
    },
    onError: (err) => {
      toast.error(err instanceof Error ? err.message : 'Could not save the folder membership.')
    },
  })

  const openMembers = (folder: FolderSummary) => {
    haptic(6)
    setEditingFolder(folder)
    setCheckedIds(new Set(folder.conversationIds))
    setMode('members')
  }

  const toggleChecked = (convId: string) => {
    haptic(4)
    setCheckedIds((prev) => {
      const next = new Set(prev)
      if (next.has(convId)) next.delete(convId)
      else next.add(convId)
      return next
    })
  }

  /** membership rows keep the caller's chat-list order — deterministic rail order */
  const memberRows = useMemo(
    () =>
      conversations.map((conv) => ({
        conv,
        name: conversationDisplayName(conv, me.id),
      })),
    [conversations, me.id],
  )

  const nameValid = newName.trim().length >= 1 && newName.trim().length <= FOLDER_NAME_MAX

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent className="mx-auto flex max-h-[88dvh] max-w-[420px] flex-col rounded-t-3xl border-white/10 bg-zinc-950 pb-[max(0.75rem,env(safe-area-inset-bottom))] dark:border-white/10 dark:bg-zinc-950">
        <DrawerTitle className="sr-only">Chat folders</DrawerTitle>
        <DrawerDescription className="sr-only">
          Create folders, rename or delete them, and pick which chats live inside
        </DrawerDescription>

        <motion.div
          initial={reducedMotion ? false : { opacity: 0, y: 24, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="flex min-h-0 flex-1 flex-col px-4 pb-2 pt-2"
        >
          {/* header */}
          <header className="flex shrink-0 items-center gap-2.5 pb-3">
            {mode === 'members' ? (
              <button
                type="button"
                aria-label="Back to folders"
                onClick={() => {
                  haptic(6)
                  setMode('list')
                  setEditingFolder(null)
                }}
                className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
              >
                <ChevronLeft className="size-5" aria-hidden />
              </button>
            ) : (
              <span
                aria-hidden
                className="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-500 to-teal-600 text-xl shadow-[0_10px_24px_-10px_rgba(16,185,129,0.7)]"
              >
                📂
              </span>
            )}
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold leading-tight text-zinc-50">
                {mode === 'members' ? 'Folder chats' : 'Chat folders'}
              </p>
              <p className="text-[11px] font-medium leading-tight text-zinc-500">
                {mode === 'members'
                  ? (editingFolder?.name ?? '')
                  : 'Filter your list — Signal-style inboxes'}
              </p>
            </div>
            <button
              type="button"
              aria-label="Close folders sheet"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
            >
              <X className="size-4.5" aria-hidden />
            </button>
          </header>

          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain">
            <AnimatePresence mode="wait" initial={false}>
              {mode === 'list' ? (
                <motion.div
                  key="folder-list"
                  initial={reducedMotion ? false : { opacity: 0, x: 14 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={reducedMotion ? undefined : { opacity: 0, x: -14 }}
                  transition={{ duration: 0.18, ease: ease.out }}
                  className="flex flex-col gap-3"
                >
                  {/* create form */}
                  <motion.section
                    initial={reducedMotion ? false : { opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={spring.soft}
                    aria-label="Create a new folder"
                    className="rounded-2xl border border-white/10 bg-white/5 p-3"
                  >
                    <p className="pb-2 text-[12px] font-semibold uppercase tracking-wide text-zinc-500">
                      New folder
                    </p>
                    <div className="flex flex-wrap gap-1.5 pb-2.5" role="group" aria-label="Folder emoji">
                      {EMOJI_PRESETS.map((emoji) => {
                        const active = newEmoji === emoji
                        return (
                          <motion.button
                            key={emoji}
                            type="button"
                            aria-label={`Emoji ${emoji}`}
                            aria-pressed={active}
                            whileTap={reducedMotion ? undefined : pressTap}
                            transition={pressSpring}
                            onClick={() => {
                              haptic(4)
                              setNewEmoji(emoji)
                            }}
                            className={cn(
                              'flex size-10 items-center justify-center rounded-xl text-lg outline-none transition-colors',
                              active
                                ? 'bg-emerald-500/25 ring-2 ring-emerald-400/70'
                                : 'bg-white/5 ring-1 ring-white/10 hover:bg-white/10',
                            )}
                          >
                            {emoji}
                          </motion.button>
                        )
                      })}
                    </div>
                    <div className="flex items-center gap-2">
                      <Input
                        value={newName}
                        maxLength={FOLDER_NAME_MAX}
                        onChange={(e) => setNewName(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' && nameValid && !createM.isPending) {
                            e.preventDefault()
                            createM.mutate()
                          }
                        }}
                        placeholder="Folder name…"
                        aria-label="Folder name"
                        className="h-11 flex-1 rounded-2xl border-white/10 bg-white/5 text-[14px] font-medium text-zinc-50 placeholder:text-zinc-600 focus-visible:border-emerald-400/60 focus-visible:ring-emerald-400/30"
                      />
                      <Button
                        type="button"
                        disabled={!nameValid || createM.isPending}
                        onClick={() => createM.mutate()}
                        aria-label="Create folder"
                        className="h-11 shrink-0 gap-1.5 rounded-2xl border-0 bg-gradient-to-r from-emerald-500 to-teal-600 px-4 text-[14px] font-bold text-white shadow-[0_12px_28px_-12px_rgba(16,185,129,0.8)] transition-transform active:scale-[0.97] disabled:from-zinc-700 disabled:to-zinc-700 disabled:text-zinc-400 disabled:shadow-none"
                      >
                        {createM.isPending ? (
                          <LoaderCircle className="size-4.5 animate-spin" aria-hidden />
                        ) : (
                          <>
                            <Plus className="size-4" strokeWidth={3} aria-hidden />
                            Create
                          </>
                        )}
                      </Button>
                    </div>
                  </motion.section>

                  {/* folder list */}
                  {foldersQ.isPending ? (
                    <div className="flex items-center justify-center gap-2 py-6" role="status" aria-label="Loading folders">
                      <LoaderCircle className="size-4 animate-spin text-emerald-500" aria-hidden />
                      <span className="text-xs font-medium text-zinc-500">Loading folders…</span>
                    </div>
                  ) : folders.length === 0 ? (
                    <p className="rounded-2xl border border-dashed border-white/10 px-4 py-6 text-center text-[12.5px] leading-relaxed text-zinc-500">
                      No folders yet. Create one above, then pick the chats that belong inside.
                    </p>
                  ) : (
                    <motion.ul
                      variants={reducedMotion ? undefined : listVariants}
                      initial={reducedMotion ? false : 'hidden'}
                      animate="shown"
                      className="flex flex-col gap-1.5"
                      aria-label="Your folders"
                    >
                      <AnimatePresence initial={false}>
                        {folders.map((folder, i) => {
                          const armed = confirmDeleteId === folder.id
                          const renaming = renamingId === folder.id
                          return (
                            <motion.li
                              key={folder.id}
                              layout={!reducedMotion}
                              variants={reducedMotion ? undefined : rowVariants}
                              exit={reducedMotion ? undefined : { opacity: 0, scale: 0.96, transition: { duration: 0.15 } }}
                              transition={spring.soft}
                              custom={i}
                              style={reducedMotion ? undefined : { transitionDelay: `${stagger(i, 0.03, 8)}s` }}
                              className="overflow-hidden rounded-2xl border border-white/10 bg-white/5"
                            >
                              <div className="flex items-center gap-2 px-2.5 py-2">
                                <span
                                  aria-hidden
                                  className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white/5 text-lg ring-1 ring-white/10"
                                >
                                  {folder.emoji}
                                </span>
                                {renaming ? (
                                  <Input
                                    autoFocus
                                    value={renameValue}
                                    maxLength={FOLDER_NAME_MAX}
                                    onChange={(e) => setRenameValue(e.target.value)}
                                    onKeyDown={(e) => {
                                      if (e.key === 'Enter' && renameValue.trim() && !renameM.isPending) {
                                        e.preventDefault()
                                        renameM.mutate({ folderId: folder.id, name: renameValue.trim() })
                                      }
                                      if (e.key === 'Escape') setRenamingId(null)
                                    }}
                                    aria-label="Folder name"
                                    className="h-9 min-w-0 flex-1 rounded-xl border-white/10 bg-white/5 text-[13.5px] font-semibold text-zinc-50 focus-visible:border-emerald-400/60 focus-visible:ring-emerald-400/30"
                                  />
                                ) : (
                                  <button
                                    type="button"
                                    onClick={() => openMembers(folder)}
                                    aria-label={`Edit chats in folder ${folder.name} (${folder.conversationIds.length} chats)`}
                                    className="flex min-w-0 flex-1 items-center gap-2 rounded-xl py-1 text-left outline-none"
                                  >
                                    <span className="min-w-0 flex-1">
                                      <span className="block truncate text-[14px] font-semibold text-zinc-100">
                                        {folder.name}
                                      </span>
                                      <span className="block text-[11px] font-medium text-zinc-500">
                                        {folder.conversationIds.length === 1
                                          ? '1 chat'
                                          : `${folder.conversationIds.length} chats`}
                                      </span>
                                    </span>
                                    <ChevronRight className="size-4 shrink-0 text-zinc-600" aria-hidden />
                                  </button>
                                )}
                                {renaming ? (
                                  <motion.button
                                    type="button"
                                    aria-label="Save name"
                                    disabled={renameM.isPending || !renameValue.trim()}
                                    whileTap={reducedMotion ? undefined : pressTap}
                                    transition={pressSpring}
                                    onClick={() => renameM.mutate({ folderId: folder.id, name: renameValue.trim() })}
                                    className="flex size-9 shrink-0 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-400 outline-none ring-1 ring-emerald-400/40 disabled:opacity-40"
                                  >
                                    <Check className="size-4" aria-hidden />
                                  </motion.button>
                                ) : (
                                  <motion.button
                                    type="button"
                                    aria-label={`Rename folder ${folder.name}`}
                                    whileTap={reducedMotion ? undefined : pressTap}
                                    transition={pressSpring}
                                    onClick={() => {
                                      haptic(4)
                                      setRenamingId(folder.id)
                                      setRenameValue(folder.name)
                                    }}
                                    className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 focus-visible:ring-emerald-400/60"
                                  >
                                    <Pencil className="size-4" aria-hidden />
                                  </motion.button>
                                )}
                                {renaming ? (
                                  <motion.button
                                    type="button"
                                    aria-label="Cancel rename"
                                    whileTap={reducedMotion ? undefined : pressTap}
                                    transition={pressSpring}
                                    onClick={() => setRenamingId(null)}
                                    className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 focus-visible:ring-emerald-400/60"
                                  >
                                    <X className="size-4" aria-hidden />
                                  </motion.button>
                                ) : (
                                  <motion.button
                                    type="button"
                                    aria-label={
                                      armed ? `Tap again to delete folder ${folder.name}` : `Delete folder ${folder.name}`
                                    }
                                    whileTap={reducedMotion ? undefined : pressTap}
                                    transition={pressSpring}
                                    onClick={() => {
                                      if (armed) {
                                        deleteM.mutate(folder.id)
                                      } else {
                                        haptic(8)
                                        setConfirmDeleteId(folder.id)
                                      }
                                    }}
                                    className={cn(
                                      'flex size-9 shrink-0 items-center justify-center rounded-full outline-none transition-colors focus-visible:ring-2 focus-visible:ring-emerald-400/60',
                                      armed
                                        ? 'bg-rose-500 text-white'
                                        : 'text-zinc-400 hover:bg-white/10 hover:text-rose-400',
                                    )}
                                  >
                                    <Trash2 className="size-4" aria-hidden />
                                  </motion.button>
                                )}
                              </div>
                              {armed && !renaming ? (
                                <motion.p
                                  initial={reducedMotion ? false : { opacity: 0, height: 0 }}
                                  animate={{ opacity: 1, height: 'auto' }}
                                  className="px-3 pb-2 text-[11px] font-semibold text-rose-400"
                                  aria-live="polite"
                                >
                                  Tap the trash again to delete — the chats themselves stay.
                                </motion.p>
                              ) : null}
                            </motion.li>
                          )
                        })}
                      </AnimatePresence>
                    </motion.ul>
                  )}
                </motion.div>
              ) : (
                <motion.div
                  key={`members-${editingFolder?.id ?? 'none'}`}
                  initial={reducedMotion ? false : { opacity: 0, x: 14 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={reducedMotion ? undefined : { opacity: 0, x: -14 }}
                  transition={{ duration: 0.18, ease: ease.out }}
                  className="flex flex-col gap-2"
                >
                  <p className="px-1 text-[12px] leading-relaxed text-zinc-500">
                    Tick the chats that live in{' '}
                    <span className="font-semibold text-zinc-300">
                      {editingFolder?.emoji} {editingFolder?.name}
                    </span>
                    . Saving replaces the folder contents.
                  </p>
                  {memberRows.length === 0 ? (
                    <p className="rounded-2xl border border-dashed border-white/10 px-4 py-6 text-center text-[12.5px] text-zinc-500">
                      You have no chats yet — start one first.
                    </p>
                  ) : (
                    <motion.ul
                      variants={reducedMotion ? undefined : listVariants}
                      initial={reducedMotion ? false : 'hidden'}
                      animate="shown"
                      className="flex flex-col gap-1"
                      aria-label="All chats"
                    >
                      {memberRows.map(({ conv, name }, i) => {
                        const checked = checkedIds.has(conv.id)
                        const other = !conv.isGroup ? conv.members.find((m) => m.id !== me.id) ?? conv.members[0] : null
                        return (
                          <motion.li
                            key={conv.id}
                            variants={reducedMotion ? undefined : rowVariants}
                            style={reducedMotion ? undefined : { transitionDelay: `${stagger(i, 0.025, 12)}s` }}
                          >
                            <motion.button
                              type="button"
                              role="checkbox"
                              aria-checked={checked}
                              aria-label={`${name} — ${conv.isGroup ? 'group chat' : 'direct chat'}`}
                              whileTap={reducedMotion ? undefined : { scale: 0.98 }}
                              transition={pressSpring}
                              onClick={() => toggleChecked(conv.id)}
                              className={cn(
                                'flex w-full items-center gap-2.5 rounded-2xl border px-2.5 py-2 text-left outline-none transition-colors',
                                checked
                                  ? 'border-emerald-400/40 bg-emerald-500/10'
                                  : 'border-white/10 bg-white/5 hover:bg-white/10',
                              )}
                            >
                              {conv.isGroup ? (
                                <span
                                  aria-hidden
                                  className="flex size-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-violet-400 to-purple-600 text-[13px] font-bold text-white"
                                >
                                  {name.slice(0, 1).toUpperCase()}
                                </span>
                              ) : (
                                <UserAvatar name={other?.name ?? name} color={other?.color ?? 'emerald'} size={36} />
                              )}
                              <span className="min-w-0 flex-1">
                                <span className="block truncate text-[13.5px] font-semibold text-zinc-100">{name}</span>
                                <span className="block text-[10.5px] font-medium uppercase tracking-wide text-zinc-500">
                                  {conv.isGroup ? 'Group' : 'Direct'}
                                </span>
                              </span>
                              <span
                                aria-hidden
                                className={cn(
                                  'flex size-6 shrink-0 items-center justify-center rounded-lg transition-colors',
                                  checked ? 'bg-emerald-500 text-white' : 'bg-white/10 text-transparent',
                                )}
                              >
                                <Check className="size-3.5" strokeWidth={3} />
                              </span>
                            </motion.button>
                          </motion.li>
                        )
                      })}
                    </motion.ul>
                  )}
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* members save bar */}
          {mode === 'members' && editingFolder ? (
            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={spring.soft}
              className="shrink-0 border-t border-white/10 pt-2.5"
            >
              <Button
                type="button"
                disabled={saveM.isPending}
                onClick={() => saveM.mutate({ folderId: editingFolder.id, conversationIds: [...checkedIds] })}
                aria-label={`Save chats in folder ${editingFolder.name}`}
                className="h-12 w-full rounded-2xl border-0 bg-gradient-to-r from-emerald-500 to-teal-600 text-[15px] font-bold text-white shadow-[0_12px_28px_-12px_rgba(16,185,129,0.8)] transition-transform active:scale-[0.98] disabled:from-zinc-700 disabled:to-zinc-700 disabled:text-zinc-400 disabled:shadow-none"
              >
                {saveM.isPending ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <>Save {checkedIds.size === 1 ? '1 chat' : `${checkedIds.size} chats`}</>
                )}
              </Button>
            </motion.div>
          ) : null}
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}
