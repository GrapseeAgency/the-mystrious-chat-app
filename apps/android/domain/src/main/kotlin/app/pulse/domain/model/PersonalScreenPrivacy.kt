package app.pulse.domain.model

/** null is unknown, not permission to expose a background room.
 *
 * [origin] - Section 13 item 6 provenance (additive): "intent" when the row
 * was produced by this device's explicit write, "migration-cache" when it was
 * loaded from storage that predates origin tracking (it cannot prove which
 * explicit user intent produced it), null when unknown/unreadable. */
data class PersonalScreenPrivacy(
    val on: Boolean? = null,
    val pending: Boolean = false,
    val unreadable: Boolean = false,
    val origin: String? = null,
)
