package app.pulse.domain.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageOriginTest {
    @Test fun `new machine messages have origin labels but legacy metadata is not trusted`() {
        val hook = """{"webhook":true,"webhookName":"Build bot"}"""
        assertEquals("Webhook · Build bot", MessageOrigin.label(hook, true))
        assertEquals("Unverified integration", MessageOrigin.label(hook, false))
        assertEquals("Bot", MessageOrigin.label("""{"bot":true}""", true))
        assertEquals("Automation", MessageOrigin.label(null, true))
        assertNull(MessageOrigin.label(null, false))
        assertNull(MessageOrigin.label(hook, true, deleted = true))
        assertNull(MessageOrigin.label("""{"bot":"true"}""", false))
        assertNull(MessageOrigin.label("""{"webhook":1}""", false))
        assertEquals("Automation", MessageOrigin.label("not-json", true))
    }
    @Test fun `display names cannot inject bidi controls or unbounded captions`() {
        assertEquals("Webhook · Builds", MessageOrigin.label("""{"webhook":true,"webhookName":"\u202eBuilds\n"}""", true))
        assertEquals("Webhook · " + "a".repeat(40), MessageOrigin.label("""{"webhook":true,"webhookName":"${"a".repeat(70)}"}""", true))
    }
    @Test fun `forward removes integration markers but preserves other content exactly when unchanged`() {
        assertNull(MessageOrigin.forwardPayload("""{"bot":true,"webhook":true,"webhookName":"Bot","webhookColor":"amber"}"""))
        assertEquals("""{"effect":"sparkles"}""", MessageOrigin.forwardPayload("""{"bot":true,"effect":"sparkles"}"""))
        val payload = """{ "lat":23.1, "lng":91.2 }"""
        assertEquals(payload, MessageOrigin.forwardPayload(payload))
        assertThrows(IllegalArgumentException::class.java) { MessageOrigin.forwardPayload("[]") }
    }
}
