// ─────────────────────────────────────────────────────────────
// Pulse Chat — main shell: four-tab layout inside the phone
// frame with ChatRoom rendered as a full-screen overlay, plus
// the system chrome: a slim command bar (Spotlight search ⌘K /
// Ctrl+K + Settings) and the Spotlight / Settings overlays.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useQueryClient } from '@tanstack/react-query'
import { Search, Settings as SettingsIcon } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { usePrefs } from '@/lib/prefs'
import { PulseNav, useNavStyle, type PulseTab } from '@/components/chat/nav-router'
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

export function MainShell({ me }: { me: AppUser }) {
  const [tab, setTab] = useState<PulseTab>('chats')
  const [navStyle] = useNavStyle()
  const railMode = navStyle === 'rail'
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

  const handleSheetClose = (next: boolean) => {
    setNewChatOpen(next)
    if (!next) {
      setTimeout(() => setSheetMounted(false), 300)
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
    <div className="relative flex h-full flex-col overflow-hidden">
      {/* system command bar — Spotlight trigger (⌘K) + Settings; hidden while a chat room owns the screen */}
      {openConversationId === null ? (
        <header className="relative z-[65] flex h-11 shrink-0 items-center gap-1 border-b border-zinc-200/70 bg-white/70 px-2 backdrop-blur-xl dark:border-zinc-800/70 dark:bg-zinc-900/70">
          <span
            aria-hidden
            className="inline-block size-1.5 shrink-0 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600"
          />
          <span className="pl-1.5 text-[10px] font-bold uppercase tracking-[0.16em] text-zinc-400 dark:text-zinc-500">
            {TAB_LABEL[tab]}
          </span>
          <div className="flex-1" />
          <button
            type="button"
            aria-label="Search"
            onClick={() => setSpotlightOpen(true)}
            className="flex size-10 items-center justify-center rounded-full text-zinc-500 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-700 active:scale-95 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
          >
            <Search className="size-[18px]" aria-hidden />
          </button>
          <button
            type="button"
            aria-label="Settings"
            onClick={() => setSettingsOpen(true)}
            className="flex size-10 items-center justify-center rounded-full text-zinc-500 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-700 active:scale-95 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
          >
            <SettingsIcon className="size-[18px]" aria-hidden />
          </button>
        </header>
      ) : null}

      <div className="flex min-h-0 flex-1">
        {railMode && !settingsOpen ? <PulseNav me={me} active={tab} onChange={setTab} /> : null}
        <div className="relative min-h-0 flex-1">
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={tab}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.16, ease: 'easeOut' }}
            className="absolute inset-0"
          >
            {tab === 'chats' ? (
              <ChatsTab
                me={me}
                onOpenConversation={(conversationId, anchorMs, jumpTargetId) => {
                  setOpenConversationAnchorMs(anchorMs)
                  setJumpMessageId(jumpTargetId ?? null)
                  setOpenConversationId(conversationId)
                }}
                onOpenContacts={() => setTab('contacts')}
                onRequestNewChat={() => openNewChat('dm')}
                onGoProfile={() => setTab('profile')}
              />
            ) : null}
            {tab === 'hub' ? <HubTab me={me} /> : null}
            {tab === 'contacts' ? (
              <ContactsTab
                me={me}
                onOpenConversation={(conversationId) => {
                  setOpenConversationAnchorMs(null)
                  setJumpMessageId(null)
                  setOpenConversationId(conversationId)
                }}
                onGoProfile={() => setTab('profile')}
                onRequestNewGroup={() => openNewChat('group')}
              />
            ) : null}
            {tab === 'profile' ? (
              <ProfileTab
                me={me}
                onOpenSavedMessage={(conversationId, messageId) => {
                  setTab('chats')
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

      {!railMode && !settingsOpen ? <PulseNav me={me} active={tab} onChange={setTab} /> : null}

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
              setTab('hub')
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
            onOpenHub={() => setTab('hub')}
          />
        ) : null}
      </AnimatePresence>
    </div>
  )
}
