package app.pulse.core.audio

/** In-process configuration ownership; OS audio focus remains a separate gate. */
class AudioOwnership {
    private var owner: String? = null
    @Synchronized fun acquire(token: String): Boolean {
        if (owner != null && owner != token) return false
        owner = token
        return true
    }
    @Synchronized fun release(token: String) {
        if (owner == token) owner = null
    }
    companion object { val shared = AudioOwnership() }
}

/** A speaker-off request must never resolve back to the loudspeaker. */
fun communicationRouteType(speaker: Boolean, speakerType: Int, receiverType: Int): Int =
    if (speaker) speakerType else receiverType
