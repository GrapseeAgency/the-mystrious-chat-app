package app.pulse.data.remote

import android.util.Log
import app.pulse.core.PulseEndpoints
import app.pulse.protocol.CallSignalDto
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationUpdatedPayload
import app.pulse.protocol.JoinedAck
import app.pulse.protocol.PresenceSnapshotPayload
import app.pulse.protocol.PulseJson
import app.pulse.protocol.ReadEventPayload
import app.pulse.protocol.SocketEvents
import app.pulse.protocol.SocketMessageEnvelope
import app.pulse.protocol.SpaceStatePayload
import app.pulse.protocol.TypingPayload
import app.pulse.protocol.StageEndedPayload
import app.pulse.protocol.StageStatePayload
import app.pulse.protocol.VoiceChunkPayload
import app.pulse.protocol.VoicePeerDto
import app.pulse.protocol.VoicePttPayload
import app.pulse.protocol.VoiceRosterPayload
import app.pulse.protocol.VoiceTranscriptPayload
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * REAL Socket.IO relay client — joins `user:{userId}` rooms exactly like the
 * web client (use-pulse-socket.ts): connect → emit join → receive joined ack
 * + presence snapshots; then the FULL S→C registry from contracts.ts arrives
 * as typed Signals.
 *
 * Wave-0 power-up:
 *  - connects DIRECTLY to [socketUrl] (the XTransformPort sandbox hack is gone);
 *  - engine.io reconnection: on, 800ms → 5s cap;
 *  - `join {userId}` re-emits on EVERY (re)connect with the last joined id;
 *  - [Signal.Connection] surfaces connect/disconnect/connect-error;
 *  - every payload decodes through tolerant @Serializable DTOs (PulseJson).
 * A blank base keeps realtime off entirely (offline-first, zero reconnect spam).
 */
