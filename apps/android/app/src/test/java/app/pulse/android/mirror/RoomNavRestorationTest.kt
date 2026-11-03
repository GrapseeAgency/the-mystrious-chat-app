package app.pulse.android.mirror

import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Section 14 items 1+3 - the process-death restoration and competing-intent
 * contracts (RoomNavRestoration) plus the open-request currency contract
 * (MirrorRoomOpenRequest.stillCurrent).
 */
class RoomNavRestorationTest {

    // ── item 1: capture/restore ─────────────────────────────────────

    @Test
    fun `capture stores IDs and the owning viewer, never a model object`() {
        val nav = RoomNavRestoration.capture("room-1", "viewer-a", "room-1", "msg-9")
        assertEquals(SavedRoomNav("room-1", "msg-9", "viewer-a"), nav)
    }

    @Test
    fun `capture drops a jump anchor that belongs to a different room`() {
        val nav = RoomNavRestoration.capture("room-1", "viewer-a", "room-2", "msg-9")
        assertEquals(SavedRoomNav("room-1", null, "viewer-a"), nav)
    }

    @Test
    fun `capture of the home surface is null - nothing to restore`() {
        assertNull(RoomNavRestoration.capture(null, "viewer-a", null, null))
    }

    @Test
    fun `restore fires only for the same owner with nothing competing`() {
        val saved = SavedRoomNav("room-1", "msg-9", "viewer-a")
        assertEquals("room-1", RoomNavRestoration.restore(saved, "viewer-a", null, openRequestActive = false))
    }

    @Test
    fun `restore never reopens under a different owner`() {
        val saved = SavedRoomNav("room-1", "msg-9", "viewer-a")
        assertNull(RoomNavRestoration.restore(saved, "viewer-b", null, openRequestActive = false))
        assertNull(RoomNavRestoration.restore(saved, null, null, openRequestActive = false))
    }

    @Test
    fun `restore yields to a live room and to an in-flight open request`() {
        val saved = SavedRoomNav("room-1", null, "viewer-a")
        assertNull(RoomNavRestoration.restore(saved, "viewer-a", "room-live", openRequestActive = false))
        assertNull(RoomNavRestoration.restore(saved, "viewer-a", null, openRequestActive = true))
    }

    @Test
    fun `restore of nothing is nothing`() {
        assertNull(RoomNavRestoration.restore(null, "viewer-a", null, openRequestActive = false))
        assertNull(RoomNavRestoration.restore(SavedRoomNav("", null, "viewer-a"), "viewer-a", null, openRequestActive = false))
    }

    // ── item 3: competing-intent yield ──────────────────────────────

    /** Proxy fake exposing only the ownership surface stillCurrent reads. */
    @Suppress("UNCHECKED_CAST")
    private fun fakeRepository(viewerId: String?, epoch: String?): PulseRepository =
        Proxy.newProxyInstance(
            PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getViewerId" -> viewerId
                "attachmentSessionKey" -> epoch
                else -> null
            }
        } as PulseRepository

    private fun request(
        api: PulseRepository,
        ticket: Long,
        tab: MirrorTab = MirrorTab.Chats,
        previous: String? = null,
        owner: String? = "viewer-a",
    ) = MirrorRoomOpenRequest("room-1", ticket, null, tab, previous, owner, api)

    @Test
    fun `the newest request stays current while nothing navigated elsewhere`() = runBlocking {
        val api = fakeRepository("viewer-a", "epoch-1")
        val repository = fakeRepository("viewer-a", "epoch-1")
        val request = request(api, 1)
        assertTrue(request.stillCurrent(request, "viewer-a", repository, MirrorTab.Chats, null))
    }

    @Test
    fun `a superseding request retires the older resolution`() = runBlocking {
        val api = fakeRepository("viewer-a", "epoch-1")
        val repository = fakeRepository("viewer-a", "epoch-1")
        val older = request(api, 1)
        val newer = request(api, 2)
        assertFalse(older.stillCurrent(newer, "viewer-a", repository, MirrorTab.Chats, null))
        assertTrue(newer.stillCurrent(newer, "viewer-a", repository, MirrorTab.Chats, null))
    }

    @Test
    fun `a viewer switch or an account epoch change yields the late resolution`() = runBlocking {
        val api = fakeRepository("viewer-a", "epoch-1")
        val request = request(api, 1)
        assertFalse(request.stillCurrent(request, "viewer-b", fakeRepository("viewer-b", "epoch-1"), MirrorTab.Chats, null))
        assertFalse(request.stillCurrent(request, "viewer-a", fakeRepository("viewer-a", "epoch-2"), MirrorTab.Chats, null))
    }

    @Test
    fun `a tab change or a room change during resolution yields it`() = runBlocking {
        val api = fakeRepository("viewer-a", "epoch-1")
        val repository = fakeRepository("viewer-a", "epoch-1")
        val request = request(api, 1, tab = MirrorTab.Chats, previous = null)
        assertFalse(request.stillCurrent(request, "viewer-a", repository, MirrorTab.Profile, null))
        assertFalse(request.stillCurrent(request, "viewer-a", repository, MirrorTab.Chats, "room-earlier"))
    }

    @Test
    fun `a request without an owner or epoch can never land`() {
        val api = fakeRepository("viewer-a", null)
        val request = request(api, 1)
        assertFalse(request.stillCurrent(request, "viewer-a", fakeRepository("viewer-a", null), MirrorTab.Chats, null))
        val ownerless = request(fakeRepository("viewer-a", "epoch-1"), 1, owner = null)
        assertFalse(ownerless.stillCurrent(ownerless, "viewer-a", fakeRepository("viewer-a", "epoch-1"), MirrorTab.Chats, null))
    }
}
