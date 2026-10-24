import type { Prisma, PrismaClient } from '../../prisma/generated-client'

/** Keep current room authority, conflict checks, writes and acknowledgement in
 * one snapshot. The HTTP entry point must bind the existing owner credential. */
export async function roomControl(db: PrismaClient, operation: (tx: Prisma.TransactionClient) => Promise<Response>): Promise<Response> {
  try {
    const response = await db.$transaction(operation, { isolationLevel: 'Serializable' })
    response.headers.set('Cache-Control', 'private, no-store')
    response.headers.set('Vary', 'Authorization')
    return response
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
      return Response.json({ error: 'Room controls changed or are busy. Refresh before retrying.' },
        { status: 409, headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
    }
    throw error
  }
}
