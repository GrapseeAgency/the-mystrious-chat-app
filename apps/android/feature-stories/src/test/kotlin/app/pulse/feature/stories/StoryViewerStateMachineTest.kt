package app.pulse.feature.stories

import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import app.pulse.domain.model.StoryUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web-truth behavior contract for the full-screen story viewer (Wave 4).
 * Constants and semantics mirror stories-sheet.tsx as captured by the
 * forensic audit; the web DEFECTS D2/D3/D6 are asserted as FIXED here.
 */
class StoryViewerStateMachineTest {

    private val now = 1_700_000_000_000L

    private fun story(
        id: String,
        mine: Boolean = false,
        viewed: Boolean = false,
        expiresInMs: Long = 3_600_000L,
        views: Int = 0,
    ) = StoryItem(
        id = id,
        kind = "text",
        caption = "story $id",
        background = "emerald",
        createdAtEpochMs = now - 60_000L,
        expiresAtEpochMs = now + expiresInMs,
        viewCount = views,
        viewedByMe = viewed,
    )

    private fun group(userId: String, vararg items: StoryItem, mine: Boolean = false) = StoryGroup(
        user = StoryUser(id = userId, name = if (mine) "Me" else userId),
        mine = mine,
        allSeen = !mine && items.all { it.viewedByMe },
        stories = items.toList(),
    )

    private fun machine(vararg groups: StoryGroup, startUserId: String? = null): StoryViewerStateMachine {
        val m = StoryViewerStateMachine(startUserId)
        m.on(StoryViewerInput.GroupsUpdated(groups.toList(), now))
        return m
    }

    // ── start position + pending mark ───────────────────────────

    @Test
    fun `starts at the first story of the requested author`() {
        val m = machine(
            group("me", story("s0", mine = true), mine = true),
            group("alice", story("a0"), story("a1")),
            group("bob", story("b0")),
            startUserId = "bob",
        )
        assertEquals("b0", m.state.value.current?.story?.id)
    }

    @Test
    fun `starts at the very first story without a start author`() {
        val m = machine(group("me", story("s0", mine = true), mine = true), group("alice", story("a0")))
        assertEquals("s0", m.state.value.current?.story?.id)
    }

    @Test
    fun `non-mine unseen story becomes a pending view mark on display`() {
        val m = machine(group("alice", story("a0")))
        assertEquals("a0", m.state.value.pendingMark?.storyId)
    }

    @Test
    fun `own stories never produce a view mark`() {
        val m = machine(group("me", story("s0", mine = true), mine = true))
        assertNull(m.state.value.pendingMark)
    }

    @Test
    fun `empty first feed auto-closes the viewer`() {
        val m = machine()
        assertTrue(m.state.value.dismissed)
    }

    // ── 5000ms playback + pause/resume ──────────────────────────

    @Test
    fun `ticks accrue elapsed and auto-advance at 5000ms`() {
        val m = machine(group("alice", story("a0"), story("a1")))
        m.on(StoryViewerInput.Tick(now + 10)) // arm the clock (first frame counts 0)
        m.on(StoryViewerInput.Tick(now + 2510))
        assertEquals(2500L, m.state.value.elapsedMs)
        m.on(StoryViewerInput.Tick(now + 5010))
        // ≥5000 → next story, elapsed reset
        assertEquals("a1", m.state.value.current?.story?.id)
        assertEquals(0L, m.state.value.elapsedMs)
    }

    @Test
    fun `hold pauses and resumes from elapsed without restart`() {
        val m = machine(group("alice", story("a0"), story("a1")))
        m.on(StoryViewerInput.Tick(now + 10))
        m.on(StoryViewerInput.Tick(now + 2010)) // 2000ms elapsed
        m.on(StoryViewerInput.HoldStart(now + 2100))
        assertTrue(m.state.value.paused)
        // ticks during hold do NOT accrue
        m.on(StoryViewerInput.Tick(now + 4100))
        m.on(StoryViewerInput.Tick(now + 6100))
        assertEquals(2000L, m.state.value.elapsedMs)
        // release resumes from the preserved elapsed (web rAF elapsedRef)
        m.on(StoryViewerInput.HoldEnd(now + 6200))
        assertFalse(m.state.value.paused)
        m.on(StoryViewerInput.Tick(now + 8200)) // +2000 → 4000
        assertEquals(4000L, m.state.value.elapsedMs)
        m.on(StoryViewerInput.Tick(now + 9300)) // ≥5000 → advance
        assertEquals("a1", m.state.value.current?.story?.id)
    }

    @Test
    fun `crosses group boundaries and closes after the last story`() {
        val m = machine(group("alice", story("a0")), group("bob", story("b0")))
        m.on(StoryViewerInput.Tick(now + 10))
        m.on(StoryViewerInput.Tick(now + 5010)) // 5000ms → a0 done
        assertEquals("b0", m.state.value.current?.story?.id)
        m.on(StoryViewerInput.Tick(now + 5011)) // clock re-arms after an advance
        m.on(StoryViewerInput.Tick(now + 10_011)) // 5000ms → b0 done → past the end
        assertTrue("viewer must close after the last story", m.state.value.dismissed)
    }

