package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseEvent
import app.pulse.protocol.SocketEvents

/** All envelopes refresh storage; only message:new is an arrival. */
fun messageEnvelopeEvent(event: String, roomId: String, message: Message): PulseEvent? {
    if (message.conversationId != roomId) return null
    return when (event) {
        SocketEvents.MESSAGE_NEW -> PulseEvent.MessageReceived(roomId, message)
        SocketEvents.MESSAGE_DELETED -> PulseEvent.MessageDeleted(roomId, message.id)
        in SocketEvents.MESSAGE_ENVELOPE_EVENTS -> PulseEvent.MessageUpdated(roomId, message)
        else -> null
    }
}
