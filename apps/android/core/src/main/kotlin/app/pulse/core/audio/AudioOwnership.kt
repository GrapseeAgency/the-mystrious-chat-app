package app.pulse.core.audio

/** In-process ownership. Passive notes may yield; live rooms/calls/recording never do implicitly. */
class AudioOwnership {
    private var owner: String? = null
    private var stopPlayback: (() -> Unit)? = null

    @Synchronized fun acquire(token: String): Boolean {
        if (owner != null && owner != token) return false
        owner = token
        return true
    }

    @Synchronized fun acquirePlayback(token: String, stop: () -> Unit): Boolean {
        if (!acquire(token)) return false
        stopPlayback = stop
        return true
    }

    /** Called on the hardware owner's thread. Never invoke external code under the lock. */
    fun acquireExclusive(token: String): Boolean {
        val interrupt = synchronized(this) { if (owner != token) stopPlayback else null }
        if (interrupt != null) runCatching { interrupt() }
        // A competing exclusive owner may have acquired during the callback; recheck.
        return acquire(token)
    }

    @Synchronized fun release(token: String) {
        if (owner == token) { owner = null; stopPlayback = null }
    }
    companion object { val shared = AudioOwnership() }
}

/** A speaker-off request must never resolve back to the loudspeaker. */
fun communicationRouteType(speaker: Boolean, speakerType: Int, receiverType: Int): Int =
    if (speaker) speakerType else receiverType
