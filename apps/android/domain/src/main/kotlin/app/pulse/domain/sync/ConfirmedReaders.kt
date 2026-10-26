package app.pulse.domain.sync

import app.pulse.domain.model.ConversationMember

/** Only the current member projection can prove peer reading. An independent
 * cached socket timestamp cannot override a hidden/removed reader. Timestamp
 * ties still require the separate identity-bearing read-progress migration. */
fun confirmedReaders(members: List<ConversationMember>, viewerId: String?, createdAtMillis: Long): List<ConversationMember> {
    if (viewerId.isNullOrBlank() || createdAtMillis <= 0) return emptyList()
    return members.filter { member ->
        member.id != viewerId && member.lastReadAt?.let { it > 0 && it >= createdAtMillis } == true
    }.distinctBy { it.id }
}
