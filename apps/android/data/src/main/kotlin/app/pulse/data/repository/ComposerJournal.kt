package app.pulse.data.repository

import androidx.room.withTransaction
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.Wave7CacheEntity
import app.pulse.data.remote.PulseApi
import app.pulse.core.result.PulseResult
import app.pulse.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

/** Local projection + coalesced desired state commit together. A dispatched
 * flight is immutable and survives edits, response loss and process restart. */
internal class ComposerJournal(private val db: PulseDatabase) {
    private val dao get() = db.wave7Dao()
    private fun prefix(owner: String) = "composer-intent:v1|$owner|"
    private fun head(key: String) = key.replaceFirst("composer-intent:v1|","composer-head:v1|")
    suspend fun hasIntent(owner: String, room: String, root: String?): Boolean {
        val key = key(owner,"draft",room,root)
        return dao.get(key) != null || dao.get(held(key)) != null
    }
    suspend fun recordBaseline(receipt: ComposerReceiptDto) {
        dao.upsert(Wave7CacheEntity(head(key(receipt.userId,receipt.kind,receipt.targetId,receipt.rootId)),
            PulseJson.encodeToString(ComposerReceiptDto.serializer(),receipt),System.currentTimeMillis()))
    }
    private fun held(key: String) = key.replaceFirst("composer-intent:v1|","composer-blocked:v1|")
    private fun key(owner: String, kind: String, target: String, root: String?): String {
        val scope = listOf(kind,target,root ?: "").joinToString("") { "${it.length}:$it" }
        return prefix(owner) + MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun decode(row: Wave7CacheEntity): ComposerIntentDto = PulseJson.decodeFromString(ComposerIntentDto.serializer(), row.json).also {
        check(it.kind in setOf("draft", "edit") && it.owner.isNotBlank() && it.targetId.isNotBlank() && it.conversationId.isNotBlank() &&
            (key(it.owner,it.kind,it.targetId,it.rootId) == row.key || held(key(it.owner,it.kind,it.targetId,it.rootId)) == row.key) && UUID.fromString(it.desiredId).toString() == it.desiredId) { "Unreadable composer intent" }
        it.flight?.let { f -> check(f.clientId.isNotBlank() && f.expectedRevision.isNotBlank()) }
    }
    private suspend fun put(key: String, value: ComposerIntentDto) = dao.upsert(Wave7CacheEntity(key,
        PulseJson.encodeToString(ComposerIntentDto.serializer(),value), dao.nextSavedOrder(prefix(value.owner))))

    /** Caller may include this nested Room transaction in draft/send atomic handoff. */
    suspend fun stage(owner: String, kind: String, target: String, room: String, root: String?, text: String,
        base: String, editedAt: String? = null, ready: Boolean = true, localEditRevision: String? = null) = db.withTransaction {
        val key = key(owner,kind,target,root)
        val previous = (dao.get(key) ?: dao.get(held(key)))?.let(::decode)
        if (previous != null && previous.text == text && previous.ready == ready && previous.blocked == null) return@withTransaction
        val baseline = dao.get(head(key))?.let { PulseJson.decodeFromString(ComposerReceiptDto.serializer(),it.json) }
        val flight = previous?.flight // never replace an uncertain transmitted operation
        dao.delete(held(key))
        put(key, ComposerIntentDto(owner,kind,target,room,root,UUID.randomUUID().toString(),text,
            if (kind == "edit") base else previous?.baseText ?: base,
            if (kind == "edit") editedAt else if (previous != null) previous.baseEditedAt else editedAt,ready,flight,null,
            if (kind == "edit") baseline?.takeIf { it.text == base && dateEqual(it.editedAt,editedAt) }?.revision
            else if (previous != null) previous.baseRevision else baseline?.revision, localEditRevision))
    }
    suspend fun editText(owner: String, id: String): String? {
        val key = key(owner,"edit",id,null)
        return (dao.get(key) ?: dao.get(held(key)))?.let(::decode)?.text
    }
    suspend fun entries(owner: String): List<ComposerIntentDto> = (dao.savedJournalPage(prefix(owner)) + dao.savedJournalPage(held(prefix(owner)))).mapNotNull {
        try { decode(it) } catch (_: Exception) { null } // raw bytes remain in durable storage
    }
    suspend fun discard(owner: String, desiredId: String) = db.withTransaction {
        for (row in dao.savedJournalPage(prefix(owner)) + dao.savedJournalPage(held(prefix(owner)))) {
            val pending = try { decode(row) } catch (_: Exception) { continue }
            if (pending.desiredId == desiredId) {
                dao.acknowledgeReadIntent(row.key,row.json)
            }
        }
    }
    private suspend fun block(key: String, value: ComposerIntentDto, message: String) {
        put(held(key),value.copy(blocked=message))
        dao.delete(key)
    }
    suspend fun recover(row: Wave7CacheEntity, replace: Boolean, api: PulseApi, current: () -> Boolean) {
        val intent = decode(row)
        check(row.key == held(key(intent.owner,intent.kind,intent.targetId,intent.rootId))) { "This operation is already queued" }
        if (replace) check(intent.flight == null) { "Reconcile the original request before replacing it" }
        val state = if (replace) unwrap(api.composerState(intent)).also { it.validateComposer(intent) } else null
        check(current()) { "Account changed" }
        db.withTransaction {
            check(dao.get(row.key)?.json == row.json) { "Recovery changed. Refresh before acting." }
            val active = key(intent.owner,intent.kind,intent.targetId,intent.rootId)
            if (state != null) {
                recordBaseline(state)
                put(active,intent.copy(desiredId=UUID.randomUUID().toString(),baseText=state.text,baseEditedAt=state.editedAt,
                    baseRevision=state.revision,blocked=null))
            } else put(active,intent.copy(blocked=null))
            dao.acknowledgeReadIntent(row.key,row.json)
        }
    }
    private fun dateEqual(a: String?, b: String?) = if (a == null || b == null) a == b else
        app.pulse.core.time.PulseTime.epochMs(a) == app.pulse.core.time.PulseTime.epochMs(b)
    private fun unwrap(result: PulseResult<ComposerReceiptDto>): ComposerReceiptDto = when(result) {
        is PulseResult.Success -> result.value
        is PulseResult.Failure -> throw ComposerTransport(result)
    }
    private class ComposerTransport(val failure: PulseResult.Failure): Exception(failure.message)

    suspend fun flush(owner: String, api: PulseApi, current: () -> Boolean,
        accepted: suspend (ComposerIntentDto, ComposerReceiptDto) -> Unit = { _,_ -> }): Int =
        DraftWriteCoordinator.forDatabase(db).serverMutex.withLock {
            val rows = dao.savedJournalPage(prefix(owner))
            for (row in rows.take(25)) {
                currentCoroutineContext().ensureActive(); if (!current()) break
                var pending: ComposerIntentDto
                try { pending = decode(row) } catch (_: Exception) {
                    db.withTransaction { dao.upsert(row.copy(key=held(row.key))); dao.acknowledgeReadIntent(row.key,row.json) }; continue
                }
                if ((!pending.ready && pending.flight == null) || pending.blocked != null) { dao.deferReadIntent(row.key,row.json,prefix(owner)); continue }
                try {
                    if (pending.flight == null) {
                        check(pending.text.length <= 2000 && (pending.kind != "edit" || pending.text.isNotBlank())) { "Text is retained locally but is outside the sendable length" }
                        val state = unwrap(api.composerState(pending)); state.validateComposer(pending)
                        check(current()) { "Account changed" }
                        if ((pending.baseRevision != null && state.revision != pending.baseRevision) ||
                            (state.text != pending.text && (state.text != pending.baseText || (pending.kind == "edit" && !dateEqual(state.editedAt,pending.baseEditedAt))))) {
                            db.withTransaction {
                                val latest = dao.get(row.key)?.let(::decode)
                                if (latest?.desiredId == pending.desiredId) block(row.key,latest,"Changed on another device. Your text is retained; review before replacing it.")
                            }
                            continue
                        }
                        val flight = ComposerFlight(pending.desiredId,pending.text,state.revision,pending.localEditRevision)
                        val frozen = db.withTransaction {
                            val latest = dao.get(row.key)?.let(::decode)
                            if (latest?.desiredId != pending.desiredId || latest.flight != null) null
                            else latest.copy(flight=flight).also { put(row.key,it) }
                        } ?: continue
                        pending = frozen
                    }
                    val flight = checkNotNull(pending.flight)
                    if (!current()) break
                    val receipt = unwrap(api.writeComposer(pending,flight)); receipt.validateComposer(pending,flight)
                    currentCoroutineContext().ensureActive(); if (!current()) break
                    // Cache acceptance must finish before retiring its replay identity.
                    if (receipt.outcome == "applied") accepted(pending,receipt)
                    db.withTransaction {
                        val latest = dao.get(row.key)?.let(::decode) ?: return@withTransaction
                        if (latest.flight?.clientId != flight.clientId) return@withTransaction
                        if (receipt.outcome == "applied") {
                            recordBaseline(receipt)
                            if (pending.kind == "edit" && flight.localEditRevision != null) {
                                val localKey = "composer-local-edit:v1|${pending.owner}|${pending.targetId}"
                                val row = dao.get(localKey)
                                val local = row?.let { PulseJson.decodeFromString(ComposerLocalEditDto.serializer(),it.json) }
                                if (row != null && local?.revision == flight.localEditRevision && local.text.trim() == flight.text)
                                    dao.acknowledgeReadIntent(localKey,row.json)
                            }
                        }
                        if (receipt.outcome != "applied") {
                            block(row.key,latest.copy(flight=null),"A newer server change won. Your text is retained for review.")
                        } else if (latest.desiredId == flight.clientId) dao.delete(row.key)
                        else if (latest.kind == "draft") put(row.key,latest.copy(flight=null,baseText=receipt.text,baseEditedAt=receipt.editedAt,baseRevision=receipt.revision))
                        else put(row.key,latest.copy(flight=null)) // another editor must retain its own observed base
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!current()) break
                    val failure = (error as? ComposerTransport)?.failure
                    val terminal = failure == null || failure.status in setOf(400,403,404,410,422) || failure.kind == PulseResult.Failure.Kind.VALIDATION
                    if (terminal) db.withTransaction {
                        val latest = dao.get(row.key)?.let(::decode)
                        if (latest?.flight?.clientId == pending.flight?.clientId && latest != null)
                            block(row.key,latest,error.message ?: "This operation needs review; your text is retained.")
                    }
                    val latest = dao.get(row.key)
                    if (latest != null) dao.deferReadIntent(row.key,latest.json,prefix(owner))
                    if (failure?.status == 401 || failure?.status == 429) break
                }
            }
            dao.savedJournalPage(prefix(owner)).size
        }
}
