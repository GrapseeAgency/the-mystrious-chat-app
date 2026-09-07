import { PrismaClient } from '../../prisma/generated-client'

// v2 — bumped after the User.avatar schema push so HMR re-evaluations construct a
// client from the freshly generated runtime instead of reusing the stale singleton.
// v3 — R35-a: same protocol after the UserVerification model push + client regen
// (the long-running dev server held a pre-UserVerification client in memory).
// v4 — R38: same protocol after the Conversation.screenPrivacy column push +
// client regen (the long-running dev server held a pre-screenPrivacy client).
// v5 — R39: same protocol after the Automation model + Message.viaAutomation
// push + client regen (the long-running dev server held a pre-Automation client).
// v6 — R40: same protocol after the Message.filePath/fileName/fileSize columns
// push + client regen (the long-running dev server held a pre-filePath client).
// v7 — R41: same protocol after the UploadedFile model push + client regen
// (durable attachment store — upload bytes live in SQLite, disk is the fast path).
// v8 — R42: same protocol after the ConversationParticipant.screenPrivacy column
// push + client regen (per-viewer screen security veil).
// v9 — R42 fix: the v8 key got bound BEFORE the regen finished (HMR evaluated
// db.ts between the edit and the client regen), so the running server kept a
// pre-screenPrivacy runtime. Key bumped again POST-regen to force a truly
// fresh client construction — do not reuse a key across a client regen.
// v10 — R43: same protocol, bumped AFTER the Message.transcript column push +
// client regen (per the v9 lesson: the key change must FOLLOW the regen).
// v11 — R43 fix: v10 was authored pre-regen again (same HMR gap) — bumped
// POST-regen so the running server constructs a client that knows .transcript.
// v12 — R44: bumped AFTER the Conversation.slowModeSeconds +
// ConversationParticipant.manualUnread push + client regen (post-regen rule).
const globalForPrisma = globalThis as unknown as {
  prismaV12: PrismaClient | undefined
}

export const db =
  globalForPrisma.prismaV12 ??
  new PrismaClient({
    log: ['query'],
  })

if (process.env.NODE_ENV !== 'production') globalForPrisma.prismaV12 = db
