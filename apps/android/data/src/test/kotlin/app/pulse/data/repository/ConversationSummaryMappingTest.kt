package app.pulse.data.repository

import app.pulse.data.local.ConversationEntity
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.UnreadExpiration
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.PulseJson
import org.junit.Assert.*
import org.junit.Test

class ConversationSummaryMappingTest {
    @Test fun detailOmissionsCannotAcknowledgeUnreadOrErasePreviewAndPinnedState() {
        val previous = Conversation("room", Conversation.Kind.GROUP, "Old name",
            lastMessagePreview = "keep until deadline", lastMessageExpiresAtEpochMs = 9000,
            unreadCount = 3, unreadExpirations = listOf(UnreadExpiration(9000, 2)), isPinned = true, isArchived = true)
        val detail = PulseJson.decodeFromString(ConversationSummaryDto.serializer(),
            """{"id":"room","isGroup":true,"name":"Renamed","ttlSeconds":60,"myManualUnread":true}""")
        val merged = detail.toDomain("me", previous)
        assertEquals("Renamed", merged.title)
        assertEquals(previous.lastMessagePreview, merged.lastMessagePreview)
        assertEquals(3, merged.unreadCount)
        assertEquals(previous.unreadExpirations, merged.unreadExpirations)
        assertEquals(previous.lastMessageExpiresAtEpochMs, merged.lastMessageExpiresAtEpochMs)
        assertTrue(merged.isPinned && merged.isArchived && merged.myManualUnread)
        val emptySummary = detail.copy(unreadCount = 0, lastMessage = null).toDomain("me", previous)
        assertEquals(0, emptySummary.unreadCount)
        assertNull(emptySummary.lastMessagePreview)
    }

    @Test fun wireDeadlinesSurviveRoomConversionWithoutNeedingMessageHistory() {
        val summary = PulseJson.decodeFromString(ConversationSummaryDto.serializer(), """{
          "id":"room","unreadCount":2,"unreadExpirations":[{"expiresAt":"2033-05-18T03:33:20Z","count":1}],
          "lastMessage":{"id":"last","conversationId":"room","senderId":"peer","content":"private","kind":"text","createdAt":"2033-05-18T03:30:00Z","expiresAt":"2033-05-18T03:33:20Z"}
        }""")
        val mapped = summary.toDomain("me")
        assertEquals(2_000_000_000_000L, mapped.lastMessageExpiresAtEpochMs)
        val restored = ConversationEntity.from(mapped).toDomain()
        assertEquals(mapped.unreadExpirations, restored.unreadExpirations)
        assertEquals(mapped.lastMessageExpiresAtEpochMs, restored.lastMessageExpiresAtEpochMs)
        assertEquals(2, restored.unreadCount)
    }
}