class PulseSocketClient(
    private val socketUrl: String,
) {
    sealed interface Signal {
        /** Realtime transport state — true on connect, false on disconnect/error. */
        data class Connection(val connected: Boolean) : Signal
        data class Joined(val onlineUserIds: List<String>) : Signal
        data class PresenceSnapshot(val onlineUserIds: List<String>) : Signal

        /**
         * The message:* envelope family (message:new/deleted/react/edited/pinned/
         * viewed, poll:voted, link:preview, translation:added) — [event] is the
         * wire event name, [dto] the authoritative (already decoded) row.
         */
        data class MessageEnvelope(val event: String, val conversationId: String, val dto: ChatMessageDto?) : Signal
        data class Read(val conversationId: String, val userId: String, val lastReadAt: String?) : Signal
        data class ConversationUpdated(val conversationId: String) : Signal
        data class Typing(val conversationId: String, val userId: String, val userName: String, val isTyping: Boolean) : Signal
        data class VoiceRoster(val conversationId: String, val roster: List<VoicePeerDto>) : Signal
        data class VoicePtt(val conversationId: String, val userId: String, val active: Boolean) : Signal
        data class VoiceChunk(val conversationId: String, val userId: String, val seq: Long, val data: String) : Signal
        data class VoiceTranscript(val conversationId: String, val speakerId: String, val text: String) : Signal
        data class StageState(val conversationId: String, val state: kotlinx.serialization.json.JsonElement?) : Signal
        data class StageEnded(val conversationId: String) : Signal
        data class SpaceState(val conversationId: String, val state: kotlinx.serialization.json.JsonElement?) : Signal
        data class CallSignal(val event: String, val payload: CallSignalDto) : Signal
    }

    private val _signals = MutableSharedFlow<Signal>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signals: SharedFlow<Signal> = _signals

    @Volatile private var socket: Socket? = null

    @Volatile private var joinedUserId: String? = null

    /**
     * Effective relay base — the injected default wins when set, but a live
     * manifest override (PulseEndpoints.socketUrl retargeted at runtime) is
     * picked up on every connect without recreating the singleton.
     */
    private fun effectiveBase(): String = socketUrl.ifBlank { PulseEndpoints.socketUrl }

    fun connect(userId: String) {
        joinedUserId = userId
        val base = effectiveBase()
        if (base.isBlank()) {
            Log.i(TAG, "Realtime disabled — no relay base baked; staying offline-first")
            return
        }
        if (socket?.connected() == true) return
        disconnectSocket() // clean slate — no double handlers across reconnects

        val sock = IO.socket(
            base,
            IO.Options().apply {
                reconnection = true
                reconnectionDelay = 800L
                reconnectionDelayMax = 5_000L
                // Edge-gateway routing: the query route connects where the
                // /socket.io/ path rule 308-redirects and breaks WS upgrades
                // (verified empirically). A direct relay ignores the extra key.
                query = "XTransformPort=3003"
            },
        )

        // EVENT_CONNECT fires on every (re)connection — the join re-emission is
        // what restores the user room after a network blip.
        sock.on(Socket.EVENT_CONNECT) {
            _signals.tryEmit(Signal.Connection(true))
            joinedUserId?.let { id ->
                sock.emit(SocketEvents.JOIN, JSONObject().put("userId", id))
            }
        }
        sock.on(Socket.EVENT_DISCONNECT) { _signals.tryEmit(Signal.Connection(false)) }
        sock.on(Socket.EVENT_CONNECT_ERROR) {
            Log.w(TAG, "connect error: ${it.firstOrNull()}")
            _signals.tryEmit(Signal.Connection(false))
        }

        sock.on(SocketEvents.JOINED) { args ->
            decode<JoinedAck>(args)?.let { _signals.tryEmit(Signal.Joined(it.onlineUserIds)) }
        }
        sock.on(SocketEvents.PRESENCE_SNAPSHOT) { args ->
            decode<PresenceSnapshotPayload>(args)?.let { _signals.tryEmit(Signal.PresenceSnapshot(it.onlineUserIds)) }
        }
        for (event in SocketEvents.MESSAGE_ENVELOPE_EVENTS) {
            sock.on(event) { args ->
                decode<SocketMessageEnvelope>(args)?.let { envelope ->
                    _signals.tryEmit(
                        Signal.MessageEnvelope(
                            event = event,
                            conversationId = envelope.conversationId ?: envelope.message?.conversationId ?: "",
                            dto = envelope.message,
                        ),
                    )
                }
            }
        }
        sock.on(SocketEvents.MESSAGE_READ) { args ->
            decode<ReadEventPayload>(args)?.let {
                _signals.tryEmit(Signal.Read(it.conversationId, it.userId, it.lastReadAt))
            }
        }
        sock.on(SocketEvents.CONVERSATION_UPDATED) { args ->
            decode<ConversationUpdatedPayload>(args)?.let {
                _signals.tryEmit(Signal.ConversationUpdated(it.conversationId))
            }
        }
        sock.on(SocketEvents.TYPING) { args ->
            decode<TypingPayload>(args)?.let {
                _signals.tryEmit(Signal.Typing(it.conversationId, it.userId, it.userName, it.isTyping))
            }
        }
        sock.on(SocketEvents.VOICE_ROSTER) { args ->
            decode<VoiceRosterPayload>(args)?.let { _signals.tryEmit(Signal.VoiceRoster(it.conversationId, it.roster)) }
        }
        sock.on(SocketEvents.VOICE_PTT) { args ->
            decode<VoicePttPayload>(args)?.let { _signals.tryEmit(Signal.VoicePtt(it.conversationId, it.userId, it.active)) }
        }
        sock.on(SocketEvents.VOICE_CHUNK) { args ->
            decode<VoiceChunkPayload>(args)?.let { _signals.tryEmit(Signal.VoiceChunk(it.conversationId, it.userId, it.seq, it.data)) }
        }
        sock.on(SocketEvents.VOICE_TRANSCRIPT) { args ->
            decode<VoiceTranscriptPayload>(args)?.let { _signals.tryEmit(Signal.VoiceTranscript(it.conversationId, it.userId, it.text)) }
        }
        sock.on(SocketEvents.STAGE_STATE) { args ->
            decode<StageStatePayload>(args)?.let { _signals.tryEmit(Signal.StageState(it.conversationId, it.state)) }
        }
        sock.on(SocketEvents.STAGE_ENDED) { args ->
            decode<StageEndedPayload>(args)?.let { _signals.tryEmit(Signal.StageEnded(it.conversationId)) }
        }
        sock.on(SocketEvents.SPACE_STATE) { args ->
            decode<SpaceStatePayload>(args)?.let { _signals.tryEmit(Signal.SpaceState(it.conversationId, it.state)) }
        }
        for (event in listOf(
            SocketEvents.CALL_OFFER, SocketEvents.CALL_ANSWER, SocketEvents.CALL_ICE,
            SocketEvents.CALL_REJECT, SocketEvents.CALL_CANCEL, SocketEvents.CALL_HANGUP,
        )) {
            sock.on(event) { args ->
                decode<CallSignalDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(event, it)) }
            }
        }

        socket = sock
        sock.connect()
    }

    /** Tolerant DTO decode for the first (only) payload arg of a relay event. */
    private inline fun <reified T> decode(args: Array<out Any?>): T? = runCatching {
        val raw = args.firstOrNull() ?: return@runCatching null
        val json = when (raw) {
            is JSONObject -> raw.toString()
            is String -> raw
            else -> raw.toString()
        }
        PulseJson.decodeFromString<T>(json)
    }.onFailure { Log.w(TAG, "payload decode failed", it) }.getOrNull()

    fun emitTyping(recipients: List<String>, conversationId: String, userId: String, userName: String, isTyping: Boolean) {
        val payload = JSONObject()
            .put("recipients", org.json.JSONArray(recipients))
            .put("conversationId", conversationId)
            .put("userId", userId)
            .put("userName", userName)
            .put("isTyping", isTyping)
        socket?.emit(SocketEvents.TYPING, payload)
    }

    fun disconnect() {
        disconnectSocket()
        joinedUserId = null
    }

    private fun disconnectSocket() {
        socket?.let {
            it.off()
            it.disconnect()
        }
        socket = null
    }

    val isJoined: Boolean get() = joinedUserId != null && socket?.connected() == true

    private companion object {
        const val TAG = "PulseSocket"
    }
}
