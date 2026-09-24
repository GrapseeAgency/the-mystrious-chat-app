package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R6 — M5 wire parity: the conversation-detail `dmBlocked` flag (R47, web
 * src/lib/serializers.ts buildConversationDetail — a UserBlock in EITHER
 * direction between the DM pair) decodes on the detail row, defaults to null
 * on summary-shaped rows (the repository mapping resolves null → false), and
 * tolerates unknown keys like every other wire DTO.
 */
class R6DmBlockedWireTest {

    /** Minimal detail row — the required id/isGroup plus the members array. */
    private fun rowJson(dmBlockedLine: String) = """
        {
          "id": "c-dm",
          "isGroup": false,
          "name": "Alice",
          "members": [{"id": "u1", "name": "Alice"}, {"id": "u2", "name": "Bob"}],
          $dmBlockedLine
          "someFutureField": {"nested": [1, 2]}
        }
    """.trimIndent()

    @Test
    fun `dmBlocked true decodes from the detail row`() {
        val row = PulseJson.decodeFromString(
            ConversationSummaryDto.serializer(),
            rowJson(""" "dmBlocked": true, """),
        )
        assertEquals(true, row.dmBlocked)
    }

    @Test
    fun `dmBlocked false decodes from the detail row`() {
        val row = PulseJson.decodeFromString(
            ConversationSummaryDto.serializer(),
            rowJson(""" "dmBlocked": false, """),
        )
        assertEquals(false, row.dmBlocked)
    }

    @Test
    fun `dmBlocked absent tolerates the summary shape and stays null`() {
        // An empty line leaves a stray ", ," — valid only if we drop it, so
        // build the absent variant without the dangling comma instead.
        val json = """
            {
              "id": "c-dm",
              "isGroup": false,
              "name": "Alice",
              "members": [{"id": "u1", "name": "Alice"}, {"id": "u2", "name": "Bob"}],
              "someFutureField": {"nested": [1, 2]}
            }
        """.trimIndent()
        val row = PulseJson.decodeFromString(ConversationSummaryDto.serializer(), json)
        assertNull(row.dmBlocked)
        // The repository's `dmBlocked == true` resolution lands on false.
        assertTrue(!(row.dmBlocked == true))
    }
}
