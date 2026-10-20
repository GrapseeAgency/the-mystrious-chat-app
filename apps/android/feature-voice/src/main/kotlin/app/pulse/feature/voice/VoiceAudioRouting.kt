package app.pulse.feature.voice

/** A received socket event alone is not permission to play audio. */
internal fun acceptsRoomAudio(ownedRoom: String?, eventRoom: String, selfId: String, speakerId: String): Boolean =
    !ownedRoom.isNullOrBlank() && ownedRoom == eventRoom && speakerId != selfId