    // ── tap zones / prev boundary ───────────────────────────────

    @Test
    fun `tap prev no-ops at the very first story`() {
        val m = machine(group("alice", story("a0")), group("bob", story("b0")))
        m.on(StoryViewerInput.TapPrev)
        assertEquals("a0", m.state.value.current?.story?.id)
        assertFalse(m.state.value.dismissed)
    }

    @Test
    fun `tap next moves one story back and forward across groups`() {
        val m = machine(group("alice", story("a0")), group("bob", story("b0")))
        m.on(StoryViewerInput.TapNext)
        assertEquals("b0", m.state.value.current?.story?.id)
        m.on(StoryViewerInput.TapPrev)
        assertEquals("a0", m.state.value.current?.story?.id)
    }

    @Test
    fun `drag dismiss closes the viewer and blocks further navigation`() {
        val m = machine(group("alice", story("a0")), group("bob", story("b0")))
        m.on(StoryViewerInput.DragDismiss)
        assertTrue(m.state.value.dismissed)
        m.on(StoryViewerInput.TapNext)
        assertTrue("dismissed is terminal", m.state.value.dismissed)
    }

    // ── D2: expiry filtering without network ────────────────────

    @Test
    fun `expired stories are filtered out on feed update without any probe`() {
        val m = machine(group("alice", story("a0", expiresInMs = 500L), story("a1")))
        // 10 minutes later: a0 (expiresAt = now+500) is expired, a1 is not.
        m.on(StoryViewerInput.GroupsUpdated(listOf(group("alice", story("a0", expiresInMs = -1L), story("a1"))), now + 600_000))
        val flatIds = m.state.value.flat.map { it.story.id }
        assertEquals(listOf("a1"), flatIds)
        assertEquals("a1", m.state.value.current?.story?.id)
    }

    @Test
    fun `a group whose only story expired disappears entirely`() {
        val m = machine(group("alice", story("a0")), group("bob", story("b0")))
        m.on(StoryViewerInput.TapNext) // at b0
        m.on(StoryViewerInput.GroupsUpdated(listOf(group("alice", story("a0", expiresInMs = -1L)), group("bob", story("b0", expiresInMs = -1L))), now))
        assertTrue("nothing survives → close", m.state.value.dismissed)
    }

    // ── D3: vanished current story → nearest survivor ───────────

    @Test
    fun `current story deleted underneath auto-advances to the nearest survivor`() {
        val m = machine(group("alice", story("a0"), story("a1"), story("a2")))
        m.on(StoryViewerInput.TapNext) // a1
        m.on(StoryViewerInput.GroupsUpdated(listOf(group("alice", story("a0"), story("a2"))), now)) // a1 gone
        assertEquals("nearest survivor keeps the flat position", "a2", m.state.value.current?.story?.id)
        assertEquals(0L, m.state.value.elapsedMs)
    }

    @Test
    fun `viewed state and elapsed are preserved across a refresh of the same story`() {
        val m = machine(group("alice", story("a0"), story("a1")))
        m.on(StoryViewerInput.Tick(now + 10))
        m.on(StoryViewerInput.Tick(now + 1510)) // 1500 elapsed
        m.on(StoryViewerInput.ViewMarkOk("a0", viewCount = 3))
        m.on(StoryViewerInput.GroupsUpdated(listOf(group("alice", story("a0", viewed = true, views = 3), story("a1"))), now + 20_000))
        assertEquals("a0", m.state.value.current?.story?.id)
        assertEquals(1500L, m.state.value.elapsedMs)
        assertNull("server-acked seen settles the pending mark", m.state.value.pendingMark)
    }

    // ── D6: optimistic mark, single retry, give-up ──────────────

    @Test
    fun `view mark failure retries exactly once then gives up`() {
        val m = machine(group("alice", story("a0")))
        assertEquals(0, m.state.value.pendingMark?.attempt)
        m.on(StoryViewerInput.ViewMarkFailed("a0"))
        assertEquals("first failure → one retry queued", 1, m.state.value.pendingMark?.attempt)
        m.on(StoryViewerInput.ViewMarkFailed("a0"))
        assertNull("second failure gives up (next fetch reconciles)", m.state.value.pendingMark)
    }

    @Test
    fun `view mark success records the count and clears the pending mark`() {
        val m = machine(group("alice", story("a0")))
        m.on(StoryViewerInput.ViewMarkOk("a0", viewCount = 7))
        assertTrue(m.state.value.current?.story?.viewedByMe == true)
        assertEquals(7, m.state.value.current?.story?.viewCount)
        assertNull(m.state.value.pendingMark)
        // allSeen recomputes: the single story is now seen
        assertTrue(m.state.value.groups.first().allSeen)
    }

    @Test
    fun `group with unseen survivors stays unseen after one story is marked`() {
        val m = machine(group("alice", story("a0"), story("a1")))
        m.on(StoryViewerInput.ViewMarkOk("a0", viewCount = 1))
        val g = m.state.value.groups.first()
        assertFalse("half-watched stack must still be unseen (per-author ring semantics)", g.allSeen)
    }
}
