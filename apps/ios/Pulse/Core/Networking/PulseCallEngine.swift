import Foundation
import Combine

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 call engine (the native useCallSession hook).
//
// Owns the call state machine, the WebRTC seam, the call:* signaling and
// the single-writer call-log write path. Behavioral spec = the web
// call-overlay.tsx hook; the relay semantics (30s ring timeout, busy map,
// offline/busy cancel, reject routing, disconnect teardown) live in
// mini-services/pulse-socket — this engine mirrors, never re-implements.
//
// Seams (tests substitute fakes — the same pattern as PulseOutboxSending):
//   • PulseCallSignalingSending — emits call:* envelopes
//   • PulseCallMediaProviding   — mic permission + peer connection factory
//   • PulseCallPeerConnecting   — one live peer connection
//
// Single-writer rule: the CALLER's client writes every terminal row
// (completed | missed | declined) via POST /api/calls — the callee never
// writes. Network-failed rows queue in PulseStore.callLogQueue and flush on
// socket reconnect / app start (PulseOutboxEngine trigger style).
// ─────────────────────────────────────────────────────────────

@MainActor
public protocol PulseCallSignalingSending: AnyObject {
    /// Emits one C→S call:* event (call:offer/answer/ice/reject/cancel/hangup).
    func send(event: String, payload: [String: Any])
}

@MainActor
public final class PulseCallEngine: ObservableObject {
    // ── published UI state (CallView binds these) ────────────
    @Published public private(set) var state: CallState = .idle
    @Published public private(set) var activePeer: CallPeer?
    /// Live duration while connecting/connected (ticks from the answer, web parity).
    @Published public private(set) var durationSec: Int = 0
    @Published public private(set) var micEnabled = true
    @Published public private(set) var speakerOn = true
    /// Non-nil = the honest glass error card replaces the call UI.
    @Published public private(set) var errorText: String?
    /// Ended-card text ('No answer', 'Declined', 'Call ended · 0:42', …).
    @Published public private(set) var summary: String?

    /// Honest mic-denied copy (web parity wording, native settings path).
    public static let micDeniedMessage =
        "Microphone access is needed for calls. Enable it for Pulse in Settings → Privacy → Microphone."

    private let machine = CallStateMachine()
    private let store: PulseStore
    private let viewer: PulseViewer
    private let signaling: any PulseCallSignalingSending
    private let media: any PulseCallMediaProviding
    private let apiProvider: () -> PulseAPIClient?
    private let toasts: ToastCenter

    // ── live media state (cleared on every teardown) ─────────
    private var pc: (any PulseCallPeerConnecting)?
    private let peerBridge: PeerBridge
    private var pendingRemoteIce: [CallIceEnvelope] = []
    private var remoteDescriptionSet = false
    /// True once call:offer actually went out (dismissError logs 'missed'
    /// only for attempts that never reached the relay — web parity).
    private var offerSent = false
    private var incomingOffer: CallOfferEnvelope?
    /// When the machine reached .ended (auto-reset ≈2.5s, web parity).
    private var endedAt: Date?

    private var cancellables: Set<AnyCancellable> = []

