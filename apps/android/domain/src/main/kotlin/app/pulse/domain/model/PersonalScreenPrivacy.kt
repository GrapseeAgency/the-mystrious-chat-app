package app.pulse.domain.model

/** null is unknown, not permission to expose a background room. */
data class PersonalScreenPrivacy(val on: Boolean? = null, val pending: Boolean = false, val unreadable: Boolean = false)
