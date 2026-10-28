package app.pulse.android

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * R42 - persistent share-in inbox. An external share is prepared (provider
 * bytes copied app-private, text captured) the moment it arrives - while the
 * URI grant is still fresh - and the entry then lives in filesDir/share-in
 * until it is durably queued into a conversation or explicitly removed.
 * A process death between "shared into Pulse" and "picked a conversation"
 * no longer loses the share; only a durable queue success or the user's
 * explicit Remove consumes an entry.
 */
object ShareInInbox {
    private const val DIR = "share-in"

    data class Entry(
        val id: String,
        val kind: String, // "text" | "image" | "file"
        val text: String? = null,
        val dataUrl: String? = null,
        val fileName: String? = null,
        val fileSize: Long? = null,
        val receivedAt: Long,
    )

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), "$id.json")

    fun store(context: Context, kind: String, text: String?, dataUrl: String?, fileName: String?, fileSize: Long?): Entry {
        val entry = Entry(
            id = java.util.UUID.randomUUID().toString(),
            kind = kind, text = text, dataUrl = dataUrl, fileName = fileName, fileSize = fileSize,
            receivedAt = System.currentTimeMillis())
        val json = JSONObject()
            .put("id", entry.id).put("kind", entry.kind)
            .put("text", entry.text ?: JSONObject.NULL)
            .put("dataUrl", entry.dataUrl ?: JSONObject.NULL)
            .put("fileName", entry.fileName ?: JSONObject.NULL)
            .put("fileSize", entry.fileSize ?: JSONObject.NULL)
            .put("receivedAt", entry.receivedAt)
        // Atomic publish: a process death mid-write never yields a half entry.
        val tmp = File(dir(context), "${entry.id}.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(context, entry.id))) {
            tmp.delete()
            file(context, entry.id).writeText(json.toString())
        }
        return entry
    }

    fun load(context: Context): List<Entry> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f ->
            try {
                val json = JSONObject(f.readText())
                Entry(
                    id = json.getString("id"),
                    kind = json.getString("kind"),
                    text = if (json.isNull("text")) null else json.getString("text"),
                    dataUrl = if (json.isNull("dataUrl")) null else json.getString("dataUrl"),
                    fileName = if (json.isNull("fileName")) null else json.getString("fileName"),
                    fileSize = if (json.isNull("fileSize")) null else json.getLong("fileSize"),
                    receivedAt = json.getLong("receivedAt"))
            } catch (_: Exception) { null }
        }?.sortedBy { it.receivedAt } ?: emptyList()

    /** Consumption: only a durable queue success or an explicit remove consumes an entry. */
    fun remove(context: Context, id: String) {
        file(context, id).delete()
    }
}
