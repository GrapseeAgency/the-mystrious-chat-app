package app.pulse.core.result

/**
 * Railway-oriented error envelope shared by every Pulse layer.
 * `Failure.kind` maps 1:1 to the web API's error semantics (403 blocked pair,
 * 429 slow-mode, 404 gone, etc.) so native surfaces can show identical copy.
 */
sealed interface PulseResult<out T> {
    data class Success<T>(val value: T) : PulseResult<T>
    data class Failure(val kind: Kind, val message: String? = null) : PulseResult<Nothing> {
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
