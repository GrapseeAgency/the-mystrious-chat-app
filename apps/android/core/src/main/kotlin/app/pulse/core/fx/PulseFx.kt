package app.pulse.core.fx

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-wide particle-burst contract — the native translation of the web
 * `pulse:particle-burst` CustomEvent (src/lib/motion.ts). Any layer can
 * request a celebration; the UI host renders it. Zero emojis in chrome,
 * celebrations are content.
 */
object PulseFx {

    enum class BurstKind { CONFETTI, HEARTS, STARS, BURST }

    data class Burst(val kind: BurstKind, val count: Int = 80)

    private val _bursts = MutableSharedFlow<Burst>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val bursts: SharedFlow<Burst> = _bursts

    fun fire(kind: BurstKind, count: Int = 80) {
        _bursts.tryEmit(Burst(kind, count))
    }
}
