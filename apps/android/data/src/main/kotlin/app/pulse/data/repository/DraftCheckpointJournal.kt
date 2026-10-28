package app.pulse.data.repository

import android.util.AtomicFile
import app.pulse.protocol.PulseJson
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Independent private-file checkpoint. SQLite stores the checkpoint UUID in
 * the same transaction as a save/send/clear, so a crash before file cleanup
 * cannot resurrect a consumed draft. Never queues network work by itself.
 */
internal class DraftCheckpointJournal(private val directory: File?) {
    @Serializable data class Snapshot(val scope: String, val text: String, val id: String = UUID.randomUUID().toString())
    private val lock = locks.getOrPut(directory?.absolutePath ?: UUID.randomUUID().toString()) { Any() }
    fun receiptKey(scope: String) = "draft-checkpoint-ack:v1|" + digest(scope)
    private fun file(scope: String): AtomicFile? = directory?.let { AtomicFile(File(it, digest(scope) + ".json")) }
    private fun readUnlocked(scope: String): Snapshot? {
        val file = file(scope) ?: return null
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return PulseJson.decodeFromString(Snapshot.serializer(), file.readFully().toString(Charsets.UTF_8)).also {
            check(it.scope == scope && UUID.fromString(it.id).toString() == it.id) { "Invalid draft checkpoint" }
        }
    }
    fun scopes(): List<String> = synchronized(lock) {
        val files = directory?.listFiles() ?: return@synchronized emptyList()
        files.map { it.name.removeSuffix(".bak") }.filter { it.endsWith(".json") }.distinct().sorted().map { name ->
            val record = PulseJson.decodeFromString(Snapshot.serializer(), AtomicFile(File(directory,name)).readFully().toString(Charsets.UTF_8))
            check(name == digest(record.scope) + ".json") { "Invalid recovery scope" }
            record.scope
        }
    }
    fun read(scope: String): Snapshot? = synchronized(lock) { readUnlocked(scope) }
    fun write(scope: String, text: String): Snapshot? = synchronized(lock) {
        val dir = directory ?: return@synchronized null
        check(dir.isDirectory || dir.mkdirs()) { "Draft recovery storage is unavailable" }
        val file = checkNotNull(file(scope))
        val record = Snapshot(scope, text)
        val stream = file.startWrite()
        try {
            stream.write(PulseJson.encodeToString(Snapshot.serializer(), record).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
        record
    }
    fun remove(scope: String, id: String) = synchronized(lock) {
        if (readUnlocked(scope)?.id == id) file(scope)?.delete()
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
