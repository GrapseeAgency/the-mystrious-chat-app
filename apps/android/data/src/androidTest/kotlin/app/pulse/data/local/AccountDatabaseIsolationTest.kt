package app.pulse.data.local

import android.content.Context
import androidx.room.Room
import app.pulse.domain.model.Message
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.core.PulseEndpoints
import app.pulse.data.repository.AccountRepositories
import io.ktor.client.HttpClient
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDatabaseIsolationTest {
    @Test fun switchingAccountsNeverSharesDraftsAndReturningRestoresOwnDraft() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldGateway = PulseEndpoints.gatewayHttpUrl
        val oldSocket = PulseEndpoints.socketUrl
        val http = HttpClient()
        val persistence = object : SessionTokenPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
            override suspend fun clear() {}
        }
        val cipher = object : SessionTokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decryptOrNull(stored: String) = stored
        }
        PulseEndpoints.applyBase(null) // no fixture or production network traffic
        val repo = AccountRepositories(context, http, SessionTokenStore(persistence, cipher)).repository
        val suffix = UUID.randomUUID().toString()
        try {
            withTimeout(30_000) {
                repo.start("account-a-$suffix")
                repo.saveDraft("same-room-id", "private draft A")
                assertEquals("private draft A", repo.observeDraft("same-room-id").first())
                // A queued composer send consumes its draft in the SAME commit,
                // including a staged write/debounce that has not run yet.
                repo.stageDraft("handoff", "send once")
                val token = checkNotNull(repo.captureDraftToken("handoff"))
                assertTrue(repo.sendMessage("handoff", "send once", draftToken = token).isSuccess)
                repo.saveStagedDraft("handoff", "send once", token)
                assertEquals("", repo.observeDraft("handoff").first())
                assertEquals("", repo.observeDrafts().first()["handoff"])
                assertEquals(1, repo.observeOutbox().first().count { it.conversationId == "handoff" })

                repo.saveDraft("handoff", "first")
                val older = checkNotNull(repo.captureDraftToken("handoff"))
                repo.saveDraft("handoff", "newer typing")
                assertTrue(repo.sendRichMessage("handoff", "first", payload = "{\"effect\":\"echo\"}", draftToken = older).isSuccess)
                assertEquals("newer typing", repo.observeDraft("handoff").first())
                assertTrue(repo.sendMessage("handoff", "thread", parentId = "root").isSuccess)
                assertEquals("newer typing", repo.observeDraft("handoff").first())
                assertTrue(repo.sendMessage("handoff", "wrong owner", draftToken = older.copy(ownerId = "other")).isFailure)

                // Thread drafts share the atomic queue handoff, never the river's key.
                repo.saveDraft("thread-room", "main composer")
                val stagedThread = checkNotNull(repo.stageThreadDraft("thread-room", "root-a", "reply once"))
                repo.stageThreadDraft("thread-room", "root-b", "keep other thread")
                assertEquals("reply once", repo.loadThreadDraft("thread-room", "root-a"))
                val threadToken = checkNotNull(repo.captureThreadDraftToken("thread-room", "root-a"))
                assertEquals(stagedThread, threadToken)
                assertTrue(repo.sendMessage("thread-room", "reply once", parentId = "root-a", draftToken = threadToken).isSuccess)
                assertEquals("", repo.loadThreadDraft("thread-room", "root-a"))
                assertEquals("main composer", repo.observeDraft("thread-room").first())
                assertEquals("keep other thread", repo.loadThreadDraft("thread-room", "root-b"))
                assertTrue(repo.sendMessage("thread-room", "wrong thread", parentId = "root-b", draftToken = threadToken).isFailure)
                assertTrue(repo.sendMessage("thread-room", "wrong river", draftToken = threadToken).isFailure)
                repo.stageThreadDraft("thread-room", "root-a", "older")
                val olderThread = checkNotNull(repo.captureThreadDraftToken("thread-room", "root-a"))
                repo.stageThreadDraft("thread-room", "root-a", "new typing")
                assertTrue(repo.sendMessage("thread-room", "older", parentId = "root-a", draftToken = olderThread).isSuccess)
                assertEquals("new typing", repo.loadThreadDraft("thread-room", "root-a"))
                repo.observeDraft(app.pulse.domain.model.threadDraftKey("thread-room", "root-a")).first { it == "new typing" }
                repo.observeDraft(app.pulse.domain.model.threadDraftKey("thread-room", "root-b")).first { it == "keep other thread" }

                // Real repository recovery: no replacement identity, no draft consumption,
                // and stale UI actions cannot remove an operation already requeued.
                val original = repo.observeOutbox().first().single { it.conversationId == "thread-room" && it.content == "reply once" }
                repo.dropOutboxEntry(original.clientId, "rejected")
                assertEquals(listOf(original.clientId), repo.observeFailedThreadReplies("thread-room", "root-a").first().map { it.clientId })
                assertTrue(repo.retryThreadReply("thread-room", "root-a", original.clientId).isFailure) // missing root
                val recoveryKey = AccountStorageKey.namespace(AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl), "account-a-$suffix")
                val recoveryDb = Room.databaseBuilder(context, PulseDatabase::class.java, "pulse-account-$recoveryKey.db").build()
                try {
                    val root = Message(id = "root-a", conversationId = "thread-room", authorId = "account-a-$suffix",
                        authorName = "Sender", kind = Message.Kind.TEXT, body = "Root", createdAt = "2026-10-20T00:00:00Z")
                    recoveryDb.messageDao().upsertAll(listOf(MessageEntity.from(root), MessageEntity.from(root.copy(id = "root-b"))))
                    assertTrue(repo.retryThreadReply("thread-room", "root-b", original.clientId).isFailure)
                    assertTrue(repo.retryThreadReply("other-room", "root-a", original.clientId).isFailure)
                    assertTrue(repo.retryThreadReply("thread-room", "root-a", original.clientId).isSuccess)
                    assertTrue(repo.discardFailedThreadReply("thread-room", "root-a", original.clientId).isFailure)
                    assertEquals(original.id, repo.observeOutbox().first().single { it.clientId == original.clientId }.id)
                    assertEquals("new typing", repo.loadThreadDraft("thread-room", "root-a"))
                    repo.dropOutboxEntry(original.clientId, "rejected again")
                    recoveryDb.messageDao().upsertAll(listOf(MessageEntity.from(root.copy(deletedAt = "2026-10-21T00:00:00Z"))))
                    assertTrue(repo.retryThreadReply("thread-room", "root-a", original.clientId).isFailure)
                    recoveryDb.messageDao().upsertAll(listOf(MessageEntity.from(root.copy(expiresAtEpochMs = 1))))
                    assertTrue(repo.retryThreadReply("thread-room", "root-a", original.clientId).isFailure)
                    recoveryDb.messageDao().deleteById("root-a")
                    assertTrue(repo.discardFailedThreadReply("thread-room", "root-a", original.clientId).isSuccess)
                    assertNull(recoveryDb.messageDao().byId("local_" + original.clientId))
                    assertEquals("new typing", repo.loadThreadDraft("thread-room", "root-a"))
                } finally { recoveryDb.close() }

                // Fail the final draft mutation, AFTER the bubble/outbox inserts.
                // SQLite must roll back all three, retaining the unsent composer.
                val owner = "account-a-$suffix"
                val key = AccountStorageKey.namespace(AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl), owner)
                val connection = android.database.sqlite.SQLiteDatabase.openDatabase(
                    context.getDatabasePath("pulse-account-$key.db").path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                )
                try {
                    connection.execSQL("CREATE TRIGGER fail_draft BEFORE INSERT ON draft BEGIN SELECT RAISE(ABORT, 'disk write failed'); END")
                    val before = repo.observeOutbox().first().size
                    val current = checkNotNull(repo.captureDraftToken("handoff"))
                    val rejected = runCatching { repo.sendMessage("handoff", "newer typing", draftToken = current).getOrThrow() }
                    assertTrue(rejected.isFailure)
                    assertEquals(before, repo.observeOutbox().first().size)
                    assertEquals("newer typing", repo.observeDraft("handoff").first())
                    repo.stageThreadDraft("thread-room", "disk-failure", "keep failed draft")
                    val failedSnapshot = repo.observeThreadDraft("thread-room", "disk-failure").first { it.saveFailed }
                    assertEquals("keep failed draft", failedSnapshot.text)
                    val failedThread = checkNotNull(repo.captureThreadDraftToken("thread-room", "disk-failure"))
                    assertTrue(runCatching { repo.sendMessage("thread-room", "keep failed draft", parentId = "disk-failure", draftToken = failedThread).getOrThrow() }.isFailure)
                    assertEquals(before, repo.observeOutbox().first().size)
                    assertEquals("keep failed draft", repo.loadThreadDraft("thread-room", "disk-failure"))
                    connection.rawQuery("SELECT COUNT(*) FROM messages WHERE body = 'newer typing'", null).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(0, cursor.getInt(0))
                    }
                } finally {
                    connection.execSQL("DROP TRIGGER fail_draft")
                    connection.close()
                }

                repo.stageThreadDraft("thread-room", "disk-failure", "recovered draft")
                repo.observeDraft(app.pulse.domain.model.threadDraftKey("thread-room", "disk-failure")).first { it == "recovered draft" }

                repo.stageThreadDraft("thread-room", "rapid-switch", "last keystroke before switching")
                repo.start("account-b-$suffix")
                assertNull(repo.loadThreadDraft("thread-room", "root-a"))
                assertNull(repo.observeDraft("same-room-id").first())
                repo.saveDraft("same-room-id", "private draft B")
                repo.start("account-a-$suffix")
                assertEquals("last keystroke before switching", repo.loadThreadDraft("thread-room", "rapid-switch"))
                assertEquals("private draft A", repo.observeDraft("same-room-id").first())
                assertEquals("newer typing", repo.observeDraft("handoff").first())
                assertEquals(3, repo.observeOutbox().first().count { it.conversationId == "handoff" })
                assertEquals("new typing", repo.loadThreadDraft("thread-room", "root-a"))
                assertEquals("keep other thread", repo.loadThreadDraft("thread-room", "root-b"))
                repo.stageDraft("clear-all", "pending old write")
                val discarded = checkNotNull(repo.captureDraftToken("clear-all"))
                repo.clearAllDrafts()
                assertEquals("", repo.loadThreadDraft("thread-room", "root-a"))
                assertEquals("", repo.loadThreadDraft("thread-room", "root-b"))
                repo.saveStagedDraft("clear-all", "pending old write", discarded)
                assertEquals("", repo.observeDraft("clear-all").first())
                assertEquals("", repo.observeDraft("handoff").first())
                val accountA = repo.boundToCurrentAccount()
                repo.stop()
                accountA.stageDraft("same-room-id", "typed immediately before disposal")
                assertNull(repo.observeDraft("same-room-id").first())
                assertEquals("typed immediately before disposal", accountA.observeDraft("same-room-id")
                    .first { it == "typed immediately before disposal" })
                repo.start("account-b-$suffix")
                assertEquals("private draft B", repo.observeDraft("same-room-id").first())
            }
        } finally {
            repo.stop()
            http.close()
            PulseEndpoints.gatewayHttpUrl = oldGateway
            PulseEndpoints.socketUrl = oldSocket
        }
    }
}
