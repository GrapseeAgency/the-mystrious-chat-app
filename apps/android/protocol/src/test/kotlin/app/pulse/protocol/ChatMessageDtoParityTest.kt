package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave-1 wire parity — ChatMessageDto decodes the FULL messaging surface
 * (threads + media + lifecycle) from real gateway JSON, tolerates unknown
 * keys, and degrades to nulls when optional keys are missing entirely.
 * Shapes mirror /api/conversations/[id]/messages + /api/messages/[id]/thread.
 */
class ChatMessageDtoParityTest {

    @Test
    fun `full media + thread + lifecycle row decodes`() {
        val json = """
        {
          "id": "m2",
          "conversationId": "c1",
          "senderId": "u1",
          "content": "check this",
          "kind": "image",
          "createdAt": "2026-02-14T10:00:00.000Z",
          "parentId": "m0",
          "replyTo": {
            "id": "m1", "conversationId": "c1", "senderId": "u2",
            "content": "the quoted", "createdAt": "2026-02-14T09:59:00.000Z"
          },
          "imagePath": "9c1f...e7.jpg",
          "audioPath": null,
          "durationMs": 42000,
          "filePath": "/api/uploads/report.pdf",
          "fileName": "report.pdf",
          "fileSize": 123456,
          "editedAt": "2026-02-14T10:05:00.000Z",
          "deletedAt": null,
          "pinnedAt": "2026-02-14T10:06:00.000Z",
          "pinnedBy": "u9",
          "viewOnce": true,
          "futureField": {"nested": [1, 2]}
        }
        """.trimIndent()

        val row = PulseJson.decodeFromString(ChatMessageDto.serializer(), json)
        assertEquals("m2", row.id)
        // thread axis
        assertEquals("m0", row.parentId)
        // quote axis — SEPARATE from parentId, never merged
        assertEquals("m1", row.replyTo?.id)
        // media
        assertEquals("9c1f...e7.jpg", row.imagePath)
        assertNull(row.audioPath)
        assertEquals(42000L, row.durationMs)
        assertEquals("/api/uploads/report.pdf", row.filePath)
        assertEquals("report.pdf", row.fileName)
        assertEquals(123456L, row.fileSize)
        // lifecycle
        assertEquals("2026-02-14T10:05:00.000Z", row.editedAt)
        assertNull(row.deletedAt)
        assertEquals("2026-02-14T10:06:00.000Z", row.pinnedAt)
        assertEquals("u9", row.pinnedBy)
        assertEquals(true, row.viewOnce)
    }

    @Test
    fun `missing keys decode to nulls (tolerant decode)`() {
        val row = PulseJson.decodeFromString(
            ChatMessageDto.serializer(),
            """
            {"id":"m3","conversationId":"c1","senderId":"u1","content":"plain",
             "createdAt":"2026-02-14T10:00:00.000Z"}
            """.trimIndent(),
        )
        assertNull(row.parentId)
        assertNull(row.replyTo)
        assertNull(row.imagePath)
        assertNull(row.audioPath)
        assertNull(row.durationMs)
        assertNull(row.filePath)
        assertNull(row.fileName)
        assertNull(row.fileSize)
        assertNull(row.editedAt)
        assertNull(row.deletedAt)
        assertNull(row.pinnedAt)
        assertNull(row.viewOnce)
        assertEquals("text", row.kind)
    }

    @Test
    fun `thread page decodes parent and ascending replies`() {
        val json = """
        {
          "parent": {"id":"root","conversationId":"c1","senderId":"u1","content":"root msg",
                     "createdAt":"2026-02-14T10:00:00.000Z"},
          "replies": [
            {"id":"r1","conversationId":"c1","senderId":"u2","content":"first reply",
             "parentId":"root","createdAt":"2026-02-14T10:01:00.000Z"},
            {"id":"r2","conversationId":"c1","senderId":"u3","content":"second reply",
             "parentId":"root","createdAt":"2026-02-14T10:02:00.000Z"}
          ],
          "unknownKey": true
        }
        """.trimIndent()

        val page = PulseJson.decodeFromString(ThreadPageDto.serializer(), json)
        assertEquals("root", page.parent?.id)
        assertEquals(listOf("r1", "r2"), page.replies.map { it.id })
        assertTrue(page.replies.all { it.parentId == "root" })
        // asc order preserved from the wire
        assertTrue(page.replies[0].createdAt < page.replies[1].createdAt)
    }

    @Test
    fun `thread page tolerates missing parent and replies`() {
        val page = PulseJson.decodeFromString(ThreadPageDto.serializer(), "{}")
        assertNull(page.parent)
        assertEquals(emptyList<ChatMessageDto>(), page.replies)
    }

    @Test
    fun `pinned page and saved toggle decode`() {
        val pinned = PulseJson.decodeFromString(
            PinnedPageDto.serializer(),
            """
            {"messages":[
              {"id":"p1","conversationId":"c1","senderId":"u1","content":"pinned",
               "createdAt":"2026-02-14T10:00:00.000Z","pinnedAt":"2026-02-14T10:06:00.000Z"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, pinned.messages.size)
        assertEquals("2026-02-14T10:06:00.000Z", pinned.messages.first().pinnedAt)

        assertEquals(emptyList<ChatMessageDto>(), PulseJson.decodeFromString(PinnedPageDto.serializer(), "{}").messages)

        val savedOn = PulseJson.decodeFromString(SavedToggleDto.serializer(), """{"saved":true}""")
        assertTrue(savedOn.saved)
        val savedOff = PulseJson.decodeFromString(SavedToggleDto.serializer(), "{}")
        assertFalse(savedOff.saved)
    }

    @Test
    fun `upload result decodes filePath and tolerates missing alias`() {
        val up = PulseJson.decodeFromString(
            UploadResultDto.serializer(),
            """{"filePath":"a1b2.jpg","imagePath":"a1b2.jpg","extra":1}""",
        )
        assertEquals("a1b2.jpg", up.filePath)
        assertEquals("a1b2.jpg", up.imagePath)

        val bare = PulseJson.decodeFromString(UploadResultDto.serializer(), """{"filePath":"a1b2.jpg"}""")
        assertEquals("a1b2.jpg", bare.filePath)
        assertNull(bare.imagePath)
    }

    @Test
    fun `conversation member decodes read watermark and tolerates its absence`() {
        val member = PulseJson.decodeFromString(
            ConversationMemberDto.serializer(),
            """
            {"id":"u1","name":"Alice","color":"emerald",
             "lastReadAt":"2026-02-14T10:01:00.000Z","role":"admin"}
            """.trimIndent(),
        )
        assertEquals("u1", member.id)
        assertEquals("2026-02-14T10:01:00.000Z", member.lastReadAt)
        assertEquals("admin", member.role)

        val bare = PulseJson.decodeFromString(
            ConversationMemberDto.serializer(),
            """{"id":"u2","name":"Bob"}""",
        )
        assertNull(bare.lastReadAt)
        assertNull(bare.role)
    }
}
