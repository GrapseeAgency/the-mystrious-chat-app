// ─────────────────────────────────────────────────────────────
// Pulse — Group Info Sheet (full-screen, Telegram-grade).
// Real APIs only:
//  - GET    /api/conversations/[id]?userId=me          → { conversation: ConversationDetail }
//  - PATCH  /api/conversations/[id]                    { requesterId, name }              (admin rename)
//  - POST   /api/conversations/[id]/members            { requesterId, userIds: [] }       (admin add)
//  - PATCH  /api/conversations/[id]/members            { requesterId, userId, role }      (admin promote/demote)
//  - DELETE /api/conversations/[id]/members/[userId]   { requesterId }                    (admin remove member)
//  - DELETE /api/conversations/[id]/members            { requesterId }                    (leave, auto-succession)
//  - GET    /api/users                                 → { users: AppUser[] }             (add-member picker)
// Member taps open <UserProfileSheet>. Cache shares the
// ['conversation', conversationId] key used by chat-room.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AtSign,
  Check,
  ChevronLeft,
  Copy,
  Crown,
  EllipsisVertical,
  LoaderCircle,
  LogOut,
  Megaphone,
  Pencil,
  Plus,
  Search,
  Trash2,
  UserPlus,
  UsersRound,
  Webhook,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationDetail, GroupRole } from '@/lib/types'
import {
  apiJson,
  formatListStamp,
  gradientFor,
  groupGradientFor,
  initialsOf,
  jsonBody,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Checkbox } from '@/components/ui/checkbox'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { UserAvatar } from '@/components/chat/user-avatar'
import { UserProfileSheet } from '@/components/chat/user-profile-sheet'

/** Mirrors GROUP_NAME_MAX in src/lib/serializers.ts (PATCH route enforces 1–48). */
const GROUP_NAME_MAX = 48
/** Mirrors WEBHOOK_NAME_MAX in src/app/api/webhooks/route.ts (POST enforces 1–32). */
const WEBHOOK_NAME_MAX = 32

interface ConversationResponse {
  conversation: ConversationDetail
}
interface UsersResponse {
  users: AppUser[]
}
interface AddedResponse {
  conversation: ConversationDetail
  added: string[]
}
interface LeaveResponse {
  ok: boolean
  remainingMembers: number
  promotedUserId: string | null
}

/** Row of GET /api/webhooks?conversationId= — Discord-style incoming hook. */
interface WebhookItem {
  id: string
  name: string
  token: string
  avatarColor: string
  url: string
  createdAt: string
  createdBy: string
}
interface WebhooksResponse {
  webhooks: WebhookItem[]
}

/** Spring stagger for the webhook rows (matches the sheet's micro-interaction set). */
const webhookListVariants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.045 } },
}
const webhookRowVariants = {
  hidden: { opacity: 0, y: 10 },
  show: {
    opacity: 1,
    y: 0,
    transition: { type: 'spring' as const, stiffness: 420, damping: 32 },
  },
}

/** Modal identity — parameterized variants carry the target member id. */
type SheetModalKind = 'rename' | 'add' | 'leave' | `promote:${string}` | `demote:${string}` | `remove:${string}` | null

function modalTarget(kind: SheetModalKind): { kind: string; userId: string | null } {
  if (kind === null || !kind.includes(':')) return { kind: kind ?? 'none', userId: null }
  const [k, id] = kind.split(':')
  return { kind: k, userId: id }
}

