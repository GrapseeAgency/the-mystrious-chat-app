package app.pulse.feature.chat

import org.junit.Assert.*
import org.junit.Test

class AttachmentSelectionTest {
    @Test fun `selections of the same URI are distinct but an upload update preserves ownership`() {
        val first = StagedMedia(StagedMedia.Kind.IMAGE, "content://same", null, null)
        val second = StagedMedia(StagedMedia.Kind.IMAGE, "content://same", null, null)
        assertNotEquals(first.selectionId, second.selectionId)
        assertEquals(first.selectionId, first.copy(uploadedPath = "uploaded.jpg", uploading = false).selectionId)
        assertEquals(first.selectionId, first.copy(viewOnce = true, sending = true).selectionId)
    }
    @Test fun `voice retry metadata preserves its chosen topic and duration`() {
        val voice = StagedMedia(StagedMedia.Kind.AUDIO, "/voice.m4a", "Voice note.m4a", 200L,
            durationMs = 1700L, intendedTopicId = "original-topic")
        val retry = voice.copy(uploading = true, error = null)
        assertEquals(1700L, retry.durationMs)
        assertEquals("original-topic", retry.intendedTopicId)
        assertEquals(voice.selectionId, retry.selectionId)
    }
}
