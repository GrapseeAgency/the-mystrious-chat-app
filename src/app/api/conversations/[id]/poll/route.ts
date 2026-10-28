// Legacy poll entry point shares the message authorization, delivery policy,
// transaction and immutable clientId receipt. Native producers enqueue kind=poll
// directly through their durable message outbox.
import { POST as createMessage } from '../messages/route'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

export async function POST(req: Request, context: { params: Promise<{ id: string }> }) {
  const body = await safeJson(req)
  const question = strField(body.question)
  const options = Array.isArray(body.options)
    ? body.options.map(option => typeof option === 'string' ? option.trim() : option)
    : body.options
  return createMessage(new Request(req.url, {
    method: 'POST', headers: req.headers, signal: req.signal,
    body: JSON.stringify({ senderId: body.senderId, clientId: body.clientId,
      content: question, kind: 'poll', payload: { question, options } }),
  }), context)
}
