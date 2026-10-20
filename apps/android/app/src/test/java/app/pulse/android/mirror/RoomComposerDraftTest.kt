package app.pulse.android.mirror

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class RoomComposerDraftTest {
    @Test fun lateAcknowledgementCannotClearNextMessage() {
        val draft = RoomComposerDraft()
        draft.update(TextFieldValue("first"))
        val submitted = draft.revision
        draft.update(TextFieldValue("next message"))
        assertFalse(draft.clearIfUnchanged(submitted))
    }
    @Test fun changingBackToIdenticalTextIsStillANewIntent() {
        val draft = RoomComposerDraft()
        draft.update(TextFieldValue("same"))
        val submitted = draft.revision
        draft.update(TextFieldValue("different"))
        draft.update(TextFieldValue("same"))
        assertFalse(draft.clearIfUnchanged(submitted))
    }
    @Test fun movingCaretDoesNotPreventClearingSubmittedText() {
        val draft = RoomComposerDraft()
        draft.update(TextFieldValue("sent"))
        val submitted = draft.revision
        draft.update(TextFieldValue("sent", selection = TextRange(2)))
        assertTrue(draft.clearIfUnchanged(submitted))
    }
    @Test fun clearInvalidatesAnOlderAcknowledgement() {
        val draft = RoomComposerDraft()
        draft.update(TextFieldValue("sent"))
        val submitted = draft.revision
        assertTrue(draft.clearIfUnchanged(submitted))
        assertFalse(draft.clearIfUnchanged(submitted))
    }
    @Test fun keystrokesJournalImmediatelyButAcknowledgementsAreNotNewEdits() {
        val saved = mutableListOf<String>()
        val draft = RoomComposerDraft { saved += it }
        draft.update(TextFieldValue("sent"))
        assertEquals(listOf("sent"), saved)
        val submitted = draft.revision
        draft.update(TextFieldValue("sent", selection = TextRange(2)))
        assertEquals(1, saved.size)
        assertTrue(draft.clearIfUnchanged(submitted, notify = false))
        assertEquals(listOf("sent"), saved)
        draft.update(TextFieldValue("next"))
        assertEquals(listOf("sent", "next"), saved)
    }
}
