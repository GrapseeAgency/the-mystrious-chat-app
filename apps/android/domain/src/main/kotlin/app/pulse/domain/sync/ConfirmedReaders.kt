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

/** Info describes recipients of THIS message, not everyone except the device
 * owner. On incoming messages the current viewer is a recipient too. */
data class MessageInfoReaders(
    val seenBy: List<ConversationMember>,
    val unconfirmed: List<ConversationMember>,
)

fun messageInfoReaders(
    members: List<ConversationMember>, viewerId: String?, senderId: String,
    createdAtMillis: Long, readByMembershipIds: List<String>,
): MessageInfoReaders {
    if (viewerId.isNullOrBlank() || senderId.isBlank()) return MessageInfoReaders(emptyList(), emptyList())
    val recipients = members.filter { it.id != senderId }.distinctBy { it.id }
    val seen = confirmedReaders(recipients, senderId, createdAtMillis, readByMembershipIds)
    val confirmed = seen.mapTo(mutableSetOf()) { it.id }
    return MessageInfoReaders(seen, recipients.filterNot { it.id in confirmed })
}
