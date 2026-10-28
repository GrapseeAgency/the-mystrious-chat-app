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

/** Original schedule time/body are instructions, never a retry-time template. */
internal class ScheduledJournal(private val db: PulseDatabase) {
    private val dao get() = db.wave7Dao()
    private val retryAdmission = ComposerRetryAdmission(db)
    private fun prefix(owner: String) = "composer-schedule:v1|$owner|"
    private fun key(command: ScheduledCommandDto) = prefix(command.owner) + command.kind + "|" + command.clientId
    private fun held(key: String) = key.replaceFirst("composer-schedule:","composer-schedule-blocked:")
    private fun ack(key: String) = key.replaceFirst("composer-schedule:","composer-schedule-ack:")
    private fun head(command: ScheduledCommandDto) = "composer-schedule-head:v1|${command.owner}|${command.conversationId}"
    private fun referenceKey(owner: String, scheduledId: String) = "composer-schedule-reference:v1|$owner|$scheduledId"
    private suspend fun releaseHead(owner: String, scheduledId: String, cancelled: Boolean = false) {
        val row = dao.get(referenceKey(owner,scheduledId)) ?: return
        val reference = PulseJson.decodeFromString(ScheduledCommandDto.serializer(),row.json)
        check(reference.owner == owner && reference.kind == "create" && reference.conversationId.isNotBlank()) { "Schedule recovery reference changed" }
        if (cancelled) {
            val accepted = dao.get(ack(key(reference)))
            if (accepted != null) {
                val receipt = PulseJson.decodeFromString(ScheduledCommandAckDto.serializer(),accepted.json)
                check(receipt.owner == owner && receipt.clientId == reference.clientId && receipt.kind == "create")
                dao.upsert(accepted.copy(json=PulseJson.encodeToString(ScheduledCommandAckDto.serializer(),receipt.copy(cancelled=true))))
            }
        }
        dao.acknowledgeReadIntent(head(reference),reference.clientId)
        if (cancelled) dao.acknowledgeReadIntent(row.key,row.json)
    }
    private fun requestHash(command: ScheduledCommandDto): String {
        val input = listOf(command.owner,command.clientId,command.kind,command.conversationId,command.text,command.scheduledAt).joinToString("") { "${it.length}:$it" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun receipt(row: Wave7CacheEntity, command: ScheduledCommandDto): ScheduledCommandAckDto =
        PulseJson.decodeFromString(ScheduledCommandAckDto.serializer(),row.json).also {
            check(!it.cancelled) { "The original schedule was cancelled. Choose a different time for a new send." }
            check(it.version == 1 && it.owner == command.owner && it.clientId == command.clientId && it.kind == command.kind && it.requestHash == requestHash(command)) { "Schedule receipt does not match these original instructions" }
        }
    private fun encode(command: ScheduledCommandDto) = PulseJson.encodeToString(ScheduledCommandDto.serializer(),command)
    private fun decode(row: Wave7CacheEntity): ScheduledCommandDto = PulseJson.decodeFromString(ScheduledCommandDto.serializer(),row.json).also {
        check(it.kind in setOf("create","cancel") && it.owner.isNotBlank() && it.clientId.isNotBlank() &&
            (row.key == key(it) || row.key == held(key(it)))) { "Unreadable scheduled operation" }
    }
    suspend fun stage(command: ScheduledCommandDto) = db.withTransaction {
        val key = key(command)
        dao.get(ack(key))?.let { receipt(it,command); return@withTransaction }
        val prior = (dao.get(key) ?: dao.get(held(key)))?.let(::decode)
        check(prior == null || prior.copy(notice=null) == command.copy(notice=null)) { "The original schedule instructions must not change" }
        if (command.kind == "create") {
            val previous = dao.get(head(command))
            check(previous == null || previous.json == command.clientId) { "An earlier schedule in this chat still needs confirmation. Resolve it in Drafts & outbox before choosing another time." }
            dao.upsert(Wave7CacheEntity(head(command),command.clientId,0))
        }
        dao.upsert(Wave7CacheEntity(key,encode(command.copy(notice=null)),dao.nextSavedOrder(prefix(command.owner))))
        dao.delete(held(key))
    }
    suspend fun result(command: ScheduledCommandDto): ScheduledItemDto? = dao.get(ack(key(command)))?.let {
        receipt(it,command).item?.copy(content=command.text)
    }
    suspend fun cancelled(command: ScheduledCommandDto): Boolean = dao.get(ack(key(command)))?.let { receipt(it,command).kind == "cancel" } == true
    /** Server acceptance does not prove that its composer/draft was consumed. */
    suspend fun acknowledge(owner: String, clientId: String, scheduledId: String, room: String) = db.withTransaction {
        val command = ScheduledCommandDto(owner,clientId,"create",room)
        val raw = checkNotNull(dao.get(ack(key(command)))) { "Schedule acceptance needs recovery review" }
        val receipt = PulseJson.decodeFromString(ScheduledCommandAckDto.serializer(),raw.json)
        check(receipt.owner == owner && receipt.clientId == clientId && receipt.kind == "create" &&
            receipt.item?.id == scheduledId && receipt.item?.conversationId == room) { "Schedule acknowledgement changed" }
        dao.acknowledgeReadIntent(head(command),clientId)
        releaseHead(owner,scheduledId)
    }
    private class Transport(val failure: PulseResult.Failure): Exception(failure.message)
    suspend fun flush(owner: String, api: PulseApi, current: () -> Boolean): Int =
        DraftWriteCoordinator.forDatabase(db).scheduleMutex.withLock {
            for (row in dao.savedJournalPage(prefix(owner)).take(25)) {
                currentCoroutineContext().ensureActive(); if (!current()) break
                if (!retryAdmission.canRun(owner)) break
                val command = try { decode(row) } catch (_: Exception) {
                    db.withTransaction { dao.upsert(row.copy(key=held(row.key))); dao.acknowledgeReadIntent(row.key,row.json) }; continue
                }
                try {
                    val result = if (command.kind == "create") when(val response = api.scheduleMessage(command.conversationId,owner,command.text,command.scheduledAt,command.clientId)) {
                        is PulseResult.Success -> PulseJson.encodeToString(ScheduledItemDto.serializer(),response.value)
                        is PulseResult.Failure -> throw Transport(response)
                    } else when(val response = api.cancelScheduled(command.clientId,owner)) {
                        is PulseResult.Success -> "cancelled"
                        is PulseResult.Failure -> throw Transport(response)
                    }
                    currentCoroutineContext().ensureActive(); if (!current()) break
                    db.withTransaction {
                        if (dao.get(row.key)?.json != row.json) return@withTransaction
                        val item = if (command.kind == "create") PulseJson.decodeFromString(ScheduledItemDto.serializer(),result).copy(content="") else null
                        val receipt = ScheduledCommandAckDto(owner=owner,clientId=command.clientId,kind=command.kind,requestHash=requestHash(command),item=item)
                        dao.upsert(Wave7CacheEntity(ack(row.key),PulseJson.encodeToString(ScheduledCommandAckDto.serializer(),receipt),System.currentTimeMillis()))
                        if (item != null) {
                            dao.upsert(Wave7CacheEntity(referenceKey(owner,item.id),encode(command.copy(text="",scheduledAt="",notice=null)),0))
                        } else if (command.kind == "cancel") releaseHead(owner,command.clientId,cancelled=true)
                        dao.acknowledgeReadIntent(row.key,row.json)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!current()) break
                    val failure = (error as? Transport)?.failure
                    if (failure?.status == 429 || (failure?.status == 503 && failure.retryAfter != null)) {
                        retryAdmission.hold(owner, failure.retryAfter)
                    }
                    val terminal = failure?.status in setOf(400,403,404,410,422) || failure?.code in setOf("schedule_already_sent", "schedule_duplicate") || failure?.kind == PulseResult.Failure.Kind.VALIDATION
                    if (terminal) db.withTransaction {
                        if (dao.get(row.key)?.json == row.json) {
                            dao.upsert(row.copy(key=held(row.key),json=encode(command.copy(notice=error.message ?: "This original schedule needs review; nothing was rescheduled."))))
                            dao.acknowledgeReadIntent(row.key,row.json)
                        }
                    } else dao.deferReadIntent(row.key,row.json,prefix(owner))
                    if (failure?.status in setOf(401,429)) break
                }
            }
            dao.savedJournalPage(prefix(owner)).size
        }
}
