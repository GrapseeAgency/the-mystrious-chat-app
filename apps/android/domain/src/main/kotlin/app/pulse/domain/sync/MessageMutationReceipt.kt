package app.pulse.domain.sync

import app.pulse.domain.model.Message

enum class MessageMutationKind { EDIT, PIN, DELETE }

/** Validate the action receipt, not just that a server returned some message. */
fun validateMessageMutationReceipt(requested: Message, incoming: Message, owner: String,
    kind: MessageMutationKind, content: String? = null) {
    check(incoming.id == requested.id && incoming.conversationId == requested.conversationId) {
        "The action returned an unrelated message."
    }
    when (kind) {
        MessageMutationKind.EDIT -> check(incoming.authorId == owner && !incoming.isDeleted &&
            incoming.editedAt != null && incoming.body == content) { "The message edit was not confirmed." }
        MessageMutationKind.DELETE -> check(incoming.authorId == owner && incoming.isDeleted) { "Message deletion was not confirmed." }
        MessageMutationKind.PIN -> check(!incoming.isDeleted) { "The pin update was not confirmed." }
    }
}

/** Tombstones win; an edit may advance only text when an unrelated event overtook
 * its HTTP response. Pin snapshots have no total revision and cannot rewind events. */
fun mergeMessageMutationReceipt(current: Message?, incoming: Message, kind: MessageMutationKind,
    superseded: Boolean): Message {
    val accepted = if (superseded && current != null) when (kind) {
        MessageMutationKind.EDIT -> if (current.isDeleted) current else current.copy(body = incoming.body, editedAt = incoming.editedAt)
        MessageMutationKind.PIN -> current
        MessageMutationKind.DELETE -> incoming
    } else incoming
    return mergeMessageState(current, accepted)
}
