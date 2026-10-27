package app.pulse.feature.settings

import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.ProfilePatch
import app.pulse.domain.model.UserProfile
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditOwnershipTest {
    private class Account {
        var owner = "owner"
        var session = "original-session"
        val original = UserProfile("owner", "Original", about = "bio", color = "amber", avatar = "/api/uploads/original.jpg", coverImage = "/api/uploads/cover.jpg")
        val reads = mutableListOf<Continuation<Result<UserProfile>>>()
        val uploads = mutableListOf<Continuation<Result<String>>>()
        val patches = mutableListOf<Pair<ProfilePatch, Continuation<Result<UserProfile>>>>()
        val checks = mutableListOf<Pair<String, Continuation<Result<HandleCheck>>>>()
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> proxy
                method.name == "getViewerId" -> owner
                method.name == "attachmentSessionKey" -> session
                method.name.startsWith("userProfile") -> { reads += args!!.last() as Continuation<Result<UserProfile>>; COROUTINE_SUSPENDED }
                method.name.startsWith("uploadMedia") -> { uploads += args!!.last() as Continuation<Result<String>>; COROUTINE_SUSPENDED }
                method.name.startsWith("patchProfile") -> { patches += (args!![0] as ProfilePatch) to (args.last() as Continuation<Result<UserProfile>>); COROUTINE_SUSPENDED }
                method.name.startsWith("checkHandle") -> { checks += (args!![0] as String) to (args.last() as Continuation<Result<HandleCheck>>); COROUTINE_SUSPENDED }
                else -> error("Unexpected repository call ${method.name}")
            }
        } as PulseRepository
    }
    private fun TestScope.loaded(account: Account): ProfileEditViewModel {
        val vm = ProfileEditViewModel(account.repo)
        runCurrent(); account.reads.single().resume(Result.success(account.original)); runCurrent()
        return vm
    }

    @Test fun unchangedFormDoesNotSendAnEmptyWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = loaded(account)
            vm.save(); runCurrent()
            assertTrue(account.patches.isEmpty())
            assertEquals("No changes to save", vm.state.value.notice)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun failedCoverUploadKeepsTheOldCoverAndDoesNotInventAnOfflineQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = loaded(account)
            vm.uploadCover("prepared"); runCurrent()
            account.uploads.single().resume(Result.failure(IllegalStateException("offline"))); runCurrent()
            assertEquals(account.original.coverImage, vm.state.value.coverImage)
            assertNull(vm.state.value.notice); assertEquals("offline", vm.state.value.error)
            assertFalse(vm.state.value.coverBusy); assertTrue(account.patches.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun successfulUploadIsNotAProfileAcknowledgementAndCannotResetTheEditedName() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = loaded(account)
            vm.uploadAvatar("prepared"); vm.removeAvatar(); runCurrent()
            assertEquals(1, account.uploads.size)
            account.uploads.single().resume(Result.success("uploaded.jpg")); runCurrent()
            assertEquals(account.original.avatar, vm.state.value.avatar)
            assertNull(vm.state.value.notice)
            vm.set("name", "new draft")
            account.patches.single().second.resume(Result.failure(IllegalStateException("refused"))); runCurrent()
            assertEquals("new draft", vm.state.value.name)
            assertEquals(account.original.avatar, vm.state.value.avatar)
            assertNull(vm.state.value.notice); assertEquals("refused", vm.state.value.error)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun accountSwitchDuringUploadPreventsTheProfilePatchAndRetiresOldState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = loaded(account)
            vm.uploadAvatar("prepared"); runCurrent()
            account.owner = "replacement"; account.session = "replacement-session"
            account.uploads.single().resume(Result.success("old-upload.jpg")); runCurrent()
            assertTrue(account.patches.isEmpty())
            assertNull(vm.state.value.userId); assertNull(vm.state.value.avatar)
            assertFalse(vm.state.value.uploading)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun saveUsesFrozenChangedFieldsAndPreservesEditsMadeWhileWaiting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = loaded(account)
            vm.set("name", "submitted"); vm.set("bio", "")
            vm.save(); vm.save(); runCurrent()
            val patch = account.patches.single().first
            assertEquals("submitted", patch.name); assertEquals("", patch.about)
            assertNull(patch.avatar); assertNull(patch.coverImage); assertNull(patch.color)
            vm.set("name", "newer edit")
            account.patches.single().second.resume(Result.success(account.original.copy(name = "submitted", about = ""))); runCurrent()
            assertEquals("newer edit", vm.state.value.name)
            assertTrue(vm.state.value.notice.orEmpty().contains("Newer edits"))
            assertFalse(vm.state.value.saving)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancellationIgnoringHandleLookupCannotPaintANewerCandidate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = loaded(account)
            vm.setHandle("alpha"); runCurrent(); advanceTimeBy(350); runCurrent()
            vm.setHandle("beta"); runCurrent(); advanceTimeBy(350); runCurrent()
            assertEquals(listOf("alpha", "beta"), account.checks.map { it.first })
            account.checks[1].second.resume(Result.success(HandleCheck(true))); runCurrent()
            account.checks[0].second.resume(Result.success(HandleCheck(false))); runCurrent()
            assertEquals("beta", vm.state.value.handle)
            assertEquals("free", vm.state.value.handleCheck.state)
        } finally { Dispatchers.resetMain() }
    }
}
