// ─────────────────────────────────────────────────────────────
// Pulse Chat — "Forward message" bottom sheet.
// Multi-select destination chats → re-posts the original payload
// (text / image / voice note / document) into each picked
// conversation via the standard message API. No schema changes —
// attachments reuse the already-uploaded file paths.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Forward, LoaderCircle, Search, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson, conversationDisplayName } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Input } from '@/components/ui/input'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'

export interface ForwardPayload {
  content: string
  imagePath: string | null
  audioPath: string | null
  durationMs: number | null
  /** R41 additive — document attachment (kind 'file'): forwarded copies are
   *  REAL downloadable documents at the destination, not caption text. */
  filePath: string | null
  fileName: string | null
  fileSize: number | null
}

interface SendResponse {
  message: unknown
}

/** fileName shown truncated on the preview strip (mirrors Photo/Voice labels). */
const FILE_PREVIEW_MAX = 28

/** One-line human summary of what is being forwarded. */
export function forwardPreviewLabel(payload: ForwardPayload): string {
  const text = payload.content.replace(/\s+/g, ' ').trim()
  if (payload.imagePath !== null) return text.length > 0 ? text : 'Photo'
  if (payload.audioPath !== null) return 'Voice message'
  if (payload.filePath !== null) {
    const name = (payload.fileName ?? '').replace(/\s+/g, ' ').trim()
    if (name.length === 0) return 'Document'
    return name.length > FILE_PREVIEW_MAX ? `${name.slice(0, FILE_PREVIEW_MAX)}…` : name
  }
  return text.length > 0 ? text : 'Message'
}

