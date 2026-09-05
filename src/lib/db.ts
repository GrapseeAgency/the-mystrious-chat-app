import { PrismaClient } from '../../prisma/generated-client'

// v2 — bumped after the User.avatar schema push so HMR re-evaluations construct a
// client from the freshly generated runtime instead of reusing the stale singleton.
const globalForPrisma = globalThis as unknown as {
  prismaV2: PrismaClient | undefined
}

export const db =
  globalForPrisma.prismaV2 ??
  new PrismaClient({
    log: ['query'],
  })

if (process.env.NODE_ENV !== 'production') globalForPrisma.prismaV2 = db