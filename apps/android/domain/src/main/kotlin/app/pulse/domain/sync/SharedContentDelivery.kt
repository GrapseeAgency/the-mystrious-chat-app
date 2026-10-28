package app.pulse.domain.sync

import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One presentation owns preparation; the original account's outbox owns upload and delivery. */
class SharedContentDelivery(private val router: PulseRepository) {
    private val owner = router.attachmentSessionKey()
    private val account = router.boundToCurrentAccount()
    private val busy = AtomicBoolean(false)
    private val committed = AtomicBoolean(false)
    private var preparedImage: String? = null
    private val imageClientIds = mutableMapOf<String, String>()

    fun isCurrent(): Boolean = owner != null && router.attachmentSessionKey() == owner
    private suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("The original share account is no longer active")
    }
    private suspend fun send(operation: suspend () -> Unit): Result<Unit> {
        if (!busy.compareAndSet(false, true)) return Result.failure(IllegalStateException("A share is already being prepared"))
        return try {
            requireCurrent()
            check(!committed.get()) { "This share was already queued. Check the chat." }
            operation()
            committed.set(true)
            requireCurrent()
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { Result.failure(failure) }
        finally { busy.set(false) }
    }
    suspend fun text(conversationId: String, text: String): Result<Unit> = send {
        // Shared text uses the same lossless length validation as the room composer.
        SendMessageUseCase(account)(conversationId, text).getOrThrow()
    }
    suspend fun image(conversationId: String, prepare: suspend () -> Result<String>): Result<Unit> = send {
        val bytes = preparedImage ?: prepare().getOrThrow().also { preparedImage = it }
        requireCurrent()
        val clientId = imageClientIds.getOrPut(conversationId) { UUID.randomUUID().toString() }
        account.queueSharedImage(conversationId, clientId, bytes, checkNotNull(owner)).getOrThrow()
    }

    /** R42 - a prepared document (pdf/txt/csv/zip) rides the same durable
     * staging path; its stable client id dedupes retries. */
    suspend fun document(conversationId: String, dataUrl: String, fileName: String, fileSize: Long): Result<Unit> = send {
        requireCurrent()
        val clientId = imageClientIds.getOrPut("document:$conversationId") { UUID.randomUUID().toString() }
        account.queueSharedDocument(conversationId, clientId, dataUrl, fileName, fileSize, checkNotNull(owner)).getOrThrow()
    }
}
