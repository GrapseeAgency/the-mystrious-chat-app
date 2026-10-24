package app.pulse.android.mirror

import app.pulse.domain.model.UserProfile
import org.junit.Assert.*
import org.junit.Test

class MirrorProfileDraftTest {
    private val original = UserProfile("owner", "Name", about = "kept bio", color = "amber", avatar = "kept.jpg", statusEmoji = "coffee", statusText = "busy")
    @Test fun onlyEditedFieldsAreSentAndAnEmptyBioIsAnExplicitClear() {
        val draft = MirrorProfileDraft("New name", "kept bio", "amber", "coffee", "busy")
        val patch = draft.patch(original)
        assertEquals("New name", patch.name)
        assertNull(patch.about); assertNull(patch.color); assertNull(patch.statusText); assertNull(patch.avatar)
        assertEquals("", draft.copy(about = "").patch(original).about)
        assertNull(draft.copy(name = " Name ").patch(original).name)
    }
    @Test fun acknowledgementChangesOnlyTheSubmittedBaselineAndCannotReplaceAnotherOwner() {
        val draft = MirrorProfileDraft("New name", "kept bio", "amber", "coffee", "busy")
        val response = original.copy(name = "New name", about = "stale bio", avatar = "stale.jpg", color = "rose")
        val accepted = draft.confirmedBaseline(original, response)
        assertEquals(original.copy(name = "New name"), accepted)
        val newerDraft = draft.copy(about = "new edit while saving")
        assertNotEquals(draft, newerDraft)
        assertEquals("new edit while saving", newerDraft.patch(accepted).about)
        assertTrue(runCatching { draft.confirmedBaseline(original, response.copy(id = "other")) }.isFailure)
    }
    @Test fun profileClippingDoesNotSplitEmojiOrCombiningCharactersAtTheWireLimit() {
        assertEquals("🌿".repeat(16), clipProfileText("🌿".repeat(17), 32))
        assertEquals("a".repeat(31), clipProfileText("a".repeat(31) + "🌿", 32))
        assertEquals("", clipProfileText("e\u0301", 1))
        assertEquals("kept", clipProfileText("kept", 32))
    }

}
