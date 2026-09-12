import Foundation
import SocketIO

/// REAL Socket.IO relay client — mirrors Android PulseSocketClient and the
/// web use-pulse-socket.ts: connect → emit join → typed signal stream.
///
/// Wave 0 power-up: subscribes the FULL S→C event set from
/// packages/protocol/src/contracts.ts (SERVER_EVENTS), re-emits `join` on
/// every (re)connect (socket.io always fires a fresh `.connect` after a
/// successful reconnect), and surfaces the connection state as a signal.
public final class PulseSocketClient {
    public enum Signal {
        case connectionState(connected: Bool)
        case joined(onlineUserIds: [String])
        case presenceSnapshot(onlineUserIds: [String])
        case messageNew(conversationId: String, raw: [String: Any])
        /// N3-b — payload is { type, message (tombstoned), recipientIds, conversationId }.
        case messageDeleted(conversationId: String, raw: [String: Any])
        /// N3-b — payload is { type, message (fresh reactions), ... }.
        case messageReact(conversationId: String, raw: [String: Any])
        case messageRead(conversationId: String, userId: String, lastReadAt: String?)
        /// Wave 0 — the rest of the message:* relay family (edited / pinned /
        /// viewed / poll:voted / link:preview / translation:added). The payload
        /// is the same SocketMessageEvent envelope; features decode it later.
        case messageEnvelope(event: String, conversationId: String, raw: [String: Any])
        /// Wave 0 — { conversationId, conversation?: WireConversationSummary }.
        case conversationUpdated(conversationId: String, raw: [String: Any])
        case typing(conversationId: String, userId: String, userName: String, isTyping: Bool)
        // Voice rooms (R21-b).
        case voiceRoster(conversationId: String, roster: [[String: Any]])
        case voicePtt(conversationId: String, userId: String, active: Bool)
        case voiceChunk(conversationId: String, userId: String, seq: Int, data: String)
        case voiceTranscript(roomId: String, speakerId: String, text: String)
        // Stage + spatial rooms.
        case stageState(conversationId: String, raw: [String: Any])
        case stageEnded(conversationId: String)
        case spaceState(conversationId: String, raw: [String: Any])
        // Call signaling (generic — includes call:reject).
        case callSignal(event: String, raw: [String: Any])
    }

    private let manager: SocketManager
    private let socket: SocketIOClient
    /// Last joined identity — re-emitted on every (re)connect.
    private var lastUserId: String?

    public init(socketURL: URL) {
        // Reconnect backoff — W0-PLAN parity with Android (800ms → 5s cap;
        // the Swift client is integer-seconds: 1s → exponential 1.5^n with
        // jitter, clamped at 5s). reconnects(true) keeps retrying forever.
        // `.connectParams` appends XTransformPort=3003 to every poll/WS request
        // — the edge routes on the query (the /socket.io/ path rule 308s and
        // would break WS upgrades). Mirrors Android PulseSocketClient exactly;
        // a direct relay server ignores the extra query key.
        manager = SocketManager(
            socketURL: socketURL,
            config: [
                .log(false), .reconnects(true),
                .reconnectWait(1), .reconnectWaitMax(5),
                .connectParams(["XTransformPort": "3003"]),
            ],
        )
        socket = manager.defaultSocket
    }

    public var signals: ((Signal) -> Void)?

