import { requireActionOwner } from '@/lib/wallet-authorization'
import { POST as create } from '../route'
export const dynamic = 'force-dynamic'
/** Versioned creation path: old gateways must fail before making any object. */
export async function POST(req: Request) {
  const body = await req.clone().json().catch(() => null)
  if (!body || typeof body.clientId !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(body.clientId)) {
    return Response.json({ error: 'A persistent creation ID is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, body.userId, 'room creation')
  if (denied) return denied
  return create(req)
}
