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

    @Test fun `confirmed view survives delayed feed and normal restart without freezing future counts`() = runTest {
        val store = Store(); val cache = store.cache()
        cache.accept(cache.begin("a"), page("viewed"))
        val stale = cache.begin("a")
        cache.viewed("a", "viewed", 4)
        val current = cache.accept(stale, page("viewed"))!!
        assertTrue(current.groups.single().allSeen)
        assertEquals(4, current.groups.single().stories.single().viewCount)
        val restartedCache = store.cache()
        val restarted = restartedCache.cached(restartedCache.begin("a"))!!
        assertTrue(restarted.groups.single().stories.single().viewedByMe)
        val fresh = cache.accept(cache.begin("a"), page("viewed"))!!
        assertTrue(fresh.groups.single().stories.single().viewedByMe)
        assertEquals(0, fresh.groups.single().stories.single().viewCount)
    }
    @Test fun `a newer pending read does not erase an actual access denial`() = runTest {
        val cache = Store().cache()
        cache.accept(cache.begin("a"), page("private"))
        val denied = cache.begin("a"); val pending = cache.begin("a")
        cache.deny(denied)
        assertNull(cache.cached(pending))
        assertNull(cache.accept(pending, page("private")))
        val old = cache.begin("a")
        cache.accept(cache.begin("a"), page("new-authorized"))
        cache.deny(old)
        assertEquals(listOf("new-authorized"), ids(cache.cached(cache.begin("a"))))
    }
}
