// ─────────────────────────────────────────────────────────────
// Pulse — chats-list loading skeleton (R27-e: extracted so the
// main list and the #/chats/archived sub-page share one shape).
// ─────────────────────────────────────────────────────────────
'use client'

import { Skeleton } from '@/components/ui/skeleton'

export function RowSkeleton() {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <Skeleton className="size-12 rounded-full" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-3.5 w-1/3" />
        <Skeleton className="h-3 w-2/3" />
      </div>
    </div>
  )
}
