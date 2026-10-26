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
