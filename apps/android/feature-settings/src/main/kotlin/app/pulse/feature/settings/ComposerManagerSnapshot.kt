package app.pulse.feature.settings

import app.pulse.domain.model.ComposerDraftToken
import app.pulse.domain.model.ComposerRecoveryEntry
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** Authority belongs to the displayed row, not whichever account is active at a later tap. */
class ComposerManagerOwner private constructor(
    val repository: PulseRepository,
    val sessionKey: String?,
    val ownerId: String?,
) {
    fun isCurrent(router: PulseRepository): Boolean =
        sessionKey != null && ownerId != null &&
            repository.attachmentSessionKey() == sessionKey && repository.viewerId == ownerId &&
            router.attachmentSessionKey() == sessionKey && router.viewerId == ownerId

    fun draft(conversationId: String, text: String) =
        ComposerManagerDraftSnapshot(this, repository.captureDraftToken(conversationId), text)

    companion object {
        fun capture(bound: PulseRepository) =
            ComposerManagerOwner(bound, bound.attachmentSessionKey(), bound.viewerId)
    }
}

/** Freeze the revision during observation. Recapturing it on Delete would bless a same-text ABA. */
data class ComposerManagerDraftSnapshot(
    val owner: ComposerManagerOwner,
    val token: ComposerDraftToken?,
    val text: String,
) {
    suspend fun clearIfCurrent(router: PulseRepository): Boolean {
        val captured = token ?: return false
        if (!owner.isCurrent(router) || captured.ownerId != owner.ownerId) return false
        return owner.repository.clearDraftIfUnchanged(captured, text)
    }
}

/** Both data and authority originate from one bound receiver. Never label an old DAO emission
 * with the router's new account. Switching invalidates cached rows before the new DAO emits. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> observeComposerManagerRows(
    router: PulseRepository,
    observe: (ComposerManagerOwner) -> Flow<List<T>>,
): Flow<List<T>> = router.observeAccountRepository().flatMapLatest { bound ->
    val owner = ComposerManagerOwner.capture(bound)
    if (owner.sessionKey == null || owner.ownerId == null) flowOf(emptyList<T>())
    else observe(owner).onStart { emit(emptyList()) }
}


/** A refreshed page (even with identical payloads) cannot renew an older confirmation. */
data class ComposerManagerRecoverySnapshot(
    val owner: ComposerManagerOwner,
    val entry: ComposerRecoveryEntry,
) {
    fun canAct(router: PulseRepository, displayed: List<ComposerManagerRecoverySnapshot>): Boolean =
        owner.isCurrent(router) && this in displayed
}
