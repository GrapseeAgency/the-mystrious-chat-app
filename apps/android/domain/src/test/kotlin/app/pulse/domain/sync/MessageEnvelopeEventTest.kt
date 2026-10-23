package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseEvent
import app.pulse.protocol.SocketEvents
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageEnvelopeEventTest {
    private val row = Message("id", "room", "peer", "Peer", Message.Kind.TEXT, "hello", "2026-10-24T00:00:00Z")
    @Test fun `only a new-message envelope reaches arrival subscribers`() {
        for (event in SocketEvents.MESSAGE_ENVELOPE_EVENTS) {
            val mapped = messageEnvelopeEvent(event, "room", row)
            when (event) {
                SocketEvents.MESSAGE_NEW -> assertTrue(mapped is PulseEvent.MessageReceived)
                SocketEvents.MESSAGE_DELETED -> assertTrue(mapped is PulseEvent.MessageDeleted)
                else -> assertTrue(mapped is PulseEvent.MessageUpdated, event)
            }
        }
    }
    @Test fun `foreign-room and unknown events cannot become arrivals`() {
        assertNull(messageEnvelopeEvent(SocketEvents.MESSAGE_NEW, "foreign", row))
        assertNull(messageEnvelopeEvent("unknown", "room", row))
    }
}
