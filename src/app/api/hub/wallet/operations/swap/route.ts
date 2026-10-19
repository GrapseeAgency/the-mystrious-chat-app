import { requireWalletOwner } from '@/lib/wallet-authorization'
import { POST as swap } from '../../../swap/route'
export const dynamic = 'force-dynamic'
export async function POST(req: Request) {
  const body = await req.clone().json().catch(() => null)
  if (!body || typeof body.clientId !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(body.clientId)) {
    return Response.json({ error: 'A persistent wallet operation ID is required.' }, { status: 400 })
  }
  const denied = await requireWalletOwner(req, body.userId)
  if (denied) return denied
  return swap(req)
}
