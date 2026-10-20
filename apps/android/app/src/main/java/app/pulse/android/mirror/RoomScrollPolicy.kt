package app.pulse.android.mirror

/** Page prepends preserve the anchor; only a new tail may request following. */
internal fun shouldFollowMessageTail(
    previousCount: Int,
    lastVisibleIndex: Int,
    previousTailId: String?,
    tailId: String?,
    ownMessage: Boolean,
    jumpActive: Boolean,
): Boolean {
    if (jumpActive || tailId == null) return false
    if (previousCount == 0) return true
    if (tailId == previousTailId) return false
    return ownMessage || lastVisibleIndex >= previousCount - 2
}
