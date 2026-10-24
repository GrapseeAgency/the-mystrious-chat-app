/** One eligibility snapshot for both live banners and remote push. Mute means no ring. */
export async function dispatchGroupCallRing(
  participants: ReadonlyArray<{ userId: string; mutedUntil: Date | null }>,
  callerId: string,
  notify: (recipientIds: string[]) => Promise<void | boolean>,
  push: (recipientIds: string[]) => Promise<{ sent: number }>,
  now = new Date(),
): Promise<{ rang: number; pushed: number }> {
  const recipients = [...new Set(participants
    .filter(member => member.userId !== callerId && (!member.mutedUntil || member.mutedUntil <= now))
    .map(member => member.userId))]
  if (recipients.length === 0) return { rang: 0, pushed: 0 }
  if (await notify([...recipients]) === false) return { rang: 0, pushed: 0 }
  const report = await push([...recipients])
  return { rang: recipients.length, pushed: report.sent }
}

export class GroupCallRingError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

/** The relay owns call lifetime. Only its first live, identity-bound admission
 * may trigger push; a timeout/lost ACK never guesses that admission succeeded. */
export async function relayGroupCallRing(
  recipients: string[],
  payload: { conversationId: string; callId: string; kind: 'voice' | 'video'; caller: { id: string; name?: string; color?: string; avatar?: string | null }; title: string },
  fetcher: typeof fetch = fetch,
): Promise<{ accepted: boolean; expiresAt: string }> {
  try {
    const response = await fetcher('http://localhost:3003/notify', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event: 'gcall:ring', recipients, payload }), signal: AbortSignal.timeout(2500),
    })
    if (response.status === 409) throw new GroupCallRingError(409, 'This call is no longer available to ring.')
    if (!response.ok) throw new Error('Relay refused ring')
    const receipt = await response.json()
    if (receipt?.ok !== true || receipt.ringReceiptVersion !== 1 ||
        receipt.conversationId !== payload.conversationId || receipt.callId !== payload.callId ||
        receipt.callerId !== payload.caller.id || typeof receipt.accepted !== 'boolean' ||
        typeof receipt.expiresAt !== 'string' || !Number.isFinite(Date.parse(receipt.expiresAt))) {
      throw new Error('Invalid ring acknowledgement')
    }
    return { accepted: receipt.accepted && Date.parse(receipt.expiresAt) > Date.now(), expiresAt: receipt.expiresAt }
  } catch (error) {
    if (error instanceof GroupCallRingError) throw error
    throw new GroupCallRingError(503, 'The live call could not be confirmed. Other members were not pushed.')
  }
}
