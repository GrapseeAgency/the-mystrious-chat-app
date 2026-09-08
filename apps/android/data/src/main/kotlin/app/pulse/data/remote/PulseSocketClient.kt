package app.pulse.data.remote

import android.util.Log
import app.pulse.protocol.JoinedAck
import app.pulse.protocol.PulseJson
import app.pulse.protocol.SocketEvents
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * REAL Socket.IO relay client — joins `user:{userId}` rooms exactly like the
 * web client (use-pulse-socket.ts): connect → emit join → receive joined ack
 * + presence snapshots; then message:new / message:deleted / message:read /
 * typing arrive as typed Signals. Auto-reconnect is engine.io default.
 */
class PulseSocketClient(
    @Suppress("unused") private val gatewayUrl: String,
) {
    sealed interface Signal {
        data class Joined(val onlineUserIds: List<String>) : Signal
        data class PresenceSnapshot(val onlineUserIds: List<String>) : Signal
        data class MessageNew(val conversationId: String, val raw: JSONObject) : Signal
        data class MessageDeleted(val conversationId: String, val messageId: String) : Signal
        data class MessageRead(val conversationId: String, val userId: String, val at: String?) : Signal
        data class Typing(val conversationId: String, val userId: String, val isTyping: Boolean) : Signal
        data class VoiceTranscript(val roomId: String, val speakerId: String, val text: String) : Signal
        data class CallSignal(val event: String, val raw: JSONObject) : Signal
    }

    private val _signals = MutableSharedFlow<Signal>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signals: SharedFlow<Signal> = _signals

    private val socket: Socket = IO.socket(app.pulse.core.PulseEndpoints.socketUrl)

    @Volatile private var joinedUserId: String? = null

    fun connect(userId: String) {
        joinedUserId = userId
        socket.on(Socket.EVENT_CONNECT) {
            socket.emit(SocketEvents.JOIN, JSONObject().put("userId", userId))
        }
        socket.on(SocketEvents.JOINED) { args ->
            runCatching {
                val ack = PulseJson.decodeFromString(
                    JoinedAck.serializer(),
                    (args.firstOrNull() as? JSONObject)?.toString() ?: "{}",
                )
                _signals.tryEmit(Signal.Joined(ack.onlineUserIds))
            }.onFailure { Log.w(TAG, "joined parse failed", it) }
        }
        socket.on(SocketEvents.PRESENCE_SNAPSHOT) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val ids = obj.optJSONArray("onlineUserIds") ?: return@on
            val list = buildList { for (i in 0 until ids.length()) add(ids.optString(i)) }
            _signals.tryEmit(Signal.PresenceSnapshot(list))
        }
        socket.on(SocketEvents.MESSAGE_NEW) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            _signals.tryEmit(Signal.MessageNew(obj.optString("conversationId"), obj))
        }
        socket.on(SocketEvents.TYPING) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            _signals.tryEmit(
                Signal.Typing(obj.optString("conversationId"), obj.optString("userId"), obj.optBoolean("isTyping")),
            )
        }
        socket.on(SocketEvents.MESSAGE_READ) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            _signals.tryEmit(
                Signal.MessageRead(obj.optString("conversationId"), obj.optString("userId"), obj.optString("at", null)),
            )
        }
        socket.on(SocketEvents.VOICE_TRANSCRIPT) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            _signals.tryEmit(
                Signal.VoiceTranscript(obj.optString("conversationId"), obj.optString("userId"), obj.optString("text")),
            )
        }
        listOf(SocketEvents.CALL_OFFER, SocketEvents.CALL_ANSWER, SocketEvents.CALL_ICE, SocketEvents.CALL_CANCEL, SocketEvents.CALL_HANGUP)
            .forEach { event ->
                socket.on(event) { args ->
                    (args.firstOrNull() as? JSONObject)?.let { _signals.tryEmit(Signal.CallSignal(event, it)) }
                }
            }
        socket.connect()
    }

    fun emitTyping(recipients: List<String>, conversationId: String, userId: String, userName: String, isTyping: Boolean) {
        val payload = JSONObject()
            .put("recipients", org.json.JSONArray(recipients))
            .put("conversationId", conversationId)
            .put("userId", userId)
            .put("userName", userName)
            .put("isTyping", isTyping)
        socket.emit(SocketEvents.TYPING, payload)
    }

    fun disconnect() {
        socket.disconnect()
        socket.off()
        joinedUserId = null
    }

    val isJoined: Boolean get() = joinedUserId != null && socket.connected()

    private companion object {
        const val TAG = "PulseSocket"
    }
}
