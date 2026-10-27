package app.pulse.core.result

/** Server-bound retirement, not a successful read and not inferred from status. */
data class ReadRetirement(val version: Int, val userId: String, val conversationId: String,
    val messageId: String, val readIntent: String) {
    fun matches(owner: String, room: String, message: String, intent: String): Boolean =
        version == 1 && owner.isNotBlank() && room.isNotBlank() && message.isNotBlank() && intent.isNotBlank() &&
            userId == owner && conversationId == room && messageId == message && readIntent == intent
}

/**
 * Railway-oriented error envelope shared by every Pulse layer.
 * `Failure.kind` maps 1:1 to the web API's error semantics (403 blocked pair,
 * 429 slow-mode, 404 gone, etc.) so native surfaces can show identical copy.
 */
sealed interface PulseResult<out T> {
    data class Success<T>(val value: T) : PulseResult<T>
    data class Failure(
        val kind: Kind,
        val message: String? = null,
        /** Machine code from the wire body (e.g. "username_taken") - null when absent. */
        val code: String? = null,
        /** Server-suggested alternative (username_taken flow). */
        val suggestion: String? = null,
        /** Raw HTTP status (409 name-clash vs username-taken branches need it). */
        val status: Int? = null,
        /**
         * Slow-mode window (R44/F-MS-20): seconds the server asked us to wait
         * on a 429 - parsed from the body's `retryAfter` (web ApiError.retryAfter
         * parity, src/lib/pulse-utils.ts). null = not a 429 / header absent.
         */
        val retryAfter: Int? = null,
        /** Echoed operation ID on a terminal receipt, never inferred from HTTP status alone. */
        val receiptClientId: String? = null,
        val readRetirement: ReadRetirement? = null,
    ) : PulseResult<Nothing> {
        fun retiresRead(owner: String, room: String, message: String, intent: String): Boolean =
            status in setOf(403, 404, 410) && readRetirement?.matches(owner, room, message, intent) == true
        enum class Kind { NETWORK, AUTH, FORBIDDEN, RATE_LIMITED, NOT_FOUND, VALIDATION, SERVER, UNKNOWN }
    }

    companion object {
        /** Map HTTP status → Failure kind (wire parity with the web client). */
        fun fromHttp(status: Int, message: String? = null): Failure = Failure(
            when (status) {
                401 -> Failure.Kind.AUTH
                403 -> Failure.Kind.FORBIDDEN
                404 -> Failure.Kind.NOT_FOUND
                422 -> Failure.Kind.VALIDATION
                429 -> Failure.Kind.RATE_LIMITED
                in 500..599 -> Failure.Kind.SERVER
                else -> Failure.Kind.UNKNOWN
            },
            message,
        )
    }
}

inline fun <T, R> PulseResult<T>.map(transform: (T) -> R): PulseResult<R> = when (this) {
    is PulseResult.Success -> PulseResult.Success(transform(value))
    is PulseResult.Failure -> this
}
