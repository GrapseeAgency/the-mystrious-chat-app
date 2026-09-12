package app.pulse.data.remote

import android.util.Log
import app.pulse.core.PulseEndpoints
import app.pulse.protocol.CallAnswerDto
import app.pulse.protocol.CallCancelDto
import app.pulse.protocol.CallHangupDto
import app.pulse.protocol.CallIceDto
import app.pulse.protocol.CallOfferDto
import app.pulse.protocol.CallRejectDto
import app.pulse.protocol.toJsonObject
import app.pulse.protocol.CallSignalDto
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ConversationUpdatedPayload
import app.pulse.protocol.JoinedAck
import app.pulse.protocol.PresenceSnapshotPayload
import app.pulse.protocol.PulseJson
import app.pulse.protocol.ReadEventPayload
import app.pulse.protocol.SocketEvents
import app.pulse.protocol.SocketMessageEnvelope
import app.pulse.protocol.PulseVoiceUser
import app.pulse.protocol.SpaceStatePayload
import app.pulse.protocol.TypingPayload
import app.pulse.protocol.spaceJoinPayload
import app.pulse.protocol.spaceLeavePayload
import app.pulse.protocol.spaceMovePayload
import app.pulse.protocol.stageApprovePayload
import app.pulse.protocol.stageEndPayload
import app.pulse.protocol.stageHandPayload
import app.pulse.protocol.stageJoinPayload
import app.pulse.protocol.stageLeavePayload
import app.pulse.protocol.stageMutePayload
import app.pulse.protocol.voiceChunkPayload
import app.pulse.protocol.voiceJoinPayload
import app.pulse.protocol.voiceLeavePayload
import app.pulse.protocol.voicePttPayload
import app.pulse.protocol.voiceTranscriptPayload
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
        /**
         * Typed call:* envelope — the payload type is the OUTER-level union
         * (fully qualified: inside this nested class the bare name
         * `CallSignal` would resolve to the nested class itself).
         */
        data class CallSignal(val signal: app.pulse.data.remote.PulseSocketClient.CallSignal) : Signal
    }

    /**
     * Typed per-event call:* signal — each payload decodes into its EXACT
     * wire DTO (flat ICE triple, durationSec hangup). Wave-3 engine feed.
     */
    sealed interface CallSignal {
        val callId: String

        data class Offer(val dto: CallOfferDto) : CallSignal {
            override val callId: String get() = dto.callId
        }

        data class Answer(val dto: CallAnswerDto) : CallSignal {
            override val callId: String get() = dto.callId
        }

        data class Ice(val dto: CallIceDto) : CallSignal {
            override val callId: String get() = dto.callId
        }

        data class Reject(val dto: CallRejectDto) : CallSignal {
            override val callId: String get() = dto.callId
        }

        data class Cancel(val dto: CallCancelDto) : CallSignal {
            override val callId: String get() = dto.callId
        }

        data class Hangup(val dto: CallHangupDto) : CallSignal {
            override val callId: String get() = dto.callId
        }
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
        // Wave 5: stage:state / space:state carry the room object at TOP level
        // (verified against the relay), so the passthrough `state` field of the
        // DTO always decodes null. The Signal therefore carries the WHOLE raw
        // element — parseStageRoomState/parseSpaceBoardState handle both shapes.
        sock.on(SocketEvents.STAGE_STATE) { args ->
            decode<StageStatePayload>(args)?.let {
                _signals.tryEmit(Signal.StageState(it.conversationId, rawElement(args)))
            }
        }
        sock.on(SocketEvents.STAGE_ENDED) { args ->
            decode<StageEndedPayload>(args)?.let { _signals.tryEmit(Signal.StageEnded(it.conversationId)) }
        }
        sock.on(SocketEvents.SPACE_STATE) { args ->
            decode<SpaceStatePayload>(args)?.let {
                _signals.tryEmit(Signal.SpaceState(it.conversationId, rawElement(args)))
            }
        }
        // Typed call:* handlers — decode into the EXACT per-event DTO, wrap
        // into the outer-level union, emit. Inline (the decoder needs the
        // instance `decode` helper — a nested object cannot reach it).
        sock.on(SocketEvents.CALL_OFFER) { args ->
            decode<CallOfferDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(CallSignal.Offer(it))) }
        }
        sock.on(SocketEvents.CALL_ANSWER) { args ->
            decode<CallAnswerDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(CallSignal.Answer(it))) }
        }
        sock.on(SocketEvents.CALL_ICE) { args ->
            decode<CallIceDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(CallSignal.Ice(it))) }
        }
        sock.on(SocketEvents.CALL_REJECT) { args ->
            decode<CallRejectDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(CallSignal.Reject(it))) }
        }
        sock.on(SocketEvents.CALL_CANCEL) { args ->
            decode<CallCancelDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(CallSignal.Cancel(it))) }
        }
        sock.on(SocketEvents.CALL_HANGUP) { args ->
            decode<CallHangupDto>(args)?.let { _signals.tryEmit(Signal.CallSignal(CallSignal.Hangup(it))) }
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

    // ── Wave-3 call signaling emission (wire-perfect payloads) ─────

    /** call:offer — opens the ring; carries the caller's identity decoration. */
    fun emitCallOffer(payload: CallOfferDto) {
        emitCall(SocketEvents.CALL_OFFER, payload.toJsonObject())
    }

    /** call:answer — callee accepted, SDP answer attached. */
    fun emitCallAnswer(payload: CallAnswerDto) {
        emitCall(SocketEvents.CALL_ANSWER, payload.toJsonObject())
    }

    /** call:ice — flat candidate triple, trickled any time after the offer. */
    fun emitCallIce(payload: CallIceDto) {
        emitCall(SocketEvents.CALL_ICE, payload.toJsonObject())
    }

    /** call:reject — callee declined (busy / explicit). */
    fun emitCallReject(payload: CallRejectDto) {
        emitCall(SocketEvents.CALL_REJECT, payload.toJsonObject())
    }

    /** call:cancel — caller aborts while ringing. */
    fun emitCallCancel(payload: CallCancelDto) {
        emitCall(SocketEvents.CALL_CANCEL, payload.toJsonObject())
    }

    /** call:hangup — either side ends an ACTIVE call (durationSec on the wire). */
    fun emitCallHangup(payload: CallHangupDto) {
        emitCall(SocketEvents.CALL_HANGUP, payload.toJsonObject())
    }

    private fun emitCall(event: String, payload: kotlinx.serialization.json.JsonObject) {
        val sock = socket ?: return
        sock.emit(event, JSONObject(payload.toString()))
    }

    // ── Wave-5 voice/stage/space emission (wire-perfect payloads) ─────
    // Best-effort like every emit: a disconnected socket is a no-op (the
    // engines re-join on the next connect), never a throw.

    private fun emitRoomEvent(event: String, payload: kotlinx.serialization.json.JsonObject) {
        val sock = socket ?: return
        runCatching { sock.emit(event, JSONObject(payload.toString())) }
            .onFailure { Log.w(TAG, "emit $event failed", it) }
    }

    /** voice:join — registers this socket's seat; re-emitted on reconnect (VR-8). */
    fun emitVoiceJoin(conversationId: String, user: PulseVoiceUser) {
        emitRoomEvent(SocketEvents.VOICE_JOIN, voiceJoinPayload(conversationId, user))
    }

    fun emitVoiceLeave(conversationId: String) {
        emitRoomEvent(SocketEvents.VOICE_LEAVE, voiceLeavePayload(conversationId))
    }

    fun emitVoicePtt(conversationId: String, userId: String, on: Boolean) {
        emitRoomEvent(SocketEvents.VOICE_PTT, voicePttPayload(conversationId, userId, on))
    }

    fun emitVoiceChunk(conversationId: String, userId: String, seq: Long, data: String) {
        emitRoomEvent(SocketEvents.VOICE_CHUNK, voiceChunkPayload(conversationId, userId, seq, data))
    }

    fun emitVoiceTranscript(conversationId: String, userId: String, text: String) {
        emitRoomEvent(SocketEvents.VOICE_TRANSCRIPT, voiceTranscriptPayload(conversationId, userId, text))
    }

    /** stage:join — asHost:true is ONLY the claim-host path (ST-7). */
    fun emitStageJoin(conversationId: String, user: PulseVoiceUser, asHost: Boolean) {
        emitRoomEvent(SocketEvents.STAGE_JOIN, stageJoinPayload(conversationId, user, asHost))
    }

    fun emitStageHand(conversationId: String, userId: String, raised: Boolean) {
        emitRoomEvent(SocketEvents.STAGE_HAND, stageHandPayload(conversationId, userId, raised))
    }

    fun emitStageApprove(conversationId: String, byUserId: String, targetUserId: String) {
        emitRoomEvent(SocketEvents.STAGE_APPROVE, stageApprovePayload(conversationId, byUserId, targetUserId))
    }

    fun emitStageMute(conversationId: String, byUserId: String, targetUserId: String) {
        emitRoomEvent(SocketEvents.STAGE_MUTE, stageMutePayload(conversationId, byUserId, targetUserId))
    }

    fun emitStageEnd(conversationId: String, byUserId: String) {
        emitRoomEvent(SocketEvents.STAGE_END, stageEndPayload(conversationId, byUserId))
    }

    fun emitStageLeave(conversationId: String) {
        emitRoomEvent(SocketEvents.STAGE_LEAVE, stageLeavePayload(conversationId))
    }

    fun emitSpaceJoin(conversationId: String, user: PulseVoiceUser) {
        emitRoomEvent(SocketEvents.SPACE_JOIN, spaceJoinPayload(conversationId, user))
    }

    fun emitSpaceMove(conversationId: String, x: Double, y: Double) {
        emitRoomEvent(SocketEvents.SPACE_MOVE, spaceMovePayload(conversationId, x, y))
    }

    fun emitSpaceLeave(conversationId: String) {
        emitRoomEvent(SocketEvents.SPACE_LEAVE, spaceLeavePayload(conversationId))
    }

    /** Tolerant raw decode of the first event arg (stage/space room passthroughs). */
    private fun rawElement(args: Array<out Any?>): kotlinx.serialization.json.JsonElement? = runCatching {
        val raw = args.firstOrNull() ?: return@runCatching null
        PulseJson.parseToJsonElement(raw.toString())
    }.getOrNull()

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
