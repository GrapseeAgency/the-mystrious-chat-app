package app.pulse.domain.model

import kotlinx.serialization.Serializable

/**
 * One held-for-later outgoing message — the framework-free mirror of the
 * wire outbox (web src/lib/pulse-outbox.ts QueuedMessage). `clientId` is
 * the stable id that links the optimistic `local_<clientId>` message row,
 * the Room outbox row and the flush bookkeeping.
 */
@Serializable
data class OutboxEntry(
    val id: Long = 0,
    val conversationId: String,
    val clientId: String,
    val content: String,
    val kind: String = "text",
    /** ISO-8601 wire timestamp — display time of the queued bubble. */
    val createdAt: String,
    /** failed POST count (diagnostics; incremented on retryable failures). */
    val attempts: Int = 0,
)

/** Optimistic queued-bubble id convention: `local_<clientId>` (web `temp-` parity). */
const val TEMP_MESSAGE_PREFIX = "local_"

/** Outcome summary of one outbox drain pass (web flushPulseOutbox return). */
data class FlushReport(
    /** entries delivered to the gateway this pass. */
    val sent: Int = 0,
    /** entries permanently dropped this pass (4xx verdicts). */
    val dropped: Int = 0,
    /** entries still held after the pass (network-failure retries). */
    val pending: Int = 0,
)

/** How the outbox drain policy treats one failed send attempt. */
enum class OutboxFailureClass {
    /** Definitive server verdict (validation/forbidden/not-found/auth) — the entry will never deliver. */
    DROP,

    /** Network-class / server hiccup — keep the entry and retry later. */
    RETRY,
}

/** Failure wrapper that carries the outbox classification alongside the reason. */
class OutboxDeliveryException(
    val failureClass: OutboxFailureClass,
    reason: String,
) : Exception(reason)
