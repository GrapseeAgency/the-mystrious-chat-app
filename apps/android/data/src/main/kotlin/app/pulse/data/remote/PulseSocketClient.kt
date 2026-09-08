package app.pulse.data.remote

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * Socket.IO realtime client — joins the same relay the web app uses
 * (identity-gated, rate-limited). Event names are protocol-level contract:
 * `message:new`, `typing`, `presence`, `voice:transcript`, `call:*`.
 */
class PulseSocketClient(gatewayUrl: String, private val sessionCookieProvider: () -> String?) {

    sealed interface Signal {
        data class MessageNew(val conversationId: String, val payload: JSONObject) : Signal
        data class Typing(val conversationId: String, val userId: String, val typing: Boolean) : Signal
        data class Presence(val userId: String, val online: Boolean) : Signal
        data class VoiceTranscript(val roomId: String, val speakerId: String, val text: String) : Signal
    }

    private val _signals = MutableSharedFlow<Signal>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signals: SharedFlow<Signal> = _signals

    @Suppress("unused")
    private val socket: Socket = IO.socket(gatewayUrl, IO.Options().apply {
        // The gateway expects the session cookie (web parity); cookie is
        // injected per-connection in N2 when auth lands.
    })

    fun emitTyping(conversationId: String, typing: Boolean) {
        JSONObject().apply {
            put("conversationId", conversationId)
            put("typing", typing)
        }.let { payload ->
            // socket.emit("typing", payload) — connected in N2
        }
    }
}
