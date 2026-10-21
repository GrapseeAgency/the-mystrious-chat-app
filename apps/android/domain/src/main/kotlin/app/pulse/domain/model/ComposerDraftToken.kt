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
