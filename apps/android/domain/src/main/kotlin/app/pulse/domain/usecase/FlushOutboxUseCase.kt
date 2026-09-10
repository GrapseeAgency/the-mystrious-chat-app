package app.pulse.domain.usecase

import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.OutboxDeliveryException
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.OutboxFailureClass
import app.pulse.domain.repository.PulseRepository

/**
 * Offline outbox drain — the native port of web flushPulseOutbox
 * (src/lib/pulse-outbox.ts): FIFO ≤50, deliver in order, STOP at the first
 * failure so later sends never overtake earlier ones.
 *
 * Per delivery:
 *  - success   → the repository swaps the optimistic `local_<clientId>` row
 *                for the real message row and deletes the outbox row;
 *  - 4xx-class → the entry is dropped permanently (row + temp bubble removed,
 *                PulseEvent.OutboxDropped emitted) and the drain stops;
 *  - network   → attempts++ (entry kept) and the drain stops — the next
 *                trigger (reconnect/foreground/worker/self-heal) retries.
 */
class FlushOutboxUseCase(private val repo: PulseRepository) {

    suspend operator fun invoke(): FlushReport {
        val queue = repo.outboxPending().take(MAX_BATCH)
        var sent = 0
        var dropped = 0
        for (entry in queue) {
            val outcome = repo.attemptOutboxSend(entry)
            if (outcome.isSuccess) {
                repo.resolveOutboxDelivery(entry, outcome.getOrThrow())
                sent += 1
                continue
            }
            val error = outcome.exceptionOrNull() ?: continue
            val classification = (error as? OutboxDeliveryException)?.failureClass ?: OutboxFailureClass.RETRY
            if (classification == OutboxFailureClass.DROP) {
                repo.dropOutboxEntry(entry.clientId, error.message ?: "Message rejected")
                dropped += 1
            } else {
                repo.bumpOutboxAttempts(entry.clientId)
            }
            break // stop-at-first-failure — temporal order is the contract
        }
        return FlushReport(sent = sent, dropped = dropped, pending = repo.outboxPending().size)
    }

    companion object {
        /** Web MAX_QUEUE parity — the drain never processes more per pass. */
        const val MAX_BATCH = 50
        /** Entry shape guard shared with the repository enqueue path. */
        fun entryId(entry: OutboxEntry): String = "local_${entry.clientId}"
    }
}
