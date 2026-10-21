/** One eligibility snapshot for both live banners and remote push. Mute means no ring. */
export async function dispatchGroupCallRing(
  participants: ReadonlyArray<{ userId: string; mutedUntil: Date | null }>,
  callerId: string,
  notify: (recipientIds: string[]) => Promise<void>,
  push: (recipientIds: string[]) => Promise<{ sent: number }>,
  now = new Date(),
): Promise<{ rang: number; pushed: number }> {
  const recipients = [...new Set(participants
    .filter(member => member.userId !== callerId && (!member.mutedUntil || member.mutedUntil <= now))
    .map(member => member.userId))]
  if (recipients.length === 0) return { rang: 0, pushed: 0 }
  await notify([...recipients])
  const report = await push([...recipients])
  return { rang: recipients.length, pushed: report.sent }
}
