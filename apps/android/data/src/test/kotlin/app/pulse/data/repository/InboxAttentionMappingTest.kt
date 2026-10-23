package app.pulse.data.repository

import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationSummaryDto
import app.pulse.protocol.SenderDto
import org.junit.Assert.*
import org.junit.Test

class InboxAttentionMappingTest {
    private val last = ChatMessageDto("message", "room", "me", "private caption", createdAt = "2026-10-23T00:00:00Z",
        sender = SenderDto("me", "Real Name"), anon = true, anonAlias = "Quiet Raven", viewOnce = true)
    @Test fun anonymousAndSingleUseInboxProjectionIsPrivate() {
        val room = ConversationSummaryDto("room", isGroup = true, lastMessage = last).toDomain("peer")
        assertEquals("Quiet Raven", room.lastMessageAuthorName)
        assertEquals("View-once message", room.lastMessagePreview)
    }
    @Test fun representedMachineIsNotAnOutgoingInboxPreview() {
        val room = ConversationSummaryDto("room", isGroup = true,
            lastMessage = last.copy(anon = false, viaAutomation = true)).toDomain("me")
        assertFalse(room.lastMessageMine)
        assertEquals("Automation", room.lastMessageAuthorName)
    }
}
