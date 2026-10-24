package app.pulse.data.repository

import app.pulse.domain.model.Message
import org.junit.Assert.*
import org.junit.Test

class ForwardMessagePlanTest {
    private val source = Message("source", "old-room", "peer", "Peer", Message.Kind.TEXT, "hello", "2026-10-22T00:00:00Z",
        replyToId = "quote", threadRootId = "root", pinnedAt = "stamp", anon = true, anonAlias = "Alias", topicId = "topic")
    private fun plan(message: Message) = ForwardMessagePlan.create(message, "target", "me", "operation", 1000)

    @Test fun copiedMessageGetsNewIdentityWithoutSourceRoutingOrPrivateMetadata() {
        val result = plan(source).message
        assertEquals("local_operation", result.id); assertEquals("target", result.conversationId); assertEquals("me", result.authorId)
        assertNull(result.replyToId); assertNull(result.threadRootId); assertNull(result.topicId); assertNull(result.pinnedAt)
        assertFalse(result.anon); assertNull(result.anonAlias); assertTrue(result.reactions.isEmpty())
    }
    @Test fun stickerLocationAndStoredAttachmentRemainComplete() {
        for ((kind, payload) in listOf(Message.Kind.STICKER to """{"emoji":"spark","pack":"Signal"}""",
            Message.Kind.LOCATION to """{"lat":23.1,"lng":91.2,"label":"Feni"}""")) {
            val p = plan(source.copy(kind = kind, body = "", payload = payload))
            assertEquals(payload, p.message.payload); assertEquals(kind.name.lowercase(), p.kind)
        }
        val image = plan(source.copy(kind = Message.Kind.IMAGE, imagePath = "stored.jpg"))
        assertEquals("stored.jpg", image.message.imagePath)
        val voice = plan(source.copy(kind = Message.Kind.VOICE, audioPath = "stored.m4a", durationMs = 1300))
        assertEquals("audio", voice.kind); assertEquals(1300L, voice.message.durationMs)
    }
    @Test fun unavailableAndUnsupportedSourcesDoNotDowngradeToText() {
        for (bad in listOf(source.copy(viewOnce = true), source.copy(deletedAt = "stamp"), source.copy(expiresAtEpochMs = 1000),
            source.copy(id = "local_pending"), source.copy(kind = Message.Kind.POLL), source.copy(kind = Message.Kind.SYSTEM),
            source.copy(kind = Message.Kind.STICKER, payload = null), source.copy(kind = Message.Kind.LOCATION, payload = """{"lat":true,"lng":0}"""),
            source.copy(kind = Message.Kind.IMAGE, imagePath = null))) {
            try { plan(bad); fail("Expected an explicit refusal for $bad") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun forwardedIntegrationBecomesAnOrdinaryOwnedMessage() {
        val result = plan(source.copy(viaAutomation = true, payload = """{"webhook":true,"webhookName":"Deploys","effect":"sparkles"}""")).message
        assertFalse(result.viaAutomation)
        assertNull(result.integrationLabel)
        assertEquals("""{"effect":"sparkles"}""", result.payload)
    }
}