    public func connect(userId: String) {
        lastUserId = userId

        socket.on(clientEvent: .connect) { [weak self] _, _ in
            guard let self else { return }
            // Re-join on the FIRST connect and on every successful reconnect
            // (socket.io fires a fresh .connect for each one). Without the
            // re-emit the server drops us from presence + user rooms.
            if let lastUserId = self.lastUserId {
                self.socket.emit("join", ["userId": lastUserId])
            }
            self.signals?(.connectionState(connected: true))
        }
        socket.on(clientEvent: .disconnect) { [weak self] _, _ in
            self?.signals?(.connectionState(connected: false))
        }
        socket.on(clientEvent: .reconnect) { [weak self] _, _ in
            // The transport just dropped and a retry is scheduled (v16 fires
            // `.reconnect` when the socket ENTERS the reconnecting state).
            // Emits made while disconnected are buffered and flushed the
            // moment the reconnect lands — re-joining here restores the user
            // room even if the .connect handler below were to race.
            guard let self, let lastUserId = self.lastUserId else { return }
            self.socket.emit("join", ["userId": lastUserId])
        }

        socket.on("joined") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any],
                  let ids = obj["onlineUserIds"] as? [String] else { return }
            self?.signals?(.joined(onlineUserIds: ids))
        }
        socket.on("presence:snapshot") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any],
                  let ids = obj["onlineUserIds"] as? [String] else { return }
            self?.signals?(.presenceSnapshot(onlineUserIds: ids))
        }

        // ── message relay family ──────────────────────────────
        socket.on("message:new") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageNew(conversationId: Self.conversationId(in: obj), raw: obj))
        }
        socket.on("message:deleted") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageDeleted(conversationId: Self.conversationId(in: obj), raw: obj))
        }
        socket.on("message:react") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageReact(conversationId: Self.conversationId(in: obj), raw: obj))
        }
        for relayEvent in ["message:edited", "message:pinned", "message:viewed", "poll:voted", "link:preview", "translation:added"] {
            socket.on(relayEvent) { [weak self] data, _ in
                guard let obj = data.first as? [String: Any] else { return }
                self?.signals?(.messageEnvelope(event: relayEvent, conversationId: Self.conversationId(in: obj), raw: obj))
            }
        }
        socket.on("conversation:updated") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            let conversationId = (obj["conversationId"] as? String)
                ?? (obj["conversation"] as? [String: Any]).flatMap { $0["id"] as? String }
                ?? ""
            self?.signals?(.conversationUpdated(conversationId: conversationId, raw: obj))
        }

        socket.on("message:read") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            // W1-DATA-B — the watermark model: the relay carries the bumped
            // participant lastReadAt; parse it tolerantly (older relays omit
            // it and callers fall back to "now").
            self?.signals?(.messageRead(
                conversationId: obj["conversationId"] as? String ?? "",
                userId: obj["userId"] as? String ?? "",
                lastReadAt: obj["lastReadAt"] as? String,
            ))
        }
        socket.on("typing") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.typing(
                conversationId: obj["conversationId"] as? String ?? "",
                userId: obj["userId"] as? String ?? "",
                userName: obj["userName"] as? String ?? "",
                isTyping: obj["isTyping"] as? Bool ?? false,
            ))
        }

        // ── voice rooms ───────────────────────────────────────
        socket.on("voice:roster") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            let roster = obj["roster"] as? [[String: Any]] ?? []
            self?.signals?(.voiceRoster(
                conversationId: obj["conversationId"] as? String ?? "",
                roster: roster,
            ))
        }
        socket.on("voice:ptt") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            // W5-f — the relay emits `on` (voice:ptt { conversationId, userId,
            // on }, mini-services/pulse-socket/index.ts L922-926); the legacy
            // `active` key stays a tolerant fallback so the speaking ring
            // never silently dies on either shape.
            self?.signals?(.voicePtt(
                conversationId: obj["conversationId"] as? String ?? "",
                userId: obj["userId"] as? String ?? "",
                active: (obj["on"] as? Bool) ?? (obj["active"] as? Bool) ?? false,
            ))
        }
        socket.on("voice:chunk") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.voiceChunk(
                conversationId: obj["conversationId"] as? String ?? "",
                userId: obj["userId"] as? String ?? "",
                seq: obj["seq"] as? Int ?? 0,
                data: obj["data"] as? String ?? "",
            ))
        }
        socket.on("voice:transcript") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.voiceTranscript(
                roomId: obj["conversationId"] as? String ?? "",
                speakerId: obj["userId"] as? String ?? "",
                text: obj["text"] as? String ?? "",
            ))
        }

        // ── stage + spatial rooms ─────────────────────────────
        socket.on("stage:state") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.stageState(conversationId: obj["conversationId"] as? String ?? "", raw: obj))
        }
        socket.on("stage:ended") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.stageEnded(conversationId: obj["conversationId"] as? String ?? ""))
        }
        socket.on("space:state") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.spaceState(conversationId: obj["conversationId"] as? String ?? "", raw: obj))
        }

        // ── call signaling (offer/answer/ice/reject/cancel/hangup) ──
        for event in ["call:offer", "call:answer", "call:ice", "call:reject", "call:cancel", "call:hangup"] {
            socket.on(event) { [weak self] data, _ in
                guard let obj = data.first as? [String: Any] else { return }
                self?.signals?(.callSignal(event: event, raw: obj))
            }
        }

        socket.connect()
    }

    public func emitTyping(recipients: [String], conversationId: String, userId: String, userName: String, isTyping: Bool) {
        socket.emit("typing", [
            "recipients": recipients,
            "conversationId": conversationId,
            "userId": userId,
            "userName": userName,
            "isTyping": isTyping,
        ])
    }

    /// W3-b — emit one C→S call signaling event (call:offer / call:answer /
    /// call:ice / call:reject / call:cancel / call:hangup). The payload shape
    /// is built by CallWire (unit-tested against the relay's validation).
    /// Emits made while disconnected are buffered by socket.io and flushed on
    /// the next reconnect (same semantics the join re-emit relies on).
    public func emitCallSignal(event: String, payload: [String: Any]) {
        socket.emit(event, payload)
    }

    // ── W5-f — voice rooms / stage / space (typed emit helpers) ──
    //
    // All 14 C→S rooms events. Payloads come from VoiceRoomWire (pure,
    // unit-tested in VoiceRoomWireTests — the EXACT shapes the relay
    // validates). Best-effort like emitTyping: never throws, emits made
    // while disconnected are buffered by socket.io and flushed on the
    // next reconnect.

    public func emitVoiceJoin(conversationId: String, userId: String, name: String, username: String?, color: String?) {
        socket.emit(PulseSocketEvents.voiceJoin.rawValue, VoiceRoomWire.voiceJoin(
            conversationId: conversationId, userId: userId, name: name, username: username, color: color,
        ))
    }

    public func emitVoiceLeave(conversationId: String) {
        socket.emit(PulseSocketEvents.voiceLeave.rawValue, VoiceRoomWire.voiceLeave(conversationId: conversationId))
    }

    public func emitVoicePtt(conversationId: String, userId: String, on: Bool) {
        socket.emit(PulseSocketEvents.voicePtt.rawValue, VoiceRoomWire.voicePtt(
            conversationId: conversationId, userId: userId, on: on,
        ))
    }

    public func emitVoiceChunk(conversationId: String, userId: String, seq: Int, data: String) {
        socket.emit(PulseSocketEvents.voiceChunk.rawValue, VoiceRoomWire.voiceChunk(
            conversationId: conversationId, userId: userId, seq: seq, data: data,
        ))
    }

    public func emitVoiceTranscript(conversationId: String, userId: String, text: String) {
        socket.emit(PulseSocketEvents.voiceTranscript.rawValue, VoiceRoomWire.voiceTranscript(
            conversationId: conversationId, userId: userId, text: text,
        ))
    }

    public func emitStageJoin(conversationId: String, userId: String, name: String, username: String?, color: String?, asHost: Bool) {
        socket.emit(PulseSocketEvents.stageJoin.rawValue, VoiceRoomWire.stageJoin(
            conversationId: conversationId, userId: userId, name: name, username: username, color: color, asHost: asHost,
        ))
    }

    public func emitStageHand(conversationId: String, userId: String, raised: Bool) {
        socket.emit(PulseSocketEvents.stageHand.rawValue, VoiceRoomWire.stageHand(
            conversationId: conversationId, userId: userId, raised: raised,
        ))
    }

    public func emitStageApprove(conversationId: String, byUserId: String, targetUserId: String) {
        socket.emit(PulseSocketEvents.stageApprove.rawValue, VoiceRoomWire.stageApprove(
            conversationId: conversationId, byUserId: byUserId, targetUserId: targetUserId,
        ))
    }

    public func emitStageMute(conversationId: String, byUserId: String, targetUserId: String) {
        socket.emit(PulseSocketEvents.stageMute.rawValue, VoiceRoomWire.stageMute(
            conversationId: conversationId, byUserId: byUserId, targetUserId: targetUserId,
        ))
    }

    public func emitStageEnd(conversationId: String, byUserId: String) {
        socket.emit(PulseSocketEvents.stageEnd.rawValue, VoiceRoomWire.stageEnd(
            conversationId: conversationId, byUserId: byUserId,
        ))
    }

    public func emitStageLeave(conversationId: String) {
        socket.emit(PulseSocketEvents.stageLeave.rawValue, VoiceRoomWire.stageLeave(conversationId: conversationId))
    }

    public func emitSpaceJoin(conversationId: String, userId: String, name: String, username: String?, color: String?) {
        socket.emit(PulseSocketEvents.spaceJoin.rawValue, VoiceRoomWire.spaceJoin(
            conversationId: conversationId, userId: userId, name: name, username: username, color: color,
        ))
    }

    public func emitSpaceMove(conversationId: String, x: Double, y: Double) {
        socket.emit(PulseSocketEvents.spaceMove.rawValue, VoiceRoomWire.spaceMove(
            conversationId: conversationId, x: x, y: y,
        ))
    }

    public func emitSpaceLeave(conversationId: String) {
        socket.emit(PulseSocketEvents.spaceLeave.rawValue, VoiceRoomWire.spaceLeave(conversationId: conversationId))
    }

    /// W5-f — generic rooms-event funnel for the session model (the socket
    /// is session-owned; mirrors emitCallSignal). Payloads are built by the
    /// pure VoiceRoomWire builders so the shape stays unit-tested.
    public func emitRoomSignal(event: String, payload: [String: Any]) {
        socket.emit(event, payload)
    }

    public func disconnect() {
        lastUserId = nil
        socket.disconnect()
        socket.removeAllHandlers()
    }

    /// SocketMessageEvent envelope: conversationId may sit at the top level or
    /// only inside `message` (tolerant, matching the relay's shapes).
    private static func conversationId(in obj: [String: Any]) -> String {
        if let direct = obj["conversationId"] as? String { return direct }
        let message = obj["message"] as? [String: Any]
        return message?["conversationId"] as? String ?? ""
    }
}
