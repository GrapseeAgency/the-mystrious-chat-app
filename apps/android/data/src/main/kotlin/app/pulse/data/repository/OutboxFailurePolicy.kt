package app.pulse.data.repository

import app.pulse.core.result.PulseResult
import app.pulse.domain.model.OutboxFailureClass

/** HTTP 400/409/410 are UNKNOWN in the shared transport, but not retryable sends. */
internal fun PulseResult.Failure.outboxFailureClass(): OutboxFailureClass =
    if (status in setOf(400, 403, 404, 409, 410, 413, 415, 422) ||
        kind in setOf(PulseResult.Failure.Kind.VALIDATION, PulseResult.Failure.Kind.FORBIDDEN, PulseResult.Failure.Kind.NOT_FOUND)) {
        OutboxFailureClass.DROP // retained as rejected for explicit user recovery, not deleted
    } else OutboxFailureClass.RETRY
