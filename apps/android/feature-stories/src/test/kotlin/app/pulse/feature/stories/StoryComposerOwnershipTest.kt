package app.pulse.feature.stories

import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoryComposerOwnershipTest {
    private class Account {
        var owner = "first"
        val uploads = mutableListOf<Continuation<Result<String>>>()
        val posts = mutableListOf<Continuation<Result<StoryItem>>>()
        val deletes = mutableListOf<Continuation<Result<Unit>>>()
        val marks = mutableListOf<Continuation<Result<Int>>>()
        val feeds = mutableListOf<Continuation<Result<List<StoryGroup>>>>()
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> proxy
                method.name == "getViewerId" || method.name == "attachmentSessionKey" -> owner
                method.name.startsWith("uploadMedia") -> { uploads += args!!.last() as Continuation<Result<String>>; COROUTINE_SUSPENDED }
                method.name.startsWith("createStory") -> { posts += args!!.last() as Continuation<Result<StoryItem>>; COROUTINE_SUSPENDED }
                method.name.startsWith("deleteStory") -> { deletes += args!!.last() as Continuation<Result<Unit>>; COROUTINE_SUSPENDED }
                method.name.startsWith("markStoryViewed") -> { marks += args!!.last() as Continuation<Result<Int>>; COROUTINE_SUSPENDED }
                method.name.startsWith("stories") -> { feeds += args!!.last() as Continuation<Result<List<StoryGroup>>>; COROUTINE_SUSPENDED }
                else -> error("Unexpected call ${method.name}")
            }
        } as PulseRepository
        private val prefs = Proxy.newProxyInstance(PulsePrefsStore::class.java.classLoader, arrayOf(PulsePrefsStore::class.java)) { _, method, _ ->
            error("Unexpected prefs call ${method.name}")
        } as PulsePrefsStore
        fun model() = StoriesViewModel(repo, prefs)
    }
    private fun feed(id: String) = listOf(StoryGroup(null, false, false, listOf(StoryItem(id, "text", caption = id, expiresAtEpochMs = Long.MAX_VALUE))))

    @Test fun `failed publication is not success and does not trigger a feed refresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = account.model(); val session = vm.composerSession()!!
            val send = async { vm.publishStory("keep this draft", "emerald", null, session) }
            runCurrent()
            account.posts.single().resume(Result.failure(IllegalStateException("offline")))
            assertTrue(send.await().isFailure)
            assertTrue(account.feeds.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `cancelled presentation cannot accept a cancellation ignoring upload response`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = account.model(); val session = vm.composerSession()!!
            var applied = false
            val upload = async { vm.uploadStoryImage("jpeg", session); applied = true }
            runCurrent(); upload.cancel()
            account.uploads.single().resume(Result.success("old.jpg")); runCurrent()
            assertTrue(upload.isCancelled); assertFalse(applied)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `retired owner is rejected before dispatch and after an in flight reply`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = account.model(); val session = vm.composerSession()!!
            val old = async { vm.uploadStoryImage("jpeg", session) }
            runCurrent(); account.owner = "replacement"
            account.uploads.single().resume(Result.success("old.jpg")); runCurrent()
            assertTrue(old.isCancelled)
            val send = async { vm.publishStory("old text", "emerald", null, session) }
            runCurrent(); assertTrue(send.isCancelled); assertTrue(account.posts.isEmpty())
            assertFalse(vm.acceptsComposer(session))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `older feed cannot overwrite the newest result or survive owner replacement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = account.model()
            vm.refresh(); runCurrent(); vm.refresh(); runCurrent()
            account.feeds[1].resume(Result.success(feed("new"))); runCurrent()
            account.feeds[0].resume(Result.success(feed("old"))); runCurrent()
            assertEquals("new", vm.groups.value.single().stories.single().id)
            account.owner = "replacement"; vm.refresh()
            assertTrue(vm.groups.value.isEmpty())
            runCurrent(); account.feeds[2].resume(Result.success(emptyList())); runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `refused actions do not optimistically remove or mark a story`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = account.model(); val owner = vm.composerSession()!!
            vm.refresh(); runCurrent(); account.feeds.single().resume(Result.success(feed("a"))); runCurrent()
            val mark = async { vm.markViewed("a", owner) }; runCurrent()
            assertFalse(vm.groups.value.single().stories.single().viewedByMe)
            account.marks.single().resume(Result.failure(IllegalStateException("offline")))
            assertTrue(mark.await().isFailure)
            val delete = async { vm.deleteStory("a", owner) }; runCurrent()
            assertEquals("a", vm.groups.value.single().stories.single().id)
            account.deletes.single().resume(Result.failure(IllegalStateException("offline")))
            assertTrue(delete.await().isFailure)
            assertFalse(vm.groups.value.single().stories.single().viewedByMe)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `confirmed deletion fences an older feed and account change drops late marks`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = account.model(); val owner = vm.composerSession()!!
            vm.refresh(); runCurrent(); account.feeds.single().resume(Result.success(feed("a"))); runCurrent()
            vm.refresh(); runCurrent()
            val delete = async { vm.deleteStory("a", owner) }; runCurrent()
            account.deletes.single().resume(Result.success(Unit)); assertTrue(delete.await().isSuccess)
            account.feeds.last().resume(Result.success(feed("a"))); runCurrent()
            assertTrue(vm.groups.value.isEmpty()); assertFalse(vm.flags.value.loading)
            val mark = async { vm.markViewed("a", owner) }; runCurrent()
            account.owner = "replacement"; account.marks.single().resume(Result.success(1)); runCurrent()
            assertTrue(mark.isCancelled); assertTrue(vm.groups.value.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
