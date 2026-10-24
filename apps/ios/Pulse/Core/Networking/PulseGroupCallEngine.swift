import Foundation
import Combine
import WebRTC

// Pulse - GROUP call engine (the native useGroupCallSession hook).
//
// Behavioral spec = src/components/chat/group-call-overlay.tsx (R8-web):
//   • MESH: one peer connection per remote member, the ONE shared local
//     audio/video track acquisition feeding all of them (web single
//     MediaStream parity). Between any two members the one with the
//     lexicographically SMALLER id creates the offer (deterministic,
//     stateless - PulseGroupCallPolicy, web applyRoster :285-309).
//   • Signaling (same relay, gcall:* family): join → state broadcast →
//     targeted offer/answer/ice. join = gcall:join + POST
//     /api/conversations/[id]/calls/ring (rings online members + pushes
//     offline ones). joinOngoing NEVER re-rings (ring:false path).
//   • Ongoing-call probe: GET /api/group-call-state every 20s while idle
//     (a late-opening room learns about the call without a live ring).
//   • Leave emits gcall:leave; gcall:ended and socket disconnect tear the
//     mesh down (disconnect teardown is the 3-d spec - the web hook
//     survives transient reconnects; iOS tears down honestly instead of
//     ghosting audio after a suspension).
//
// Seams (tests substitute fakes - the PulseOutboxSending pattern):
//   • PulseCallSignalingSending - emits gcall:* envelopes (reused)
//   • PulseGroupCallMediaProviding - permissions + bare peer factory +
//     the ONE shared mic/camera acquisition
//
// Cross-engine exclusion: a live group call owns the audio session; the
// 1:1 PulseCallEngine checks `PulseGroupCallEngine.active?.isBusy` before
// dialing and this engine refuses to join while a 1:1 call is live.

@MainActor
public final class PulseGroupCallEngine: ObservableObject {
    public enum UiState: Equatable {
        case idle
        case joining
        case active
        case ended
    }

    /// A live incoming ring (web GroupCallSessionControls['ring'] parity).
    public struct Ring: Equatable {
        public let conversationId: String
        public let kind: CallKind
        public let caller: PulseGroupCallMember
        public let title: String
        public let callId: String
        public let expiresAt: Date

        /// Tolerant decode of the relayed gcall:ring payload
        /// { conversationId, kind, caller: {id,name,color,avatar}, title }.
        static func decode(_ raw: [String: Any], now: Date = Date()) -> Ring? {
            guard let conversationId = raw["conversationId"] as? String,
                  !conversationId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let callId = raw["callId"] as? String, !callId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let expiry = raw["expiresAt"] as? String, let deadline = PulseFormat.date(expiry), deadline > now else { return nil }
            let callerRaw = raw["caller"] as? [String: Any] ?? [:]
            guard let caller = PulseGroupCallMember.decode(callerRaw) else { return nil }
            let title = (raw["title"] as? String) ?? ""
            return Ring(
                conversationId: conversationId,
                kind: CallKind(wireValue: raw["kind"] as? String),
                caller: caller,
                title: title, callId: callId,
                expiresAt: min(deadline, now.addingTimeInterval(PulseGroupCallPolicy.ringTimeoutSec)),
            )
        }
    }

    // published UI state (GroupCallView binds these)
    @Published public private(set) var uiState: UiState = .idle
    @Published public private(set) var kind: CallKind = .voice
    /// Roster INCLUDING me, join-ordered (while in the call).
    @Published public private(set) var members: [PulseGroupCallMember] = []
    /// Live incoming ring (nil = none). Surfaces while idle, any screen.
    @Published public private(set) var ring: Ring?
    /// True while the OPEN conversation has a live call I am NOT in.
    @Published public private(set) var ongoingElsewhere = false
    @Published public private(set) var ongoingMembers: [PulseGroupCallMember] = []
    @Published public private(set) var ongoingKind: CallKind = .voice
    /// Non-nil = the honest glass error card replaces the call UI.
    @Published public private(set) var errorText: String?
    /// Ended-card text ('You left · 1:05', 'Call ended', 'Connection lost').
    @Published public private(set) var summary: String?
    @Published public private(set) var durationSec = 0
    @Published public private(set) var micEnabled = true
    @Published public private(set) var cameraEnabled = true
    /// The shared local camera track once capture is live (nil = audio-only).
    @Published public private(set) var localVideoTrack: RTCVideoTrack?
    /// Remote video tracks keyed by remote member id (mesh ontrack parity).
    @Published public private(set) var remoteTracks: [String: RTCVideoTrack] = [:]
    /// Per-peer ICE connection state (tile presence dots).
    @Published public private(set) var peerStates: [String: PulseCallConnectionState] = [:]
    /// The room display name feeding the header (set at start/join).
    @Published public private(set) var displayTitle = "Group call"

