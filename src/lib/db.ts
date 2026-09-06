import { PrismaClient } from '../../prisma/generated-client'

// v2 — bumped after the User.avatar schema push so HMR re-evaluations construct a
// client from the freshly generated runtime instead of reusing the stale singleton.
// v3 — R35-a: same protocol after the UserVerification model push + client regen
// (the long-running dev server held a pre-UserVerification client in memory).
// v4 — R38: same protocol after the Conversation.screenPrivacy column push +
// client regen (the long-running dev server held a pre-screenPrivacy client).
// v5 — R39: same protocol after the Automation model + Message.viaAutomation
// push + client regen (the long-running dev server held a pre-Automation client).
const globalForPrisma = globalThis as unknown as {
  prismaV5: PrismaClient | undefined
}

export const db =
  globalForPrisma.prismaV5 ??
  new PrismaClient({
    log: ['query'],
  })

if (process.env.NODE_ENV !== 'production') globalForPrisma.prismaV5 = db
