package app.pulse.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wave-2 domain rule (spec §1 row 2): the viewer's own poll pick derives
 * from options[].votedBy ONLY. The wire myOptionId is actor-relative on
 * poll:voted relayed rows (every recipient would see the voter's pick as
 * "mine") and null on history GETs — it must be IGNORED even when set.
 */
class PollPickTest {

    private val tally = PollInfo(
        id = "p1",
        question = "Lunch spot?",
        closed = false,
        options = listOf(
            PollOptionInfo(id = "optA", text = "Ramen", position = 0, voteCount = 2, votedBy = listOf("u2", "u3")),
            PollOptionInfo(id = "optB", text = "Tacos", position = 1, voteCount = 1, votedBy = listOf("u4")),
            PollOptionInfo(id = "optC", text = "Pizza", position = 2, voteCount = 0, votedBy = emptyList()),
        ),
        totalVotes = 3,
        // Deliberately WRONG (points at "Tacos" while u2 voted "Ramen") —
        // pickFor must never read it.
        myOptionId = "optB",
    )

    @Test
    fun `viewer in option B picks B via votedBy`() {
        val u4 = tally.pickFor("u4")
        assertEquals("optB", u4)
        // votedBy wins over the (wrong) myOptionId for u2.
        val u2 = tally.pickFor("u2")
        assertEquals("optA", u2)
    }

    @Test
    fun `viewer nowhere in votedBy picks nothing`() {
        assertNull(tally.pickFor("u9"))
        assertNull(tally.pickFor("u1"))
    }

    @Test
    fun `null viewer picks nothing`() {
        assertNull(tally.pickFor(null))
    }

    @Test
    fun `myOptionId is ignored even when it names a real option`() {
        // For EVERY viewer the pick must come from votedBy — the myOptionId
        // field is never consulted, not even as a fallback.
        assertEquals("optA", tally.pickFor("u2"))
        assertEquals("optB", tally.pickFor("u4"))
        assertNull(tally.pickFor("nobody"))
        assertNull(tally.pickFor(null))
    }

    @Test
    fun `first votedBy match wins for duplicate listings`() {
        // Defensive: a user id listed on two options (never on the real wire)
        // resolves deterministically to the first by position order.
        val messy = tally.copy(
            options = listOf(
                PollOptionInfo(id = "optA", text = "Ramen", votedBy = listOf("u2")),
                PollOptionInfo(id = "optB", text = "Tacos", votedBy = listOf("u2")),
            ),
        )
        assertEquals("optA", messy.pickFor("u2"))
    }
}