    /// Honest mic-denied copy - the SAME native wording the 1:1 engine uses.
    public static let micDeniedMessage = PulseCallEngine.micDeniedMessage

    // deps
    private let viewer: PulseViewer
    private let signaling: any PulseCallSignalingSending
    private let media: any PulseGroupCallMediaProviding
    private let apiProvider: () -> PulseAPIClient?
    private let connectedProvider: () -> Bool
    private let voiceCallBusy: () -> Bool
    private let toasts: ToastCenter
    private let activateAudioSession: @MainActor () -> Bool
    private let restoreAudioSession: @MainActor () -> Void
    private var probeGeneration = UUID()

    // live mesh state (cleared on every teardown)
    private var peers: [String: any PulseGroupCallPeerConnecting] = [:]
    private var peerBridges: [String: MeshPeerBridge] = [:]
    private var pendingIce: [String: [CallIceEnvelope]] = [:]
    private var peerRemoteDescriptionSet: Set<String> = []
    private var localDescriptionSent: Set<String> = []
    private var pendingLocalIce: [String: [[String: Any]]] = [:]
    private var negotiatingPeers: Set<String> = []
    private var expectedJoin: (roomId: String, callId: String)?
    private var joinedCallId: String?
    private var joinSentAt: Date?
    private var pendingRing = false
    private var ringTask: Task<Void, Never>?
    private var ownsAudioSession = false
    private var sharedAudio: RTCAudioTrack?
    private var cameraLive = false
    private var joinedConv: String?
    private var joinedAt: Date?
    private var joinIntent = false
    private var mediaReady = false
    /// The conversation currently open on screen (drives the probe +
    /// the ONGOING banner, web hook options.conversationId parity).
    /// Public read-only since 3-d - ChatRoomView's onDisappear compares it
    /// before standing the room's probe/banner down.
    public private(set) var activeConversationId: String?
    /// The conversation whose live call the ONGOING banner describes
    /// (set from gcall:state / the probe, cleared with the banner).
    private var ongoingConversationId: String?
    private var ongoingCallId: String?

    private var cancellables: Set<AnyCancellable> = []
    private var joinGeneration = UUID()
    private(set) var preparationTask: Task<Void, Never>?

    /// Weak process-wide mirror of the LIVE group engine - the 1:1 engine
    /// consults it so the two call surfaces never fight over the audio
    /// session. Set in init; a session restart replaces it, `stop()` drops
    /// it (weak → nil on dealloc).
    static weak var active: PulseGroupCallEngine?

