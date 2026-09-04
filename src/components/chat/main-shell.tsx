// ─────────────────────────────────────────────────────────────
// Pulse Chat — main shell: four-tab layout inside the phone
// frame with ChatRoom rendered as a full-screen overlay, plus
// the system chrome: a glass command bar (Spotlight search ⌘K /
// Ctrl+K + Settings), edge-swipe tab switching, the liquid-glass
// floating dock (acrylic style) and the Spotlight / Settings
// overlays.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion, type Variants } from 'framer-motion'
import { useQueryClient } from '@tanstack/react-query'
import { Search, Settings as SettingsIcon } from 'lucide-react'
import { toast } from 'sonner'
import { haptic } from '@/lib/pulse-settings'
import type { AppUser } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { usePrefs } from '@/lib/prefs'
import { ease, pressSpring, pressTap } from '@/lib/motion'
import { useUiTheme } from '@/lib/ui-theme'
import {
  PulseNavBar,
  zoneFor,
  useNavStyle,
  type PulseTab,
  type NavContextAction,
} from '@/components/chat/nav-router'
import { ChatsTab } from '@/components/chat/chats-tab'
import { ContactsTab } from '@/components/chat/contacts-tab'
import { ProfileTab } from '@/components/chat/profile-tab'
import { HubTab } from '@/components/hub/hub-tab'
import { ChatRoom } from '@/components/chat/chat-room'
import { NewChatSheet } from '@/components/chat/new-chat-sheet'
import { JoinGroupSheet } from '@/components/chat/join-sheet'
import { SettingsScreen } from '@/components/chat/settings-screen'
import { SpotlightOverlay } from '@/components/chat/spotlight'

const TAB_LABEL: Record<PulseTab, string> = {
  chats: 'Chats',
  hub: 'Hub',
  contacts: 'Contacts',
  profile: 'Profile',
}

/** Canonical tab order — drives auto direction for slide/fade transitions. */
const TAB_ORDER: Array<PulseTab> = ['chats', 'hub', 'contacts', 'profile']

/**
 * Direction-aware tab-panel transition (R22): content slides ±24px +
 * fades with the signature swift-out ease. popLayout lets the outgoing
 * panel fly while the incoming one settles. Reduced motion → instant swap.
 */
function makePanelVariants(reduced: boolean): Variants {
  return {
    enter: (dir: number) => (reduced ? { opacity: 0 } : { opacity: 0, x: dir * 24 }),
    center: { opacity: 1, x: 0 },
    exit: (dir: number) => (reduced ? { opacity: 0 } : { opacity: 0, x: dir * -24 }),
  }
}

