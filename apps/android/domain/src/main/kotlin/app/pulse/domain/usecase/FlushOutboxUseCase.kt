package app.pulse.domain.usecase

import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.OutboxDeliveryException
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.OutboxFailureClass
import app.pulse.domain.repository.PulseRepository

/** Single writer: FIFO within each room, round-robin across eligible rooms.
 * Unknown network/auth/gateway failures still stop the pass. Rejected intents
 * remain recoverable but no longer hold up later operations.
 */
class FlushOutboxUseCase(private val repo: PulseRepository) {

    suspend operator fun invoke(): FlushReport {
        val heldRooms = mutableSetOf<String>()
        val attempted = mutableSetOf<String>()
        val servedRooms = mutableSetOf<String>()
        var sent = 0
        var dropped = 0
        while (attempted.size < MAX_BATCH) {
            var entry = repo.outboxReady(heldRooms + servedRooms).firstOrNull { it.clientId !in attempted }
            if (entry == null && servedRooms.isNotEmpty()) {
                servedRooms.clear()
                entry = repo.outboxReady(heldRooms).firstOrNull { it.clientId !in attempted }
            }
            entry ?: break
            servedRooms += entry.conversationId
            attempted += entry.clientId
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
                continue
            } else {
                repo.bumpOutboxAttempts(entry.clientId)
            }
            if (classification == OutboxFailureClass.ROOM_RETRY) {
                heldRooms += entry.conversationId
                continue
            }
            break // Unknown/global failures cannot safely be bypassed.
        }
        return FlushReport(sent = sent, dropped = dropped, pending = repo.pendingOutboxCount())
    }

    companion object {
        /** Web MAX_QUEUE parity - the drain never processes more per pass. */
        const val MAX_BATCH = 50
        /** Entry shape guard shared with the repository enqueue path. */
        fun entryId(entry: OutboxEntry): String = "local_${entry.clientId}"
    }
}
