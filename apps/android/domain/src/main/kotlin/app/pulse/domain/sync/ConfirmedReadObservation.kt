package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX

/** Only a validated exact HTTP ACK may call this; enqueue/refusal are not proof. */
fun confirmedReadObservation(current: Message?, room: String, messageId: String, intent: String): Message? {
    val parts = intent.split('.')
    if (current == null || current.conversationId != room || current.id != messageId || current.id.startsWith(TEMP_MESSAGE_PREFIX) ||
        parts.size != 2 || parts.any { it.isBlank() } || parts[1] in current.readByMembershipIds) return null
    return current.copy(readByMembershipIds = current.readByMembershipIds + parts[1])
}

/** Broadcast DTOs omit private reader evidence; omission is not an owner-scoped
 * negative proof. Retain ONLY the current owner's already-confirmed membership.
 * Never use this for canonical GETs or union arbitrary cached peer arrays. */
fun retainOwnSocketReadObservation(current: Message?, incoming: Message, membership: String?): Message {
    if (membership.isNullOrBlank() || current == null || current.id != incoming.id ||
        current.conversationId != incoming.conversationId || incoming.id.startsWith(TEMP_MESSAGE_PREFIX) ||
        current.deletedAt != null || incoming.deletedAt != null ||
        membership !in current.readByMembershipIds || membership in incoming.readByMembershipIds) return incoming
    return incoming.copy(readByMembershipIds = incoming.readByMembershipIds + membership)
}

/** Sending/editing a message is not a new read observation. Preserve the current
 * exact proof (including a canonical negative) while accepting the action fields.
 * Canonical GETs and dedicated read receipts must NOT use this merge. */
fun retainAcknowledgedReadState(current: Message?, incoming: Message): Message {
    if (current == null || current.id != incoming.id || current.conversationId != incoming.conversationId ||
        incoming.id.startsWith(TEMP_MESSAGE_PREFIX)) return incoming
    return incoming.copy(readByMembershipIds = current.readByMembershipIds)
}
