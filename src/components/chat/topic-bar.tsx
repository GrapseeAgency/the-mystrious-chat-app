// Zulip-style topic chip rail. "General" is the implicit default
// (topicId null); real Topic rows render as glass chips with a live
// filed-message count badge. The "+" chip opens a small inline
// composer with an icon preset row. Horizontal scroll, spring taps,
// layout-animated active fill.
'use client'

import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { Check, LoaderCircle, Plus, X } from 'lucide-react'
import { TOPIC_ICON_GLYPHS } from '@/components/ui/icons'
import { TOPIC_ICON_DEFAULT, TOPIC_ICON_IDS, topicIconId, type TopicIconId } from '@/lib/icon-ids'
import type { TopicSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { spring, stagger, pressTap } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'

const GeneralGlyph = TOPIC_ICON_GLYPHS.chat

/** Renders a persisted topic icon id as its designed glyph. */
function TopicIconGlyph({ value, className }: { value: string; className?: string }) {
  // module-scope record member access: stable component reference
  const Glyph = TOPIC_ICON_GLYPHS[topicIconId(value)]
  return <Glyph className={className} aria-hidden />
}

export function TopicBar({
  topics,
  activeTopicId,
  onSelect,
  onCreate,
  reducedMotion = false,
}: {
  topics: TopicSummary[]
  activeTopicId: string | null
  onSelect: (topicId: string | null) => void
  /** creates (or dedupes to) a topic, resolves its id; null on failure */
  onCreate: (name: string, icon: TopicIconId) => Promise<string | null>
  reducedMotion?: boolean
}) {
  const [createOpen, setCreateOpen] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [iconDraft, setIconDraft] = useState<TopicIconId>(TOPIC_ICON_DEFAULT)
  const [submitting, setSubmitting] = useState(false)

  const submitCreate = async () => {
    const name = nameDraft.trim()
    if (name.length === 0 || submitting) return
    setSubmitting(true)
    const id = await onCreate(name, iconDraft)
    setSubmitting(false)
    if (id !== null) {
      setCreateOpen(false)
      setNameDraft('')
      setIconDraft(TOPIC_ICON_DEFAULT)
    }
  }

  const chipMotion = reducedMotion
    ? {}
    : { layout: true as const, transition: spring.snappy }

  return (
    <div
      role="tablist"
      aria-label="Topics rail"
      className="relative z-20 shrink-0 border-b border-zinc-200/80 bg-white/85 backdrop-blur-md dark:border-zinc-800 dark:bg-zinc-900/80"
    >
      <div className="pulse-scroll flex items-center gap-1.5 overflow-x-auto px-2.5 py-1.5">
        {/* General - the implicit topic (topicId null) */}
        <motion.button
          key="topic-general"
          type="button"
          role="tab"
          aria-selected={activeTopicId === null}
          {...chipMotion}
          whileTap={reducedMotion ? undefined : pressTap}
          onClick={() => {
            haptic(8)
            onSelect(null)
          }}
          className={cn(
            'relative flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3 text-[12.5px] font-semibold outline-none transition-colors after:absolute after:inset-y-[-5px] after:inset-x-[-4px] after:content-[""]',
            activeTopicId === null
              ? 'bg-amber-500 text-white shadow-sm shadow-amber-600/25'
              : 'border border-zinc-200/80 bg-white/70 text-zinc-600 backdrop-blur hover:border-amber-300 hover:text-amber-700 dark:border-white/10 dark:bg-zinc-900/60 dark:text-zinc-300 dark:hover:text-amber-400',
          )}
        >
          <GeneralGlyph className="size-3.5" aria-hidden />
          General
        </motion.button>

        {topics.map((topic, i) => {
          const active = topic.id === activeTopicId
          return (
            <motion.button
              key={topic.id}
              type="button"
              role="tab"
              aria-selected={active}
              {...chipMotion}
              initial={reducedMotion ? false : { opacity: 0, scale: 0.9, y: 4 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              transition={{ ...spring.bouncy, delay: reducedMotion ? 0 : stagger(i, 0.03, 6) }}
              whileTap={reducedMotion ? undefined : pressTap}
              onClick={() => {
                haptic(8)
                onSelect(topic.id)
              }}
              className={cn(
                'relative flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3 text-[12.5px] font-semibold outline-none transition-colors after:absolute after:inset-y-[-5px] after:inset-x-[-4px] after:content-[""]',
                active
                  ? 'bg-amber-500 text-white shadow-sm shadow-amber-600/25'
                  : 'border border-zinc-200/80 bg-white/70 text-zinc-600 backdrop-blur hover:border-amber-300 hover:text-amber-700 dark:border-white/10 dark:bg-zinc-900/60 dark:text-zinc-300 dark:hover:text-amber-400',
              )}
            >
              <TopicIconGlyph value={topic.emoji} className="size-3.5 shrink-0" />
              <span className="max-w-[120px] truncate">{topic.name}</span>
              <span
                className={cn(
                  'flex h-[17px] min-w-[17px] items-center justify-center rounded-full px-1 text-[9.5px] font-bold tabular-nums',
                  active
                    ? 'bg-white/25 text-white'
                    : 'bg-zinc-200/80 text-zinc-500 dark:bg-white/10 dark:text-zinc-400',
                )}
              >
                {topic.messageCount > 99 ? '99+' : topic.messageCount}
              </span>
            </motion.button>
          )
        })}

        {/* plus chip: create a topic */}
        <motion.button
          key="topic-create"
          type="button"
          aria-label="New topic"
          aria-expanded={createOpen}
          {...chipMotion}
          whileTap={reducedMotion ? undefined : { scale: 0.88 }}
          onClick={() => {
            haptic(8)
            setCreateOpen((v) => !v)
          }}
          className={cn(
            'relative flex size-9 shrink-0 items-center justify-center rounded-full outline-none transition-colors after:absolute after:inset-y-[-5px] after:inset-x-[-4px] after:content-[""]',
            createOpen
              ? 'bg-amber-500 text-white'
              : 'border border-dashed border-zinc-300 text-zinc-400 hover:border-amber-400 hover:text-amber-600 dark:border-zinc-600 dark:text-zinc-500 dark:hover:text-amber-400',
          )}
        >
          <Plus className={cn('size-4 transition-transform', createOpen && 'rotate-45')} aria-hidden />
        </motion.button>
      </div>

      {/* inline topic composer, anchored under the rail */}
      <AnimatePresence>
        {createOpen ? (
          <motion.div
            key="topic-create-panel"
            initial={reducedMotion ? false : { opacity: 0, y: -8, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={reducedMotion ? { opacity: 0 } : { opacity: 0, y: -6, scale: 0.98 }}
            transition={spring.snappy}
            style={{ willChange: 'transform, opacity' }}
            className="absolute top-full left-2 right-2 z-30 mt-1 rounded-2xl border border-zinc-200/80 bg-white/95 p-3 shadow-xl shadow-zinc-900/10 backdrop-blur-2xl dark:border-white/10 dark:bg-zinc-900/95 dark:shadow-black/40"
          >
            <div className="flex items-center justify-between pb-2">
              <p className="text-[11px] font-bold uppercase tracking-widest text-amber-700 dark:text-amber-400">
                New topic
              </p>
              <button
                type="button"
                aria-label="Cancel new topic"
                onClick={() => setCreateOpen(false)}
                className="rounded-full p-1 text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800"
              >
                <X className="size-4" aria-hidden />
              </button>
            </div>
            <div className="flex items-center gap-1.5">
              <input
                autoFocus
                value={nameDraft}
                maxLength={32}
                onChange={(e) => setNameDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    void submitCreate()
                  }
                }}
                placeholder="Topic name..."
                aria-label="Topic name"
                className="h-10 min-w-0 flex-1 rounded-xl border border-zinc-200 bg-zinc-50 px-3 text-sm outline-none transition-colors focus:border-amber-400 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100"
              />
              <button
                type="button"
                aria-label="Create topic"
                disabled={nameDraft.trim().length === 0 || submitting}
                onClick={() => void submitCreate()}
                className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-amber-500 text-white shadow-sm shadow-amber-600/25 outline-none transition-all hover:bg-amber-500/90 active:scale-90 disabled:opacity-50"
              >
                {submitting ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <Check className="size-4" aria-hidden />
                )}
              </button>
            </div>
            <div className="flex gap-1 pt-2" role="radiogroup" aria-label="Topic icon">
              {TOPIC_ICON_IDS.map((id) => {
                const active = iconDraft === id
                return (
                  <motion.button
                    key={id}
                    type="button"
                    role="radio"
                    aria-checked={active}
                    aria-label={`Topic icon ${id}`}
                    whileTap={reducedMotion ? undefined : { scale: 0.85 }}
                    transition={{ duration: 0.15 }}
                    onClick={() => setIconDraft(id)}
                    className={cn(
                      'flex size-8 items-center justify-center rounded-lg text-amber-700 outline-none transition-colors dark:text-amber-400',
                      active
                        ? 'bg-amber-500/15 ring-1 ring-amber-400'
                        : 'hover:bg-zinc-100 dark:hover:bg-zinc-800',
                    )}
                  >
                    <TopicIconGlyph value={id} className="size-4" />
                  </motion.button>
                )
              })}
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  )
}
