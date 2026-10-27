package app.pulse.feature.calls

import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallState

/** Permission is consent for the ringing call the user tapped, not the next caller. */
internal fun canApplyCallPermission(requestedCallId: String?, current: CallSnapshot): Boolean =
    requestedCallId != null && requestedCallId == current.callId && current.state == CallState.INCOMING_RINGING

/** Main-thread permission slot: one user's gesture cannot become another session's call. */
internal class OutgoingCallPermissionGate<T> {
    private data class Request<T>(val owner: String, val target: T)
    private var pending: Request<T>? = null

    fun begin(owner: String?, target: T): Boolean {
        if (owner.isNullOrBlank() || pending != null) return false
        pending = Request(owner, target)
        return true
    }

    fun complete(granted: Boolean, owner: String?): T? {
        val request = pending
        pending = null
        return request?.takeIf { granted && it.owner == owner }?.target
    }

    fun cancel() { pending = null }
}