    public init(
        store: PulseStore,
        viewer: PulseViewer,
        signaling: any PulseCallSignalingSending,
        media: any PulseCallMediaProviding,
        apiProvider: @escaping () -> PulseAPIClient?,
        toasts: ToastCenter
    ) {
        self.store = store
        self.viewer = viewer
        self.signaling = signaling
        self.media = media
        self.apiProvider = apiProvider
        self.toasts = toasts
        self.peerBridge = PeerBridge()
        self.peerBridge.engine = self

        // 1s heartbeat: defensive timers + duration ticker + ended auto-reset.
        Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.tick() }
            .store(in: &cancellables)
    }

    /// UI gating — one call at a time (relay busy map is the backstop).
    public var isBusy: Bool { state != .idle }

    // ── outgoing ─────────────────────────────────────────────

    /// Entry point from the contact row / chat toolbar. Shows the ring
    /// immediately (web parity), then acquires media + sends call:offer.
    public func startOutgoing(to peer: CallPeer, conversationId: String, kind: CallKind = .voice) {
        guard case .idle = machine.state else { return }
        guard !viewer.id.isEmpty, !peer.id.isEmpty, peer.id != viewer.id, !conversationId.isEmpty else { return }
        let info = CallInfo(
            callId: CallWire.newCallId(),
            conversationId: conversationId,
            kind: kind,
            direction: .outgoing,
            peer: peer,
        )
        micEnabled = true
        speakerOn = true
        errorText = nil
        summary = nil
        apply(.startOutgoing(info), now: Date())
        Task { await prepareAndSendOffer(callId: info.callId) }
    }

    private func prepareAndSendOffer(callId: String) async {
        let granted = await media.requestMicPermission()
        // Canceled (or a new call took over) while the prompt was up.
        guard machine.owns(callId: callId), machine.state == .outgoingRinging else { return }
        guard granted else {
            errorText = Self.micDeniedMessage
            return
        }
        PulseCallAudioSession.shared.activateForCall()
        guard let connection = media.makePeerConnection(delegate: peerBridge) else {
            errorText = "Could not start the call. Try again."
            apply(.abortLocal, now: Date())
            return
        }
        pc = connection
        do {
            let sdp = try await connection.createOffer()
            try await connection.setLocalDescription(sdp: sdp)
            guard machine.owns(callId: callId), machine.state == .outgoingRinging,
                  let call = machine.call else { return }
            offerSent = true
            signaling.send(event: "call:offer", payload: CallWire.offerPayload(
                callId: call.callId,
                conversationId: call.conversationId,
                from: viewer.id,
                to: call.peer.id,
                kind: call.kind,
                sdp: sdp,
                callerName: viewer.name,
                callerColor: viewer.color ?? "emerald",
                callerAvatar: viewer.avatar,
            ))
        } catch {
            // Local SDP/media failure before the offer reached the relay —
            // no emit, no row (web 'Call failed' parity, honest UI only).
            errorText = "Could not start the call. Try again."
            apply(.abortLocal, now: Date())
        }
    }

    // ── incoming ─────────────────────────────────────────────

    public func acceptIncoming() {
        guard case .incomingRinging = machine.state, let call = machine.call else { return }
        apply(.acceptRequested, now: Date())
        Task { await prepareAndSendAnswer(call) }
    }

    private func prepareAndSendAnswer(_ call: CallInfo) async {
        let granted = await media.requestMicPermission()
        guard machine.owns(callId: call.callId), machine.state == .connecting else { return }
        guard granted else {
            // Callee could not join → reject politely; the caller logs the row.
            sendReject(for: call)
            errorText = Self.micDeniedMessage
            apply(.abortLocal, now: Date())
            return
        }
        PulseCallAudioSession.shared.activateForCall()
        guard let connection = media.makePeerConnection(delegate: peerBridge) else {
            sendReject(for: call)
            apply(.abortLocal, now: Date())
            return
        }
        pc = connection
        do {
            guard let offer = incomingOffer else {
                sendReject(for: call)
                apply(.abortLocal, now: Date())
                return
            }
            try await connection.setRemoteDescription(sdp: offer.sdp, type: "offer")
            remoteDescriptionSet = true
            // Early candidates queued while we were ringing drain now.
            await drainPendingIce()
            let answerSdp = try await connection.createAnswer()
            try await connection.setLocalDescription(sdp: answerSdp)
            guard machine.owns(callId: call.callId), machine.state == .connecting else { return }
            signaling.send(event: "call:answer", payload: CallWire.answerPayload(
                callId: call.callId,
                conversationId: call.conversationId,
                from: viewer.id,
                to: call.peer.id,
                kind: call.kind,
                sdp: answerSdp,
            ))
        } catch {
            // Answer pipeline failed → reject so the caller tears down.
            sendReject(for: call)
            apply(.abortLocal, now: Date())
        }
    }

    private func sendReject(for call: CallInfo) {
        signaling.send(event: "call:reject", payload: CallWire.rejectPayload(
            callId: call.callId,
            conversationId: call.conversationId,
            from: viewer.id,
            to: call.peer.id,
            kind: call.kind,
        ))
    }

    public func declineIncoming() {
        guard case .incomingRinging = machine.state else { return }
        apply(.rejectRequested, now: Date())
    }

    // ── live controls ────────────────────────────────────────

    /// End/decline/cancel — the machine maps the current state:
    /// outgoingRinging → cancel+missed row, incomingRinging → reject,
    /// connecting/connected → hangup (+completed row for the caller).
    public func endCall() {
        guard machine.state != .idle else { return }
        apply(.hangupRequested, now: Date())
    }

    /// The mic-denied (or SDP-failed) error card's Close — web parity: a
    /// failed OUTGOING attempt still counts as an unanswered call (missed row).
    public func dismissError() {
        errorText = nil
        if let call = machine.call, !machine.isTerminal,
           call.direction == .outgoing, !offerSent,
           CallLogMapper.shouldCallerWrite(direction: call.direction, peerId: call.peer.id, viewerId: viewer.id) {
            let body = CallLogMapper.restBody(
                viewerId: viewer.id,
                conversationId: call.conversationId,
                peerId: call.peer.id,
                kind: call.kind,
                outcome: .missed,
                durationSec: 0,
            )
            Task { await writeLogRow(body) }
        }
        if !machine.isTerminal, machine.state != .idle {
            apply(.abandon, now: Date())
        }
    }

    /// Mute = RTCAudioTrack.isEnabled toggle (web track.enabled parity).
    public func toggleMute() {
        micEnabled.toggle()
        pc?.setAudioEnabled(micEnabled)
    }

    /// Speaker toggle → AVAudioSession.overrideOutputAudioPort (earpiece ⇄
    /// loudspeaker). Hardware-gated: real routes need a physical device.
    public func toggleSpeaker() {
        speakerOn.toggle()
        PulseCallAudioSession.shared.setSpeaker(speakerOn)
    }

    // ── signaling inbound (session routes .callSignal here) ──

    public func handleCallSignal(event: String, raw: [String: Any]) {
        let now = Date()
        switch event {
        case "call:offer":
            guard let offer = CallOfferEnvelope.decode(raw) else { return }
            guard offer.base.from != viewer.id else { return }
            guard case .idle = machine.state else {
                // Already ringing/talking → immediate busy-reject; the caller
                // logs 'declined' (web parity). The machine state is untouched.
                signaling.send(event: "call:reject", payload: CallWire.rejectPayload(
                    callId: offer.base.callId,
                    conversationId: offer.base.conversationId,
                    from: viewer.id,
                    to: offer.base.from,
                    kind: offer.base.kind,
                ))
                return
            }
            let info = CallInfo(
                callId: offer.base.callId,
                conversationId: offer.base.conversationId,
                kind: offer.base.kind,
                direction: .incoming,
                peer: offer.caller,
            )
            incomingOffer = offer
            micEnabled = true
            errorText = nil
            summary = nil
            apply(.offerArrived(info), now: now)

        case "call:answer":
            guard let answer = CallAnswerEnvelope.decode(raw) else { return }
            guard machine.owns(callId: answer.base.callId),
                  machine.call?.direction == .outgoing else { return }
            guard case .outgoingRinging = machine.state else { return } // duplicate answer = no-op
            apply(.answerArrived, now: now)
            Task { await applyAnswer(answer.sdp) }

        case "call:ice":
            guard let ice = CallIceEnvelope.decode(raw) else { return }
            guard machine.owns(callId: ice.base.callId), !machine.isTerminal else { return }
            machine.noteSignalingActivity(now: now)
            if remoteDescriptionSet, let pc {
                Task { try? await pc.addRemoteCandidate(
                    candidate: ice.candidate,
                    sdpMid: ice.sdpMid,
                    sdpMLineIndex: ice.sdpMLineIndex,
                ) }
            } else {
                // Early candidate (relay relays ICE while ringing) — queue
                // until the remote description lands, then drain.
                pendingRemoteIce.append(ice)
            }

        case "call:reject":
            guard let base = CallSignalEnvelope.decode(raw) else { return }
            guard machine.owns(callId: base.callId),
                  machine.call?.direction == .outgoing else { return }
            apply(.rejectReceived, now: now)

        case "call:cancel":
            guard let base = CallSignalEnvelope.decode(raw) else { return }
            guard machine.owns(callId: base.callId) else { return }
            apply(.cancelReceived(reason: CallCancelReason(wireValue: raw["reason"] as? String)), now: now)

        case "call:hangup":
            guard let base = CallSignalEnvelope.decode(raw) else { return }
            guard machine.owns(callId: base.callId) else { return }
            apply(.hangupReceived, now: now)

        default:
            break
        }
    }

    private func applyAnswer(_ sdp: String) async {
        guard let pc else { return }
        do {
            try await pc.setRemoteDescription(sdp: sdp, type: "answer")
            remoteDescriptionSet = true
            await drainPendingIce()
        } catch {
            // 'Call failed' — no row, no emit (web parity).
            apply(.abortLocal, now: Date())
        }
    }

    private func drainPendingIce() async {
        let queued = pendingRemoteIce
        pendingRemoteIce.removeAll()
        guard let pc else { return }
        for ice in queued {
            try? await pc.addRemoteCandidate(
                candidate: ice.candidate,
                sdpMid: ice.sdpMid,
                sdpMLineIndex: ice.sdpMLineIndex,
            )
        }
    }

    // ── peer bridge callbacks (main actor via PeerBridge) ────

    func handleLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) {
        guard let call = machine.call, !machine.isTerminal else { return }
        signaling.send(event: "call:ice", payload: CallWire.icePayload(
            callId: call.callId,
            conversationId: call.conversationId,
            from: viewer.id,
            to: call.peer.id,
            kind: call.kind,
            candidate: candidate,
            sdpMid: sdpMid,
            sdpMLineIndex: sdpMLineIndex,
        ))
    }

    func handleConnectionState(_ pcState: PulseCallConnectionState) {
        let now = Date()
        switch pcState {
        case .connecting:
            break
        case .connected:
            apply(.peerConnectionReady, now: now)
        case .disconnected:
            apply(.peerConnectionDisconnected, now: now)
        case .failed:
            apply(.peerConnectionFailed, now: now)
        case .closed:
            apply(.hangupReceived, now: now)
        }
    }

    // ── heartbeat ────────────────────────────────────────────

    func tick() {
        let now = Date()
        if let timeout = machine.checkTimeouts(now: now) {
            apply(timeout, now: now)
        }
        switch machine.state {
        case .connecting, .connected:
            if let answeredAt = machine.answeredAt {
                durationSec = max(0, Int(now.timeIntervalSince(answeredAt).rounded()))
            }
        default:
            break
        }
        if case .ended = machine.state, let endedAt, now.timeIntervalSince(endedAt) >= 2.5 {
            apply(.reset, now: now)
        }
    }

    // ── transitions ──────────────────────────────────────────

    private func apply(_ input: CallMachineInput, now: Date) {
        let transition = machine.apply(input, now: now)
        guard !transition.isNoOp else { return }
        state = machine.state
        activePeer = machine.call?.peer
        if machine.state == .idle {
            durationSec = 0
        }
        if case .ended(let end) = transition.to {
            handleTerminal(input: input, end: end, now: now)
        }
        if machine.state == .idle {
            summary = nil
            endedAt = nil
        }
    }

    /// Wire effects + single-writer log row for a terminal transition.
    private func handleTerminal(input: CallMachineInput, end: CallEndContext, now: Date) {
        endedAt = now
        summary = Self.summaryText(for: input, end: end, direction: machine.call?.direction)
        teardownMedia()

        let call = machine.call
        // ── wire effects (what the relay still needs to hear) ──
        switch input {
        case .hangupRequested, .rejectRequested:
            if let call {
                switch end.outcome {
                case .completed:
                    sendHangup(call: call, durationSec: end.durationSec)
                case .missed:
                    // Self-cancel while ringing (relay drops the ring for both).
                    signaling.send(event: "call:cancel", payload: CallWire.cancelPayload(
                        callId: call.callId,
                        conversationId: call.conversationId,
                        from: viewer.id,
                        to: call.peer.id,
                        kind: call.kind,
                    ))
                case .declined:
                    sendReject(for: call)
                }
            }
        case .peerConnectionFailed, .connectTimeout, .disconnectGraceExpired:
            // Answered-then-ended by a defensive timer — the survivor must
            // stop the media UI (relay tolerates hangup on answered sessions).
            if let call { sendHangup(call: call, durationSec: end.durationSec) }
        case .staleTimeout:
            // Only answered calls emit; a stale ring dies via the server's own
            // 30s timeout (authoritative, shorter than our defensive 40s).
            if end.outcome == .completed, let call {
                sendHangup(call: call, durationSec: end.durationSec)
            }
        default:
            // reject/cancel/hangup RECEIVED + ringTimeout + abortLocal — the
            // relay already routed the peer side (or there is nothing to say).
            break
        }

        // ── single-writer log row ────────────────────────────
        // .abortLocal writes NOTHING (web 'Call failed' parity). The callee
        // never writes. Only the caller's terminal outcomes become rows.
        if input != .abortLocal, let call,
           CallLogMapper.shouldCallerWrite(direction: call.direction, peerId: call.peer.id, viewerId: viewer.id) {
            let body = CallLogMapper.restBody(
                viewerId: viewer.id,
                conversationId: call.conversationId,
                peerId: call.peer.id,
                kind: call.kind,
                outcome: end.outcome,
                durationSec: end.durationSec,
            )
            Task { await writeLogRow(body) }
        }
    }

    private func sendHangup(call: CallInfo, durationSec: Int) {
        signaling.send(event: "call:hangup", payload: CallWire.hangupPayload(
            callId: call.callId,
            conversationId: call.conversationId,
            from: viewer.id,
            to: call.peer.id,
            kind: call.kind,
            durationSec: durationSec,
        ))
    }

    private func teardownMedia() {
        pc?.close()
        pc = nil
        pendingRemoteIce.removeAll()
        remoteDescriptionSet = false
        offerSent = false
        incomingOffer = nil
        PulseCallAudioSession.shared.restore()
    }

    // ── call-log write path (single writer, offline queue) ───

    private func writeLogRow(_ body: [String: Any]) async {
        guard let api = apiProvider() else {
            try? store.appendCallLogQueue(payload: body)
            return
        }
        do {
            _ = try await api.createCallLogRow(body)
        } catch {
            if PulseOutboxEngine.isDroppable(error) {
                // 4xx — retrying can never succeed (peer left the DM, group
                // conversation, …): honest toast, row dropped (web parity).
                toasts.show("Could not save this call to history")
            } else {
                // Network-class failure — queue locally, flush on socket
                // reconnect / app start (outbox trigger style).
                try? store.appendCallLogQueue(payload: body)
                toasts.show("Call will be added to history when you're back online")
            }
        }
    }

    /// Flush trigger: socket (re)connect (session) and app start (session).
    /// FIFO drain; stops at the first network-class failure; 4xx rows are
    /// dropped (outbox parity).
    public func flushCallLogQueue() async {
        guard let api = apiProvider() else { return }
        let rows = (try? store.callLogQueueAll()) ?? []
        for row in rows {
            guard let id = row.id else { continue }
            guard let body = row.payload() else {
                try? store.deleteCallLogQueue(id: id)
                continue
            }
            do {
                _ = try await api.createCallLogRow(body)
                try? store.deleteCallLogQueue(id: id)
            } catch {
                if PulseOutboxEngine.isDroppable(error) {
                    try? store.deleteCallLogQueue(id: id)
                    continue
                }
                try? store.bumpCallLogQueueAttempts(id: id)
                return
            }
        }
    }

    /// Reconnect/foreground trigger — fire-and-forget wrapper.
    public func flushCallLogQueueOnReconnect() {
        Task { await flushCallLogQueue() }
    }

    // ── summary text (web CANCEL_SUMMARY + ended-card parity) ──

    static func summaryText(for input: CallMachineInput, end: CallEndContext, direction: CallDirection?) -> String? {
        let endedText = end.durationSec >= 1
            ? "Call ended · \(CallFormat.duration(end.durationSec))"
            : "Call ended"
        switch input {
        case .abortLocal:
            return "Call failed"
        case .hangupRequested, .rejectRequested:
            switch end.outcome {
            case .declined: return "Declined"
            case .missed: return "Call canceled"
            case .completed: return endedText
            }
        case .rejectReceived:
            return "Declined"
        case .cancelReceived(let reason):
            if end.outcome == .completed { return endedText }
            if direction == .incoming { return "Missed call" }
            return cancelSummary(reason)
        case .hangupReceived:
            return endedText
        case .ringTimeout:
            return cancelSummary(.timeout)
        case .connectTimeout, .peerConnectionFailed, .disconnectGraceExpired, .staleTimeout:
            return endedText
        default:
            return nil
        }
    }

    static func cancelSummary(_ reason: CallCancelReason) -> String {
        switch reason {
        case .timeout: return "No answer"
        case .cancel: return "Call canceled"
        case .busy: return "Peer is busy"
        case .offline: return "Peer is offline"
        }
    }

    // ── peer bridge (WebRTC callbacks → main actor) ──────────

    /// Nonisolated bridge: WebRTC threads land here, then hop to the main
    /// actor before touching engine state.
    private final class PeerBridge: PulseCallPeerDelegate {
        weak var engine: PulseCallEngine?

        func peerDidProduceLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) {
            Task { @MainActor in
                self.engine?.handleLocalCandidate(
                    candidate: candidate,
                    sdpMid: sdpMid,
                    sdpMLineIndex: sdpMLineIndex,
                )
            }
        }

        func peerConnectionStateDidChange(_ state: PulseCallConnectionState) {
            Task { @MainActor in
                self.engine?.handleConnectionState(state)
            }
        }
    }
}
