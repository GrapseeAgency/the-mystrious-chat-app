package app.pulse.domain.sync

import app.pulse.domain.model.Message

fun threadRootAvailable(root: Message?, roomId: String, rootId: String, now: Long = System.currentTimeMillis()): Boolean =
    root != null && !root.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX) && root.id == rootId && root.conversationId == roomId && root.threadRootId == null &&
        !root.isDeleted && (root.expiresAtEpochMs?.let { it > now } != false)

/** Transport errors retain offline history. Explicit denial must close it until revalidated. */
fun threadAccessDenied(status: Int?): Boolean = status == 403 || status == 404 || status == 410

/** Cached reply chips follow live storage rows, not increments for relay deliveries. */
fun cachedThreadReplyCounts(rows: List<Message>, now: Long = System.currentTimeMillis()): Map<String, Int> {
    val roots = rows.filter { threadRootAvailable(it, it.conversationId, it.id, now) }.associateBy { it.id }
    return rows.asSequence().filter { reply ->
        val root = reply.threadRootId?.let { roots[it] }
        root != null && reply.conversationId == root.conversationId && !reply.isDeleted &&
            (reply.expiresAtEpochMs?.let { it > now } != false)
    }.groupingBy { checkNotNull(it.threadRootId) }.eachCount()
}

/** Index in replies only. Callers add their own header offset after layout. */
fun threadReplyTargetIndex(replies: List<Message>, root: Message, targetId: String,
    now: Long = System.currentTimeMillis()): Int? {
    if (!threadRootAvailable(root, root.conversationId, root.id, now)) return null
    return replies.indexOfFirst { it.id == targetId && it.conversationId == root.conversationId &&
        it.threadRootId == root.id && !it.isDeleted && (it.expiresAtEpochMs?.let { expiry -> expiry > now } != false) }
        .takeIf { it >= 0 }
}

/** Root IDs are globally unique; the containing database supplies account/gateway isolation. */
fun threadDenialKey(rootId: String): String = "thread-access-denied:v1|" + rootId

/** Access responses are ordered by request start, not by network completion. */
class ThreadValidationFence {
    private val tickets = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun begin(rootId: String): String = java.util.UUID.randomUUID().toString().also { tickets[rootId] = it }
    fun isCurrent(rootId: String, ticket: String): Boolean = tickets[rootId] == ticket
    fun finish(rootId: String, ticket: String) { tickets.remove(rootId, ticket) }
}


fun mayUseOfflineThread(error: Exception): Boolean =
    error is app.pulse.domain.model.PulseApiException && error.kind == "NETWORK" && error.status == null

fun cachedThreadTarget(root: Message?, reply: Message?, roomId: String, rootId: String, targetId: String,
    denied: Boolean, now: Long = System.currentTimeMillis()): Pair<Message, Message>? {
    if (denied || !threadRootAvailable(root, roomId, rootId, now) || root == null || reply == null) return null
    return if (threadReplyTargetIndex(listOf(reply), root, targetId, now) != null) root to reply else null
}

/** A transport outage can open saved history; HTTP refusal/cancellation can never fall back. */
suspend fun resolveThreadJump(repo: app.pulse.domain.repository.PulseRepository, roomId: String, rootId: String,
    targetId: String): Pair<Message, List<Message>> = try {
    repo.loadThread(rootId)
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Exception) {
    if (!mayUseOfflineThread(error)) throw error
    val saved = repo.cachedThreadTarget(roomId, rootId, targetId) ?: throw error
    saved.first to listOf(saved.second)
}


/** Only live server-backed rows actually intersecting the thread viewport count. */
fun visibleThreadReadIds(root: Message?, replies: List<Message>, visibleIds: Set<String>,
    now: Long = System.currentTimeMillis()): List<String> {
    root ?: return emptyList()
    if (!threadRootAvailable(root, root.conversationId, root.id, now)) return emptyList()
    return (listOf(root) + replies).filter {
        it.id in visibleIds && !it.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX) && !it.isDeleted &&
            it.conversationId == root.conversationId && (it.id == root.id || it.threadRootId == root.id) &&
            (it.expiresAtEpochMs?.let { expiry -> expiry > now } != false)
    }.map { it.id }
}

fun visibleThreadReadBoundary(root: Message?, replies: List<Message>, visibleIds: Set<String>,
    now: Long = System.currentTimeMillis()): String? = visibleThreadReadIds(root, replies, visibleIds, now).lastOrNull()
