package app.pulse.domain.sync

import app.pulse.domain.model.Message

fun threadRootAvailable(root: Message?, roomId: String, rootId: String, now: Long = System.currentTimeMillis()): Boolean =
    root != null && root.id == rootId && root.conversationId == roomId && root.threadRootId == null &&
        !root.isDeleted && (root.expiresAtEpochMs?.let { it > now } != false)

/** Transport errors retain offline history. Explicit denial must close it until revalidated. */
fun threadAccessDenied(status: Int?): Boolean = status == 403 || status == 404 || status == 410
