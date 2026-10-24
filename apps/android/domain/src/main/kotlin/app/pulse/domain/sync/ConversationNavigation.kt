package app.pulse.domain.sync

import app.pulse.domain.model.Conversation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A confirmed create can precede the UI's next Room emission. Resolve by ID,
 * never by title and never by a fabricated placeholder conversation. */
suspend fun resolveConversationForOpen(
    id: String,
    cached: List<Conversation>,
    load: suspend (String) -> Result<Conversation>,
): Conversation {
    currentCoroutineContext().ensureActive()
    require(id.isNotBlank()) { "A conversation is required" }
    val result = cached.firstOrNull { it.id == id } ?: load(id).getOrThrow()
    currentCoroutineContext().ensureActive()
    check(result.id == id) { "The returned conversation does not match the requested chat" }
    return result
}

/** Note to Self has a distinct server contract; a one-member DM is invalid. */
suspend fun resolvePersonalConversation(
    owner: String,
    peer: String,
    cached: List<Conversation>,
    createSelf: suspend () -> Result<Conversation>,
    createDirect: suspend (String) -> Result<Conversation>,
): Conversation {
    currentCoroutineContext().ensureActive()
    require(owner.isNotBlank() && peer.isNotBlank()) { "A chat identity is required" }
    val self = owner == peer
    val expected = setOf(owner, peer)
    fun matches(row: Conversation) = row.id.isNotBlank() && row.kind == Conversation.Kind.DM &&
        row.isSelf == self && row.memberIds.size == expected.size && row.memberIds.toSet() == expected
    val result = cached.firstOrNull(::matches) ?: (if (self) createSelf() else createDirect(peer)).getOrThrow()
    currentCoroutineContext().ensureActive()
    check(matches(result)) { "The returned chat does not match the selected people" }
    return result
}
