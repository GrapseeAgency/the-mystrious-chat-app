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
    private fun prefix(owner: String) = "composer-schedule:v1|$owner|"
    private fun key(command: ScheduledCommandDto) = prefix(command.owner) + command.kind + "|" + command.clientId
    private fun held(key: String) = key.replaceFirst("composer-schedule:","composer-schedule-blocked:")
    private fun ack(key: String) = key.replaceFirst("composer-schedule:","composer-schedule-ack:")
    private fun head(command: ScheduledCommandDto) = "composer-schedule-head:v1|${command.owner}|${command.conversationId}"
    private fun encode(command: ScheduledCommandDto) = PulseJson.encodeToString(ScheduledCommandDto.serializer(),command)
    private fun decode(row: Wave7CacheEntity): ScheduledCommandDto = PulseJson.decodeFromString(ScheduledCommandDto.serializer(),row.json).also {
        check(it.kind in setOf("create","cancel") && it.owner.isNotBlank() && it.clientId.isNotBlank() &&
            (row.key == key(it) || row.key == held(key(it)))) { "Unreadable scheduled operation" }
    }
    suspend fun stage(command: ScheduledCommandDto) = db.withTransaction {
        val key = key(command)
        if (dao.get(ack(key)) != null) return@withTransaction
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
        PulseJson.decodeFromString(ScheduledItemDto.serializer(),it.json)
    }
    suspend fun cancelled(command: ScheduledCommandDto): Boolean = dao.get(ack(key(command)))?.json == "cancelled"
    private class Transport(val failure: PulseResult.Failure): Exception(failure.message)
    suspend fun flush(owner: String, api: PulseApi, current: () -> Boolean): Int =
        DraftWriteCoordinator.forDatabase(db).scheduleMutex.withLock {
            for (row in dao.savedJournalPage(prefix(owner)).take(25)) {
                currentCoroutineContext().ensureActive(); if (!current()) break
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
                        dao.upsert(Wave7CacheEntity(ack(row.key),result,System.currentTimeMillis()))
                        dao.acknowledgeReadIntent(row.key,row.json)
                        if (command.kind == "create") dao.acknowledgeReadIntent(head(command),command.clientId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!current()) break
                    val failure = (error as? Transport)?.failure
                    val terminal = failure?.status in setOf(400,403,404,410,422) || failure?.code == "schedule_already_sent" || failure?.kind == PulseResult.Failure.Kind.VALIDATION
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