export function GroupInfoSheet({
  open,
  onClose,
  conversationId,
  meId,
}: {
  open: boolean
  onClose: () => void
  conversationId: string | null
  meId: string
}) {
  return (
    <AnimatePresence>
      {open && conversationId !== null ? (
        <motion.div
          key={`group-info-${conversationId}`}
          initial={{ x: '100%' }}
          animate={{ x: 0 }}
          exit={{ x: '100%' }}
          transition={{ type: 'spring', stiffness: 320, damping: 34 }}
          className="fixed inset-0 z-[70] flex flex-col bg-white shadow-2xl dark:bg-zinc-900"
          role="dialog"
          aria-modal="true"
          aria-label="Conversation info"
        >
          <GroupInfoInner
            conversationId={conversationId}
            meId={meId}
            onClose={onClose}
          />
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

/** Everything stateful lives here; unmounts with the sheet so nested layers never linger. */
function GroupInfoInner({
  conversationId,
  meId,
  onClose,
}: {
  conversationId: string
  meId: string
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [modal, setModal] = useState<SheetModalKind>(null)
  const [profileUser, setProfileUser] = useState<AppUser | null>(null)

  const detailQ = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: async (): Promise<ConversationDetail> => {
      const res = await apiJson<ConversationResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}?userId=${encodeURIComponent(meId)}`,
      )
      return res.conversation
    },
    staleTime: 10_000,
    refetchInterval: 30_000,
  })

  const detail = detailQ.data ?? null
  const isGroup = detail?.isGroup ?? false
  const myRole: GroupRole = detail?.members.find((m) => m.id === meId)?.role ?? 'member'
  const isAdmin = myRole === 'admin'
  const adminCount = detail?.members.filter((m) => m.role === 'admin').length ?? 0

  // Escape closes the topmost layer only (modal > profile drawer > sheet).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      if (modal !== null || profileUser !== null) return // an inner layer owns this Escape
      onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [modal, profileUser, onClose])

  const leaveGroup = useMutation({
    mutationFn: async (): Promise<LeaveResponse> => {
      return apiJson<LeaveResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        {
          method: 'DELETE',
          body: JSON.stringify({ requesterId: meId }),
        },
      )
    },
    onSuccess: (data) => {
      const promoted = data.promotedUserId
        ? (detail?.members.find((m) => m.id === data.promotedUserId)?.name ?? null)
        : null
      toast.success(
        promoted ? `You left the group — ${promoted} is now an admin` : 'You left the group',
      )
      void queryClient.invalidateQueries({ queryKey: ['conversations', meId] })
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      setModal(null)
      onClose()
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not leave the group')
    },
  })

  return (
    <>
      {/* header */}
      <header className="relative z-10 flex min-h-14 shrink-0 items-center gap-1 border-b border-zinc-200 bg-white/90 px-2 pt-[env(safe-area-inset-top)] backdrop-blur-md dark:border-zinc-800 dark:bg-zinc-900/90">
        <Button
          variant="ghost"
          size="icon"
          aria-label="Back to chat"
          onClick={onClose}
          className="size-10 shrink-0 rounded-full text-zinc-600 hover:bg-transparent hover:text-zinc-900 active:scale-95 dark:text-zinc-300 dark:hover:text-white"
        >
          <ChevronLeft className="size-6" aria-hidden />
        </Button>
        <p className="text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          {isGroup ? 'Group info' : 'Chat info'}
        </p>
      </header>

      {/* body */}
      {detailQ.isPending ? (
        <InfoSkeleton isGroup={isGroup} />
      ) : detailQ.isError || detail === null ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-8 text-center">
          <UsersRound className="size-8 text-zinc-300 dark:text-zinc-600" aria-hidden />
          <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">
            Could not load the conversation info.
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void detailQ.refetch()}
            className="h-9 rounded-full px-4 text-xs font-semibold"
          >
            Try again
          </Button>
        </div>
      ) : isGroup ? (
        <GroupInfoBody
          detail={detail}
          meId={meId}
          isAdmin={isAdmin}
          adminCount={adminCount}
          onOpenModal={setModal}
          onOpenProfile={setProfileUser}
        />
      ) : (
        <DmInfoBody detail={detail} meId={meId} onOpenProfile={setProfileUser} />
      )}

      {/* nested modals — absolute inside the sheet so they layer above z-[70] */}
      {detail !== null && isGroup ? (
        <>
          <RenameModal
            open={modal === 'rename'}
            onClose={() => setModal(null)}
            conversationId={conversationId}
            meId={meId}
            currentName={detail.name ?? ''}
          />
          <AddMembersModal
            open={modal === 'add'}
            onClose={() => setModal(null)}
            conversationId={conversationId}
            meId={meId}
            existingIds={detail.members.map((m) => m.id)}
          />
          <LeaveModal
            open={modal === 'leave'}
            onClose={() => setModal(null)}
            groupName={detail.name?.trim() || 'this group'}
            pending={leaveGroup.isPending}
            onConfirm={() => leaveGroup.mutate()}
          />
        </>
      ) : null}

      {/* role/remove confirms + the member profile drawer (portaled) */}
      <MemberActionHost
        conversationId={conversationId}
        meId={meId}
        members={detail?.members ?? []}
        modal={modal}
        setModal={setModal}
        profileUser={profileUser}
        onCloseProfile={() => setProfileUser(null)}
      />
    </>
  )
}

// ── group body ───────────────────────────────────────────────

function GroupInfoBody({
  detail,
  meId,
  isAdmin,
  adminCount,
  onOpenModal,
  onOpenProfile,
}: {
  detail: ConversationDetail
  meId: string
  isAdmin: boolean
  adminCount: number
  onOpenModal: (m: SheetModalKind) => void
  onOpenProfile: (u: AppUser) => void
}) {
  const groupName = detail.name?.trim() || 'Group'
  const gradient = groupGradientFor(detail.id)
  return (
    <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain">
      {/* hero */}
      <div className={cn('relative overflow-hidden px-5 pb-5 pt-7 text-white', gradient)}>
        <div className="absolute -right-10 -top-12 size-44 rounded-full bg-white/15 blur-2xl" />
        <div className="absolute -bottom-14 -left-8 size-40 rounded-full bg-black/10 blur-2xl" />
        <div className="relative flex items-center gap-4">
          <div className="flex size-[72px] shrink-0 items-center justify-center rounded-[22px] border-2 border-white/40 bg-black/10 text-2xl font-black tracking-tight">
            {initialsOf(groupName)}
          </div>
          <div className="min-w-0">
            <h2 className="truncate text-xl font-bold leading-tight tracking-tight">{groupName}</h2>
            <p className="mt-0.5 text-[13px] font-medium text-white/85">
              {detail.members.length} {detail.members.length === 1 ? 'member' : 'members'} ·{' '}
              {adminCount} {adminCount === 1 ? 'admin' : 'admins'}
            </p>
            {detail.broadcastMode ? (
              <Badge className="mt-1.5 gap-1 border-white/30 bg-black/25 text-[10px] font-semibold uppercase tracking-wide text-white hover:bg-black/25">
                <Megaphone className="size-3" aria-hidden />
                Announcements only
              </Badge>
            ) : null}
          </div>
        </div>
        {detail.broadcastMode ? (
          <p className="relative mt-3 flex items-center gap-1.5 text-[11px] leading-snug text-white/75">
            <Megaphone className="size-3.5 shrink-0" aria-hidden />
            Only admins can send messages while announcement mode is on.
          </p>
        ) : null}
      </div>

      {/* actions row */}
      <div className="flex items-stretch gap-2 px-4 py-4">
        {isAdmin ? (
          <>
            <ActionTile
              label="Add member"
              onClick={() => onOpenModal('add')}
              icon={<UserPlus className="size-5" aria-hidden />}
            />
            <ActionTile
              label="Rename"
              onClick={() => onOpenModal('rename')}
              icon={<Pencil className="size-5" aria-hidden />}
            />
          </>
        ) : null}
        <ActionTile
          label="Leave group"
          destructive
          wide={!isAdmin}
          onClick={() => onOpenModal('leave')}
          icon={<LogOut className="size-5" aria-hidden />}
        />
      </div>

      {/* webhooks — Discord-style incoming integrations */}
      <WebhooksSection conversationId={detail.id} meId={meId} isAdmin={isAdmin} />

      {/* members */}
      <div className="px-4 pb-[max(1.5rem,env(safe-area-inset-bottom))]">
        <div className="mb-2 flex items-center justify-between px-1">
          <h3 className="text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
            Members
          </h3>
          <span className="text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
            {detail.members.length}
          </span>
        </div>
        <ul className="pulse-scroll max-h-[46dvh] space-y-0.5 overflow-y-auto rounded-2xl border border-zinc-200 bg-zinc-50/60 p-1.5 dark:border-zinc-800 dark:bg-zinc-800/40">
          {detail.members.length === 0 ? (
            <li className="px-3 py-6 text-center text-xs text-zinc-500 dark:text-zinc-400">
              No members yet.
            </li>
          ) : (
            [...detail.members]
              .sort((a, b) => {
                if (a.role !== b.role) return a.role === 'admin' ? -1 : 1
                return a.name.localeCompare(b.name)
              })
              .map((member) => (
                <MemberRow
                  key={member.id}
                  member={member}
                  meId={meId}
                  isAdmin={isAdmin}
                  onOpenProfile={onOpenProfile}
                  onOpenModal={onOpenModal}
                />
              ))
          )}
        </ul>
      </div>
    </div>
  )
}

function ActionTile({
  label,
  icon,
  onClick,
  destructive = false,
  wide = false,
}: {
  label: string
  icon: ReactNode
  onClick: () => void
  destructive?: boolean
  wide?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      className={cn(
        'flex min-h-[72px] flex-1 flex-col items-center justify-center gap-1.5 rounded-2xl border p-2 outline-none transition-all active:scale-[0.96]',
        wide && 'flex-[1.4]',
        destructive
          ? 'border-destructive/25 bg-destructive/5 text-destructive hover:bg-destructive/10'
          : 'border-zinc-200 bg-white text-zinc-700 shadow-sm hover:border-emerald-300 hover:bg-emerald-500/5 hover:text-emerald-700 dark:border-zinc-700 dark:bg-zinc-800/60 dark:text-zinc-200 dark:hover:border-emerald-500/40 dark:hover:text-emerald-400',
      )}
    >
      {icon}
      <span className="text-[11px] font-semibold tracking-tight">{label}</span>
    </button>
  )
}

// ── webhooks (Discord-style incoming integrations) ───────────

/**
 * Management card for incoming webhooks. Everyone sees the list and can
 * copy ingest URLs; creation + deletion are admin-only. Data is fully
 * real: GET/POST /api/webhooks + DELETE /api/webhooks/[token].
 */
function WebhooksSection({
  conversationId,
  meId,
  isAdmin,
}: {
  conversationId: string
  meId: string
  isAdmin: boolean
}) {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<WebhookItem | null>(null)

  const webhooksQ = useQuery({
    queryKey: ['webhooks', conversationId],
    queryFn: async (): Promise<WebhookItem[]> => {
      const res = await apiJson<WebhooksResponse>(
        `/api/webhooks?conversationId=${encodeURIComponent(conversationId)}&requesterId=${encodeURIComponent(meId)}`,
      )
      return res.webhooks
    },
    staleTime: 5_000,
  })

  const copyUrl = async (webhook: WebhookItem) => {
    try {
      await navigator.clipboard.writeText(`${location.origin}/api/webhooks/${webhook.token}`)
      toast.success('Webhook URL copied')
    } catch {
      toast.error('Could not copy the URL')
    }
  }

  const deleteWebhook = useMutation({
    mutationFn: async (token: string) => {
      return apiJson<{ ok: boolean }>(
        `/api/webhooks/${encodeURIComponent(token)}?requesterId=${encodeURIComponent(meId)}`,
        { method: 'DELETE' },
      )
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['webhooks', conversationId] })
      toast.success('Webhook deleted')
      setDeleteTarget(null)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not delete the webhook')
    },
  })

  const webhooks = webhooksQ.data ?? []

  return (
    <div className="px-4 pb-1">
      <div className="mb-2 flex items-center justify-between px-1">
        <h3 className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
          <Webhook className="size-3.5" aria-hidden />
          Webhooks
        </h3>
        <span className="text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
          {webhooks.length}
        </span>
      </div>

      {webhooksQ.isPending ? (
        <div
          className="space-y-2 rounded-2xl border border-zinc-200 bg-zinc-50/60 p-3 dark:border-zinc-800 dark:bg-zinc-800/40"
          aria-busy="true"
          aria-label="Loading webhooks"
        >
          {Array.from({ length: 2 }).map((_, i) => (
            <div key={i} className="flex items-center gap-3">
              <Skeleton className="size-8 rounded-full" />
              <div className="flex-1 space-y-1.5">
                <Skeleton className="h-3.5 w-1/2 rounded-md" />
                <Skeleton className="h-3 w-1/3 rounded-md" />
              </div>
            </div>
          ))}
        </div>
      ) : webhooksQ.isError ? (
        <div className="rounded-2xl border border-destructive/25 bg-destructive/5 p-4 text-center">
          <p className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
            Could not load webhooks.
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void webhooksQ.refetch()}
            className="mt-2 h-8 rounded-full px-4 text-xs font-semibold"
          >
            Try again
          </Button>
        </div>
      ) : webhooks.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-zinc-300 p-5 text-center dark:border-zinc-700">
          <Webhook className="mx-auto size-6 text-zinc-300 dark:text-zinc-600" aria-hidden />
          <p className="mt-2 text-xs font-medium text-zinc-500 dark:text-zinc-400">
            No webhooks yet
          </p>
          <p className="mt-1 text-[11px] leading-relaxed text-zinc-400 dark:text-zinc-500">
            {isAdmin
              ? 'Create one to let outside services post into this chat.'
              : 'Admins can add Discord-style integrations here.'}
          </p>
        </div>
      ) : (
        <motion.ul
          variants={webhookListVariants}
          initial="hidden"
          animate="show"
          className="pulse-scroll max-h-52 space-y-0.5 overflow-y-auto rounded-2xl border border-zinc-200 bg-zinc-50/60 p-1.5 dark:border-zinc-800 dark:bg-zinc-800/40"
        >
          {webhooks.map((webhook) => (
            <WebhookRow
              key={webhook.id}
              webhook={webhook}
              isAdmin={isAdmin}
              onCopy={copyUrl}
              onDelete={() => setDeleteTarget(webhook)}
            />
          ))}
        </motion.ul>
      )}

      {isAdmin ? (
        <Button
          type="button"
          variant="outline"
          onClick={() => setCreateOpen(true)}
          className="mt-2 h-11 w-full rounded-xl border-emerald-500/40 text-sm font-semibold text-emerald-700 hover:bg-emerald-500/10 hover:text-emerald-800 active:scale-[0.98] dark:text-emerald-400 dark:hover:text-emerald-300"
        >
          <Plus className="size-4" aria-hidden />
          Create webhook
        </Button>
      ) : null}

      {createOpen ? (
        <WebhookCreateModal
          onClose={() => setCreateOpen(false)}
          conversationId={conversationId}
          meId={meId}
        />
      ) : null}
      {deleteTarget ? (
        <WebhookDeleteModal
          target={deleteTarget}
          pending={deleteWebhook.isPending}
          onClose={() => setDeleteTarget(null)}
          onConfirm={() => deleteWebhook.mutate(deleteTarget.token)}
        />
      ) : null}
    </div>
  )
}

function WebhookRow({
  webhook,
  isAdmin,
  onCopy,
  onDelete,
}: {
  webhook: WebhookItem
  isAdmin: boolean
  onCopy: (webhook: WebhookItem) => Promise<void>
  onDelete: () => void
}) {
  return (
    <motion.li
      variants={webhookRowVariants}
      className="flex min-h-[52px] items-center gap-3 rounded-xl px-2 py-1.5"
    >
      <span
        className={cn('size-3 shrink-0 rounded-full bg-gradient-to-br shadow-sm', gradientFor(webhook.avatarColor))}
        aria-hidden
      />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-zinc-800 dark:text-zinc-100">
          {webhook.name}
        </p>
        <p className="mt-0.5 truncate text-[11px] text-zinc-400 dark:text-zinc-500">
          /api/webhooks/{webhook.token} · added {formatListStamp(webhook.createdAt)}
        </p>
      </div>
      <motion.button
        type="button"
        whileTap={{ scale: 0.88 }}
        onClick={() => void onCopy(webhook)}
        aria-label={`Copy ${webhook.name} webhook URL`}
        className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-200/70 hover:text-zinc-700 focus-visible:ring-2 focus-visible:ring-emerald-500/50 dark:hover:bg-zinc-700 dark:hover:text-zinc-200"
      >
        <Copy className="size-4" aria-hidden />
      </motion.button>
      {isAdmin ? (
        <motion.button
          type="button"
          whileTap={{ scale: 0.88 }}
          onClick={onDelete}
          aria-label={`Delete ${webhook.name}`}
          className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-destructive/10 hover:text-destructive focus-visible:ring-2 focus-visible:ring-destructive/50"
        >
          <Trash2 className="size-4" aria-hidden />
        </motion.button>
      ) : null}
    </motion.li>
  )
}

function WebhookCreateModal({
  onClose,
  conversationId,
  meId,
}: {
  onClose: () => void
  conversationId: string
  meId: string
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const trimmed = name.trim()
  const valid = trimmed.length > 0 && trimmed.length <= WEBHOOK_NAME_MAX

  const create = useMutation({
    mutationFn: async (): Promise<WebhookItem> => {
      return apiJson<WebhookItem>(
        '/api/webhooks',
        jsonBody({ conversationId, name: trimmed, requesterId: meId }),
      )
    },
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ['webhooks', conversationId] })
      toast.success(`Webhook “${created.name}” created`)
      onClose()
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not create the webhook')
    },
  })

  return (
    <SheetModal open onClose={onClose} label="Create webhook">
      <form
        onSubmit={(event) => {
          event.preventDefault()
          if (valid && !create.isPending) create.mutate()
        }}
      >
        <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
          Create webhook
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
          Outside services POST to its URL and drop messages into this chat as a named sender.
        </p>
        <div className="mt-3 space-y-1.5">
          <Label htmlFor="webhook-name-input" className="sr-only">
            Webhook name
          </Label>
          <Input
            id="webhook-name-input"
            value={name}
            maxLength={WEBHOOK_NAME_MAX}
            onChange={(event) => setName(event.target.value.slice(0, WEBHOOK_NAME_MAX))}
            placeholder="e.g. Deploys · CI · Weather"
            autoFocus
            autoComplete="off"
            enterKeyHint="done"
            aria-invalid={trimmed.length === 0 || undefined}
            className="h-11 rounded-xl border-zinc-200 bg-zinc-50 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
          />
          <p className="text-right text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
            {trimmed.length}/{WEBHOOK_NAME_MAX}
          </p>
        </div>
        <div className="mt-3 flex gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            className="h-10 flex-1 rounded-xl text-sm font-semibold"
          >
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!valid || create.isPending}
            className="h-10 flex-1 rounded-xl bg-emerald-600 text-sm font-semibold text-white hover:bg-emerald-500 active:scale-[0.98]"
          >
            {create.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              'Create'
            )}
          </Button>
        </div>
      </form>
    </SheetModal>
  )
}

function WebhookDeleteModal({
  target,
  pending,
  onClose,
  onConfirm,
}: {
  target: WebhookItem
  pending: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <SheetModal open onClose={onClose} label="Delete webhook">
      <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
        Delete “{target.name}”?
      </h3>
      <p className="mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
        Its URL stops working immediately. Messages it already posted stay in the chat.
      </p>
      <div className="mt-4 flex gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={onClose}
          className="h-10 flex-1 rounded-xl text-sm font-semibold"
        >
          Cancel
        </Button>
        <Button
          type="button"
          variant="destructive"
          onClick={onConfirm}
          disabled={pending}
          className="h-10 flex-1 rounded-xl text-sm font-semibold active:scale-[0.98]"
        >
          {pending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Delete'}
        </Button>
      </div>
    </SheetModal>
  )
}

// ── member row ───────────────────────────────────────────────

function MemberRow({
  member,
  meId,
  isAdmin,
  onOpenProfile,
  onOpenModal,
}: {
  member: ConversationDetail['members'][number]
  meId: string
  isAdmin: boolean
  onOpenProfile: (u: AppUser) => void
  onOpenModal: (m: SheetModalKind) => void
}) {
  const isMe = member.id === meId
  const openProfile = () => onOpenProfile(member)

  return (
    <li>
      <div
        role="button"
        tabIndex={0}
        aria-label={`Open ${member.name}'s profile`}
        onClick={openProfile}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            openProfile()
          }
        }}
        className="flex min-h-[60px] w-full cursor-pointer items-center gap-3 rounded-xl px-2 py-2 outline-none transition-colors hover:bg-white focus-visible:ring-2 focus-visible:ring-emerald-500/50 active:bg-white dark:hover:bg-zinc-800 dark:active:bg-zinc-700/60"
      >
        <UserAvatar name={member.name} color={member.color} size={44} />
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-1.5 truncate text-sm font-semibold text-zinc-800 dark:text-zinc-100">
            <span className="truncate">{member.name}</span>
            {isMe ? (
              <span className="shrink-0 text-[11px] font-medium text-emerald-600 dark:text-emerald-400">
                you
              </span>
            ) : null}
            {member.role === 'admin' ? (
              <span className="flex shrink-0 items-center gap-0.5 rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-amber-600 dark:text-amber-400">
                <Crown className="size-2.5" aria-hidden />
                Admin
              </span>
            ) : null}
          </p>
          <p className="mt-0.5 flex items-center gap-1 truncate text-[11px] text-zinc-400 dark:text-zinc-500">
            {member.username ? (
              <>
                <AtSign className="size-3 shrink-0" aria-hidden />
                <span className="truncate">{member.username}</span>
                <span className="px-0.5 text-zinc-300 dark:text-zinc-600">·</span>
              </>
            ) : null}
            <span className="shrink-0">active {formatListStamp(member.lastSeenAt)}</span>
          </p>
        </div>

        {isAdmin && !isMe ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                aria-label={`Manage ${member.name}`}
                onClick={(event) => event.stopPropagation()}
                className="flex size-10 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-200/70 hover:text-zinc-700 active:scale-90 dark:hover:bg-zinc-700 dark:hover:text-zinc-200"
              >
                <EllipsisVertical className="size-[18px]" aria-hidden />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent
              align="end"
              sideOffset={6}
              className="z-[90] w-52 rounded-xl border-zinc-200 dark:border-zinc-700"
            >
              <PromoteDemoteItems member={member} onOpenModal={onOpenModal} />
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
      </div>
    </li>
  )
}

/**
 * Role + removal actions for one member. Removal uses the dedicated
 * DELETE /api/conversations/[id]/members/[userId] sub-route (kick non-admin
 * members); kicking admins is rejected server-side, so that item is hidden
 * for admin targets instead of guaranteeing a 403.
 */
function PromoteDemoteItems({
  member,
  onOpenModal,
}: {
  member: ConversationDetail['members'][number]
  onOpenModal: (m: SheetModalKind) => void
}) {
  return (
    <>
      {member.role === 'member' ? (
        <DropdownMenuItem
          onSelect={() => onOpenModal(`promote:${member.id}`)}
          className="gap-2.5 rounded-lg py-2.5 text-sm"
        >
          <Crown className="size-4 text-amber-500" aria-hidden />
          Promote to admin
        </DropdownMenuItem>
      ) : (
        <DropdownMenuItem
          onSelect={() => onOpenModal(`demote:${member.id}`)}
          className="gap-2.5 rounded-lg py-2.5 text-sm"
        >
          <UsersRound className="size-4 text-zinc-500" aria-hidden />
          Demote to member
        </DropdownMenuItem>
      )}
      {member.role === 'member' ? (
        <>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onSelect={() => onOpenModal(`remove:${member.id}`)}
            className="gap-2.5 rounded-lg py-2.5 text-sm text-destructive focus:bg-destructive/10 focus:text-destructive"
          >
            <LogOut className="size-4" aria-hidden />
            Remove from group
          </DropdownMenuItem>
        </>
      ) : null}
    </>
  )
}

// ── DM fallback body ─────────────────────────────────────────

function DmInfoBody({
  detail,
  meId,
  onOpenProfile,
}: {
  detail: ConversationDetail
  meId: string
  onOpenProfile: (u: AppUser) => void
}) {
  const other = detail.members.find((m) => m.id !== meId) ?? detail.members[0] ?? null
  if (!other) {
    return (
      <div className="flex flex-1 items-center justify-center px-8 text-center text-sm text-zinc-500 dark:text-zinc-400">
        This chat has no other members yet.
      </div>
    )
  }
  return (
    <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain">
      <div className={cn('relative overflow-hidden px-5 pb-5 pt-7 text-white', gradientFor(other.color))}>
        <div className="absolute -right-10 -top-12 size-44 rounded-full bg-white/15 blur-2xl" />
        <div className="relative flex items-center gap-4">
          <UserAvatar name={other.name} color={other.color} size={72} className="rounded-[22px]" />
          <div className="min-w-0">
            <h2 className="truncate text-xl font-bold leading-tight tracking-tight">{other.name}</h2>
            {other.username ? (
              <p className="mt-0.5 flex items-center gap-0.5 text-[13px] font-medium text-white/85">
                <AtSign className="size-3.5" aria-hidden />
                {other.username}
              </p>
            ) : null}
            {other.statusText ? (
              <p className="mt-0.5 truncate text-xs text-white/80">
                {other.statusEmoji ? `${other.statusEmoji} ` : ''}
                {other.statusText}
              </p>
            ) : null}
          </div>
        </div>
      </div>
      <div className="px-4 py-4">
        <button
          type="button"
          onClick={() => onOpenProfile(other)}
          className="w-full rounded-2xl border border-zinc-200 bg-white p-3.5 text-left outline-none transition-colors hover:border-emerald-300 dark:border-zinc-800 dark:bg-zinc-800/60 dark:hover:border-emerald-500/40"
        >
          <p className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">About</p>
          <p className="mt-1 text-sm leading-relaxed text-zinc-700 dark:text-zinc-200">{other.about}</p>
          <p className="mt-2 text-[11px] text-zinc-400 dark:text-zinc-500">
            Last active {formatListStamp(other.lastSeenAt)}
          </p>
        </button>
      </div>
    </div>
  )
}

// ── loading skeleton ─────────────────────────────────────────

function InfoSkeleton({ isGroup }: { isGroup: boolean }) {
  return (
    <div
      className="min-h-0 flex-1 overflow-hidden"
      aria-busy="true"
      aria-label="Loading conversation info"
    >
      <div className="px-5 pb-5 pt-7">
        <div className="flex items-center gap-4">
          <Skeleton className="size-[72px] rounded-[22px]" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-2/3 rounded-lg" />
            <Skeleton className="h-3.5 w-1/3 rounded-md" />
          </div>
        </div>
      </div>
      {isGroup ? (
        <>
          <div className="flex gap-2 px-4 py-4">
            <Skeleton className="h-[72px] flex-1 rounded-2xl" />
            <Skeleton className="h-[72px] flex-1 rounded-2xl" />
            <Skeleton className="h-[72px] flex-1 rounded-2xl" />
          </div>
          <div className="space-y-2 px-4">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 rounded-xl p-2">
                <Skeleton className="size-11 rounded-full" />
                <div className="flex-1 space-y-1.5">
                  <Skeleton className="h-3.5 w-1/2 rounded-md" />
                  <Skeleton className="h-3 w-1/3 rounded-md" />
                </div>
              </div>
            ))}
          </div>
        </>
      ) : (
        <div className="px-4 py-4">
          <Skeleton className="h-24 w-full rounded-2xl" />
        </div>
      )}
    </div>
  )
}

