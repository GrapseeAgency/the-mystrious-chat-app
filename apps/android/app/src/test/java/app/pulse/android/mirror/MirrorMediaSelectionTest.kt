package app.pulse.android.mirror

import app.pulse.domain.model.AttachmentDraft
import org.junit.Assert.*
import org.junit.Test

class MirrorMediaSelectionTest {
    @Test fun retryKeepsTheOriginalOperationAndMetadataInsteadOfReplacingIt() {
        val selection = MirrorMediaSelection(AttachmentDraft("selection-id-0001", "room", "image", "bytes",
            topicId = "original-topic", replyToId = "original-reply", anon = true), "original-session", 7L)
            .withViewOnce(true)
        val frozen = selection.freeze(" first caption ")
        assertEquals("first caption", frozen.submitted?.caption)
        assertEquals("selection-id-0001", frozen.submitted?.selectionId)
        assertEquals("original-topic", frozen.submitted?.topicId)
        assertEquals("original-reply", frozen.submitted?.replyToId)
        assertEquals(true, frozen.submitted?.anon)
        assertEquals(true, frozen.submitted?.viewOnce)
        assertEquals("original-session", frozen.sessionKey)
        assertEquals(7L, frozen.draftRevision)
        assertSame(frozen, frozen.freeze("changed after uncertain result"))
        assertSame(frozen, frozen.withViewOnce(false))
        assertNull(selection.submitted)
    }

    @Test fun documentReviewKeepsItsCaptionRevisionAndCannotTurnOnViewOnce() {
        val selection = MirrorMediaSelection(AttachmentDraft("selection-id-0002", "room", "file", "bytes",
            fileName = "notes.txt", fileSize = 5, caption = "draft at picker launch"), "session", 12L)
        assertSame(selection, selection.withViewOnce(true))
        val frozen = selection.freeze("edited in review")
        assertEquals("edited in review", frozen.submitted?.caption)
        assertEquals("notes.txt", frozen.submitted?.fileName)
        assertEquals(5L, frozen.submitted?.fileSize)
        assertEquals(12L, frozen.draftRevision)
    }
}

    // ── Section 14 item 2 - external-intent return ownership ────────

    @Test fun aPickerResultReturningIntoItsOwnSessionAndLiveRoomIsAccepted() {
        val selection = MirrorMediaSelection(AttachmentDraft("selection-id-0003", "room", "image", "bytes"), "session-1")
        assertTrue(MirrorMediaSelection.stillOwned(selection, "session-1", live = true, currentSessionKey = "session-1"))
    }

    @Test fun aPickerResultReturningAfterAnAccountSwitchIsDropped() {
        val selection = MirrorMediaSelection(AttachmentDraft("selection-id-0004", "room", "file", "bytes", fileName = "a.pdf", fileSize = 1), "session-1")
        assertFalse(MirrorMediaSelection.stillOwned(selection, "session-1", live = true, currentSessionKey = "session-2"))
        assertFalse(MirrorMediaSelection.stillOwned(selection, null, live = true, currentSessionKey = null))
    }

    @Test fun aPickerResultReturningAfterItsRoomClosedIsDropped() {
        val selection = MirrorMediaSelection(AttachmentDraft("selection-id-0005", "room", "image", "bytes"), "session-1")
        assertFalse(MirrorMediaSelection.stillOwned(selection, "session-1", live = false, currentSessionKey = "session-1"))
        assertFalse(MirrorMediaSelection.stillOwned(null, "session-1", live = true, currentSessionKey = "session-1"))
    }
