package app.pulse.android.mirror

import app.pulse.domain.repository.PulseRepository

/**
 * Section 14 item 1 - what the back-stack remembers across process death.
 * Only IDs (never model objects) are captured, together with the owner that
 * opened them, so a restored composition re-resolves everything fresh.
 */
internal data class SavedRoomNav(
    val roomId: String,
    val jumpId: String?,
    val navOwner: String?,
)

/**
 * Section 14 items 1+3 - pure navigation decisions over plain values so the
 * restoration and supersede contracts are JVM-testable without composition.
 */
internal object RoomNavRestoration {

    /**
     * Captured whenever the open room (or its jump anchor) changes. A null
     * open room captures null (home has nothing worth restoring).
     */
    fun capture(
        openRoomId: String?,
        viewerId: String?,
        jumpRoomId: String?,
        jumpMessageId: String?,
    ): SavedRoomNav? {
        val id = openRoomId ?: return null
        return SavedRoomNav(
            roomId = id,
            jumpId = jumpMessageId?.takeIf { jumpRoomId == id },
            navOwner = viewerId,
        )
    }

    /**
     * The one-shot process-death restore. Fires only when the saved nav
     * belongs to the CURRENT owner, no live room or in-flight open request
     * competes with it, and an identity is hydrated. Everything else yields
     * to a newer navigation choice (item 3). The caller consumes the saved
     * slots after this returns regardless of the verdict.
     */
    fun restore(
        saved: SavedRoomNav?,
        viewerId: String?,
        openRoomId: String?,
        openRequestActive: Boolean,
    ): String? {
        val nav = saved ?: return null
        if (openRoomId != null || openRequestActive) return null
        if (viewerId.isNullOrBlank() || nav.navOwner != viewerId) return null
        if (nav.roomId.isBlank()) return null
        return nav.roomId
    }
}

/**
 * The open request captured by [MirrorRoot.openById]; the ticket is a
 * monotonic sequence, and the api + ownerEpoch pins the account that was
 * bound at request time so a late resolution can never land in another
 * account's shell.
 */
internal data class MirrorRoomOpenRequest(
    val id: String,
    val ticket: Long,
    val jumpId: String?,
    val tab: MirrorTab,
    val previousRoomId: String?,
    val ownerId: String?,
    val api: PulseRepository,
    val personal: Boolean = false,
    val ownerEpoch: String? = api.attachmentSessionKey(),
) {
    /**
     * Section 14 item 3 - the competing-intent yield contract, extracted
     * from the resolution effect so it is unit-testable. A delayed
     * room/contact/search resolution must land ONLY when it is still the
     * newest request, still owned by the same account and epoch, and the
     * user has not navigated (tab) or opened/closed a room in the meantime.
     */
    fun stillCurrent(
        current: MirrorRoomOpenRequest?,
        viewerId: String?,
        repository: PulseRepository,
        tab: MirrorTab,
        openRoomId: String?,
    ): Boolean {
        if (current != this) return false
        val owner = ownerId ?: return false
        val epoch = ownerEpoch ?: return false
        if (viewerId != owner || api.viewerId != owner) return false
        if (api.attachmentSessionKey() != epoch || repository.attachmentSessionKey() != epoch) return false
        if (tab != this.tab || openRoomId != previousRoomId) return false
        return true
    }
}
