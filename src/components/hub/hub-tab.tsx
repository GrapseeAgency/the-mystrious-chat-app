// ─────────────────────────────────────────────────────────────
// Pulse Hub — the economy/control deck (6 live panels):
//   Wallet · Tasks · Market · Logs · Swap · Apps (100 matrix)
// Every panel talks to real /api/hub/* routes — zero mocks.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  BadgeCheck,
  Check,
  ChevronRight,
  CircleDollarSign,
  Coins,
  Flame,
  Gem,
  Gift,
  ListTodo,
  Loader2,
  Plus,
  Search,
  Send,
  ShoppingBag,
  Terminal,
  Repeat,
  X,
} from 'lucide-react'
import type { AppUser, HubTaskItem, LedgerEntry, LogEntry, MarketListingItem, SwapInfo, WalletState } from '@/lib/types'
import { apiJson, buzz } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { navigateHash, useHashRoute } from '@/lib/hash-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  MATRIX,
  CATEGORY_CHIPS,
  slugForCategory,
  type MatrixApp,
  type MatrixCategory,
} from '@/lib/hub-catalog'
import {
  LoadErrorCard,
  SkeletonDots,
  hydrateInstalledSet,
  useInstallToggle,
  useInstalledSet,
  useWalletMini,
} from '@/components/hub/hub-data'
import { HubCategoryPage, MINE_SLUG } from '@/components/hub/hub-category-page'
import { AppDetailPage } from '@/components/hub/app-detail-sheet'

type HubPanel = 'wallet' | 'tasks' | 'market' | 'logs' | 'swap' | 'apps'

const PANELS: Array<{ id: HubPanel; label: string; Icon: typeof Coins }> = [
  { id: 'wallet', label: 'Wallet', Icon: Coins },
  { id: 'tasks', label: 'Tasks', Icon: ListTodo },
  { id: 'market', label: 'Market', Icon: ShoppingBag },
  { id: 'swap', label: 'Swap', Icon: Repeat },
  { id: 'logs', label: 'Logs', Icon: Terminal },
  { id: 'apps', label: 'Apps', Icon: BadgeCheck },
]

// ── Wallet panel ─────────────────────────────────────────────

