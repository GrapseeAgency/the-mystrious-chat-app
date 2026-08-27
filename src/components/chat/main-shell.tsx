// ─────────────────────────────────────────────────────────────
// Pulse Chat — main shell: three-tab layout inside the phone
// frame with ChatRoom rendered as a full-screen overlay.
// ─────────────────────────────────────────────────────────────
'use client'

import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import type { AppUser } from '@/lib/types'
import { BottomNav, type PulseTab } from '@/components/chat/bottom-nav'
import { ChatsTab } from '@/components/chat/chats-tab'
import { ContactsTab } from '@/components/chat/contacts-tab'
import { ProfileTab } from '@/components/chat/profile-tab'
import { ChatRoom } from '@/components/chat/chat-room'
import { NewChatSheet } from '@/components/chat/new-chat-sheet'

export function MainShell({ me }: { me: AppUser }) {
  const [tab, setTab] = useState<PulseTab>('chats')
  const [openConversationId, setOpenConversationId] = useState<string | null>(null)
  const [newChatOpen, setNewChatOpen] = useState(false)
  const [newChatMode, setNewChatMode] = useState<'dm' | 'group'>('dm')
  // generation bumps per open-session so the sheet remounts with fresh state;
  // sheetMounted keeps it rendered through the vaul close animation
  const [newChatGeneration, setNewChatGeneration] = useState(0)
  const [sheetMounted, setSheetMounted] = useState(false)

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

  return (
    <div className="relative flex h-full flex-col overflow-hidden">
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
                onOpenConversation={setOpenConversationId}
                onOpenContacts={() => setTab('contacts')}
                onRequestNewChat={() => openNewChat('dm')}
                onGoProfile={() => setTab('profile')}
              />
            ) : null}
            {tab === 'contacts' ? (
              <ContactsTab
                me={me}
                onOpenConversation={setOpenConversationId}
                onGoProfile={() => setTab('profile')}
                onRequestNewGroup={() => openNewChat('group')}
              />
            ) : null}
            {tab === 'profile' ? <ProfileTab me={me} /> : null}
          </motion.div>
        </AnimatePresence>

        <AnimatePresence>
          {openConversationId !== null ? (
            <ChatRoom
              key="chat-room"
              me={me}
              conversationId={openConversationId}
              onClose={() => setOpenConversationId(null)}
            />
          ) : null}
        </AnimatePresence>
      </div>

      <BottomNav me={me} active={tab} onChange={setTab} />

      {sheetMounted ? (
        <NewChatSheet
          key={newChatGeneration}
          me={me}
          open={newChatOpen}
          onOpenChange={handleSheetClose}
          initialMode={newChatMode}
          onConversationOpened={(conversationId) => {
            setNewChatOpen(false)
            setOpenConversationId(conversationId)
          }}
        />
      ) : null}
    </div>
  )
}
