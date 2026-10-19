package app.pulse.domain.model

import app.pulse.protocol.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Screen-owned, main-dispatcher coordinator. Network operations serialize; drawing
 * persists through a separate short lock, never behind an HTTP request. */
class WhiteboardSession(
    private val scope: CoroutineScope,
    private val viewerId: String,
    private val loadDraft: suspend () -> List<WhiteboardStrokePostDto>,
    private val updateDraft: suspend ((List<WhiteboardStrokePostDto>) -> List<WhiteboardStrokePostDto>) -> List<WhiteboardStrokePostDto>,
    private val load: suspend () -> WhiteboardPageDto,
    private val post: suspend (List<WhiteboardStrokePostDto>) -> WhiteboardPostResultDto,
    private val undo: suspend () -> WhiteboardUndoResultDto,
    private val clear: suspend () -> WhiteboardClearResultDto,
) {
    data class State(
        val remote: List<WhiteboardStrokeDto> = emptyList(),
        val pending: List<WhiteboardStrokePostDto> = emptyList(),
        val ready: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val network = Mutex()
    private val storage = Mutex()
    private var epoch = 0L
    private var started = false

    fun start(): Job = scope.launch {
        if (started) return@launch
        started = true
        try {
            storage.withLock { mutable.value = mutable.value.copy(pending = loadDraft()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(error = "Could not read saved drawings.")
            return@launch
        }
        while (isActive) {
            attempt { network.withLock { refresh(); flush() } }
            delay(1500)
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.value = mutable.value.copy(error = "Whiteboard operation failed; saved drafts were retained. ${e.message.orEmpty()}") }
    }

    private suspend fun persist(transform: (List<WhiteboardStrokePostDto>) -> List<WhiteboardStrokePostDto>) {
        // A successful storage commit precedes the UI claim and any HTTP request.
        withContext(NonCancellable) {
            mutable.value = mutable.value.copy(pending = updateDraft(transform))
        }
    }

    private suspend fun refresh() {
        val page = load()
        currentCoroutineContext().ensureActive()
        if (page.operationsVersion != 1 || !page.complete) mutable.value = mutable.value.copy(ready = false)
        check(page.operationsVersion == 1) { "Server upgrade required for safe drawing sync." }
        check(page.complete) { "Board exceeds snapshot limit; no partial board was adopted." }
        storage.withLock {
            val nextEpoch = page.resetAt ?: 0L
            val ids = page.strokes.map { it.id }.toSet()
            persist { latest -> latest.filter { stroke ->
                // Legacy unknown-epoch drafts are kept for explicit review, NOT replayed.
                val base = stroke.baseResetAt
                stroke.clientId == null || base == null ||
                    (base >= nextEpoch && "wb:$viewerId:${stroke.clientId}" !in ids)
            } }
            epoch = nextEpoch
            mutable.value = mutable.value.copy(remote = page.strokes.distinctBy { it.id }, ready = true,
                error = if (mutable.value.pending.any { it.clientId == null || it.baseResetAt == null }) "Older drafts retained locally; their delivery state is unknown." else null)
        }
    }

    private suspend fun flush() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = storage.withLock { mutable.value.pending.filter { it.clientId != null && it.baseResetAt == epoch }.take(40) }
            if (batch.isEmpty()) return
            val ack = post(batch)
            currentCoroutineContext().ensureActive()
            check(ack.ids.size == batch.size && ack.ids == batch.map { "wb:$viewerId:${it.clientId}" }) { "Invalid drawing acknowledgement." }
            storage.withLock {
                val sent = batch.map { it.clientId }.toSet()
                persist { latest -> latest.filterNot { it.clientId in sent } }
                // Keep acknowledged ink visible until the next authoritative snapshot.
                val additions = batch.mapIndexed { i, s -> WhiteboardStrokeDto(id = ack.ids[i], userId = viewerId, color = s.color, width = s.width, points = s.points) }
                mutable.value = mutable.value.copy(remote = (mutable.value.remote + additions).distinctBy { it.id })
            }
        }
    }

    fun draw(color: String, width: Double, points: List<List<Double>>): Job? {
        if (!mutable.value.ready || mutable.value.busy || points.size !in 2..500) return null
        val stroke = WhiteboardStrokePostDto(color = color, width = width, points = points,
            clientId = UUID.randomUUID().toString(), baseResetAt = epoch)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            attempt {
                storage.withLock { persist { it + stroke } }
                network.withLock { flush() }
            }
        }
    }

    fun undoMine(): Job? {
        if (!mutable.value.ready || mutable.value.busy || mutable.value.pending.isNotEmpty()) return null
        mutable.value = mutable.value.copy(busy = true)
        return scope.launch {
            try { attempt { network.withLock { check(undo().success); refresh() } } }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun clearBoard(): Job? {
        if (!mutable.value.ready || mutable.value.busy) return null
        mutable.value = mutable.value.copy(busy = true)
        return scope.launch {
            try {
                attempt {
                    network.withLock {
                        val result = clear()
                        check(result.success && result.reset && result.at != null) { "Invalid Clear acknowledgement." }
                        storage.withLock {
                            epoch = result.at!!
                            persist { latest -> latest.filter { (it.baseResetAt ?: -1L) >= epoch } }
                            mutable.value = mutable.value.copy(remote = emptyList())
                        }
                        refresh()
                    }
                }
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
