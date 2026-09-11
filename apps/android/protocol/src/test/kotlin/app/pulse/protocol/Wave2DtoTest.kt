package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave-2 wire parity — poll / link preview / saved library / topics /
 * transcribe DTOs decode the REAL gateway shapes (src/lib/serializers.ts
 * mapMessage + the Wave-2 routes), tolerate unknown keys, and degrade to
 * nulls/defaults when optional keys are missing entirely.
 */
class Wave2DtoTest {

    /** Realistic message.poll JSON — matches serializers.ts mapMessage exactly. */
    private val pollMessageJson = """
    {
      "id": "m-poll",
      "conversationId": "c1",
      "senderId": "u1",
      "content": "",
      "kind": "poll",
      "createdAt": "2026-02-20T10:00:00.000Z",
      "poll": {
        "id": "p1",
        "question": "Lunch spot today?",
        "closed": false,
        "options": [
          {"id": "optA", "text": "Ramen", "position": 0, "voteCount": 2,
           "votedBy": ["u2", "u3"]},
          {"id": "optB", "text": "Tacos", "position": 1, "voteCount": 1,
           "votedBy": ["u4"]},
          {"id": "optC", "text": "Pizza", "position": 2, "voteCount": 0,
           "votedBy": []}
        ],
        "totalVotes": 3,
        "myOptionId": "optB",
        "someFutureField": {"nested": [1, 2]}
      },
      "anotherUnknownKey": true
    }
    """.trimIndent()

    @Test
    fun `poll inside ChatMessageDto decodes with votedBy arrays and myOptionId`() {
        val row = PulseJson.decodeFromString(ChatMessageDto.serializer(), pollMessageJson)
        assertEquals("m-poll", row.id)
        assertEquals("poll", row.kind)

        val poll = row.poll?.decodePollDto()
        assertTrue(poll != null, "poll JsonElement must decode into PollDto")
        assertEquals("p1", poll!!.id)
        assertEquals("Lunch spot today?", poll.question)
        assertFalse(poll.closed)
        assertEquals(3, poll.totalVotes)
        // myOptionId parses — but clients derive the pick from votedBy ONLY.
        assertEquals("optB", poll.myOptionId)
        assertEquals(listOf("optA", "optB", "optC"), poll.options.map { it.id })
        assertEquals(listOf("Ramen", "Tacos", "Pizza"), poll.options.map { it.text })
        assertEquals(listOf(0, 1, 2), poll.options.map { it.position })
        assertEquals(listOf(2, 1, 0), poll.options.map { it.voteCount })
        assertEquals(listOf("u2", "u3"), poll.options[0].votedBy)
        assertEquals(listOf("u4"), poll.options[1].votedBy)
        assertEquals(emptyList<String>(), poll.options[2].votedBy)
    }

    @Test
    fun `poll decode is tolerant of garbage and missing shapes`() {
        val row = PulseJson.decodeFromString(
            ChatMessageDto.serializer(),
            """
            {"id":"m2","conversationId":"c1","senderId":"u1","content":"",
             "createdAt":"2026-02-20T10:00:00.000Z",
             "poll":{"question":"id-less tally"},"linkPreview":{"noUrlHere":true}}
            """.trimIndent(),
        )
        // Poll without id → decode fails → null (tolerant).
        assertNull(row.poll?.decodePollDto())
        // LinkPreview without url → decode fails → null (tolerant).
        assertNull(row.linkPreview?.decodeLinkPreviewDto())
        // Absent entirely → null.
        val bare = PulseJson.decodeFromString(
            ChatMessageDto.serializer(),
            """
            {"id":"m3","conversationId":"c1","senderId":"u1","content":"x",
             "createdAt":"2026-02-20T10:00:00.000Z"}
            """.trimIndent(),
        )
        assertNull(bare.poll?.decodePollDto())
        assertNull(bare.linkPreview?.decodeLinkPreviewDto())
    }

    @Test
    fun `link preview decodes from ChatMessageDto`() {
        val row = PulseJson.decodeFromString(
            ChatMessageDto.serializer(),
            """
            {"id":"m4","conversationId":"c1","senderId":"u1",
             "content":"check https://example.com","kind":"text",
             "createdAt":"2026-02-20T10:00:00.000Z",
             "linkUrl":"https://example.com",
             "linkPreview":{"url":"https://example.com","title":"Example Domain",
               "description":"This domain is for use in examples.",
               "imageUrl":"https://example.com/og.png","siteName":"example.com"}}
            """.trimIndent(),
        )
        val preview = row.linkPreview?.decodeLinkPreviewDto()
        assertTrue(preview != null, "linkPreview JsonElement must decode into LinkPreviewDto")
        assertEquals("https://example.com", preview!!.url)
        assertEquals("Example Domain", preview.title)
        assertEquals("This domain is for use in examples.", preview.description)
        assertEquals("https://example.com/og.png", preview.imageUrl)
        assertEquals("example.com", preview.siteName)
        assertEquals("https://example.com", row.linkUrl)
    }

