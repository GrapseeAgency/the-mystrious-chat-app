import Combine
import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f session-scoped voice rooms owner (voice + stage +
// space). Created by PulseSession.startVoiceRooms(viewer:) at
// identity adoption (mirrors startStories), so room MEMBERSHIP
// SURVIVES surface close (VR-1) — the ChatRoomView pill + the
// fullScreenCover host both read this one instance.
//
// Consumes the 7 rooms signals via session.signals (they keep
// flowing to other subscribers unchanged) and emits through
// session.emitRoomSignal (payloads from the pure VoiceRoomWire
// builders, unit-tested against the relay's validation).
// ─────────────────────────────────────────────────────────────

@MainActor
public final class VoiceRoomSessionModel: ObservableObject {
    public enum Surface: Equatable, Sendable {
        case voice, stage, space
    }

    /// One caption strip row (ephemeral — never stored, VR-7/spec §1.4).
    public struct Caption: Equatable, Identifiable, Sendable {
        public let id: UUID
        public let userId: String
        public let name: String
        public let color: String?
        public let text: String
        /// Received-at stamp for the 7 s TTL sweep.
        public let at: Date
    }

    // ── published state (three pure machines + UI bits) ──
    @Published public private(set) var voice = VoiceRoomModel()
    @Published public private(set) var stage: StageModel
    @Published public private(set) var space: SpaceModel
    @Published public private(set) var captions: [Caption] = []
    /// The open surface (nil = closed; the pill shows while joined).
    @Published public private(set) var surface: Surface?
    /// VR-7 — captions toggle, persisted (PulsePrefs.voiceCaptionsKey).
    @Published public private(set) var captionsEnabled: Bool
    /// VR-10 — honest mic issue banner (denied / busy / absent); the
    /// room still joins and listens.
    @Published public private(set) var micIssue: String?

    public static let captionTtlMs: Double = 7000
    public static let captionKeep = 3
    /// Client-side spacing between caption emits (server enforces 700 ms).
    public static let transcriptSpacingMs: Double = 700

    private unowned let session: PulseSession
    private var cancellables: Set<AnyCancellable> = []
    private let engine = VoiceRoomAudioEngine()
    private var accumulator = CaptionWindowAccumulator()
    private var lastTranscriptEmitMs: Double = 0
    /// The caption toggle store (the ONLY persisted artefact of the
    /// rooms feature — spec §1.4). Key shared with PulsePrefs.
    private let defaults: UserDefaults

    public init(session: PulseSession, defaults: UserDefaults = .standard) {
        self.session = session
        self.defaults = defaults
        let myId = session.viewer?.id ?? ""
        stage = StageModel(myId: myId)
        space = SpaceModel(myId: myId)
        captionsEnabled = defaults.bool(forKey: PulsePrefs.voiceCaptionsKey)

        // Engine callbacks fire on the audio tap thread — hop to the main
        // queue (strict FIFO keeps chunk ORDER intact; Task would not).
        engine.onChunk = { [weak self] chunk in
            DispatchQueue.main.async {
                self?.handleLocalChunk(chunk)
            }
        }
        engine.onSamples = { [weak self] samples in
            DispatchQueue.main.async {
                self?.handleCapturedSamples(samples)
            }
        }
        engine.onError = { [weak self] text in
            DispatchQueue.main.async {
                self?.micIssue = text
            }
        }

        // 1 s caption TTL sweep (VR-7; PulseSession pruneTypers pattern).
        Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.sweepCaptions() }
            .store(in: &cancellables)
        // Rooms signals are NOT self-subscribed: PulseSession.handle routes
        // the 7 rooms events + connectionState here (single delivery).
    }

    // ── viewer helpers ───────────────────────────────────

    private var viewerId: String { session.viewer?.id ?? "" }
    private var viewerName: String { session.viewer?.name ?? "Someone" }
    private var viewerUsername: String? { session.viewer?.username }
    private var viewerColor: String? { session.viewer?.color }

    private func nowMs() -> Double {
        Date().timeIntervalSince1970 * 1000
    }

    // ── signal routing ───────────────────────────────────

    /// Routed from PulseSession.handle — the 7 rooms signals AND
    /// connectionState land here exactly once.
    func handle(_ signal: PulseSocketClient.Signal) {
        switch signal {
        case .voiceRoster(let conversationId, let rawPeers):
            handleVoiceRoster(conversationId: conversationId, rawPeers: rawPeers)
        case .voicePtt(let conversationId, let userId, let active):
            guard conversationId == voice.conversationId else { return }
            voice.applyPtt(userId: userId, on: active)
        case .voiceChunk(let conversationId, let userId, let seq, let data):
            handleVoiceChunk(conversationId: conversationId, userId: userId, seq: seq, data: data)
        case .voiceTranscript(let roomId, let speakerId, let text):
            guard roomId == voice.conversationId else { return }
            let peer = voice.roster.first { $0.id == speakerId }
            appendCaption(userId: speakerId, name: peer?.name ?? "Someone", color: peer?.color, text: text)
        case .stageState(let conversationId, let raw):
            guard conversationId == stage.conversationId else { return }
            if let state = Self.decode(WireStageState.self, from: raw) {
                stage.apply(state: state)
            }
        case .stageEnded(let conversationId):
            guard conversationId == stage.conversationId else { return }
            finishStageTeardown(toast: "The stage ended")
        case .spaceState(let conversationId, let raw):
            guard conversationId == space.conversationId else { return }
            if let state = Self.decode(WireSpaceState.self, from: raw) {
                _ = space.apply(state: state, nowMs: nowMs())
            }
        case .connectionState(let isOn):
            handleConnection(connected: isOn)
        default:
            break
        }
    }

    private static func decode<T: Decodable>(_ type: T.Type, from raw: [String: Any]) -> T? {
        guard JSONSerialization.isValidJSONObject(raw),
              let data = try? JSONSerialization.data(withJSONObject: raw) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    // ── voice room (VR-1…VR-11) ──────────────────────────

    public func joinVoice(conversationId: String) {
        guard voice.status == .idle || voice.status == .error else { return }
        voice.beginJoin(conversationId: conversationId)
        open(.voice)
        Task {
            await activateAudioAndJoin()
        }
    }

    /// Permission → session → engine → optimistic join + emit.
    private func activateAudioAndJoin() async {
        let granted = await engine.requestMicPermission()
        if let failure = engine.startCapture(micGranted: granted) {
            micIssue = failure
        } else {
            micIssue = nil
        }
        engine.resetChunker()
        voice.markJoined()
        emitVoiceJoin()
    }

    public func leaveVoice() {
        guard voice.status != .idle else { return }
        stopTransmitting(emitPttOff: voice.transmitting)
        emitVoiceLeave()
        voice.leave()
        engine.stopCapture()
        micIssue = nil
    }

    /// VR-3 — PTT hold/latch share this gate: disabled when muted,
    /// not joined, disconnected or mic-denied (honest, never fake).
    public var canTransmit: Bool {
        voice.status == .joined
            && !voice.micMuted
            && voice.connected
            && micIssue == nil
    }

    public func setPtt(on: Bool) {
        guard voice.status == .joined else { return }
        if on {
            guard canTransmit, !voice.transmitting else { return }
            voice.transmitting = true
            engine.setGate(transmitting: true, muted: voice.micMuted)
            if captionsEnabled {
                accumulator.setEnabled(true)
            }
            emitVoicePtt(on: true)
        } else {
            guard voice.transmitting else { return }
            stopTransmitting(emitPttOff: true)
        }
    }

    /// Release path: gate OFF first (no further samples), then the
    /// proportional partial flush + caption tail (VR-4/VR-7).
    private func stopTransmitting(emitPttOff: Bool) {
        voice.transmitting = false
        engine.setGate(transmitting: false, muted: voice.micMuted)
        engine.flushPartial()
        if let tail = accumulator.takeTail() {
            startTranscription(window: tail)
        }
        accumulator.setEnabled(false)
        if emitPttOff {
            emitVoicePtt(on: false)
        }
    }

    /// VR-6 — software gate is the truth; muting while transmitting
    /// force-stops the PTT (the model reports the force-stop).
    public func setMicMuted(_ muted: Bool) {
        let forceStopped = voice.setMicMuted(muted)
        if forceStopped {
            engine.setGate(transmitting: false, muted: true)
            engine.flushPartial()
            if let tail = accumulator.takeTail() {
                startTranscription(window: tail)
            }
            accumulator.setEnabled(false)
            emitVoicePtt(on: false)
        } else {
            engine.setGate(transmitting: voice.transmitting, muted: muted)
        }
    }

    public func setCaptions(_ enabled: Bool) {
        captionsEnabled = enabled
        defaults.set(enabled, forKey: PulsePrefs.voiceCaptionsKey)
        if !enabled {
            accumulator.clear() // clear-on-off
        }
    }

    /// VR-10 — the mic banner's "Try again": re-run permission + engine
    /// start; the room stays joined and listening throughout.
    public func retryMicAccess() async {
        let granted = await engine.requestMicPermission()
        if let failure = engine.startCapture(micGranted: granted) {
            micIssue = failure
        } else {
            micIssue = nil
        }
    }

    private func handleVoiceRoster(conversationId: String, rawPeers: [[String: Any]]) {
        guard conversationId == voice.conversationId else { return }
        guard let roster = WireVoiceRoster.decode(conversationId: conversationId, rawPeers: rawPeers) else { return }
        voice.applyRoster(roster) // FIX #2: wholesale replace + playback reset for vanished peers

        let containsMe = (roster.peers ?? []).contains { $0.id == viewerId }
        guard !containsMe else { return }
        // VR-8 — joined but absent: rate-limited re-join (2 s).
        let now = nowMs()
        guard voice.mayResync(nowMs: now) else { return }
        voice.noteResync(nowMs: now)
        // FIX #3 seam — stage-joined but the voice roster dropped me
        // (forced removal): re-arm the seat, then re-join so I keep HEARING.
        stage.markVoiceSeatRemoved()
        emitVoiceJoin()
        stage.consumeVoiceSeatRequest()
    }

    private func handleVoiceChunk(conversationId: String, userId: String, seq: Int, data: String) {
        guard conversationId == voice.conversationId, userId != viewerId else { return }
        guard let samples = VoiceRoomAudioEngine.decode(base64: data), !samples.isEmpty else {
            return // corrupt chunk dropped silently (VR-5)
        }
        let now = nowMs()
        let durationMs = Double(samples.count) / (VoiceRoomAudioEngine.sampleRate / 1000.0)
        guard case .schedule(let atMs) = voice.decide(
            seq: seq,
            userId: userId,
            nowMs: now,
            chunkDurationMs: durationMs,
        ) else { return }
        engine.play(userId: userId, samples: samples, atMs: atMs, nowMs: now)
    }

    private func handleLocalChunk(_ chunk: VoicePcmChunkOut) {
        guard let conversationId = voice.conversationId else { return }
        emitRoom(.voiceChunk, VoiceRoomWire.voiceChunk(
            conversationId: conversationId,
            userId: viewerId,
            seq: chunk.seq,
            data: chunk.base64,
        ))
    }

    private func handleCapturedSamples(_ samples: [Int16]) {
        guard voice.transmitting, captionsEnabled else { return }
        if let window = accumulator.feed(samples) {
            startTranscription(window: window)
        }
    }

    // ── captions pipeline (VR-7) ─────────────────────────

    private func startTranscription(window: [Int16]) {
        guard let conversationId = voice.conversationId, !window.isEmpty else {
            accumulator.endFlush()
            return
        }
        let wav = WavEncoder.base64Wav(samples: window)
        let requesterId = viewerId
        Task { [weak self] in
            guard let self else { return }
            defer { self.accumulator.endFlush() } // single-flight opens again
            do {
                let result = try await self.session.api.transcribeVoice(
                    conversationId: conversationId,
                    requesterId: requesterId,
                    audioBase64: wav,
                )
                let text = String(result.transcript.prefix(280))
                guard !text.isEmpty else { return } // honest silence
                let now = self.nowMs()
                guard now - self.lastTranscriptEmitMs >= Self.transcriptSpacingMs else { return }
                self.lastTranscriptEmitMs = now
                self.emitRoom(.voiceTranscript, VoiceRoomWire.voiceTranscript(
                    conversationId: conversationId,
                    userId: requesterId,
                    text: text,
                ))
            } catch {
                // Failures = honest silence; the window is never retried.
            }
        }
    }

    private func appendCaption(userId: String, name: String, color: String?, text: String) {
        guard !text.isEmpty else { return }
        captions.append(Caption(id: UUID(), userId: userId, name: name, color: color, text: text, at: Date()))
        if captions.count > Self.captionKeep {
            captions.removeFirst(captions.count - Self.captionKeep)
        }
    }

    private func sweepCaptions() {
        let cutoff = Date().addingTimeInterval(-Self.captionTtlMs / 1000.0)
        captions.removeAll { $0.at < cutoff }
    }

    // ── stage (ST-1…ST-8) ────────────────────────────────

    public func joinStage(conversationId: String) {
        guard !stage.joined else { return }
        stage.beginJoin(conversationId: conversationId, asHost: false)
        open(.stage)
        emitStageJoin(asHost: false) // ST-1 — ALWAYS as listener
        // FIX #3 — every joined role holds a voice seat (audience hears).
        if stage.consumeVoiceSeatRequest() {
            emitVoiceJoin(conversationId: conversationId)
        }
    }

    public func leaveStage() {
        guard stage.joined, let conversationId = stage.conversationId else { return }
        emitRoom(.stageLeave, VoiceRoomWire.stageLeave(conversationId: conversationId))
        let seatConversationId = conversationId
        stage.leave()
        // The voice seat existed only for the stage → release it too.
        if !voice.userJoinedVoice, voice.conversationId == seatConversationId {
            releaseVoiceSeat()
        }
    }

    public func setHandRaised(_ raised: Bool) {
        guard stage.joined, let conversationId = stage.conversationId else { return }
        if raised {
            guard stage.canRaiseHand else { return } // listener-only (ST-3)
        }
        stage.setHandRaised(raised)
        emitRoom(.stageHand, VoiceRoomWire.stageHand(conversationId: conversationId, userId: viewerId, raised: raised))
    }

    /// ST-3 — host-only approve (hand → speaker, server truth follows).
    public func approveHand(userId: String) {
        guard stage.isHost, let conversationId = stage.conversationId else { return }
        emitRoom(.stageApprove, VoiceRoomWire.stageApprove(
            conversationId: conversationId,
            byUserId: viewerId,
            targetUserId: userId,
        ))
    }

    /// ST-4 — host-only mute (speaker → listener; the server ALSO
    /// force-removes the target's voice seat).
    public func muteMember(userId: String) {
        guard stage.isHost, let conversationId = stage.conversationId else { return }
        emitRoom(.stageMute, VoiceRoomWire.stageMute(
            conversationId: conversationId,
            byUserId: viewerId,
            targetUserId: userId,
        ))
    }

    /// ST-5 — the host fires this on the SECOND tap of the two-tap confirm
    /// (the 2600 ms reset window lives in StageEndConfirm).
    public func endStage() {
        guard stage.canEnd, let conversationId = stage.conversationId else { return }
        emitRoom(.stageEnd, VoiceRoomWire.stageEnd(conversationId: conversationId, byUserId: viewerId))
    }

    /// ST-7 — the host seat is empty: claim it with an asHost re-join.
    public func claimHost() {
        guard stage.canClaimHost, let conversationId = stage.conversationId else { return }
        stage.wasHost = true
        emitStageJoin(asHost: true)
    }

    private func emitStageJoin(asHost: Bool) {
        guard let conversationId = stage.conversationId else { return }
        emitRoom(.stageJoin, VoiceRoomWire.stageJoin(
            conversationId: conversationId,
            userId: viewerId,
            name: viewerName,
            username: viewerUsername,
            color: viewerColor,
            asHost: asHost,
        ))
    }

    /// stage:ended — full local teardown + surface close + toast (ST-5).
    private func finishStageTeardown(toast: String) {
        let seatConversationId = stage.conversationId
        stage.applyEnded()
        surface = nil
        session.toasts.show(toast)
        if !voice.userJoinedVoice, voice.conversationId == seatConversationId {
            releaseVoiceSeat()
        }
    }

    // ── space (SP-1…SP-5) ────────────────────────────────

    public func joinSpace(conversationId: String) {
        guard !space.joined else { return }
        space.beginJoin()
        open(.space)
        // Remember which conversation the space seat belongs to (SpaceModel
        // is pure — no wire ids). The re-join path uses it too.
        spaceConversationId = conversationId
        emitRoom(.spaceJoin, VoiceRoomWire.spaceJoin(
            conversationId: conversationId,
            userId: viewerId,
            name: viewerName,
            username: viewerUsername,
            color: viewerColor,
        ))
    }

    public func leaveSpace() {
        guard space.joined else { return }
        if let conversationId = spaceConversationId {
            emitRoom(.spaceLeave, VoiceRoomWire.spaceLeave(conversationId: conversationId))
        }
        space.leave()
    }

    /// SP-3 — tap-to-move AND drag share this: clamp + optimistic target
    /// + 80 ms throttle; the emit fires only when the throttle allows.
    public func moveSpace(x: Double, y: Double) {
        guard space.joined, let conversationId = spaceConversationId else { return }
        guard case .emit(let clampedX, let clampedY) = space.localMove(x: x, y: y, nowMs: nowMs()) else { return }
        emitRoom(.spaceMove, VoiceRoomWire.spaceMove(conversationId: conversationId, x: clampedX, y: clampedY))
    }

    /// The space conversation id — SpaceModel is pure (no wire ids), so
    /// the session carries it alongside.
    private var spaceConversationId: String?

    // ── surface lifecycle ────────────────────────────────

    /// The conversation the open surface belongs to — captured at open()
    /// (joinVoice/joinStage/joinSpace set the ids BEFORE opening), so an
    /// in-surface leave keeps the surface's Join card pointed at the right
    /// room and the fullScreenCover host can pass it to the surfaces.
    @Published public private(set) var surfaceConversationId: String?

    public func open(_ surface: Surface) {
        self.surface = surface
        surfaceConversationId = currentConversationId(for: surface)
    }

    /// The conversation id backing one surface kind at this moment.
    public func currentConversationId(for surface: Surface) -> String? {
        switch surface {
        case .voice: return voice.conversationId
        case .stage: return stage.conversationId
        case .space: return spaceConversationId
        }
    }

    /// VR-1 entry state — true when ANY room kind of this conversation is
    /// joined (the chat header mic tints active; the live pill additionally
    /// requires a closed surface and is voice-specific).
    public func isInRoom(_ conversationId: String) -> Bool {
        if voice.conversationId == conversationId, voice.status != .idle { return true }
        if stage.joined, stage.conversationId == conversationId { return true }
        if space.joined, spaceConversationId == conversationId { return true }
        return false
    }

    /// Surface close: voice membership SURVIVES (VR-1); stage close emits
    /// stage:leave (ST-7); space close emits space:leave (SP-1).
    public func closeSurface() {
        surface = nil
        if stage.joined {
            leaveStage()
        }
        if space.joined {
            leaveSpace()
        }
    }

    // ── connection transitions (VR-8 / ST-8 / SP-5) ──────

    private func handleConnection(connected: Bool) {
        voice.setConnected(connected)
        if connected {
            space.noteConnected()
            // Re-join on every successful (re)connect.
            if voice.userJoinedVoice, let conversationId = voice.conversationId {
                emitVoiceJoin(conversationId: conversationId)
            }
            if stage.joined {
                emitStageJoin(asHost: stage.wasHost) // remembered role (ST-8)
                if let conversationId = stage.conversationId, !voice.userJoinedVoice {
                    emitVoiceJoin(conversationId: conversationId)
                }
            }
            if space.joined, let conversationId = spaceConversationId {
                emitRoom(.spaceJoin, VoiceRoomWire.spaceJoin(
                    conversationId: conversationId,
                    userId: viewerId,
                    name: viewerName,
                    username: viewerUsername,
                    color: viewerColor,
                ))
            }
        } else {
            // Chunks are DROPPED (never queued) while disconnected (VR-4):
            // force the gate off and release the PTT honestly.
            if voice.transmitting {
                stopTransmitting(emitPttOff: true)
            }
            space.noteDisconnected() // FIX #5 — attempts → honest error
        }
    }

    // ── emit funnels ─────────────────────────────────────

    private func emitRoom(_ event: PulseSocketEvents, _ payload: [String: Any]) {
        session.emitRoomSignal(event: event.rawValue, payload: payload)
    }

    private func emitVoiceJoin(conversationId: String? = nil) {
        let conversation = conversationId ?? voice.conversationId
        guard let conversation else { return }
        emitRoom(.voiceJoin, VoiceRoomWire.voiceJoin(
            conversationId: conversation,
            userId: viewerId,
            name: viewerName,
            username: viewerUsername,
            color: viewerColor,
        ))
    }

    private func emitVoiceLeave() {
        guard let conversation = voice.conversationId else { return }
        emitRoom(.voiceLeave, VoiceRoomWire.voiceLeave(conversationId: conversation))
    }

    private func emitVoicePtt(on: Bool) {
        guard let conversation = voice.conversationId else { return }
        emitRoom(.voicePtt, VoiceRoomWire.voicePtt(conversationId: conversation, userId: viewerId, on: on))
    }

    /// The seat existed only for a stage/space context — release without
    /// touching the user's voice-room intent.
    private func releaseVoiceSeat() {
        if let conversation = voice.conversationId {
            emitRoom(.voiceLeave, VoiceRoomWire.voiceLeave(conversationId: conversation))
        }
        voice.leave()
        engine.stopCapture()
        micIssue = nil
    }
}
