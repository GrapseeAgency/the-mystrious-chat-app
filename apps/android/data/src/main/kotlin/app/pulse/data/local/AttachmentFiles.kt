package app.pulse.data.local

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Large data URLs must not live in a Room CursorWindow (typically limited to 2 MB).
 * The account database owns references to these immutable app-private files. */
internal class AttachmentFiles(private val directory: File) {
    private val keyPattern = Regex("[A-Za-z0-9_-]{16,128}\\.[a-f0-9]{64}")
    private fun file(key: String): File {
        require(keyPattern.matches(key)) { "Invalid saved attachment reference" }
        return File(directory, "$key.upload")
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun put(selectionId: String, dataUrl: String): String {
        require(selectionId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        val bytes = dataUrl.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val key = "$selectionId.${digest(bytes)}"
        val target = file(key)
        directory.mkdirs()
        if (target.exists()) { read(key); target.setLastModified(System.currentTimeMillis()); return key }
        val temp = File.createTempFile("preparing-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(target)) { "Could not save the attachment" }
        } finally { temp.delete() }
        return key
    }

    fun read(key: String): String {
        val source = file(key)
        require(source.length() in 1..MAX_BYTES.toLong()) { "Saved attachment is missing or too large" }
        val bytes = source.readBytes()
        check(digest(bytes) == key.substringAfterLast('.')) { "Saved attachment is damaged" }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Interrupted imports/discards leave no DB reference. A day-long grace
     * protects files prepared immediately before the database commit. */
    fun prune(referenced: Set<String>, now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.forEach { candidate ->
            val key = candidate.name.removeSuffix(".upload")
            if (candidate.isFile && now - candidate.lastModified() > 86_400_000L &&
                (candidate.name.endsWith(".tmp") || (candidate.name.endsWith(".upload") && key !in referenced))) candidate.delete()
        }
    }

    fun remove(key: String) { file(key).delete() }

    companion object { private const val MAX_BYTES = ((10 * 1024 * 1024 + 2) / 3) * 4 + 128 }
}
