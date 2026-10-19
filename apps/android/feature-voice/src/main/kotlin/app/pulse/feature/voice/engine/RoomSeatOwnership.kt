package app.pulse.feature.voice.engine

/** One relay voice seat may be shared by independent voice and stage membership. */
class RoomSeatOwnership {
    var roomId: String? = null
        private set
    var voiceJoined = false
        private set
    var stageJoined = false
        private set
    private var stageMaySpeak = false
    var microphoneEnabled = false
        private set
    private var permissionGeneration = 0L
    val mayEnableMicrophone get() = roomId != null && if (stageJoined) stageMaySpeak else voiceJoined
    val mayTransmit get() = mayEnableMicrophone && microphoneEnabled

    fun joinVoice(id: String): Boolean {
        if (id.isBlank() || (roomId != null && roomId != id)) return false
        roomId = id; voiceJoined = true
        return true
    }
    fun joinStage(id: String): Boolean {
        if (id.isBlank() || (roomId != null && roomId != id)) return false
        if (!stageJoined) { revokeMicrophone(); stageMaySpeak = false }
        roomId = id; stageJoined = true
        return true
    }
    fun stageRole(maySpeak: Boolean) {
        if (!stageJoined) return
        if (!maySpeak) revokeMicrophone()
        stageMaySpeak = maySpeak
    }
    fun requestMicrophone(): Long? = if (mayEnableMicrophone) ++permissionGeneration else null
    fun acceptsPermission(ticket: Long) = ticket == permissionGeneration && mayEnableMicrophone
    fun resolveMicrophone(ticket: Long, granted: Boolean): Boolean {
        if (ticket != permissionGeneration || !mayEnableMicrophone) return false
        microphoneEnabled = granted
        permissionGeneration++ // results are consumed once, including denial
        return granted
    }
    fun revokeMicrophone() { permissionGeneration++; microphoneEnabled = false }
    fun cancelPermission() { permissionGeneration++ }
    /** True means the last owner left: emit voice:leave and release audio. */
    fun leaveVoice(): Boolean {
        voiceJoined = false; revokeMicrophone()
        if (!stageJoined) roomId = null
        return roomId == null
    }
    fun leaveStage(): Boolean {
        stageJoined = false; stageMaySpeak = false; revokeMicrophone()
        if (!voiceJoined) roomId = null
        return roomId == null
    }
    fun clear() { leaveStage(); leaveVoice() }
}

/** Intent only: caller still checks permission, role, mute, connection and audio ownership. */
class PushToTalkPolicy {
    private var beganLatched = false
    private var cancelled = true
    fun press(transmitting: Boolean): Boolean {
        beganLatched = transmitting
        cancelled = false
        return !transmitting
    }
    fun release(heldMs: Long): Boolean = !cancelled && !beganLatched && heldMs < 260L
    fun cancel() { cancelled = true }
}
