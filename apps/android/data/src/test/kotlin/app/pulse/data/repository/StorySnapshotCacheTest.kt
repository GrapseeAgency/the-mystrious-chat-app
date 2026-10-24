package app.pulse.data.repository

import app.pulse.protocol.StoriesPageDto
import app.pulse.protocol.StoryGroupDto
import app.pulse.protocol.StoryItemDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StorySnapshotCacheTest {
    private fun page(vararg ids: String) = StoriesPageDto(groups = listOf(StoryGroupDto(stories = ids.map { StoryItemDto(id = it) })))
    private fun ids(page: StoriesPageDto?) = page?.groups?.flatMap { it.stories }?.map { it.id }
    private class Store {
        val disk = mutableMapOf<String, StoriesPageDto>()
        var failWrite = false
        var failErase = false
        fun cache() = StorySnapshotCache({ disk[it] }, { key, page ->
            check(!failWrite) { "synthetic disk failure" }; disk[key] = page
        }, { check(!failErase); disk.remove(it); Unit })
    }
    @Test fun `late feed cannot restore a confirmed deletion and restart sees the persisted removal`() = runTest {
        val store = Store(); val cache = store.cache()
        cache.accept(cache.begin("a"), page("deleted", "keep"))
        val late = cache.begin("a")
        cache.remove("a", "deleted")
        assertEquals(listOf("keep"), ids(cache.accept(late, page("deleted", "keep"))))
        val restarted = store.cache()
        assertEquals(listOf("keep"), ids(restarted.cached(restarted.begin("a"))))
        assertEquals(listOf("keep"), ids(cache.accept(cache.begin("a"), page("deleted", "keep"))))
    }
    @Test fun `newest response and account cache stay independent`() = runTest {
        val cache = Store().cache()
        val old = cache.begin("a"); val latest = cache.begin("a")
        cache.accept(latest, page("new"))
        assertEquals(listOf("new"), ids(cache.accept(old, page("old"))))
        cache.accept(cache.begin("b"), page("same")); cache.remove("a", "same")
        assertEquals(listOf("same"), ids(cache.cached(cache.begin("b"))))
    }
    @Test fun `cache write failure keeps fresh memory and deletion tombstone rather than resurrecting disk data`() = runTest {
        val store = Store(); val cache = store.cache()
        cache.accept(cache.begin("a"), page("deleted", "keep"))
        store.failWrite = true
        try { cache.remove("a", "deleted"); fail("write should fail") } catch (_: IllegalStateException) { }
        assertEquals(listOf("keep"), ids(cache.cached(cache.begin("a"))))
        assertEquals(listOf("fresh"), ids(cache.accept(cache.begin("a"), page("fresh", "deleted"))))
    }
    @Test fun `denied snapshot cannot fall back to private disk data even if erase fails`() = runTest {
        val store = Store(); val cache = store.cache()
        cache.accept(cache.begin("a"), page("private"))
        val old = cache.begin("a"); val denied = cache.begin("a")
        store.failErase = true
        try { cache.deny(denied); fail("erase should fail") } catch (_: IllegalStateException) { }
        assertNull(cache.accept(old, page("private")))
        assertNull(cache.cached(cache.begin("a")))
        assertEquals(listOf("authorized"), ids(cache.accept(cache.begin("a"), page("authorized"))))
    }
}
