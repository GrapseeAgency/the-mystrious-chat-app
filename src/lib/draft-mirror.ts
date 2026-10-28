/** Browser compatibility adapter. No fresh GET may silently rebase local edits
 * onto text the composer never observed. Native uses its durable composer journal. */
export class DraftMirror {
  private revision?: string
  private observed?: string
  private seed?: Promise<void>
  private busy = false
  private blocked = false
  private generation = 0
  private desired?: { text: string; previous: string; generation: number }
  private flight?: { text: string; clientId: string; expectedRevision: string; generation: number }
  constructor(private readonly owner: string, private readonly room: string,
    private readonly current: () => boolean, private readonly request: typeof fetch,
    private readonly identity: () => string, private readonly notice: (message: string) => void) {}
  private get url() { return `/api/conversations/${encodeURIComponent(this.room)}/draft` }
  private failConflict(): never {
    this.blocked = true
    throw new Error('Draft sync paused because another device changed it. Your local text is kept; compare the drafts before replacing either copy.')
  }
  async prime(observed: string): Promise<void> {
    if (!this.current() || this.revision || this.blocked) return
    this.observed ??= observed
    if (!this.seed) this.seed = (async () => {
      const response = await this.request(`${this.url}?userId=${encodeURIComponent(this.owner)}`, { cache: 'no-store' })
      if (!response.ok) throw new Error('Draft sync could not read its revision. Your local text is kept.')
      const value = await response.json()
      if (!this.current()) return
      if (value.userId !== this.owner || value.conversationId !== this.room || typeof value.revision !== 'string' ||
          (value.draft !== null && typeof value.draft !== 'string')) throw new Error('Draft revision was not confirmed.')
      if ((value.draft ?? '') !== this.observed) this.failConflict()
      this.revision = value.revision
    })()
    try { await this.seed } finally { this.seed = undefined }
  }
  async submit(text: string, previous: string): Promise<void> {
    this.desired = { text, previous, generation: ++this.generation }
    if (!this.current()) return
    if (this.blocked) { this.notice('Draft sync is paused after a conflict. Your local text is kept.'); return }
    if (this.busy) return
    this.busy = true
    try {
      while (this.desired && this.current() && !this.blocked) {
        if (!this.flight) {
          const desired = this.desired
          await this.prime(desired.previous)
          if (!this.current() || !this.revision) return
          // Changes while the GET was pending may replace desired text; they
          // still descend from the same observed baseline, not the fetched body.
          this.flight = { text: this.desired.text, clientId: this.identity(), expectedRevision: this.revision, generation: this.desired.generation }
        }
        const flight = this.flight
        const response = await this.request(this.url, { method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: this.owner, draft: flight.text, clientId: flight.clientId, expectedRevision: flight.expectedRevision }), keepalive: true })
        const receipt = await response.json()
        if (!this.current()) return
        if (response.status === 409 && ['conflict', 'superseded'].includes(receipt.outcome)) this.failConflict()
        if (!response.ok || receipt.ok !== true || receipt.outcome !== 'applied' || receipt.userId !== this.owner ||
            receipt.conversationId !== this.room || receipt.clientId !== flight.clientId || receipt.draft !== flight.text || typeof receipt.revision !== 'string') {
          throw new Error('Draft sync was not confirmed. Your local text is kept; the same operation will be retried on the next edit.')
        }
        this.revision = receipt.revision
        this.flight = undefined
        if (this.desired.generation === flight.generation) this.desired = undefined
      }
    } catch (error) { if (this.current()) this.notice(error instanceof Error ? error.message : 'Draft sync needs attention; local text is kept.') }
    finally { this.busy = false }
  }
}
