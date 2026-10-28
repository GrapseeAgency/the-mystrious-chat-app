package app.pulse.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SavedIntentReceiptTest {
    private val intent = SavedIntentDto("owner", "m", "r", true, "operation", expectedRevision = "0")
    private val accepted = SavedStateReceiptDto(2, "owner", "m", true, "2026-10-28T00:00:00.000Z", "1", "operation", "applied", visible = true)
    @Test fun `receipt binds actor identity and verdict while conflict can return different canonical state`() {
        accepted.validateSavedState("owner", "m", intent)
        accepted.copy(saved = false, savedAt = null, outcome = "conflict", visible = false).validateSavedState("owner", "m", intent)
        for (bad in listOf(accepted.copy(userId = "other"), accepted.copy(messageId = "other"),
            accepted.copy(clientId = "other"), accepted.copy(savedReceiptVersion = 1), accepted.copy(revision = ""),
            accepted.copy(savedAt = "bad"), accepted.copy(outcome = "unknown"), accepted.copy(saved = false, savedAt = null))) {
            assertThrows(Exception::class.java) { bad.validateSavedState("owner", "m", intent) }
        }
    }
    @Test fun `journal round trips without changing causal identity`() {
        val json = PulseJson.encodeToString(SavedIntentDto.serializer(), intent)
        assertEquals(intent, PulseJson.decodeFromString(SavedIntentDto.serializer(), json))
        assertEquals("saved-intent:v1|owner|m|operation", intent.key)
    }
    @Test fun `saved revisions compare numerically without floating point or machine integer limits`() {
        assertTrue(compareSavedRevisions("10", "9") > 0)
        assertTrue(compareSavedRevisions("9007199254740993", "9007199254740992") > 0)
        listOf("", "01", "-1", "1\n", "1.0", "v1", "1".repeat(41)).forEach { assertFalse(validSavedRevision(it)) }
    }

    @Test fun `private feed bodies cannot default missing lists or continuation proof to empty success`() {
        val body = """{"feedPageVersion":1,"hasMore":false}"""
        assertThrows(Exception::class.java) { PulseJson.decodeFromString(SearchPageDto.serializer(), body) }
        assertThrows(Exception::class.java) { PulseJson.decodeFromString(MentionsPageDto.serializer(), body) }
        assertThrows(Exception::class.java) { PulseJson.decodeFromString(SavedPageDto.serializer(), body) }
        assertNull(PulseJson.decodeFromString(SearchPageDto.serializer(), """{"messages":[],"feedPageVersion":1}""").hasMore)
        assertNull(PulseJson.decodeFromString(MentionsPageDto.serializer(), """{"items":[],"feedPageVersion":1}""").hasMore)
        assertNull(PulseJson.decodeFromString(SavedPageDto.serializer(), """{"items":[],"feedPageVersion":1}""").hasMore)
    }

}
