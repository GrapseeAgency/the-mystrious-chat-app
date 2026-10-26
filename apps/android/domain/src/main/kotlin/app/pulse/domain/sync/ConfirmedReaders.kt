package app.pulse.domain.sync

import app.pulse.domain.model.ConversationMember

/** Only the current member projection can prove peer reading. An independent
 * cached socket timestamp cannot override a hidden/removed reader. Timestamp
 * watermarks alone are never evidence of observing this message. */
fun confirmedReaders(members: List<ConversationMember>, viewerId: String?, createdAtMillis: Long, readByMembershipIds: List<String> = emptyList()): List<ConversationMember> {
    if (viewerId.isNullOrBlank() || createdAtMillis <= 0) return emptyList()
    return members.filter { member ->
        member.id != viewerId && member.readMembershipId in readByMembershipIds && member.lastReadAt?.let { it > 0 } == true
    }.distinctBy { it.id }
}
