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