// ── modal shell (inside the sheet's stacking context) ────────

function SheetModal({
  open,
  onClose,
  label,
  children,
}: {
  open: boolean
  onClose: () => void
  label: string
  children: ReactNode
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return (
    <AnimatePresence>
      {open ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.16 }}
          className="absolute inset-0 z-20 flex items-center justify-center bg-black/50 p-5 backdrop-blur-[2px]"
          onClick={onClose}
          role="dialog"
          aria-modal="true"
          aria-label={label}
        >
          <motion.div
            initial={{ scale: 0.9, y: 16, opacity: 0 }}
            animate={{ scale: 1, y: 0, opacity: 1 }}
            exit={{ scale: 0.95, y: 8, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 400, damping: 30 }}
            className="w-full max-w-[340px] rounded-2xl border border-zinc-200 bg-white p-5 shadow-2xl dark:border-zinc-800 dark:bg-zinc-900"
            onClick={(event) => event.stopPropagation()}
          >
            {children}
          </motion.div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

// ── rename ───────────────────────────────────────────────────

function RenameModal({
  open,
  onClose,
  conversationId,
  meId,
  currentName,
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  meId: string
  currentName: string
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(currentName)

  const trimmed = name.trim()
  const valid = trimmed.length > 0 && trimmed.length <= GROUP_NAME_MAX && trimmed !== currentName

  const rename = useMutation({
    mutationFn: async (): Promise<ConversationResponse> => {
      return apiJson<ConversationResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}`,
        { method: 'PATCH', body: JSON.stringify({ requesterId: meId, name: trimmed }) },
      )
    },
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, name: trimmed } : old,
      )
      return { prev }
    },
    onSuccess: (data) => {
      // route returns the detail built for the requester (me) — safe to cache directly
      queryClient.setQueryData(['conversation', conversationId], data.conversation)
      void queryClient.invalidateQueries({ queryKey: ['conversations', meId] })
      toast.success('Group renamed')
      onClose()
    },
    onError: (error: Error, _vars, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      toast.error(error.message || 'Could not rename the group')
    },
  })

  return (
    <SheetModal open={open} onClose={onClose} label="Rename group">
      <form
        onSubmit={(event) => {
          event.preventDefault()
          if (valid && !rename.isPending) rename.mutate()
        }}
      >
        <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
          Rename group
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
          Visible to every member. 1–{GROUP_NAME_MAX} characters.
        </p>
        <div className="mt-3 space-y-1.5">
          <Label htmlFor="group-rename-input" className="sr-only">
            Group name
          </Label>
          <Input
            id="group-rename-input"
            value={name}
            maxLength={GROUP_NAME_MAX}
            onChange={(event) => setName(event.target.value.slice(0, GROUP_NAME_MAX))}
            autoFocus
            autoComplete="off"
            enterKeyHint="done"
            aria-invalid={trimmed.length === 0 || undefined}
            className="h-11 rounded-xl border-zinc-200 bg-zinc-50 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
          />
          <p className="text-right text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
            {trimmed.length}/{GROUP_NAME_MAX}
          </p>
        </div>
        <div className="mt-3 flex gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            className="h-10 flex-1 rounded-xl text-sm font-semibold"
          >
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!valid || rename.isPending}
            className="h-10 flex-1 rounded-xl bg-emerald-600 text-sm font-semibold text-white hover:bg-emerald-500 active:scale-[0.98]"
          >
            {rename.isPending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Save name'}
          </Button>
        </div>
      </form>
    </SheetModal>
  )
}

// ── add members ──────────────────────────────────────────────

function AddMembersModal({
  open,
  onClose,
  conversationId,
  meId,
  existingIds,
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  meId: string
  existingIds: string[]
}) {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const usersQ = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<UsersResponse>('/api/users')
      return res.users
    },
    enabled: open,
  })

  const existingSet = useMemo(() => new Set(existingIds), [existingIds])
  const candidates = useMemo(() => {
    const q = search.trim().toLowerCase()
    return (usersQ.data ?? [])
      .filter((u) => !existingSet.has(u.id))
      .filter(
        (u) =>
          q.length === 0 ||
          u.name.toLowerCase().includes(q) ||
          (u.username ?? '').toLowerCase().includes(q),
      )
  }, [usersQ.data, existingSet, search])

  const addMembers = useMutation({
    mutationFn: async (userIds: string[]): Promise<AddedResponse> => {
      return apiJson<AddedResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        jsonBody({ requesterId: meId, userIds }),
      )
    },
    onMutate: async (userIds: string[]) => {
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      const now = new Date().toISOString()
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) => {
        if (!old) return old
        const known = new Set(old.members.map((m) => m.id))
        const fresh = (usersQ.data ?? [])
          .filter((u) => userIds.includes(u.id) && !known.has(u.id))
          .map((u) => ({ ...u, lastReadAt: now, role: 'member' as GroupRole }))
        return fresh.length > 0 ? { ...old, members: [...old.members, ...fresh] } : old
      })
      return { prev }
    },
    onSuccess: (data) => {
      // route returns the detail built for the requester (me) — safe to cache directly
      queryClient.setQueryData(['conversation', conversationId], data.conversation)
      void queryClient.invalidateQueries({ queryKey: ['conversations', meId] })
      toast.success(
        data.added.length === 1 ? '1 member added' : `${data.added.length} members added`,
      )
      onClose()
    },
    onError: (error: Error, _vars, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      toast.error(error.message || 'Could not add members')
    },
  })

  const toggle = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <SheetModal open={open} onClose={onClose} label="Add members">
      <div className="flex flex-col">
        <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
          Add members
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
          Pick people already on this Pulse. They start with no unread backlog.
        </p>

        {/* search */}
        <div className="relative mt-3">
          <Search
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-zinc-400"
            aria-hidden
          />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value.slice(0, 40))}
            placeholder="Search people…"
            aria-label="Search people to add"
            autoComplete="off"
            className="h-10 rounded-xl border-zinc-200 bg-zinc-50 pl-9 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
          />
        </div>

        {/* candidates */}
        <div className="pulse-scroll mt-2 max-h-[38dvh] min-h-[120px] space-y-0.5 overflow-y-auto rounded-xl border border-zinc-200 bg-zinc-50/60 p-1.5 dark:border-zinc-800 dark:bg-zinc-800/40">
          {usersQ.isPending ? (
            <div className="space-y-2 p-2" aria-busy="true">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="flex items-center gap-3">
                  <Skeleton className="size-9 rounded-full" />
                  <Skeleton className="h-3.5 w-1/2 rounded-md" />
                </div>
              ))}
            </div>
          ) : usersQ.isError ? (
            <p className="px-3 py-6 text-center text-xs text-zinc-500 dark:text-zinc-400">
              Could not load people. Close and try again.
            </p>
          ) : candidates.length === 0 ? (
            <p className="px-3 py-6 text-center text-xs text-zinc-500 dark:text-zinc-400">
              {search.trim().length > 0
                ? 'No one matches that search.'
                : 'Everyone on this Pulse is already here.'}
            </p>
          ) : (
            candidates.map((user) => {
              const checked = selected.has(user.id)
              return (
                <label
                  key={user.id}
                  className="flex min-h-[52px] cursor-pointer items-center gap-3 rounded-xl px-2 py-1.5 transition-colors hover:bg-white dark:hover:bg-zinc-800"
                >
                  <Checkbox
                    checked={checked}
                    onCheckedChange={() => toggle(user.id)}
                    aria-label={`Add ${user.name}`}
                    className="data-[state=checked]:border-emerald-600 data-[state=checked]:bg-emerald-600"
                  />
                  <UserAvatar name={user.name} color={user.color} size={36} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-semibold text-zinc-800 dark:text-zinc-100">
                      {user.name}
                    </span>
                    {user.username ? (
                      <span className="block truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                        @{user.username}
                      </span>
                    ) : null}
                  </span>
                  {checked ? (
                    <Check className="size-4 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
                  ) : null}
                </label>
              )
            })
          )}
        </div>

        <div className="mt-3 flex gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            className="h-10 flex-1 rounded-xl text-sm font-semibold"
          >
            Cancel
          </Button>
          <Button
            type="button"
            disabled={selected.size === 0 || addMembers.isPending}
            onClick={() => addMembers.mutate([...selected])}
            className="h-10 flex-[1.4] rounded-xl bg-emerald-600 text-sm font-semibold text-white hover:bg-emerald-500 active:scale-[0.98]"
          >
            {addMembers.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : selected.size > 0 ? (
              `Add ${selected.size}`
            ) : (
              'Add'
            )}
          </Button>
        </div>
      </div>
    </SheetModal>
  )
}

// ── leave confirm ────────────────────────────────────────────

function LeaveModal({
  open,
  onClose,
  groupName,
  pending,
  onConfirm,
}: {
  open: boolean
  onClose: () => void
  groupName: string
  pending: boolean
  onConfirm: () => void
}) {
  return (
    <SheetModal open={open} onClose={onClose} label="Leave group">
      <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
        Leave “{groupName}”?
      </h3>
      <p className="mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
        You&apos;ll stop receiving messages. History stays on your device. If you&apos;re the last
        admin, the longest-standing member is promoted automatically.
      </p>
      <div className="mt-4 flex gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={onClose}
          className="h-10 flex-1 rounded-xl text-sm font-semibold"
        >
          Cancel
        </Button>
        <Button
          type="button"
          disabled={pending}
          onClick={onConfirm}
          className="h-10 flex-1 rounded-xl bg-destructive text-sm font-semibold text-white hover:bg-destructive/90 active:scale-[0.98]"
        >
          {pending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Leave group'}
        </Button>
      </div>
    </SheetModal>
  )
}

// ── role change + removal confirms + member profile drawer ───

function MemberActionHost({
  conversationId,
  meId,
  members,
  modal,
  setModal,
  profileUser,
  onCloseProfile,
}: {
  conversationId: string | null
  meId: string
  members: ConversationDetail['members']
  modal: SheetModalKind
  setModal: (m: SheetModalKind) => void
  profileUser: AppUser | null
  onCloseProfile: () => void
}) {
  const queryClient = useQueryClient()
  const { kind, userId } = modalTarget(modal)
  const target = userId ? (members.find((m) => m.id === userId) ?? null) : null

  const setRole = useMutation({
    mutationFn: async ({ userId: targetId, role }: { userId: string; role: GroupRole }) => {
      return apiJson<ConversationResponse>(
        `/api/conversations/${encodeURIComponent(conversationId ?? '')}/members`,
        { method: 'PATCH', body: JSON.stringify({ requesterId: meId, userId: targetId, role }) },
      )
    },
    onMutate: async ({ userId: targetId, role }) => {
      if (conversationId === null) return
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old
          ? { ...old, members: old.members.map((m) => (m.id === targetId ? { ...m, role } : m)) }
          : old,
      )
      return { prev }
    },
    onSuccess: (_data, { role }) => {
      // refetch instead of caching: the PATCH detail is built for the TARGET viewer
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['conversations', meId] })
      toast.success(role === 'admin' ? 'Promoted to admin' : 'Demoted to member')
    },
    onError: (error: Error, _vars, ctx) => {
      if (ctx?.prev && conversationId !== null) {
        queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      }
      // surfaces the API verbatim — e.g. "Cannot demote the last admin — promote someone else first."
      toast.error(error.message || 'Could not change the role')
    },
    onSettled: () => setModal(null),
  })

  const removeMember = useMutation({
    mutationFn: async (targetId: string) => {
      return apiJson<{ ok: boolean }>(
        `/api/conversations/${encodeURIComponent(conversationId ?? '')}/members/${encodeURIComponent(targetId)}`,
        { method: 'DELETE', body: JSON.stringify({ requesterId: meId }) },
      )
    },
    onMutate: async (targetId: string) => {
      if (conversationId === null) return
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, members: old.members.filter((m) => m.id !== targetId) } : old,
      )
      return { prev }
    },
    onSuccess: (_data, targetId) => {
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['conversations', meId] })
      toast.success(`${members.find((m) => m.id === targetId)?.name ?? 'Member'} removed from the group`)
    },
    onError: (error: Error, _userId, ctx) => {
      if (ctx?.prev && conversationId !== null) {
        queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      }
      toast.error(error.message || 'Could not remove the member')
    },
    onSettled: () => setModal(null),
  })

  return (
    <>
      {kind === 'promote' && target ? (
        <SheetModal open onClose={() => setModal(null)} label={`Promote ${target.name}`}>
          <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Make {target.name} an admin?
          </h3>
          <p className="mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
            Admins can rename the group, add and remove members, and toggle announcement mode.
          </p>
          <div className="mt-4 flex gap-2">
            <Button
              variant="outline"
              onClick={() => setModal(null)}
              className="h-10 flex-1 rounded-xl text-sm font-semibold"
            >
              Cancel
            </Button>
            <Button
              onClick={() => setRole.mutate({ userId: target.id, role: 'admin' })}
              disabled={setRole.isPending}
              className="h-10 flex-1 rounded-xl bg-emerald-600 text-sm font-semibold text-white hover:bg-emerald-500 active:scale-[0.98]"
            >
              {setRole.isPending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Promote'}
            </Button>
          </div>
        </SheetModal>
      ) : null}

      {kind === 'demote' && target ? (
        <SheetModal open onClose={() => setModal(null)} label={`Demote ${target.name}`}>
          <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Demote {target.name}?
          </h3>
          <p className="mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
            They lose admin powers. A group always keeps at least one admin.
          </p>
          <div className="mt-4 flex gap-2">
            <Button
              variant="outline"
              onClick={() => setModal(null)}
              className="h-10 flex-1 rounded-xl text-sm font-semibold"
            >
              Cancel
            </Button>
            <Button
              onClick={() => setRole.mutate({ userId: target.id, role: 'member' })}
              disabled={setRole.isPending}
              className="h-10 flex-1 rounded-xl bg-emerald-600 text-sm font-semibold text-white hover:bg-emerald-500 active:scale-[0.98]"
            >
              {setRole.isPending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Demote'}
            </Button>
          </div>
        </SheetModal>
      ) : null}

      {kind === 'remove' && target ? (
        <SheetModal open onClose={() => setModal(null)} label={`Remove ${target.name}`}>
          <h3 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Remove {target.name} from the group?
          </h3>
          <p className="mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
            They lose access to this chat. They can be added back anytime.
          </p>
          <div className="mt-4 flex gap-2">
            <Button
              variant="outline"
              onClick={() => setModal(null)}
              className="h-10 flex-1 rounded-xl text-sm font-semibold"
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => removeMember.mutate(target.id)}
              disabled={removeMember.isPending}
              className="h-10 flex-1 rounded-xl text-sm font-semibold active:scale-[0.98]"
            >
              {removeMember.isPending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : 'Remove'}
            </Button>
          </div>
        </SheetModal>
      ) : null}

      {/* member profile drawer (portals to body; z raised inside user-profile-sheet) */}
      <UserProfileSheet
        user={profileUser}
        open={profileUser !== null}
        onOpenChange={(v) => {
          if (!v) onCloseProfile()
        }}
      />
    </>
  )
}