export function MainShell({ me }: { me: AppUser }) {
  // tab + last travel direction kept together so AnimatePresence always
  // knows which way to slide (dock taps: index order · swipes: gesture)
  const [navState, setNavState] = useState<{ tab: PulseTab; dir: 1 | -1 }>({ tab: 'chats', dir: 1 })
  const tab = navState.tab
  const changeTab = useCallback((next: PulseTab, dir?: 1 | -1) => {
    setNavState((prev) => {
      if (prev.tab === next) return prev
      const nextDir: 1 | -1 = dir ?? (TAB_ORDER.indexOf(next) > TAB_ORDER.indexOf(prev.tab) ? 1 : -1)
      return { tab: next, dir: nextDir }
    })
  }, [])
  const [navStyle] = useNavStyle()
  const navZone = zoneFor(navStyle)
  /** floating-top / command-bar render their own top bar — the default header hides */
  const headerOwnedByNav = navZone === 'top'
  const [uiTheme] = useUiTheme()
  const [openConversationId, setOpenConversationId] = useState<string | null>(null)
  /** frozen pre-open read watermark for the open conversation (unread divider) */
  const [openConversationAnchorMs, setOpenConversationAnchorMs] = useState<number | null>(null)
  /** message to scroll-to + flash once the room's history is rendered (global-search hit) */
  const [jumpMessageId, setJumpMessageId] = useState<string | null>(null)
  const [newChatOpen, setNewChatOpen] = useState(false)
  const [newChatMode, setNewChatMode] = useState<'dm' | 'group'>('dm')
  // generation bumps per open-session so the sheet remounts with fresh state;
  // sheetMounted keeps it rendered through the vaul close animation
  const [newChatGeneration, setNewChatGeneration] = useState(0)
  const [sheetMounted, setSheetMounted] = useState(false)
  // system overlays — Spotlight search palette + full-screen Settings tree
  const [spotlightOpen, setSpotlightOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const queryClient = useQueryClient()
  const hydratePrefs = usePrefs((s) => s.hydrate)
  const reducedMotion = useReducedMotion()
  const panelVariants = makePanelVariants(reducedMotion === true)
  // edge-swipe intent anchor: only touches born within 24px of a screen
  // edge can become tab switches (never hijacks vertical scroll)
  const swipeAnchor = useRef<{ x: number; y: number; edge: 'left' | 'right' } | null>(null)
  /** invite deep-link code lifted from ?join= (null = none pending) */
  const [pendingInviteCode, setPendingInviteCode] = useState<string | null>(() => {
    // pure read — consumed on first render, cleaned up just after mount
    if (typeof window === 'undefined') return null
    const code = new URLSearchParams(window.location.search).get('join')?.trim().toUpperCase() ?? ''
    return code.length > 0 ? code.slice(0, 16) : null
  })

  // strip ?join= from the URL once the sheet is up (history-only side
  // effect — no setState here, so refresh never re-prompts)
  useEffect(() => {
    if (pendingInviteCode === null) return
    const params = new URLSearchParams(window.location.search)
    if (!params.has('join')) return
    params.delete('join')
    const rest = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : ''))
  }, [pendingInviteCode])

  // pull server-truth preferences once per session (real /api/settings)
  useEffect(() => {
    void hydratePrefs(me.id)
  }, [hydratePrefs, me.id])

  // ⌘K / Ctrl+K toggles Spotlight from anywhere in the shell
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setSettingsOpen(false)
        setSpotlightOpen((v) => !v)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const openNewChat = (mode: 'dm' | 'group') => {
    setNewChatMode(mode)
    setNewChatGeneration((g) => g + 1)
    setSheetMounted(true)
    // render the fresh sheet closed for a frame so vaul plays its slide-up
    setNewChatOpen(false)
    setTimeout(() => setNewChatOpen(true), 30)
  }

  /** contextual-dock chip — per-tab action wired to the same flows as the shell */
  const handleContextAction = useCallback((action: NavContextAction) => {
    if (action === 'new-chat') openNewChat('dm')
    else if (action === 'new-group') openNewChat('group')
    else if (action === 'search') setSpotlightOpen(true)
    else setSettingsOpen(true)
  }, [])

  const handleSheetClose = (next: boolean) => {
    setNewChatOpen(next)
    if (!next) {
      setTimeout(() => setSheetMounted(false), 300)
    }
  }

  // ── edge-swipe tab switching (R22) ─────────────────────────
  // left edge + swipe right → previous tab · right edge + swipe left → next.
  // Pure passive listeners: no preventDefault, so vertical scroll and inner
  // carousels are never hijacked; intent cancels the moment it turns vertical.
  const onContentTouchStart = (e: React.TouchEvent) => {
    if (reducedMotion) return
    if (openConversationId !== null || settingsOpen || spotlightOpen) return
    const t = e.touches[0]
    if (!t) return
    const edge: 'left' | 'right' | null =
      t.clientX <= 24 ? 'left' : t.clientX >= window.innerWidth - 24 ? 'right' : null
    swipeAnchor.current = edge ? { x: t.clientX, y: t.clientY, edge } : null
  }

  const onContentTouchMove = (e: React.TouchEvent) => {
    const anchor = swipeAnchor.current
    if (!anchor) return
    const t = e.touches[0]
    if (!t) return
    if (Math.abs(t.clientY - anchor.y) > Math.abs(t.clientX - anchor.x) + 8) {
      swipeAnchor.current = null // vertical intent — release the gesture
    }
  }

  const onContentTouchEnd = (e: React.TouchEvent) => {
    const anchor = swipeAnchor.current
    swipeAnchor.current = null
    if (!anchor || reducedMotion) return
    const t = e.changedTouches[0]
    if (!t) return
    const dx = t.clientX - anchor.x
    if (Math.abs(dx) < 56) return // too short — a tap, not a swipe
    const idx = TAB_ORDER.indexOf(tab)
    if (anchor.edge === 'left' && dx > 0 && idx > 0) {
      haptic(8)
      changeTab(TAB_ORDER[idx - 1], -1)
    } else if (anchor.edge === 'right' && dx < 0 && idx < TAB_ORDER.length - 1) {
      haptic(8)
      changeTab(TAB_ORDER[idx + 1], 1)
    }
  }

  /** Spotlight → People row: open (or lazily create) a 1:1 DM, same call as NewChatSheet. */
  const openDmByUserId = useCallback(
    async (userId: string) => {
      try {
        const res = await apiJson<{ conversation: { id: string } }>('/api/conversations', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ creatorId: me.id, memberIds: [userId], isGroup: false }),
        })
        void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
        setOpenConversationAnchorMs(null)
        setJumpMessageId(null)
        setOpenConversationId(res.conversation.id)
      } catch (err) {
        toast.error(err instanceof Error ? err.message : 'Could not open that chat')
      }
    },
    [me.id, queryClient],
  )

  return (
    <div data-ui={`ui-${uiTheme}`} className="ui-root relative flex h-full flex-col overflow-hidden">
      {/* system command bar — floating capsule top bar (Spotlight ⌘K + Settings).
          Hidden while a chat room owns the screen or the nav style brings its own. */}
      {openConversationId === null && !headerOwnedByNav ? (
        <header
          className="sticky top-0 z-[65] mx-2 mt-1.5 flex h-11 shrink-0 items-center gap-1 rounded-full border border-zinc-200/70 bg-white/70 px-3 shadow-[0_6px_24px_-8px_rgba(0,0,0,0.18),inset_0_1px_0_rgba(255,255,255,0.55)] backdrop-blur-2xl backdrop-saturate-150 [will-change:transform] dark:border-white/10 dark:bg-zinc-900/65 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.08),0_6px_24px_-8px_rgba(0,0,0,0.5)]"
        >
          <span
            aria-hidden
            className="inline-block size-1.5 shrink-0 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600"
          />
          <span className="pl-1.5 text-[10px] font-bold uppercase tracking-[0.16em] text-zinc-400 dark:text-zinc-500">
            {TAB_LABEL[tab]}
          </span>
          <div className="flex-1" />
          <motion.button
            type="button"
            aria-label="Search"
            onClick={() => setSpotlightOpen(true)}
            whileTap={reducedMotion ? undefined : pressTap}
            transition={pressSpring}
            className="flex size-10 items-center justify-center rounded-full text-zinc-500 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-700 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
          >
            <Search className="size-[18px]" aria-hidden />
          </motion.button>
          <motion.button
            type="button"
            aria-label="Settings"
            onClick={() => setSettingsOpen(true)}
            whileTap={reducedMotion ? undefined : pressTap}
            transition={pressSpring}
            className="flex size-10 items-center justify-center rounded-full text-zinc-500 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-700 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
          >
            <SettingsIcon className="size-[18px]" aria-hidden />
          </motion.button>
        </header>
      ) : null}

      <div className="flex min-h-0 flex-1">
        {navZone === 'side' && !settingsOpen ? (
          <PulseNavBar
            me={me}
            active={tab}
            onChange={changeTab}
            onSearch={() => setSpotlightOpen(true)}
            onSettings={() => setSettingsOpen(true)}
            onContextAction={handleContextAction}
          />
        ) : null}
        <div
          className="relative min-h-0 flex-1"
          onTouchStart={onContentTouchStart}
          onTouchMove={onContentTouchMove}
          onTouchEnd={onContentTouchEnd}
          onTouchCancel={() => {
            swipeAnchor.current = null
          }}
        >
        {/* sync crossfade: both panels are absolute inset-0 already, so the
            outgoing and incoming overlap for 220ms (slide+fade swap).
            (popLayout stalled the enter tween at its first keyframe here.) */}
        <AnimatePresence initial={false} custom={navState.dir}>
          <motion.div
            key={tab}
            role="tabpanel"
            aria-label={`${TAB_LABEL[tab]} tab`}
            custom={navState.dir}
            variants={panelVariants}
            initial="enter"
            animate="center"
            exit="exit"
            transition={{ duration: reducedMotion ? 0 : 0.22, ease: ease.out }}
            className="absolute inset-0 z-[1]"
            style={reducedMotion ? undefined : { willChange: 'transform' }}
          >
            {tab === 'chats' ? (
              <ChatsTab
                me={me}
                onOpenConversation={(conversationId, anchorMs, jumpTargetId) => {
                  setOpenConversationAnchorMs(anchorMs)
                  setJumpMessageId(jumpTargetId ?? null)
                  setOpenConversationId(conversationId)
                }}
                onOpenContacts={() => changeTab('contacts')}
                onRequestNewChat={() => openNewChat('dm')}
                onGoProfile={() => changeTab('profile')}
              />
            ) : null}
            {tab === 'hub' ? (
              <HubTab
                me={me}
                onOpenConversation={(conversationId) => {
                  setOpenConversationAnchorMs(null)
                  setJumpMessageId(null)
                  setOpenConversationId(conversationId)
                }}
              />
            ) : null}
            {tab === 'contacts' ? (
              <ContactsTab
                me={me}
                onOpenConversation={(conversationId) => {
                  setOpenConversationAnchorMs(null)
                  setJumpMessageId(null)
                  setOpenConversationId(conversationId)
                }}
                onGoProfile={() => changeTab('profile')}
                onRequestNewGroup={() => openNewChat('group')}
              />
            ) : null}
            {tab === 'profile' ? (
              <ProfileTab
                me={me}
                onOpenSavedMessage={(conversationId, messageId) => {
                  changeTab('chats')
                  setOpenConversationAnchorMs(null)
                  setJumpMessageId(messageId)
                  setOpenConversationId(conversationId)
                }}
              />
            ) : null}
          </motion.div>
        </AnimatePresence>

        <AnimatePresence>
          {openConversationId !== null ? (
            <ChatRoom
              key="chat-room"
              me={me}
              conversationId={openConversationId}
              unreadAnchorMs={openConversationAnchorMs}
              initialJumpMessageId={jumpMessageId}
              onClose={() => setOpenConversationId(null)}
            />
          ) : null}
        </AnimatePresence>
        </div>
      </div>

      {/* R25 nav system — 12 architectures, one mount point. Bottom/top/overlay
          zones float here; the side rail renders in the flex row above. Hidden
          while a chat room, Settings or a blocking sheet owns the screen. */}
      <AnimatePresence>
        {openConversationId === null && navZone !== 'side' && !settingsOpen && !sheetMounted && pendingInviteCode === null ? (
          <PulseNavBar
            key={`nav-${navStyle}`}
            me={me}
            active={tab}
            onChange={changeTab}
            onSearch={() => setSpotlightOpen(true)}
            onSettings={() => setSettingsOpen(true)}
            onContextAction={handleContextAction}
          />
        ) : null}
      </AnimatePresence>

      {sheetMounted ? (
        <NewChatSheet
          key={newChatGeneration}
          me={me}
          open={newChatOpen}
          onOpenChange={handleSheetClose}
          initialMode={newChatMode}
          onConversationOpened={(conversationId) => {
            setNewChatOpen(false)
            setOpenConversationAnchorMs(null)
            setJumpMessageId(null)
            setOpenConversationId(conversationId)
          }}
        />
      ) : null}

      {pendingInviteCode !== null ? (
        <JoinGroupSheet
          me={me}
          code={pendingInviteCode}
          open
          onClose={() => setPendingInviteCode(null)}
          onJoined={(conversationId) => {
            setPendingInviteCode(null)
            setOpenConversationAnchorMs(null)
            setJumpMessageId(null)
            setOpenConversationId(conversationId)
          }}
        />
      ) : null}

      {/* Settings — full-screen tree (z-70, above tab content; dock hidden while open) */}
      <AnimatePresence>
        {settingsOpen ? (
          <SettingsScreen
            open
            onClose={() => setSettingsOpen(false)}
            me={me}
            onOpenHub={() => {
              setSettingsOpen(false)
              changeTab('hub')
            }}
          />
        ) : null}
      </AnimatePresence>

      {/* Spotlight — global search palette (z-90, above everything incl. the dock) */}
      <AnimatePresence>
        {spotlightOpen ? (
          <SpotlightOverlay
            open
            onClose={() => setSpotlightOpen(false)}
            onOpenConversation={(conversationId, anchorMs, jumpTargetId) => {
              setSpotlightOpen(false)
              setOpenConversationAnchorMs(anchorMs ?? null)
              setJumpMessageId(jumpTargetId ?? null)
              setOpenConversationId(conversationId)
            }}
            onOpenDm={(userId) => void openDmByUserId(userId)}
            onRequestNewChat={() => openNewChat('dm')}
            onOpenHub={() => changeTab('hub')}
          />
        ) : null}
      </AnimatePresence>
    </div>
  )
}
