package app.pulse.domain.model

/** The send may consume only this account, room, thread and local draft revision. */
data class ComposerDraftToken(val conversationId: String, val ownerId: String, val revision: Long, val parentId: String? = null) {
    val storageKey: String get() = threadDraftKey(conversationId, parentId)
    fun owns(room: String, owner: String?, parent: String?): Boolean =
        conversationId == room && ownerId == owner && parentId == parent
}

private const val THREAD_DRAFT_PREFIX = "thread-draft:v1|"
fun threadDraftKey(conversationId: String, parentId: String?): String = if (parentId == null) conversationId else
    "$THREAD_DRAFT_PREFIX${conversationId.length}:$conversationId|${parentId.length}:$parentId"
fun isThreadDraftKey(key: String): Boolean = key.startsWith(THREAD_DRAFT_PREFIX)

data class ThreadDraftSnapshot(val text: String?, val revision: Long, val saveFailed: Boolean = false)

/** Parse the length-delimited local namespace without splitting IDs on delimiters. */
fun composerDraftScope(key: String): Pair<String, String?> {
    if (!isThreadDraftKey(key)) return key to null
    var offset = THREAD_DRAFT_PREFIX.length
    fun field(): String {
        val colon = key.indexOf(':', offset)
        require(colon >= offset)
        val size = key.substring(offset, colon).toInt()
        require(size in 1..128 && colon + 1 + size <= key.length)
        offset = colon + 1 + size
        return key.substring(colon + 1, offset)
    }
    val room = field(); require(offset < key.length && key[offset++] == '|')
    val root = field(); require(offset == key.length)
    return room to root
}

/** A bounded recovery page; a version fences actions against background ACKs. */
data class ComposerRecoveryEntry(val key: String, val version: String, val kind: String, val targetId: String,
    val conversationId: String, val text: String, val notice: String, val canRetry: Boolean = false, val canReplace: Boolean = false)
data class ComposerRecoveryPage(val items: List<ComposerRecoveryEntry>, val nextCursor: String? = null)
