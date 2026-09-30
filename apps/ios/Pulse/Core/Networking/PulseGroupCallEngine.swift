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

        /// Tolerant decode of the relayed gcall:ring payload
        /// { conversationId, kind, caller: {id,name,color,avatar}, title }.
        static func decode(_ raw: [String: Any]) -> Ring? {
            guard let conversationId = raw["conversationId"] as? String,
                  !conversationId.isEmpty else { return nil }
            let callerRaw = raw["caller"] as? [String: Any] ?? [:]
            guard let caller = PulseGroupCallMember.decode(callerRaw) else { return nil }
            let title = (raw["title"] as? String) ?? ""
            return Ring(
                conversationId: conversationId,
                kind: CallKind(wireValue: raw["kind"] as? String),
                caller: caller,
                title: title,
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

    // live mesh state (cleared on every teardown)
    private var peers: [String: any PulseGroupCallPeerConnecting] = [:]
    private var peerBridges: [String: MeshPeerBridge] = [:]
    private var pendingIce: [String: [CallIceEnvelope]] = [:]
    private var peerRemoteDescriptionSet: Set<String> = []
    private var sharedAudio: RTCAudioTrack?
    private var cameraLive = false
    private var joinedConv: String?
    private var joinedAt: Date?
    private var joinIntent = false
    private var mediaReady = false
    private var ringArrivedAt: Date?
    /// The conversation currently open on screen (drives the probe +
    /// the ONGOING banner, web hook options.conversationId parity).
    /// Public read-only since 3-d - ChatRoomView's onDisappear compares it
    /// before standing the room's probe/banner down.
    public private(set) var activeConversationId: String?
    /// The conversation whose live call the ONGOING banner describes
    /// (set from gcall:state / the probe, cleared with the banner).
    private var ongoingConversationId: String?

    private var cancellables: Set<AnyCancellable> = []

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
        toasts: ToastCenter
    ) {
        self.viewer = viewer
        self.signaling = signaling
        self.media = media
        self.apiProvider = apiProvider
        self.connectedProvider = connectedProvider
        self.voiceCallBusy = voiceCallBusy
        self.toasts = toasts
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
        PulseHaptics.tap()
        startJoin(target.conversationId, wanted: target.kind, ringOthers: false, title: target.title.isEmpty ? displayTitle : target.title)
    }

    /// Silently join the ONGOING call in the open room - never re-rings.
    public func joinOngoing() {
        guard uiState == .idle, let conv = ongoingConversationId else { return }
        PulseHaptics.tap()
        let wanted = ongoingKind
        ongoingElsewhere = false
        startJoin(conv, wanted: wanted, ringOthers: false, title: displayTitle)
    }

    public func dismissRing() {
        ring = nil
        ringArrivedAt = nil
    }

    public func ignoreOngoing() {
        ongoingElsewhere = false
    }

    public func leaveCall() {
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
        errorText = nil
        uiState = .idle
    }

    public func dismissSummary() {
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

    private func startJoin(_ targetConvId: String, wanted: CallKind, ringOthers: Bool, title: String) {
        guard uiState == .idle else { return }
        guard !targetConvId.isEmpty, !viewer.id.isEmpty else { return }
        guard !voiceCallBusy() else {
            toasts.show("Finish your current call first")
            return
        }
        guard connectedProvider() else {
            toasts.show("You are offline - calls need a connection")
            return
        }
        joinIntent = true
        kind = wanted
        displayTitle = title
        errorText = nil
        summary = nil
        ring = nil
        ringArrivedAt = nil
        uiState = .joining
        Task { await beginJoin(targetConvId, wanted: wanted, ringOthers: ringOthers, title: title) }
    }

    private func beginJoin(_ convId: String, wanted: CallKind, ringOthers: Bool, title: String) async {
        var resolved = wanted
        // Camera capability resolves FIRST (web acquireMedia degrade: no
        // usable camera → the call degrades to voice with an honest toast).
        if wanted == .video {
            let cameraOk = await media.requestCameraPermission() && media.canCaptureVideo()
            guard uiState == .joining, joinedConv == nil else { return }
            if !cameraOk {
                toasts.show("Camera unavailable - joining as a voice call")
                resolved = .voice
                kind = .voice
            }
        }
        let micGranted = await media.requestMicPermission()
        guard uiState == .joining, joinedConv == nil else { return }
        guard micGranted else {
            uiState = .idle
            joinIntent = false
            errorText = Self.micDeniedMessage
            return
        }
        PulseCallAudioSession.shared.activateForCall()
        guard let audio = media.makeSharedAudioTrack() else {
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
        signaling.send(event: "gcall:join", payload: GroupCallWire.joinPayload(
            conversationId: convId,
            kind: resolved,
            userId: viewer.id,
            userName: viewer.name,
            userColor: viewer.color,
            userAvatar: viewer.avatar,
        ))
        if ringOthers {
            // Ring the other members (online → socket banner, offline → push).
            // Fire-and-forget - the response only confirms validation.
            Task { await ringOthersViaApi(conversationId: convId, kind: resolved) }
        }
    }

    private func ringOthersViaApi(conversationId: String, kind: CallKind) async {
        guard let api = apiProvider() else { return }
        do {
            try await api.ringGroupCall(conversationId: conversationId, userId: viewer.id, kind: kind)
        } catch {
            toasts.show("Could not ring other members")
        }
    }

    // mesh bookkeeping

    /// One peer connection per remote member, fed from the ONE shared local
    /// acquisition (web ensurePeerConnection :212-245 parity).
    private func ensurePeerConnection(_ peerId: String) -> (any PulseGroupCallPeerConnecting)? {
        if let existing = peers[peerId] { return existing }
        guard mediaReady, joinedConv != nil else { return nil }
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
        peers[peerId]?.close()
        peers[peerId] = nil
        peerBridges[peerId] = nil
        pendingIce[peerId] = nil
        peerRemoteDescriptionSet.remove(peerId)
        remoteTracks[peerId] = nil
        peerStates[peerId] = nil
    }

    /// Roster sync for a call I'm in. Departed members get their connection
    /// closed; arrivals whose id sorts below mine get my offer (web
    /// applyRoster parity - the deterministic mesh rule lives in the policy).
    private func applyRoster(_ roster: [PulseGroupCallMember], rosterConvId: String) {
        members = roster
        ongoingMembers = roster
        guard mediaReady else { return }
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
        for memberId in plan.toOffer {
            Task { await createAndSendOffer(to: memberId, conversationId: rosterConvId) }
        }
    }

    private func createAndSendOffer(to peerId: String, conversationId: String) async {
        guard joinedConv == conversationId, let pc = ensurePeerConnection(peerId) else { return }
        do {
            let sdp = try await pc.createOffer()
            try await pc.setLocalDescription(sdp: sdp)
            guard joinedConv == conversationId, peers[peerId] === pc else { return }
            signaling.send(event: "gcall:offer", payload: GroupCallWire.offerPayload(
                conversationId: conversationId,
                from: viewer.id,
                to: peerId,
                kind: kind,
                sdp: sdp,
            ))
        } catch {
            NSLog("[gcall] offer create failed: %@", error.localizedDescription)
        }
    }

    private func answerOffer(from peerId: String, conversationId: String, sdp: String) async {
        guard joinedConv == conversationId, let pc = ensurePeerConnection(peerId) else { return }
        do {
            try await pc.setRemoteDescription(sdp: sdp, type: "offer")
            peerRemoteDescriptionSet.insert(peerId)
            let answerSdp = try await pc.createAnswer()
            try await pc.setLocalDescription(sdp: answerSdp)
            guard joinedConv == conversationId else { return }
            signaling.send(event: "gcall:answer", payload: GroupCallWire.answerPayload(
                conversationId: conversationId,
                from: viewer.id,
                to: peerId,
                sdp: answerSdp,
            ))
            await drainPendingIce(for: peerId, pc: pc)
        } catch {
            NSLog("[gcall] offer apply failed: %@", error.localizedDescription)
        }
    }

    private func applyAnswer(from peerId: String, sdp: String) async {
        guard joinedConv != nil, let pc = peers[peerId] else { return }
        do {
            try await pc.setRemoteDescription(sdp: sdp, type: "answer")
            peerRemoteDescriptionSet.insert(peerId)
            await drainPendingIce(for: peerId, pc: pc)
        } catch {
            NSLog("[gcall] answer apply failed: %@", error.localizedDescription)
        }
    }

    private func drainPendingIce(for peerId: String, pc: any PulseGroupCallPeerConnecting) async {
        guard let queued = pendingIce[peerId], !queued.isEmpty else { return }
        pendingIce[peerId] = nil
        for ice in queued {
            try? await pc.addRemoteCandidate(
                candidate: ice.candidate,
                sdpMid: ice.sdpMid,
                sdpMLineIndex: ice.sdpMLineIndex,
            )
        }
    }

    // signaling inbound (session routes .groupCallSignal here)

    public func handleGroupCallSignal(event: String, raw: [String: Any]) {
        switch event {
        case "gcall:ring":
            guard let decoded = Ring.decode(raw) else { return }
            guard decoded.caller.id != viewer.id else { return }
            // Only ring when idle - a participant never sees their own ring
            // (web :459-464). The ended-card state stays untouched too.
            guard uiState == .idle else { return }
            ring = decoded
            ringArrivedAt = Date()
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
            if iAmMember {
                // Membership per the relay (this device joined, or the same
                // account joined from ANOTHER device - iOS never silently
                // attaches media for the cross-device case; joinedConv is
                // only ever set by the local join pipeline).
                ongoingElsewhere = false
                applyRoster(roster, rosterConvId: conv)
            } else if uiState == .idle, joinedConv == nil {
                if !roster.isEmpty {
                    ongoingKind = CallKind(wireValue: raw["kind"] as? String)
                    ongoingMembers = roster
                    ongoingConversationId = conv
                    ongoingElsewhere = true
                } else {
                    ongoingMembers = []
                    ongoingConversationId = nil
                    ongoingElsewhere = false
                }
            }

        case "gcall:offer":
            guard let conv = raw["conversationId"] as? String, conv == joinedConv,
                  let from = raw["from"] as? String, !from.isEmpty, from != viewer.id,
                  let sdp = raw["sdp"] as? String, !sdp.isEmpty else { return }
            Task { await answerOffer(from: from, conversationId: conv, sdp: sdp) }

        case "gcall:answer":
            guard let from = raw["from"] as? String, !from.isEmpty, from != viewer.id,
                  let sdp = raw["sdp"] as? String, !sdp.isEmpty else { return }
            guard joinedConv != nil, peers[from] != nil else { return }
            Task { await applyAnswer(from: from, sdp: sdp) }

        case "gcall:ice":
            guard let ice = CallIceEnvelope.decode(raw) else { return }
            guard ice.base.from != viewer.id, joinedConv != nil else { return }
            let peerId = ice.base.from
            if peerRemoteDescriptionSet.contains(peerId), let pc = peers[peerId] {
                Task { try? await pc.addRemoteCandidate(
                    candidate: ice.candidate,
                    sdpMid: ice.sdpMid,
                    sdpMLineIndex: ice.sdpMLineIndex,
                ) }
            } else {
                // Early candidate (the peer's SDP hasn't landed yet) - queue
                // until the remote description arrives, then drain.
                pendingIce[peerId, default: []].append(ice)
            }

        case "gcall:ended":
            // The relay only fans this to the call's own room, but the conv
            // guard keeps a stray event from killing an unrelated call.
            let conv = raw["conversationId"] as? String
            if let joinedConv, conv == nil || conv == joinedConv {
                teardownMedia()
                uiState = .ended
                summary = "Call ended"
            }
            ongoingElsewhere = false
            ongoingMembers = []
            ongoingConversationId = nil

        case "gcall:full":
            if joinIntent {
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
        Task { await probeOngoing(conversationId: conv) }
    }

    private func probeOngoing(conversationId: String) async {
        guard let api = apiProvider() else { return }
        // Socket down / no call → { members: [] } (honest empty).
        guard let snapshot = try? await api.groupCallState(conversationId: conversationId) else { return }
        // Late answers re-verify against the CURRENT context.
        guard activeConversationId == conversationId, uiState == .idle, joinedConv == nil else { return }
        let roster = snapshot.members
        if !roster.isEmpty, !roster.contains(where: { $0.id == viewer.id }) {
            ongoingKind = snapshot.resolvedKind
            ongoingMembers = roster
            ongoingConversationId = conversationId
            ongoingElsewhere = true
        } else {
            ongoingElsewhere = false
            if roster.contains(where: { $0.id == viewer.id }) {
                ongoingMembers = []
                ongoingConversationId = nil
            }
        }
    }

    // heartbeat

    func tick() {
        if uiState == .active, let joinedAt {
            durationSec = max(0, Int(Date().timeIntervalSince(joinedAt).rounded()))
        }
        // The local ring guard - group rings have no server-side timeout;
        // a stuck incoming surface is worse than a missed ring.
        if ring != nil, let arrivedAt = ringArrivedAt,
           Date().timeIntervalSince(arrivedAt) >= PulseGroupCallPolicy.ringTimeoutSec {
            ring = nil
            ringArrivedAt = nil
        }
    }

    // teardown

    private func teardownMedia() {
        for (_, pc) in peers {
            pc.close()
        }
        peers.removeAll()
        peerBridges.removeAll()
        pendingIce.removeAll()
        peerRemoteDescriptionSet.removeAll()
        remoteTracks.removeAll()
        peerStates.removeAll()
        if cameraLive {
            media.stopSharedVideoCapture()
        }
        cameraLive = false
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
        PulseCallAudioSession.shared.restore()
    }

    // peer bridge callbacks (main actor via MeshPeerBridge)

    func handleLocalCandidate(peerId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) {
        guard let conv = joinedConv else { return }
        signaling.send(event: "gcall:ice", payload: GroupCallWire.icePayload(
            conversationId: conv,
            from: viewer.id,
            to: peerId,
            kind: kind,
            candidate: candidate,
            sdpMid: sdpMid,
            sdpMLineIndex: sdpMLineIndex,
        ))
    }

    func handlePeerState(peerId: String, state: PulseCallConnectionState) {
        peerStates[peerId] = state
    }

    /// A remote member's video track arrived over their m=video line -
    /// publish it so the grid tile renders the live surface.
    func handleRemoteVideoTrack(peerId: String, track: RTCVideoTrack) {
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
                self.engine?.handleLocalCandidate(
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
                self.engine?.handlePeerState(peerId: peerId, state: state)
            }
        }

        func peerDidReceiveRemoteVideoTrack(_ track: RTCVideoTrack) {
            let peerId = self.peerId
            Task { @MainActor in
                self.engine?.handleRemoteVideoTrack(peerId: peerId, track: track)
            }
        }
    }
}
