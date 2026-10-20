package app.pulse.feature.calls

import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallState

/** Permission is consent for the ringing call the user tapped, not the next caller. */
internal fun canApplyCallPermission(requestedCallId: String?, current: CallSnapshot): Boolean =
    requestedCallId != null && requestedCallId == current.callId && current.state == CallState.INCOMING_RINGING