    public init(
        viewer: PulseViewer,
        signaling: any PulseCallSignalingSending,
        media: any PulseGroupCallMediaProviding,
        apiProvider: @escaping () -> PulseAPIClient?,
        connectedProvider: @escaping () -> Bool,
        voiceCallBusy: @escaping () -> Bool,
        toasts: ToastCenter,
        activateAudioSession: @escaping @MainActor () -> Bool = { PulseCallAudioSession.shared.activateForCall() },
        restoreAudioSession: @escaping @MainActor () -> Void = { PulseCallAudioSession.shared.restore() }
    ) {
        self.viewer = viewer
        self.signaling = signaling
        self.media = media
        self.apiProvider = apiProvider
        self.connectedProvider = connectedProvider
        self.voiceCallBusy = voiceCallBusy
        self.toasts = toasts
        self.activateAudioSession = activateAudioSession
        self.restoreAudioSession = restoreAudioSession
        Self.active = self

        // 1s heartbeat: duration ticker + ring timeout guard.
        Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.tick() }
            .store(in: &cancellables)
        // 20s ongoing-call probe (web probe interval parity).
        Timer.publish(every: 20, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.probeNow() }
            .store(in: &cancellables)
    }

    deinit {
        // No actor-isolated state is touched here - the weak static mirror
        // clears itself on dealloc and the Combine subscriptions die with
        // the cancellables store.
    }

    /// UI gating - one call at a time across BOTH engines.
    public var isBusy: Bool { uiState != .idle }
    /// True while joined/joining (banner + CallKit coordination).
    public var isInCall: Bool { uiState == .joining || uiState == .active }

    // room context (ChatRoomView onAppear/onDisappear)

    /// The open group conversation. Setting it kicks an immediate probe
    /// (web effect-on-mount parity); clearing it hides the ONGOING banner.
    public func setActiveConversation(_ conversationId: String?) {
        guard activeConversationId != conversationId else { return }
        activeConversationId = conversationId
        probeGeneration = UUID()
        if ongoingConversationId != conversationId {
            ongoingElsewhere = false
        }
        probeNow()
    }

    // actions

    /// Start a NEW group call in the open conversation (rings everyone:
    /// online → gcall:ring relay, offline → push fanout).
    public func startCall(kind wanted: CallKind, conversationId: String, title: String) {
        startJoin(conversationId, wanted: wanted, ringOthers: true, title: title)
    }

    /// Accept an incoming ring (the ring already went out - NEVER re-rings).
    public func joinCall() {
        guard let target = ring, uiState == .idle else { return }
        guard target.expiresAt > Date() else { dismissRing(); return }
        PulseHaptics.tap()
        startJoin(target.conversationId, wanted: target.kind, ringOthers: false, title: target.title.isEmpty ? displayTitle : target.title, expectedCallId: target.callId)
    }

    /// Silently join the ONGOING call in the open room - never re-rings.
    public func joinOngoing() {
        guard uiState == .idle, ongoingElsewhere, let conv = ongoingConversationId, let callId = ongoingCallId else { return }
        PulseHaptics.tap()
        let wanted = ongoingKind
        ongoingElsewhere = false
        startJoin(conv, wanted: wanted, ringOthers: false, title: displayTitle, expectedCallId: callId)
    }

    public func dismissRing() {
        ring = nil
    }

    public func ignoreOngoing() {
        ongoingElsewhere = false
    }

    public func leaveCall() {
        joinGeneration = UUID()
        preparationTask?.cancel()
        guard uiState == .joining || uiState == .active else { return }
        PulseHaptics.tap()
        if let conv = joinedConv {
            signaling.send(event: "gcall:leave", payload: GroupCallWire.leavePayload(
                conversationId: conv,
                from: viewer.id,
            ))
        }
        let elapsed = joinedAt.map { Int(Date().timeIntervalSince($0).rounded()) } ?? 0
        teardownMedia()
        uiState = .ended
        summary = PulseGroupCallPolicy.leaveSummary(elapsedSec: max(0, elapsed))
    }

    public func dismissError() {
        guard !isInCall else { return }
        joinGeneration = UUID()
        preparationTask?.cancel()
        errorText = nil
        uiState = .idle
    }

    public func dismissSummary() {
        guard !isInCall else { return }
        summary = nil
        uiState = .idle
    }

    /// Mute = the ONE shared audio track's isEnabled flip - every mesh peer
    /// sees it at once (web track.enabled on the single stream parity).
    public func toggleMic() {
        guard isInCall else { return }
        micEnabled.toggle()
        media.setSharedAudioEnabled(micEnabled)
        PulseHaptics.tap()
    }

    /// Camera (video) toggle - the shared camera track's isEnabled flip.
    public func toggleCamera() {
        guard kind == .video, cameraLive, localVideoTrack != nil else { return }
        cameraEnabled.toggle()
        media.setSharedVideoEnabled(cameraEnabled)
        PulseHaptics.tap()
    }

    /// Front ⇄ back camera flip (honest no-op without a live camera).
    public func flipCamera() {
        guard cameraLive else { return }
        media.switchSharedCamera()
    }

    // socket disconnect (mission spec: teardown on disconnect)

    /// The relay connection dropped while joined - the mesh is dead
    /// (media kept flowing to a socket that stopped routing), so tear down
    /// honestly instead of ghosting. The web hook tolerates transient
    /// reconnects; iOS suspensions kill the socket, so the honest state is
    /// an ended call.
    public func handleSocketDisconnected() {
        guard isInCall else { return }
        teardownMedia()
        uiState = .ended
        summary = "Connection lost"
    }

    // the join pipeline (web startJoin parity)

    private func startJoin(_ targetConvId: String, wanted: CallKind, ringOthers: Bool, title: String, expectedCallId: String? = nil) {
        guard uiState == .idle else { return }
        guard !targetConvId.isEmpty, !viewer.id.isEmpty else { return }
        guard !voiceCallBusy() else {
            toasts.show("Finish your current call first")
            return
        }
        guard connectedProvider(), signaling.isReady else {
            toasts.show("You are offline - calls need a connection")
            return
        }
        joinIntent = true
        expectedJoin = expectedCallId.map { (targetConvId, $0) }
        kind = wanted
        displayTitle = title
        errorText = nil
        summary = nil
        // CallKit observes ring removal synchronously. Publish joining first
        // so answering does not report the incoming system call as unanswered.
        uiState = .joining
        ring = nil
        let ticket = UUID()
        joinGeneration = ticket
        preparationTask = Task { await beginJoin(targetConvId, wanted: wanted, ringOthers: ringOthers, title: title, ticket: ticket, expectedCallId: expectedCallId) }
    }

    private func beginJoin(_ convId: String, wanted: CallKind, ringOthers: Bool, title: String, ticket: UUID, expectedCallId: String?) async {
        guard preparationIsCurrent(ticket) else { return }
        var resolved = wanted
        // Camera capability resolves FIRST (web acquireMedia degrade: no
        // usable camera → the call degrades to voice with an honest toast).
        if wanted == .video {
            let cameraOk = await media.requestCameraPermission() && media.canCaptureVideo()
            guard preparationIsCurrent(ticket) else { return }
            if !cameraOk {
                toasts.show("Camera unavailable - joining as a voice call")
                resolved = .voice
                kind = .voice
            }
        }
        let micGranted = await media.requestMicPermission()
        guard preparationIsCurrent(ticket) else { return }
        guard micGranted else {
            uiState = .idle
            joinIntent = false
            errorText = Self.micDeniedMessage
            return
        }
        guard activateAudioSession() else {
            uiState = .idle
            joinIntent = false
            errorText = "Audio is unavailable or in use. Leave your room or stop recording first."
            return
        }
        ownsAudioSession = true
        guard let audio = media.makeSharedAudioTrack() else {
            ownsAudioSession = false
            restoreAudioSession()
            uiState = .idle
            joinIntent = false
            errorText = "Could not start the call. Try again."
            return
        }
        media.setSharedAudioEnabled(true)
        micEnabled = true
        cameraEnabled = true
        if resolved == .video, let video = media.startSharedVideoCapture() {
            cameraLive = true
            cameraEnabled = true
            localVideoTrack = video
        } else {
            cameraLive = false
            cameraEnabled = false
            localVideoTrack = nil
            if resolved == .video {
                // Capture refused mid-join (device yanked) - degrade honestly.
                toasts.show("Camera unavailable - joining as a voice call")
                resolved = .voice
                kind = .voice
            }
        }
        sharedAudio = audio
        joinedConv = convId
        joinedAt = nil // set on the first roster (web joinedAtRef parity)
        mediaReady = true
        // The roster can only arrive AFTER the relay processed our join, so
        // every peer from here on attaches ready shared tracks.
        pendingRing = ringOthers
        joinSentAt = Date()
        guard signaling.send(event: "gcall:join", payload: GroupCallWire.joinPayload(
            conversationId: convId,
            kind: resolved,
            userId: viewer.id,
            userName: viewer.name,
            userColor: viewer.color,
            userAvatar: viewer.avatar,
            expectedCallId: expectedCallId,
        )) else {
            handleSocketDisconnected()
            return
        }
    }

    private func preparationIsCurrent(_ ticket: UUID) -> Bool {
        guard !Task.isCancelled, joinGeneration == ticket, uiState == .joining, joinedConv == nil else { return false }
        guard connectedProvider(), signaling.isReady else {
            handleSocketDisconnected()
            return false
        }
        return true
    }

    // Ring only after our own roster admission; a full/rejected join must not send pushes.
    private func ringAdmittedCall(conversationId: String) {
        guard pendingRing else { return }
        pendingRing = false
        guard let api = apiProvider(), let callId = joinedCallId else { return }
        let ticket = joinGeneration, resolved = kind
        ringTask = Task { [weak self] in
            guard let self, !Task.isCancelled, self.joinGeneration == ticket,
                  self.joinedConv == conversationId else { return }
            do {
                try await api.ringGroupCall(conversationId: conversationId, userId: self.viewer.id, kind: resolved, callId: callId)
            } catch {
                guard !Task.isCancelled, self.joinGeneration == ticket, self.joinedConv == conversationId else { return }
                self.toasts.show("Could not ring other members")
            }
        }
    }

    // mesh bookkeeping

    /// One peer connection per remote member, fed from the ONE shared local
    /// acquisition (web ensurePeerConnection :212-245 parity).
    private func ensurePeerConnection(_ peerId: String) -> (any PulseGroupCallPeerConnecting)? {
        guard mediaReady, joinedConv != nil, members.contains(where: { $0.id == peerId }) else { return nil }
        if let existing = peers[peerId] { return existing }
        let bridge = MeshPeerBridge(peerId: peerId, engine: self)
        guard let pc = media.makeMeshPeerConnection(delegate: bridge) else { return nil }
        peers[peerId] = pc
        peerBridges[peerId] = bridge
        // Attach the shared tracks at creation (web addTrack parity) so the
        // SDP negotiation carries them from the first offer/answer.
        if let audio = sharedAudio {
            pc.attachSharedTrack(audio, streamId: Self.sharedStreamId)
        }
        if kind == .video, cameraLive, let video = localVideoTrack {
            pc.attachSharedTrack(video, streamId: Self.sharedStreamId)
        }
        return pc
    }

    static let sharedStreamId = "pulse-gstream0"

    private func closePeer(_ peerId: String) {
        let old = peers.removeValue(forKey: peerId)
        peerBridges[peerId] = nil // invalidate queued callbacks before native close
        old?.close()
        pendingIce[peerId] = nil
        peerRemoteDescriptionSet.remove(peerId)
        localDescriptionSent.remove(peerId)
        pendingLocalIce[peerId] = nil
        negotiatingPeers.remove(peerId)
        remoteTracks[peerId] = nil
        peerStates[peerId] = nil
    }

    /// Roster sync for a call I'm in. Departed members get their connection
    /// closed; arrivals whose id sorts below mine get my offer (web
    /// applyRoster parity - the deterministic mesh rule lives in the policy).
    private func applyRoster(_ roster: [PulseGroupCallMember], rosterConvId: String) {
        guard joinedConv == rosterConvId, mediaReady else { return }
        members = roster
        ongoingMembers = roster
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: viewer.id,
            rosterIds: roster.map(\.id),
            connectedPeerIds: Array(peers.keys),
        )
        for peerId in plan.departing {
            closePeer(peerId)
        }
        if joinedAt == nil {
            joinedAt = Date()
            durationSec = 0
        }
        if uiState == .joining {
            uiState = .active
        }
        joinSentAt = nil
        ringAdmittedCall(conversationId: rosterConvId)
        let ticket = joinGeneration
        for memberId in plan.toOffer {
            Task { await createAndSendOffer(to: memberId, conversationId: rosterConvId, ticket: ticket) }
        }
    }

    private func ownsPeer(_ peerId: String, _ pc: any PulseGroupCallPeerConnecting, _ ticket: UUID) -> Bool {
        !Task.isCancelled && joinGeneration == ticket && isInCall && peers[peerId] === pc
    }

    private func createAndSendOffer(to peerId: String, conversationId: String, ticket: UUID) async {
        guard joinGeneration == ticket, joinedConv == conversationId, !negotiatingPeers.contains(peerId),
              let pc = ensurePeerConnection(peerId), !localDescriptionSent.contains(peerId) else { return }
        negotiatingPeers.insert(peerId)
        defer { if ownsPeer(peerId, pc, ticket) { negotiatingPeers.remove(peerId) } }
        do {
            let sdp = try await pc.createOffer()
            guard ownsPeer(peerId, pc, ticket) else { return }
            try await pc.setLocalDescription(sdp: sdp)
            guard ownsPeer(peerId, pc, ticket) else { return }
            guard signaling.send(event: "gcall:offer", payload: GroupCallWire.offerPayload(
                conversationId: conversationId, from: viewer.id, to: peerId, kind: kind, sdp: sdp, callId: joinedCallId
            )) else { handleSocketDisconnected(); return }
            flushLocalIce(for: peerId)
        } catch {
            guard ownsPeer(peerId, pc, ticket) else { return }
            closePeer(peerId)
            toasts.show("Could not connect to a call member. Leave and rejoin to retry.")
        }
    }

    private func answerOffer(from peerId: String, conversationId: String, sdp: String, ticket: UUID) async {
        guard joinGeneration == ticket, joinedConv == conversationId, !negotiatingPeers.contains(peerId),
              let pc = ensurePeerConnection(peerId), !peerRemoteDescriptionSet.contains(peerId) else { return }
        negotiatingPeers.insert(peerId)
        defer { if ownsPeer(peerId, pc, ticket) { negotiatingPeers.remove(peerId) } }
        do {
            try await pc.setRemoteDescription(sdp: sdp, type: "offer")
            guard ownsPeer(peerId, pc, ticket) else { return }
            peerRemoteDescriptionSet.insert(peerId)
            let answerSdp = try await pc.createAnswer()
            guard ownsPeer(peerId, pc, ticket) else { return }
            try await pc.setLocalDescription(sdp: answerSdp)
            guard ownsPeer(peerId, pc, ticket) else { return }
            guard signaling.send(event: "gcall:answer", payload: GroupCallWire.answerPayload(
                conversationId: conversationId, from: viewer.id, to: peerId, sdp: answerSdp, callId: joinedCallId
            )) else { handleSocketDisconnected(); return }
            flushLocalIce(for: peerId)
            await drainPendingIce(for: peerId, pc: pc, ticket: ticket)
        } catch {
            guard ownsPeer(peerId, pc, ticket) else { return }
            closePeer(peerId)
            toasts.show("Could not connect to a call member. Leave and rejoin to retry.")
        }
    }

    private func applyAnswer(from peerId: String, sdp: String, ticket: UUID) async {
        guard joinGeneration == ticket, joinedConv != nil, let pc = peers[peerId],
              localDescriptionSent.contains(peerId), !peerRemoteDescriptionSet.contains(peerId),
              !negotiatingPeers.contains(peerId) else { return }
        negotiatingPeers.insert(peerId)
        defer { if ownsPeer(peerId, pc, ticket) { negotiatingPeers.remove(peerId) } }
        do {
            try await pc.setRemoteDescription(sdp: sdp, type: "answer")
            guard ownsPeer(peerId, pc, ticket) else { return }
            peerRemoteDescriptionSet.insert(peerId)
            await drainPendingIce(for: peerId, pc: pc, ticket: ticket)
        } catch {
            guard ownsPeer(peerId, pc, ticket) else { return }
            closePeer(peerId)
            toasts.show("Could not connect to a call member. Leave and rejoin to retry.")
        }
    }

    private func drainPendingIce(for peerId: String, pc: any PulseGroupCallPeerConnecting, ticket: UUID) async {
        guard ownsPeer(peerId, pc, ticket), let queued = pendingIce.removeValue(forKey: peerId) else { return }
        for ice in queued {
            guard ownsPeer(peerId, pc, ticket) else { return }
            try? await pc.addRemoteCandidate(candidate: ice.candidate, sdpMid: ice.sdpMid, sdpMLineIndex: ice.sdpMLineIndex)
        }
    }

    private func flushLocalIce(for peerId: String) {
        localDescriptionSent.insert(peerId)
        let queued = pendingLocalIce.removeValue(forKey: peerId) ?? []
        for payload in queued {
            guard signaling.send(event: "gcall:ice", payload: payload) else { handleSocketDisconnected(); return }
        }
    }

    private func matchesJoinedCall(_ raw: [String: Any]) -> Bool {
        guard let conv = joinedConv, let callId = joinedCallId else { return false }
        return raw["conversationId"] as? String == conv && raw["callId"] as? String == callId
    }

    // signaling inbound (session routes .groupCallSignal here)

    public func handleGroupCallSignal(event: String, raw: [String: Any]) {
        switch event {
        case "gcall:ring":
            guard let decoded = Ring.decode(raw) else { return }
            // CallKit and the banner must keep the same caller until answered or dismissed.
            guard decoded.caller.id != viewer.id, ring == nil else { return }
            // Only ring when idle - a participant never sees their own ring
            // (web :459-464). The ended-card state stays untouched too.
            guard uiState == .idle, !voiceCallBusy() else { return }
            ring = decoded
            PulseHaptics.incoming()

        case "gcall:state":
            guard let conv = raw["conversationId"] as? String, !conv.isEmpty else { return }
            // Web applyRoster parity (group-call-overlay.tsx :470): states for
            // conversations other than the OPEN room are ignored - no phantom
            // 'Ongoing' banners for rooms you are not looking at. The one
            // addition to the web rule: a state for the call I am actually IN
            // still syncs the roster when its room is closed (the shell-level
            // overlay must stay consistent - web only ever filters the banner
            // path, its session dies with the room's page context).
            guard conv == activeConversationId || conv == joinedConv else { return }
            let roster = PulseGroupCallMember.decodeList(raw["members"])
            let iAmMember = roster.contains { $0.id == viewer.id }
            if conv == joinedConv {
                if let expectedJoin, raw["callId"] as? String != expectedJoin.callId { return }
                guard let callId = raw["callId"] as? String, !callId.isEmpty,
                      joinedCallId == nil || joinedCallId == callId else { return }
                joinedCallId = callId
                expectedJoin = nil
                guard iAmMember else {
                    teardownMedia()
                    uiState = .ended
                    summary = "You are no longer in this call"
                    return
                }
                // Membership per the relay (this device joined, or the same
                // account joined from ANOTHER device - iOS never silently
                // attaches media for the cross-device case; joinedConv is
                // only ever set by the local join pipeline).
                ongoingElsewhere = false
                applyRoster(roster, rosterConvId: conv)
            } else if uiState == .idle, joinedConv == nil {
                probeGeneration = UUID() // a slower HTTP probe cannot undo this relay state
                if !roster.isEmpty && !iAmMember, let callId = raw["callId"] as? String, !callId.isEmpty {
                    ongoingKind = CallKind(wireValue: raw["kind"] as? String)
                    ongoingMembers = roster
                    ongoingConversationId = conv
                    ongoingCallId = callId
                    ongoingElsewhere = true
                } else {
                    ongoingMembers = []
                    ongoingConversationId = nil
                    ongoingElsewhere = false
                }
            }

        case "gcall:offer":
            guard matchesJoinedCall(raw), raw["to"] as? String == viewer.id,
                  let conv = joinedConv, let from = raw["from"] as? String,
                  from < viewer.id, members.contains(where: { $0.id == from }),
                  let sdp = raw["sdp"] as? String, !sdp.isEmpty else { return }
            let ticket = joinGeneration
            Task { await answerOffer(from: from, conversationId: conv, sdp: sdp, ticket: ticket) }

        case "gcall:answer":
            guard matchesJoinedCall(raw), raw["to"] as? String == viewer.id,
                  let from = raw["from"] as? String, viewer.id < from, peers[from] != nil,
                  let sdp = raw["sdp"] as? String, !sdp.isEmpty else { return }
            let ticket = joinGeneration
            Task { await applyAnswer(from: from, sdp: sdp, ticket: ticket) }

        case "gcall:ice":
            guard matchesJoinedCall(raw), raw["to"] as? String == viewer.id,
                  let ice = CallIceEnvelope.decode(raw), ice.base.from != viewer.id,
                  members.contains(where: { $0.id == ice.base.from }) else { return }
            let peerId = ice.base.from, ticket = joinGeneration
            if peerRemoteDescriptionSet.contains(peerId), let pc = peers[peerId] {
                Task {
                    guard ownsPeer(peerId, pc, ticket) else { return }
                    try? await pc.addRemoteCandidate(candidate: ice.candidate, sdpMid: ice.sdpMid, sdpMLineIndex: ice.sdpMLineIndex)
                }
            } else if pendingIce[peerId, default: []].count < 256 {
                pendingIce[peerId, default: []].append(ice)
            }

        case "gcall:ended":
            if uiState == .joining, let expectedJoin, raw["conversationId"] as? String == expectedJoin.roomId,
               raw["callId"] as? String == expectedJoin.callId {
                teardownMedia()
                uiState = .ended
                summary = "This call is no longer available"
            }
            if let ring, raw["conversationId"] as? String == ring.conversationId, raw["callId"] as? String == ring.callId {
                dismissRing()
            }
            if matchesJoinedCall(raw) {
                teardownMedia()
                uiState = .ended
                summary = "Call ended"
            }
            if raw["conversationId"] as? String == ongoingConversationId, raw["callId"] as? String == ongoingCallId {
                ongoingElsewhere = false
                ongoingMembers = []
                ongoingConversationId = nil
            }

        case "gcall:full":
            if joinIntent, uiState == .joining, let joinedConv, raw["conversationId"] as? String == joinedConv {
                teardownMedia()
                uiState = .idle
                toasts.show("That call is full (8 max)")
            }

        default:
            break
        }
    }

    // ongoing-call probe (web 20s probe parity)

    private func probeNow() {
        guard uiState == .idle, joinedConv == nil,
              let conv = activeConversationId, !conv.isEmpty else { return }
        let ticket = UUID(), joinTicket = joinGeneration
        probeGeneration = ticket
        Task { await probeOngoing(conversationId: conv, ticket: ticket, joinTicket: joinTicket) }
    }

    private func probeOngoing(conversationId: String, ticket: UUID, joinTicket: UUID) async {
        guard probeGeneration == ticket, joinGeneration == joinTicket, let api = apiProvider() else { return }
        // Socket down / no call → { members: [] } (honest empty).
        guard let snapshot = try? await api.groupCallState(conversationId: conversationId) else { return }
        // Late answers re-verify against the CURRENT context.
        guard probeGeneration == ticket, joinGeneration == joinTicket,
              activeConversationId == conversationId, uiState == .idle, joinedConv == nil else { return }
        let roster = snapshot.members
        if !roster.isEmpty, !roster.contains(where: { $0.id == viewer.id }), let callId = snapshot.callId, !callId.isEmpty {
            ongoingKind = snapshot.resolvedKind
            ongoingMembers = roster
            ongoingConversationId = conversationId
            ongoingCallId = callId
            ongoingElsewhere = true
        } else {
            ongoingElsewhere = false
            ongoingMembers = []
            ongoingConversationId = nil
        }
    }

    // heartbeat

    func tick(now: Date = Date()) {
        if uiState == .joining, let sentAt = joinSentAt, now.timeIntervalSince(sentAt) >= 30 {
            leaveCall()
            summary = "Call could not connect. Try again."
        }
        if uiState == .active, let joinedAt {
            durationSec = max(0, Int(Date().timeIntervalSince(joinedAt).rounded()))
        }
        // Preserve the relay deadline; delayed arrivals cannot restart it.
        if ring.map({ now >= $0.expiresAt }) == true {
            ring = nil
        }
    }

    // teardown

    private func teardownMedia() {
        expectedJoin = nil
        joinGeneration = UUID()
        preparationTask?.cancel()
        ringTask?.cancel()
        ringTask = nil
        let oldPeers = peers
        peers.removeAll()
        peerBridges.removeAll()
        for (_, pc) in oldPeers { pc.close() }
        localDescriptionSent.removeAll()
        pendingLocalIce.removeAll()
        negotiatingPeers.removeAll()
        joinedCallId = nil
        joinSentAt = nil
        pendingRing = false
        pendingIce.removeAll()
        peerRemoteDescriptionSet.removeAll()
        remoteTracks.removeAll()
        peerStates.removeAll()
        if cameraLive {
            media.stopSharedVideoCapture()
        }
        cameraLive = false
        if sharedAudio != nil { media.setSharedAudioEnabled(false) }
        sharedAudio = nil
        localVideoTrack = nil
        joinedConv = nil
        joinedAt = nil
        joinIntent = false
        mediaReady = false
        members = []
        durationSec = 0
        micEnabled = true
        cameraEnabled = true
        if ownsAudioSession {
            ownsAudioSession = false
            restoreAudioSession()
        }
    }

    // peer bridge callbacks (main actor via MeshPeerBridge)

    private func handleLocalCandidate(peerId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) {
        guard let conv = joinedConv, peers[peerId] != nil else { return }
        let payload = GroupCallWire.icePayload(
            conversationId: conv,
            from: viewer.id,
            to: peerId,
            kind: kind,
            candidate: candidate,
            sdpMid: sdpMid,
            sdpMLineIndex: sdpMLineIndex,
            callId: joinedCallId,
        )
        if localDescriptionSent.contains(peerId) {
            if !signaling.send(event: "gcall:ice", payload: payload) { handleSocketDisconnected() }
        } else if pendingLocalIce[peerId, default: []].count < 256 {
            pendingLocalIce[peerId, default: []].append(payload)
        }
    }

    private func handlePeerState(peerId: String, state: PulseCallConnectionState) {
        if state == .failed || state == .closed { closePeer(peerId) }
        peerStates[peerId] = state
    }

    /// A remote member's video track arrived over their m=video line -
    /// publish it so the grid tile renders the live surface.
    private func handleRemoteVideoTrack(peerId: String, track: RTCVideoTrack) {
        guard remoteTracks[peerId] !== track else { return }
        remoteTracks[peerId] = track
    }

    // peer bridge (WebRTC callbacks → main actor, per peer)

    /// Nonisolated bridge: WebRTC threads land here, then hop to the main
    /// actor before touching engine state (PulseCallEngine.PeerBridge
    /// pattern, with the peer id captured per connection).
    private final class MeshPeerBridge: PulseCallPeerDelegate {
        let peerId: String
        weak var engine: PulseGroupCallEngine?

        init(peerId: String, engine: PulseGroupCallEngine) {
            self.peerId = peerId
            self.engine = engine
        }

        func peerDidProduceLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) {
            let peerId = self.peerId
            Task { @MainActor in
                guard let engine = self.engine, engine.peerBridges[peerId] === self else { return }
                engine.handleLocalCandidate(
                    peerId: peerId,
                    candidate: candidate,
                    sdpMid: sdpMid,
                    sdpMLineIndex: sdpMLineIndex,
                )
            }
        }

        func peerConnectionStateDidChange(_ state: PulseCallConnectionState) {
            let peerId = self.peerId
            Task { @MainActor in
                guard let engine = self.engine, engine.peerBridges[peerId] === self else { return }
                engine.handlePeerState(peerId: peerId, state: state)
            }
        }

        func peerDidReceiveRemoteVideoTrack(_ track: RTCVideoTrack) {
            let peerId = self.peerId
            Task { @MainActor in
                guard let engine = self.engine, engine.peerBridges[peerId] === self else { return }
                engine.handleRemoteVideoTrack(peerId: peerId, track: track)
            }
        }
    }
}