    @Test
    fun `saved page decodes nested conversation and message`() {
        val page = PulseJson.decodeFromString(
            SavedPageDto.serializer(),
            """
            {
              "items": [
                {
                  "savedAt": "2026-02-20T12:00:00.000Z",
                  "conversation": {"id": "c1", "isGroup": true, "name": "Wave Crew"},
                  "message": {"id": "m5", "conversationId": "c1", "senderId": "u2",
                    "content": "the saved one", "kind": "image",
                    "createdAt": "2026-02-20T11:00:00.000Z",
                    "imagePath": "9c1f...e7.jpg",
                    "sender": {"id": "u2", "name": "Bob"}}
                },
                {
                  "savedAt": "2026-02-20T13:00:00.000Z",
                  "conversation": {"id": "c2", "isGroup": false, "name": "Alice"},
                  "message": {"id": "m6", "conversationId": "c2", "senderId": "u3",
                    "content": "plain text", "kind": "text",
                    "createdAt": "2026-02-20T11:30:00.000Z"}
                }
              ],
              "serverSays": "cap 100, no pagination"
            }
            """.trimIndent(),
        )
        assertEquals(2, page.items.size)
        val first = page.items[0]
        assertEquals("2026-02-20T12:00:00.000Z", first.savedAt)
        assertEquals("c1", first.conversation.id)
        assertTrue(first.conversation.isGroup)
        assertEquals("Wave Crew", first.conversation.name)
        assertEquals("m5", first.message.id)
        assertEquals("9c1f...e7.jpg", first.message.imagePath)
        assertEquals("Bob", first.message.sender?.name)
        val second = page.items[1]
        assertFalse(second.conversation.isGroup)
        assertEquals("Alice", second.conversation.name)
        // Tolerant: {} → empty items.
        assertEquals(emptyList<SavedItemDto>(), PulseJson.decodeFromString(SavedPageDto.serializer(), "{}").items)
    }

    @Test
    fun `topics page and topic envelope decode tolerantly`() {
        val page = PulseJson.decodeFromString(
            TopicsPageDto.serializer(),
            """
            {
              "topics": [
                {"id": "t1", "name": "Design", "emoji": "🎨",
                 "lastMessageAt": "2026-02-20T14:00:00.000Z", "messageCount": 7},
                {"id": "t2", "name": "Launch", "messageCount": 2}
              ],
              "unknown": [1, 2, 3]
            }
            """.trimIndent(),
        )
        assertEquals(listOf("t1", "t2"), page.topics.map { it.id })
        assertEquals("Design", page.topics[0].name)
        assertEquals("🎨", page.topics[0].emoji)
        assertEquals("2026-02-20T14:00:00.000Z", page.topics[0].lastMessageAt)
        assertEquals(7, page.topics[0].messageCount)
        // Missing emoji/lastMessageAt decode to the defaults.
        assertEquals("Launch", page.topics[1].name)
        assertEquals("💬", page.topics[1].emoji)
        assertNull(page.topics[1].lastMessageAt)
        assertEquals(2, page.topics[1].messageCount)
        assertEquals(emptyList<TopicDto>(), PulseJson.decodeFromString(TopicsPageDto.serializer(), "{}").topics)

        // POST /topics → { topic } envelope (200 existing / 201 new).
        val env = PulseJson.decodeFromString(
            TopicEnvelopeDto.serializer(),
            """{"topic": {"id": "t9", "name": "Design", "emoji": "🎨", "messageCount": 0}}""",
        )
        assertEquals("t9", env.topic?.id)
        assertNull(PulseJson.decodeFromString(TopicEnvelopeDto.serializer(), "{}").topic)
    }

    @Test
    fun `transcribe result and ok dto decode`() {
        val fresh = PulseJson.decodeFromString(
            TranscribeResultDto.serializer(),
            """{"transcript":"hey team, shipping the demo","transcribedAt":"2026-02-20T15:00:00.000Z","cached":false}""",
        )
        assertEquals("hey team, shipping the demo", fresh.transcript)
        assertEquals("2026-02-20T15:00:00.000Z", fresh.transcribedAt)
        assertFalse(fresh.cached)

        val cacheHit = PulseJson.decodeFromString(
            TranscribeResultDto.serializer(),
            """{"transcript":"same text","cached":true,"futureField":7}""",
        )
        assertEquals("same text", cacheHit.transcript)
        assertTrue(cacheHit.cached)
        assertNull(cacheHit.transcribedAt)

        val ok = PulseJson.decodeFromString(OkDto.serializer(), """{"ok":true}""")
        assertTrue(ok.ok)
        assertFalse(PulseJson.decodeFromString(OkDto.serializer(), "{}").ok)
    }
}