export function ForwardSheet({
  me,
  open,
  onOpenChange,
  payload,
  /** id of the conversation the forward originates from (labelled "(here)") */
  originId,
}: {
  me: AppUser
  open: boolean
  onOpenChange: (open: boolean) => void
  payload: ForwardPayload
  originId: string
}) {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [pickedIds, setPickedIds] = useState<string[]>([])

  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<{ conversations: ConversationSummary[] }>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    enabled: open,
    staleTime: 5_000,
  })

  const rows = useMemo(() => {
    const list = conversations.data ?? []
    const needle = search.trim().toLowerCase()
    const filtered =
      needle.length === 0
        ? list
        : list.filter((c) => conversationDisplayName(c, me.id).toLowerCase().includes(needle))
    return filtered.map((c) => ({
      conversation: c,
      title: conversationDisplayName(c, me.id),
      isHere: c.id === originId,
    }))
  }, [conversations.data, search, me.id, originId])

  const forward = useMutation({
    mutationFn: async (targets: string[]) => {
      let done = 0
      for (const targetId of targets) {
        await apiJson<SendResponse>(
          `/api/conversations/${encodeURIComponent(targetId)}/messages`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              senderId: me.id,
              content: payload.content,
              ...(payload.imagePath ? { imagePath: payload.imagePath } : {}),
              ...(payload.audioPath
                ? {
                    audioPath: payload.audioPath,
                    ...(payload.durationMs ? { durationMs: payload.durationMs } : {}),
                  }
                : {}),
              // R41 — documents forward as REAL kind 'file' messages (the API
              // requires kind 'file' + filePath + fileName; fileSize optional).
              ...(payload.filePath
                ? {
                    kind: 'file',
                    filePath: payload.filePath,
                    fileName: payload.fileName ?? '',
                    ...(payload.fileSize !== null ? { fileSize: payload.fileSize } : {}),
                  }
                : {}),
            }),
          },
        )
        done += 1
      }
      return done
    },
    onSuccess: (done) => {
      haptic(16)
      toast.success(done === 1 ? 'Forwarded to 1 chat' : `Forwarded to ${done} chats`)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      onOpenChange(false)
    },
    onError: () => {
      toast.error('Could not forward — check your connection and try again')
    },
  })

  const togglePick = (conversationId: string) => {
    haptic(8)
    setPickedIds((prev) =>
      prev.includes(conversationId)
        ? prev.filter((id) => id !== conversationId)
        : [...prev, conversationId],
    )
  }

  const previewLabel = forwardPreviewLabel(payload)

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="mx-auto w-full max-w-[420px] rounded-t-[1.75rem] border-t border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
        <div className="mx-4 flex min-h-0 flex-col pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          <div className="flex items-center justify-between pb-1">
            <div>
              <DrawerTitle className="flex items-center gap-1.5 text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                <Forward className="size-4 text-emerald-500" aria-hidden />
                Forward to…
              </DrawerTitle>
              <DrawerDescription className="sr-only">
                Pick one or more chats to forward this message into
              </DrawerDescription>
            </div>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close"
              onClick={() => onOpenChange(false)}
              className="size-9 rounded-full text-zinc-400 hover:text-zinc-600 active:scale-95 dark:hover:text-zinc-300"
            >
              <X className="size-[18px]" aria-hidden />
            </Button>
          </div>

          {/* preview strip */}
          <div className="mb-2 flex items-center gap-2 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 dark:border-zinc-700 dark:bg-zinc-800/60">
            <span className="truncate text-xs font-medium text-zinc-600 dark:text-zinc-300" aria-label="Message to forward">
              {previewLabel.slice(0, 90)}
            </span>
          </div>

          {/* search */}
          <div className="relative mb-2">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400" aria-hidden />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search chats…"
              aria-label="Search chats"
              className="h-10 rounded-xl border-zinc-200 bg-zinc-50 pl-9 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
            />
          </div>

          {/* chat list */}
          <ul className="pulse-scroll max-h-[46vh] min-h-[120px] space-y-1 overflow-y-auto pr-1">
            {conversations.isPending ? (
              <li role="status" aria-label="Loading chats" className="flex justify-center py-6">
                <LoaderCircle className="size-5 animate-spin text-zinc-400" aria-hidden />
              </li>
            ) : rows.length === 0 ? (
              <li className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
                No chats match “{search.trim()}”.
              </li>
            ) : (
              rows.map(({ conversation, title, isHere }) => {
                const picked = pickedIds.includes(conversation.id)
                return (
                  <li key={conversation.id}>
                    <button
                      type="button"
                      role="checkbox"
                      aria-checked={picked}
                      onClick={() => togglePick(conversation.id)}
                      className={cn(
                        'flex w-full items-center gap-3 rounded-xl p-2.5 text-left outline-none transition-colors',
                        picked
                          ? 'bg-emerald-500/10 ring-1 ring-emerald-400/60'
                          : 'bg-zinc-50 hover:bg-zinc-100 dark:bg-zinc-800/60 dark:hover:bg-zinc-800',
                      )}
                    >
                      {conversation.isGroup ? (
                        <GroupAvatar title={title} id={conversation.id} size={38} />
                      ) : (
                        (() => {
                          const other = conversation.members.find((m) => m.id !== me.id)
                          return (
                            <UserAvatar name={title} color={other?.color} size={38} />
                          )
                        })()
                      )}
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                          {title}
                          {isHere ? <span className="ml-1 text-xs font-normal text-zinc-400">(here)</span> : null}
                        </span>
                      </span>
                      <span
                        className={cn(
                          'flex size-5 shrink-0 items-center justify-center rounded-full border-2 transition-colors',
                          picked
                            ? 'border-emerald-500 bg-emerald-500 text-white'
                            : 'border-zinc-300 dark:border-zinc-600',
                        )}
                        aria-hidden
                      >
                        {picked ? <Check className="size-3" /> : null}
                      </span>
                    </button>
                  </li>
                )
              })
            )}
          </ul>

          {/* CTA */}
          <Button
            disabled={pickedIds.length === 0 || forward.isPending}
            onClick={() => forward.mutate(pickedIds)}
            className="mt-2 h-11 w-full gap-2 rounded-2xl bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/20 hover:bg-emerald-500/90 disabled:opacity-50"
          >
            {forward.isPending ? (
              <>
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
                Sending…
              </>
            ) : (
              <>
                <Forward className="size-4" aria-hidden />
                Send to {pickedIds.length > 0 ? pickedIds.length : ''}{' '}
                {pickedIds.length === 1 ? 'chat' : 'chats'}
              </>
            )}
          </Button>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
