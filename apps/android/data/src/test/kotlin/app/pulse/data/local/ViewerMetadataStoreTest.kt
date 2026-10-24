package app.pulse.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ViewerMetadataStoreTest {
    @Test fun `metadata survives reopen without changing identity credentials or unrelated fields`() = runBlocking {
        val file = File.createTempFile("viewer-metadata", ".preferences_pb").apply { delete() }
        var job = SupervisorJob()
        var storage = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        var prefs = PulsePrefsStoreImpl(storage, PulsePrefsLocalStore(storage))
        val credential = stringPreferencesKey("session.token")
        val vault = stringPreferencesKey("session.vault")
        try {
            prefs.setViewer("owner", "Before", "amber")
            prefs.setServerBase("https://metadata.test")
            prefs.setDarkOverride("dark")
            storage.edit { it[credential] = "test-ciphertext"; it[vault] = "test-vault" }
            assertTrue(prefs.updateViewerMetadata("owner", "Confirmed", null) { true })
            assertEquals("amber", prefs.viewerColor.first())
            assertTrue(prefs.updateViewerMetadata("owner", null, "rose") { true })
            job.cancelAndJoin(); job = SupervisorJob()
            storage = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            prefs = PulsePrefsStoreImpl(storage, PulsePrefsLocalStore(storage))
            assertEquals("owner", prefs.viewerId.first())
            assertEquals("Confirmed", prefs.viewerName.first())
            assertEquals("rose", prefs.viewerColor.first())
            assertEquals("https://metadata.test", prefs.serverBase.first())
            assertEquals("dark", prefs.darkOverride.first())
            assertEquals("test-ciphertext", storage.data.first()[credential])
            assertEquals("test-vault", storage.data.first()[vault])
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun `foreign owner retired session and signed out metadata cannot replace the viewer`() = runBlocking {
        val file = File.createTempFile("viewer-metadata", ".preferences_pb").apply { delete() }
        val job = SupervisorJob()
        val storage = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val prefs = PulsePrefsStoreImpl(storage, PulsePrefsLocalStore(storage))
        try {
            prefs.setViewer("first", "First", "rose")
            prefs.setViewer("second", "Second", null)
            assertNull(prefs.viewerColor.first()) // never inherit the previous person's color
            assertFalse(prefs.updateViewerMetadata("first", "Wrong", "orange") { true })
            assertFalse(prefs.updateViewerMetadata("second", "Retired", "pink") { false })
            assertEquals("second", prefs.viewerId.first())
            assertEquals("Second", prefs.viewerName.first())
            assertNull(prefs.viewerColor.first())
            prefs.setViewer(null, null, null)
            assertFalse(prefs.updateViewerMetadata("second", "Resurrected", "teal") { true })
            assertNull(prefs.viewerId.first()); assertNull(prefs.viewerName.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun `session is checked inside the transaction after a queued write is released`() = runBlocking {
        val file = File.createTempFile("viewer-metadata", ".preferences_pb").apply { delete() }
        val job = SupervisorJob()
        val storage = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val prefs = PulsePrefsStoreImpl(storage, PulsePrefsLocalStore(storage))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicBoolean(true)
        try {
            prefs.setViewer("owner", "Original", "amber")
            val blocked = async { storage.edit { entered.complete(Unit); release.await() } }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                prefs.updateViewerMetadata("owner", "Late", "rose") { active.get() }
            }
            active.set(false)
            release.complete(Unit); blocked.await()
            assertFalse(queued.await())
            assertEquals("Original", prefs.viewerName.first())
            assertEquals("amber", prefs.viewerColor.first())
        } finally { release.complete(Unit); job.cancelAndJoin(); file.delete() }
    }
}
