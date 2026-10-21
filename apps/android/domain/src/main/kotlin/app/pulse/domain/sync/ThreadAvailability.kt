package app.pulse.domain.sync

import app.pulse.domain.model.Message

fun threadRootAvailable(root: Message?, roomId: String, rootId: String, now: Long = System.currentTimeMillis()): Boolean =
    root != null && root.id == rootId && root.conversationId == roomId && root.threadRootId == null &&
        !root.isDeleted && (root.expiresAtEpochMs?.let { it > now } != false)

/** Transport errors retain offline history. Explicit denial must close it until revalidated. */
fun threadAccessDenied(status: Int?): Boolean = status == 403 || status == 404 || status == 410

/** Cached reply chips follow live storage rows, not increments for relay deliveries. */
fun cachedThreadReplyCounts(rows: List<Message>, now: Long = System.currentTimeMillis()): Map<String, Int> {
    val roots = rows.filter { threadRootAvailable(it, it.conversationId, it.id, now) &&
        !it.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX) }.associateBy { it.id }
    return rows.asSequence().filter { reply ->
        val root = reply.threadRootId?.let { roots[it] }
        root != null && reply.conversationId == root.conversationId && !reply.isDeleted &&
            (reply.expiresAtEpochMs?.let { it > now } != false)
    }.groupingBy { checkNotNull(it.threadRootId) }.eachCount()
}
