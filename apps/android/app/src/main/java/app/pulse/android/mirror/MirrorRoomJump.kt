package app.pulse.android.mirror

/** Room transitions can keep the outgoing room composed. A jump belongs to a
 * destination and one delivery, not every mounted room or later same-ID tap. */
internal data class MirrorRoomJump(
    val conversationId: String,
    val messageId: String,
    val deliveryId: String = java.util.UUID.randomUUID().toString(),
) {
    fun forRoom(displayedRoom: String, activeRoom: String?): MirrorRoomJump? =
        takeIf { conversationId == displayedRoom && conversationId == activeRoom }
}

internal data class MirrorReminderTarget(val conversationId: String, val messageId: String?) {
    companion object {
        fun from(conversationId: String?, messageId: String?): MirrorReminderTarget? {
            val room = conversationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return MirrorReminderTarget(room, messageId?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}