function WalletPanel({ me }: { me: AppUser }) {
  const qc = useQueryClient()
  const [toUsername, setToUsername] = useState('')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')

  const walletQ = useQuery({
    queryKey: ['hub-wallet', me.id],
    queryFn: async () => {
      const res = await apiJson<{ wallet: WalletState; ledger: LedgerEntry[] }>(
        `/api/hub/wallet?userId=${encodeURIComponent(me.id)}`,
      )
      return res
    },
    refetchInterval: 30_000,
  })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['hub-wallet', me.id] })

  const checkin = useMutation({
    mutationFn: () =>
      apiJson<{ wallet: WalletState; reward: number; streak: number }>('/api/hub/wallet/checkin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      }),
    onSuccess: (data) => toast.success(`Checked in — +${data.reward} PC · ${data.streak}-day streak`),
    onError: (err: Error) => toast.error(err.message),
    onSettled: invalidate,
  })

  const transfer = useMutation({
    mutationFn: (input: { toUsername: string; amount: number; note: string }) =>
      apiJson<{ wallet: WalletState }>('/api/hub/wallet/transfer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, ...input }),
      }),
    onSuccess: (_d, v) => {
      toast.success(`Sent ${v.amount} PC to @${v.toUsername}`)
      setToUsername('')
      setAmount('')
      setNote('')
    },
    onError: (err: Error) => toast.error(err.message),
    onSettled: invalidate,
  })

  const wallet = walletQ.data?.wallet

  return (
    <div className="flex flex-col gap-4">
      {/* balance hero */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-emerald-500 via-teal-500 to-cyan-600 p-5 text-white shadow-lg">
        <div className="absolute -right-6 -top-8 size-28 rounded-full bg-white/10 blur-xl" />
        <p className="text-[11px] font-medium uppercase tracking-wider text-white/70">Pulse balance</p>
        <div className="mt-1 flex items-end gap-4">
          <div>
            <p className="text-3xl font-bold leading-none">{wallet ? wallet.coins.toLocaleString() : '—'}</p>
            <p className="mt-1 text-[11px] font-medium text-white/80">PC · Pulse Coins</p>
          </div>
          <div className="border-l border-white/20 pl-4">
            <p className="flex items-center gap-1 text-xl font-bold leading-none">
              <Gem className="size-4" /> {wallet ? wallet.gems : '—'}
            </p>
            <p className="mt-1 text-[11px] font-medium text-white/80">Gems</p>
          </div>
        </div>
        <Button
          size="sm"
          disabled={checkin.isPending || wallet?.checkedInToday}
          onClick={() => checkin.mutate()}
          className="mt-4 w-full bg-white font-semibold text-emerald-700 hover:bg-white/90 disabled:opacity-60"
        >
          {wallet?.checkedInToday ? (
            <>Checked in today · streak {wallet.streak}</>
          ) : (
            <>
              <Gift className="mr-1.5 size-4" /> Daily check-in · +25 PC
            </>
          )}
        </Button>
      </div>

      {/* transfer */}
      <section aria-label="Transfer coins" className="glass-deep glass-sheen rounded-2xl p-4">
        <h3 className="flex items-center gap-1.5 text-sm font-semibold">
          <Send className="size-4 text-emerald-600" /> Send coins by @handle
        </h3>
        <div className="mt-3 grid grid-cols-2 gap-2">
          <Input
            value={toUsername}
            onChange={(e) => setToUsername(e.target.value.replace(/^@/, '').toLowerCase())}
            placeholder="@handle"
            className="col-span-2 h-9"
          />
          <Input
            value={amount}
            onChange={(e) => setAmount(e.target.value.replace(/[^0-9]/g, ''))}
            placeholder="Amount (PC)"
            inputMode="numeric"
            className="h-9"
          />
          <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" className="h-9" />
        </div>
        <Button
          size="sm"
          className="mt-2.5 w-full"
          disabled={transfer.isPending || !toUsername || !amount}
          onClick={() => transfer.mutate({ toUsername, amount: Number(amount), note })}
        >
          {transfer.isPending ? 'Sending…' : 'Transfer'}
        </Button>
      </section>

      {/* ledger */}
      <section aria-label="Ledger" className="glass-deep glass-sheen rounded-2xl">
        <h3 className="border-b border-zinc-200 px-4 py-2.5 text-sm font-semibold dark:border-white/10">Ledger · last {walletQ.data?.ledger.length ?? 0}</h3>
        <ul className="pulse-scroll max-h-72 divide-y divide-zinc-100 overflow-y-auto dark:divide-white/5">
          {(walletQ.data?.ledger ?? []).map((row) => (
            <li key={row.id} className="glass-row-hover flex items-center gap-3 px-4 py-2.5">
              {row.amount >= 0 ? (
                <ArrowDownToLine className="size-4 shrink-0 text-emerald-600" />
              ) : (
                <ArrowUpFromLine className="size-4 shrink-0 text-rose-500" />
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-medium">{row.note || row.kind}</p>
                <p className="text-[11px] text-zinc-500">{new Date(row.createdAt).toLocaleString()}</p>
              </div>
              <p className={cn('text-sm font-bold tabular-nums', row.amount >= 0 ? 'text-emerald-600' : 'text-rose-500')}>
                {row.amount >= 0 ? '+' : ''}
                {row.amount} {row.asset}
              </p>
            </li>
          ))}
          {(walletQ.data?.ledger.length ?? 0) === 0 ? (
            <li className="px-4 py-6 text-center text-[13px] text-zinc-500">
              No movements yet — check in above to mint your first coins.
            </li>
          ) : null}
        </ul>
      </section>
    </div>
  )
}

// ── Tasks panel (3-column kanban) ────────────────────────────

const COLUMNS: Array<{ id: HubTaskItem['status']; label: string }> = [
  { id: 'todo', label: 'To do' },
  { id: 'doing', label: 'Doing' },
  { id: 'done', label: 'Done' },
]

function TasksPanel({ me }: { me: AppUser }) {
  const qc = useQueryClient()
  const [title, setTitle] = useState('')

  const tasksQ = useQuery({
    queryKey: ['hub-tasks', me.id],
    queryFn: async () => {
      const res = await apiJson<{ tasks: HubTaskItem[] }>(`/api/hub/tasks?userId=${encodeURIComponent(me.id)}`)
      return res.tasks
    },
  })
  const invalidate = () => void qc.invalidateQueries({ queryKey: ['hub-tasks', me.id] })

  const add = useMutation({
    mutationFn: (t: string) =>
      apiJson<{ task: HubTaskItem }>('/api/hub/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, title: t }),
      }),
    onSuccess: () => {
      setTitle('')
      invalidate()
    },
    onError: (err: Error) => toast.error(err.message),
  })

  const move = useMutation({
    mutationFn: (input: { id: string; status: HubTaskItem['status'] }) =>
      apiJson(`/api/hub/tasks/${input.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, status: input.status }),
      }),
    onSettled: invalidate,
    onError: (err: Error) => toast.error(err.message),
  })

  const remove = useMutation({
    mutationFn: (id: string) => apiJson(`/api/hub/tasks/${id}?userId=${encodeURIComponent(me.id)}`, { method: 'DELETE' }),
    onSettled: invalidate,
    onError: (err: Error) => toast.error(err.message),
  })

  const byStatus = useMemo(() => {
    const map: Record<HubTaskItem['status'], HubTaskItem[]> = { todo: [], doing: [], done: [] }
    for (const t of tasksQ.data ?? []) map[t.status]?.push(t)
    return map
  }, [tasksQ.data])

  return (
    <div className="flex flex-col gap-3">
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (title.trim()) add.mutate(title.trim())
        }}
      >
        <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="New task…" className="h-9" />
        <Button type="submit" size="sm" disabled={!title.trim() || add.isPending} className="shrink-0">
          <Check className="size-4" />
        </Button>
      </form>

      <div className="grid grid-cols-3 gap-2">
        {COLUMNS.map((col) => (
          <div key={col.id} className="rounded-xl bg-zinc-100/70 p-2 dark:bg-white/5">
            <p className="mb-2 px-1 text-[11px] font-bold uppercase tracking-wide text-zinc-500">
              {col.label} · {byStatus[col.id].length}
            </p>
            <ul className="flex flex-col gap-1.5">
              {byStatus[col.id].map((t) => (
                <motion.li
                  key={t.id}
                  layout
                  initial={{ opacity: 0, scale: 0.95 }}
                  animate={{ opacity: 1, scale: 1 }}
                  className="rounded-lg border border-zinc-200 bg-white p-2 shadow-sm dark:border-white/10 dark:bg-zinc-900/80"
                >
                  <p className={cn('text-[12px] font-medium leading-snug', t.status === 'done' && 'text-zinc-400 line-through')}>
                    {t.title}
                  </p>
                  <div className="mt-1.5 flex items-center justify-between">
                    <button
                      type="button"
                      aria-label="Advance status"
                      className="text-[10px] font-semibold text-emerald-600 hover:underline"
                      onClick={() =>
                        move.mutate({ id: t.id, status: t.status === 'todo' ? 'doing' : t.status === 'doing' ? 'done' : 'todo' })
                      }
                    >
                      {t.status === 'done' ? '↺ reopen' : '→ next'}
                    </button>
                    <button
                      type="button"
                      aria-label="Delete task"
                      className="text-[10px] text-zinc-400 hover:text-rose-500"
                      onClick={() => remove.mutate(t.id)}
                    >
                      <X className="size-4" aria-hidden />
                    </button>
                  </div>
                </motion.li>
              ))}
              {byStatus[col.id].length === 0 ? (
                <li className="rounded-lg border border-dashed border-zinc-300 px-2 py-3 text-center text-[10px] text-zinc-400 dark:border-white/15">
                  empty
                </li>
              ) : null}
            </ul>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Market panel ─────────────────────────────────────────────

function MarketPanel({ me }: { me: AppUser }) {
  const qc = useQueryClient()
  const [title, setTitle] = useState('')
  const [price, setPrice] = useState('')
  const [description, setDescription] = useState('')

  const marketQ = useQuery({
    queryKey: ['hub-market', me.id],
    queryFn: async () => {
      const res = await apiJson<{ listings: MarketListingItem[] }>(`/api/hub/market?userId=${encodeURIComponent(me.id)}`)
      return res.listings
    },
    refetchInterval: 30_000,
  })
  const walletQ = useQuery({
    queryKey: ['hub-wallet-mini', me.id],
    queryFn: async () => {
      const res = await apiJson<{ wallet: WalletState }>(`/api/hub/wallet?userId=${encodeURIComponent(me.id)}`)
      return res.wallet
    },
  })

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['hub-market', me.id] })
    void qc.invalidateQueries({ queryKey: ['hub-wallet-mini', me.id] })
  }

  const list = useMutation({
    mutationFn: () =>
      apiJson('/api/hub/market', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, title, description, price: Number(price) }),
      }),
    onSuccess: () => {
      toast.success('Listing posted to the market')
      setTitle('')
      setPrice('')
      setDescription('')
    },
    onError: (err: Error) => toast.error(err.message),
    onSettled: invalidate,
  })

  const buy = useMutation({
    mutationFn: (id: string) =>
      apiJson<{ ok: boolean }>(`/api/hub/market/${id}/buy`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      }),
    onSuccess: () => toast.success('Purchased — coins transferred to the seller'),
    onError: (err: Error) => toast.error(err.message),
    onSettled: invalidate,
  })

  return (
    <div className="flex flex-col gap-3">
      <section aria-label="Create listing" className="glass-deep glass-sheen rounded-2xl p-4">
        <h3 className="flex items-center gap-1.5 text-sm font-semibold">
          <CircleDollarSign className="size-4 text-emerald-600" /> Sell something
          {walletQ.data ? <span className="ml-auto text-xs font-medium text-zinc-500">you: {walletQ.data.coins} PC</span> : null}
        </h3>
        <form
          className="mt-3 flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (title.trim() && price) list.mutate()
          }}
        >
          <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title (what are you selling?)" className="h-9" />
          <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Description (optional)" className="h-9" />
          <div className="flex gap-2">
            <Input value={price} onChange={(e) => setPrice(e.target.value.replace(/[^0-9]/g, ''))} placeholder="Price PC" inputMode="numeric" className="h-9" />
            <Button type="submit" size="sm" disabled={!title.trim() || !price || list.isPending} className="shrink-0">
              List
            </Button>
          </div>
        </form>
      </section>

      <section aria-label="Listings" className="flex flex-col gap-2">
        <h3 className="px-1 text-sm font-semibold">Open board</h3>
        {(marketQ.data ?? []).map((l) => (
          <motion.div
            key={l.id}
            layout
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            className={cn(
              'rounded-xl border p-3',
              l.status === 'sold'
                ? 'border-zinc-200/80 bg-zinc-50/60 opacity-60 dark:border-white/10 dark:bg-transparent'
                : 'glass-deep glass-sheen border-transparent',
            )}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{l.title}</p>
                {l.description ? <p className="mt-0.5 line-clamp-2 text-xs text-zinc-500">{l.description}</p> : null}
                <p className="mt-1 text-[11px] text-zinc-500">
                  {l.status === 'sold'
                    ? `sold to ${l.buyer?.username ? `@${l.buyer.username}` : l.buyer?.name ?? '—'}`
                    : `by ${l.seller?.username ? `@${l.seller.username}` : l.seller?.name ?? '—'}`}
                  {' · '}
                  {new Date(l.createdAt).toLocaleDateString()}
                </p>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-1.5">
                <p className="text-sm font-bold text-emerald-600">{l.price} PC</p>
                {l.status === 'open' && !l.mine ? (
                  <Button size="sm" variant="outline" className="h-7 px-2.5 text-xs" disabled={buy.isPending} onClick={() => buy.mutate(l.id)}>
                    Buy
                  </Button>
                ) : null}
                {l.mine && l.status === 'open' ? <span className="text-[10px] font-semibold text-zinc-400">yours</span> : null}
              </div>
            </div>
          </motion.div>
        ))}
        {(marketQ.data?.length ?? 0) === 0 ? (
          <p className="rounded-xl border border-dashed border-zinc-300 px-4 py-8 text-center text-[13px] text-zinc-500 dark:border-white/15">
            The board is empty — be the first seller.
          </p>
        ) : null}
      </section>
    </div>
  )
}

// ── Swap panel ───────────────────────────────────────────────

function SwapPanel({ me }: { me: AppUser }) {
  const qc = useQueryClient()
  const [direction, setDirection] = useState<'pc2gem' | 'gem2pc'>('pc2gem')
  const [amount, setAmount] = useState('')

  const swapQ = useQuery({
    queryKey: ['hub-swap'],
    queryFn: async () => {
      const res = await apiJson<SwapInfo>('/api/hub/swap')
      return res
    },
    refetchInterval: 45_000,
  })
  const walletQ = useQuery({
    queryKey: ['hub-wallet-swap', me.id],
    queryFn: async () => {
      const res = await apiJson<{ wallet: WalletState }>(`/api/hub/wallet?userId=${encodeURIComponent(me.id)}`)
      return res.wallet
    },
  })

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['hub-swap'] })
    void qc.invalidateQueries({ queryKey: ['hub-wallet-swap', me.id] })
  }

  const swap = useMutation({
    mutationFn: () =>
      apiJson<{ wallet: WalletState; note: string }>('/api/hub/swap', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, direction, amount: Number(amount) }),
      }),
    onSuccess: (d) => toast.success(d.note),
    onError: (err: Error) => toast.error(err.message),
    onSettled: invalidate,
  })

  const rate = swapQ.data?.rates
  const hint =
    direction === 'pc2gem'
      ? rate
        ? `${Number(amount || 0) / rate.pcPerGemBuy} GEM for ${amount || 0} PC`
        : ''
      : rate
        ? `${Number(amount || 0) * rate.pcPerGemSell} PC for ${amount || 0} GEM`
        : ''

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-2">
        <div className="glass-deep glass-sheen rounded-2xl p-3">
          <p className="text-[11px] font-medium uppercase text-zinc-500">Buy rate</p>
          <p className="mt-1 text-lg font-bold">{rate ? `${rate.pcPerGemBuy} PC → 1 GEM` : '—'}</p>
        </div>
        <div className="glass-deep glass-sheen rounded-2xl p-3">
          <p className="text-[11px] font-medium uppercase text-zinc-500">Sell rate</p>
          <p className="mt-1 text-lg font-bold">{rate ? `1 GEM → ${rate.pcPerGemSell} PC` : '—'}</p>
        </div>
      </div>

      {swapQ.data ? (
        <div className="grid grid-cols-4 gap-2 text-center">
          {[
            { label: 'swaps', value: swapQ.data.stats.swaps },
            { label: 'PC bought', value: swapQ.data.stats.pcBought },
            { label: 'coins live', value: swapQ.data.stats.circulatingCoins },
            { label: 'gems live', value: swapQ.data.stats.circulatingGems },
          ].map((s) => (
            <div key={s.label} className="rounded-xl bg-zinc-100/70 px-2 py-2.5 dark:bg-white/5">
              <p className="text-sm font-bold tabular-nums">{s.value.toLocaleString()}</p>
              <p className="text-[10px] text-zinc-500">{s.label}</p>
            </div>
          ))}
        </div>
      ) : null}

      <section aria-label="Exchange" className="glass-deep glass-sheen rounded-2xl p-4">
        <div className="grid grid-cols-2 gap-1.5 rounded-xl bg-zinc-100 p-1 dark:bg-white/5">
          {(
            [
              { id: 'pc2gem', label: 'PC → GEM' },
              { id: 'gem2pc', label: 'GEM → PC' },
            ] as const
          ).map((opt) => (
            <button
              key={opt.id}
              type="button"
              onClick={() => setDirection(opt.id)}
              className={cn(
                'relative rounded-lg py-1.5 text-xs font-semibold transition-colors',
                direction === opt.id ? 'text-white' : 'text-zinc-600 dark:text-zinc-300',
              )}
            >
              {direction === opt.id ? (
                <motion.span layoutId="swap-tab" className="absolute inset-0 rounded-lg bg-emerald-600" transition={{ type: 'spring', stiffness: 500, damping: 35 }} />
              ) : null}
              <span className="relative">{opt.label}</span>
            </button>
          ))}
        </div>

        <Input
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^0-9]/g, ''))}
          placeholder={direction === 'pc2gem' ? 'PC amount (multiples of 100)' : 'GEM amount'}
          inputMode="numeric"
          className="mt-3 h-10 text-center text-lg font-bold"
        />
        {hint && Number(amount) > 0 ? <p className="mt-1.5 text-center text-xs font-medium text-emerald-600">{hint}</p> : null}

        <Button className="mt-3 w-full" disabled={swap.isPending || !amount} onClick={() => swap.mutate()}>
          <Repeat className="mr-1.5 size-4" /> {swap.isPending ? 'Exchanging…' : 'Exchange'}
        </Button>
        {walletQ.data ? (
          <p className="mt-2 text-center text-[11px] text-zinc-500">
            balance: {walletQ.data.coins.toLocaleString()} PC · {walletQ.data.gems} GEM
          </p>
        ) : null}
      </section>
    </div>
  )
}

// ── Logs panel (terminal) ────────────────────────────────────

function LogsPanel() {
  const logsQ = useQuery({
    queryKey: ['hub-logs'],
    queryFn: async () => {
      const res = await apiJson<{ logs: LogEntry[] }>('/api/hub/logs?limit=80')
      return res.logs
    },
    refetchInterval: 12_000,
  })

  return (
    <div className="overflow-hidden rounded-2xl border border-zinc-200/80 bg-zinc-950 font-mono shadow-inner dark:border-white/10 dark:bg-zinc-900/80">
      <div className="flex items-center gap-2 border-b border-zinc-800 px-3 py-2 dark:border-white/10">
        <span className="size-2.5 rounded-full bg-rose-500" />
        <span className="size-2.5 rounded-full bg-amber-400" />
        <span className="size-2.5 rounded-full bg-emerald-500" />
        <p className="ml-1 text-[11px] font-semibold text-zinc-400">pulse://logs — live event stream</p>
      </div>
      <ul className="max-h-[26rem] divide-y divide-zinc-900 overflow-y-auto p-2 text-[11px] leading-relaxed dark:divide-white/5">
        {(logsQ.data ?? []).map((l) => (
          <li key={l.id} className="flex gap-2 py-1.5">
            <span className="shrink-0 text-zinc-600">{new Date(l.createdAt).toLocaleTimeString()}</span>
            <span
              className={cn(
                'shrink-0 font-semibold uppercase',
                l.kind === 'checkin' && 'text-emerald-400',
                l.kind === 'transfer' && 'text-cyan-400',
                l.kind === 'swap' && 'text-violet-400',
                l.kind === 'market' && 'text-amber-400',
                l.kind !== 'checkin' && l.kind !== 'transfer' && l.kind !== 'swap' && l.kind !== 'market' && 'text-zinc-400',
              )}
            >
              [{l.kind}]
            </span>
            <span className="min-w-0 text-zinc-300">
              {l.user ? `@${l.user.username ?? l.user.name} ` : ''}
              {l.message}
            </span>
          </li>
        ))}
        {(logsQ.data?.length ?? 0) === 0 ? (
          <li className="py-8 text-center text-zinc-600">no events yet — use the economy panels to generate some</li>
        ) : null}
      </ul>
    </div>
  )
}

// ── Apps panel (100 matrix, real installs) ───────────────────

/** Extra chips for MATRIX categories missing from the static chip list. */
const EXTRA_CHIP_LABELS: Partial<Record<MatrixCategory, string>> = {
  'E-Commerce / Global FinTech': 'Global FinTech',
  'E-Commerce / Hyper-Apps': 'Hyper-Apps',
}

function ConnectedChip({ appName, pending, onToggle }: { appName: string; pending: boolean; onToggle: () => void }) {
  return (
    <motion.button
      type="button"
      initial={{ scale: 0.7, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={{ type: 'spring', stiffness: 500, damping: 22 }}
      onClick={(e) => {
        e.stopPropagation()
        buzz(12)
        onToggle()
      }}
      disabled={pending}
      aria-pressed="true"
      aria-label={`Disconnect ${appName}`}
      className={cn(
        'relative inline-flex h-8 items-center gap-1.5 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-3 text-[11px] font-bold text-emerald-700 outline-none transition-colors active:scale-[0.97] dark:text-emerald-400',
        'after:absolute after:-inset-1.5 after:content-[\'\']', // extends the touch target past 44px
        'focus-visible:ring-2 focus-visible:ring-emerald-500/60 disabled:opacity-60',
      )}
    >
      {pending ? (
        <Loader2 className="size-3 animate-spin" aria-hidden />
      ) : (
        <span className="relative flex size-1.5">
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-500 opacity-60" />
          <span className="relative inline-flex size-1.5 rounded-full bg-emerald-500" />
        </span>
      )}
      Connected
    </motion.button>
  )
}

function ConnectChip({ appName, pending, onToggle }: { appName: string; pending: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        buzz(12)
        onToggle()
      }}
      disabled={pending}
      aria-pressed="false"
      aria-label={`Connect ${appName}`}
      className={cn(
        'relative inline-flex h-8 items-center gap-1 rounded-full border border-zinc-200 px-3 text-[11px] font-bold text-zinc-600 outline-none transition-colors hover:border-emerald-400 hover:text-emerald-600 active:scale-[0.97] dark:border-white/15 dark:text-zinc-300 dark:hover:border-emerald-500/50 dark:hover:text-emerald-400',
        'after:absolute after:-inset-1.5 after:content-[\'\']', // extends the touch target past 44px
        'focus-visible:ring-2 focus-visible:ring-emerald-500/60 disabled:opacity-60',
      )}
    >
      {pending ? <Loader2 className="size-3 animate-spin" aria-hidden /> : <Plus className="size-3" aria-hidden />}
      Connect
    </button>
  )
}

function AppTile({
  app,
  me,
  installed,
  onOpen,
}: {
  app: MatrixApp
  me: AppUser
  installed: boolean
  onOpen: (app: MatrixApp) => void
}) {
  const toggle = useInstallToggle(app, me.id)
  return (
    <li>
      <motion.div
        role="button"
        tabIndex={0}
        aria-label={`Open ${app.name} details`}
        onClick={() => onOpen(app)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            onOpen(app)
          }
        }}
        className={cn(
          'group w-full cursor-pointer rounded-xl border p-3 text-left shadow-sm outline-none transition-all active:scale-[0.97]',
          'border-zinc-200/80 bg-white hover:border-emerald-400 focus-visible:ring-2 focus-visible:ring-emerald-500/60',
          'dark:border-white/10 dark:bg-zinc-900/70 dark:hover:border-emerald-500/50',
        )}
      >
        <div className="flex items-center justify-between gap-1.5">
          <p className="truncate text-[13px] font-bold">{app.name}</p>
          <ChevronRight className="size-3.5 shrink-0 text-zinc-400 transition-transform group-hover:translate-x-0.5 group-hover:text-emerald-600" />
        </div>
        <p className="mt-0.5 truncate text-[10px] font-medium text-zinc-500">
          #{String(app.n).padStart(3, '0')} · {app.category.split(' /')[0]}
        </p>
        <div className="mt-2.5">
          {installed ? (
            <ConnectedChip appName={app.name} pending={toggle.isPending} onToggle={() => toggle.mutate(false)} />
          ) : (
            <ConnectChip appName={app.name} pending={toggle.isPending} onToggle={() => toggle.mutate(true)} />
          )}
        </div>
      </motion.div>
    </li>
  )
}

/**
 * Apps panel — the hub root catalog surface (R27-b). Search filters the
 * live matrix; chips + tiles navigate REAL hash sub-pages (#/hub/c/:slug,
 * #/hub/app/:id). Install truth comes from the shared installed-set cache.
 */
function AppsPanel({ me }: { me: AppUser }) {
  const qc = useQueryClient()
  const [search, setSearch] = useState('')
  const hydrateStarted = useRef(false)

  const setQ = useInstalledSet(me.id)
  const knownIds = useMemo(() => new Set(setQ.data ?? []), [setQ.data])

  // hydrate the real install set once per mount so the "My apps" count chip
  // shows server truth immediately (Promise.all over the catalog, cached 60s)
  useEffect(() => {
    if (hydrateStarted.current) return
    hydrateStarted.current = true
    void hydrateInstalledSet(qc, me.id).catch(() => {})
  }, [qc, me.id])

  const counts = useMemo(() => {
    const map: Record<string, number> = {}
    for (const a of MATRIX) map[a.category] = (map[a.category] ?? 0) + 1
    return map
  }, [])

  // chips = static order first, then any live MATRIX category the static list misses
  const chips = useMemo(() => {
    const present = new Set(MATRIX.map((a) => a.category))
    const known = CATEGORY_CHIPS.filter((c) => c.id !== 'all' && present.has(c.id))
    const knownChipIds = new Set(known.map((c) => c.id))
    const extras = [...present]
      .filter((c) => !knownChipIds.has(c))
      .map((c) => ({ id: c, label: EXTRA_CHIP_LABELS[c] ?? c }))
    return [...known, ...extras] as Array<{ id: MatrixCategory; label: string }>
  }, [])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return MATRIX.filter(
      (a) =>
        q === '' ||
        a.name.toLowerCase().includes(q) ||
        a.input.toLowerCase().includes(q) ||
        a.secret.toLowerCase().includes(q),
    )
  }, [search])

  return (
    <div className="flex flex-col gap-3">
      {/* real catalog search — glass pill field */}
      <div className="glass-deep glass-sheen relative rounded-full">
        <Search className="absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-zinc-400" aria-hidden />
        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search 100 platforms…"
          aria-label="Search the app catalog"
          className="h-11 rounded-full border-transparent bg-transparent pl-10 shadow-none focus-visible:ring-1 focus-visible:ring-emerald-500/50 dark:bg-transparent"
        />
      </div>

      <div className="flex gap-1.5 overflow-x-auto pb-1 [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        <button
          type="button"
          onClick={() => {
            buzz(12)
            navigateHash(`/hub/c/${MINE_SLUG}`)
          }}
          aria-label="Open my connected apps"
          className={cn(
            'relative shrink-0 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-3 py-1.5 text-[11px] font-bold text-emerald-700 transition-colors hover:bg-emerald-500/20 active:scale-[0.97] dark:text-emerald-400',
            'after:absolute after:-inset-1 after:content-[""]', // extends the touch target past 44px
          )}
        >
          My apps{setQ.data !== undefined ? ` · ${setQ.data.length}` : ''}
        </button>
        {chips.map((chip) => (
          <button
            key={chip.id}
            type="button"
            onClick={() => {
              buzz(10)
              navigateHash(`/hub/c/${slugForCategory(chip.id)}`)
            }}
            aria-label={`Open the ${chip.label} category`}
            className={cn(
              'relative shrink-0 rounded-full border border-zinc-200 px-3 py-1.5 text-[11px] font-semibold text-zinc-600 transition-colors hover:border-emerald-400 active:scale-[0.97] dark:border-white/15 dark:bg-white/5 dark:text-zinc-300 dark:hover:border-emerald-500/50',
              'after:absolute after:-inset-1 after:content-[""]', // extends the touch target past 44px
            )}
          >
            {chip.label} · {counts[chip.id] ?? 0}
          </button>
        ))}
      </div>

      <ul className="grid grid-cols-2 gap-2">
        {filtered.map((app) => (
          <AppTile
            key={app.n}
            app={app}
            me={me}
            installed={knownIds.has(String(app.n))}
            onOpen={(a) => {
              buzz(10)
              navigateHash(`/hub/app/${a.n}`)
            }}
          />
        ))}
      </ul>
      {filtered.length === 0 ? (
        <p className="rounded-xl border border-dashed border-zinc-300 px-4 py-8 text-center text-[13px] text-zinc-500 dark:border-white/15">
          Nothing matches “{search}”.
        </p>
      ) : null}
    </div>
  )
}

// ── Hub shell ────────────────────────────────────────────────

export function HubTab({
  me,
  onOpenConversation,
}: {
  me: AppUser
  /** open a conversation (community chats) in the main chat surface */
  onOpenConversation?: (conversationId: string) => void
}) {
  const [panel, setPanel] = useState<HubPanel>('wallet')
  const { path } = useHashRoute()
  const walletQ = useWalletMini(me.id)

  // ── #/hub/* sub-page routing — handled inside the Hub tab ──
  // #/hub/c/<slug>  → category page (or "mine" = My apps)
  // #/hub/app/<id>  → app page (replaces the old detail sheet)
  const subPath = path.startsWith('/hub/') ? path.slice('/hub/'.length) : null
  const catSlug = subPath?.startsWith('c/') ? subPath.slice('c/'.length) : null
  const appId = subPath?.startsWith('app/') ? decodeURIComponent(subPath.slice('app/'.length)) : null

  // depth-aware slide direction: root(0) → category(1) → app(2)
  const depth = catSlug !== null ? 1 : appId !== null ? 2 : 0
  const [nav, setNav] = useState<{ depth: number; dir: 'forward' | 'back' }>({ depth: 0, dir: 'forward' })
  if (nav.depth !== depth) {
    // documented React pattern: adjust state during render when a route changes
    setNav({ depth, dir: depth > nav.depth ? 'forward' : 'back' })
  }
  const direction = nav.dir

  return (
    <div className="relative flex h-full flex-col overflow-hidden">
      <header className="glass-deep glass-sheen z-10 shrink-0 px-4 pb-3 pt-3">
        <div className="flex items-center gap-2.5">
          <div
            aria-hidden
            className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-md"
          >
            <Flame className="size-[18px]" />
          </div>
          <div className="min-w-0 flex-1">
            <h1 className="text-[16.5px] font-bold leading-tight">Hub</h1>
            <p className="truncate text-[11px] text-zinc-500">Economy · tasks · market · the 100-app matrix</p>
          </div>
          {/* real wallet chip — same cache key as the Wallet panel */}
          {walletQ.data ? (
            <button
              type="button"
              onClick={() => setPanel('wallet')}
              aria-label={`Wallet balance ${walletQ.data.coins} Pulse Coins — open wallet panel`}
              className="glass-pill flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3 text-[11px] font-bold text-emerald-700 outline-none transition-transform active:scale-95 dark:text-emerald-400"
            >
              <Coins className="size-3.5" aria-hidden />
              {walletQ.data.coins.toLocaleString()} PC
              <span className="ml-0.5 inline-flex items-center gap-0.5 text-sky-500 dark:text-sky-400">
                <Gem className="size-3" aria-hidden />
                {walletQ.data.gems}
              </span>
            </button>
          ) : (
            <SkeletonDots className="shrink-0 px-2 py-2" label="Loading wallet" />
          )}
        </div>
      </header>

      <nav aria-label="Hub panels" className="shrink-0 px-3 py-2">
        <div className="glass-pill flex gap-1 overflow-x-auto p-1 [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
          {PANELS.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => {
                buzz(6)
                setPanel(id)
              }}
              aria-current={panel === id ? 'true' : undefined}
              className={cn(
                'relative flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-xs font-semibold transition-colors',
                'after:absolute after:-inset-1 after:content-[""]', // extends the touch target past 44px
                panel === id ? 'text-white' : 'text-zinc-600 hover:text-emerald-600 dark:text-zinc-300',
              )}
            >
              {panel === id ? (
                <motion.span layoutId="hub-panel-pill" className="absolute inset-0 rounded-full bg-emerald-600" transition={{ type: 'spring', stiffness: 500, damping: 35 }} />
              ) : null}
              <Icon className="relative size-3.5" />
              <span className="relative">{label}</span>
            </button>
          ))}
        </div>
      </nav>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto px-4 pb-6">
        <motion.div key={panel} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.16 }}>
          {panel === 'wallet' ? <WalletPanel me={me} /> : null}
          {panel === 'tasks' ? <TasksPanel me={me} /> : null}
          {panel === 'market' ? <MarketPanel me={me} /> : null}
          {panel === 'swap' ? <SwapPanel me={me} /> : null}
          {panel === 'logs' ? <LogsPanel /> : null}
          {panel === 'apps' ? <AppsPanel me={me} /> : null}
        </motion.div>
      </div>

      {/* hash-routed hub sub-pages — slide over the rails, browser back works */}
      <AnimatePresence initial={false}>
        {catSlug !== null ? (
          <HubCategoryPage key={`c-${catSlug}`} slug={catSlug} me={me} direction={direction} />
        ) : appId !== null ? (
          <AppDetailPage key={`a-${appId}`} appId={appId} me={me} onOpenConversation={onOpenConversation} direction={direction} />
        ) : null}
      </AnimatePresence>
    </div>
  )
}
