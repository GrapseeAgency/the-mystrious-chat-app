import XCTest
import Combine
import WebRTC
@testable import Pulse

@MainActor
final class GroupCallPreparationOwnershipTests: XCTestCase {
    private final class Signals: PulseCallSignalingSending {
        var isReady = true
        var events: [String] = []
        var payloads: [(String, [String: Any])] = []
        var rejectJoin = false
        @discardableResult
        func send(event: String, payload: [String: Any]) -> Bool {
            guard isReady, !(rejectJoin && event == "gcall:join") else { return false }
            events.append(event)
            payloads.append((event, payload))
            return true
        }
    }
    private final class Media: PulseGroupCallMediaProviding {
        var requested: (() -> Void)?
        var prompts: [CheckedContinuation<Bool, Never>] = []
        var tracks = 0
        var lendAudio = false
        var audioEnabled: [Bool] = []
        var peer: Peer?
        private lazy var factory = RTCPeerConnectionFactory()
        func requestMicPermission() async -> Bool {
            await withCheckedContinuation { prompts.append($0); requested?() }
        }
        func requestCameraPermission() async -> Bool { false }
        func canCaptureVideo() -> Bool { false }
        func makeMeshPeerConnection(delegate: (any PulseCallPeerDelegate)?) -> (any PulseGroupCallPeerConnecting)? {
            peer?.delegate = delegate
            return peer
        }
        func makeSharedAudioTrack() -> RTCAudioTrack? {
            tracks += 1
            return lendAudio ? factory.audioTrack(withTrackId: "test-group-audio") : nil
        }
        func startSharedVideoCapture() -> RTCVideoTrack? { nil }
        func stopSharedVideoCapture() {}
        func setSharedAudioEnabled(_ enabled: Bool) { audioEnabled.append(enabled) }
        func setSharedVideoEnabled(_ enabled: Bool) {}
        func switchSharedCamera() {}
    }

    private final class Peer: PulseGroupCallPeerConnecting {
        var delegate: (any PulseCallPeerDelegate)?
        var offerRequested: (() -> Void)?
        var offer: CheckedContinuation<String, Never>?
        var onSetLocal: (() -> Void)?
        var closed = false
        func createOffer() async throws -> String {
            await withCheckedContinuation { offer = $0; offerRequested?() }
        }
        func createAnswer() async throws -> String { "answer" }
        func setLocalDescription(sdp: String) async throws { onSetLocal?() }
        func setRemoteDescription(sdp: String, type: String) async throws {}
        func addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) async throws {}
        func attachSharedTrack(_ track: RTCMediaStreamTrack, streamId: String) {}
        func setAudioEnabled(_ enabled: Bool) {}
        func enableLocalVideoCapture() -> Bool { false }
        func setVideoEnabled(_ enabled: Bool) {}
        func switchCamera() {}
        func localVideoTrack() -> RTCVideoTrack? { nil }
        func remoteVideoTrack() -> RTCVideoTrack? { nil }
        func close() { closed = true }
    }

    private func engine(_ media: Media, _ signals: Signals, restore: @escaping @MainActor () -> Void = {}) -> PulseGroupCallEngine {
        PulseGroupCallEngine(viewer: PulseViewer(id: "me", name: "Me"), signaling: signals, media: media,
            apiProvider: { nil }, connectedProvider: { true }, voiceCallBusy: { false }, toasts: ToastCenter(),
            activateAudioSession: { true }, restoreAudioSession: restore)
    }

    private func join(_ engine: PulseGroupCallEngine, _ media: Media, room: String = "room") async throws {
        let prompt = expectation(description: "microphone")
        media.requested = { prompt.fulfill() }
        engine.startCall(kind: .voice, conversationId: room, title: room)
        let preparation = try XCTUnwrap(engine.preparationTask)
        await fulfillment(of: [prompt], timeout: 1)
        let continuation = media.prompts.removeFirst()
        continuation.resume(returning: true)
        await preparation.value
    }

    private func roster(_ room: String = "room", call: String = "current", ids: [String] = ["me"]) -> [String: Any] {
        ["conversationId": room, "callId": call, "kind": "voice",
         "members": ids.map { ["id": $0, "name": $0, "color": "emerald"] }]
    }

    func testOldPermissionCannotJoinAfterLeavingAndStartingAnotherCall() async throws {
        let media = Media(), signals = Signals()
        let engine = PulseGroupCallEngine(viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, connectedProvider: { true },
            voiceCallBusy: { false }, toasts: ToastCenter())
        let first = expectation(description: "old microphone prompt")
        media.requested = { first.fulfill() }
        engine.startCall(kind: .voice, conversationId: "old-room", title: "Old")
        let old = try XCTUnwrap(engine.preparationTask)
        await fulfillment(of: [first], timeout: 1)
        engine.leaveCall()
        engine.dismissSummary()
        let second = expectation(description: "new microphone prompt")
        media.requested = { second.fulfill() }
        engine.startCall(kind: .voice, conversationId: "new-room", title: "New")
        let current = try XCTUnwrap(engine.preparationTask)
        await fulfillment(of: [second], timeout: 1)
        media.prompts[0].resume(returning: true)
        await old.value
        XCTAssertEqual(engine.uiState, .joining)
        XCTAssertEqual(media.tracks, 0)
        XCTAssertFalse(signals.events.contains("gcall:join"))
        media.prompts[1].resume(returning: false)
        await current.value
        XCTAssertEqual(engine.uiState, .idle)
        XCTAssertEqual(media.tracks, 0)
    }

    func testUnjoinedTransportDoesNotPromptOrAcquireTracks() {
        let media = Media(), signals = Signals()
        signals.isReady = false
        let engine = engine(media, signals)
        engine.startCall(kind: .voice, conversationId: "room", title: "Room")
        XCTAssertEqual(engine.uiState, .idle)
        XCTAssertNil(engine.preparationTask)
        XCTAssertEqual(media.tracks, 0)
        XCTAssertTrue(signals.events.isEmpty)
    }

    func testDisconnectInvalidatesPermissionEvenWhenTransportReconnects() async throws {
        let media = Media(), signals = Signals()
        let engine = engine(media, signals)
        let prompt = expectation(description: "permission pending")
        media.requested = { prompt.fulfill() }
        engine.startCall(kind: .voice, conversationId: "room", title: "Room")
        let preparation = try XCTUnwrap(engine.preparationTask)
        await fulfillment(of: [prompt], timeout: 1)
        signals.isReady = false
        engine.handleSocketDisconnected()
        signals.isReady = true
        media.prompts[0].resume(returning: true)
        await preparation.value
        XCTAssertEqual(engine.uiState, .ended)
        XCTAssertEqual(media.tracks, 0)
        XCTAssertTrue(signals.events.isEmpty)
    }

    func testFailedJoinDispatchReleasesOnlyItsOwnAudioAndEnds() async throws {
        let media = Media(), signals = Signals()
        media.lendAudio = true
        signals.rejectJoin = true
        var restores = 0
        let engine = engine(media, signals, restore: { restores += 1 })
        try await join(engine, media)
        XCTAssertEqual(engine.uiState, .ended)
        XCTAssertEqual(media.audioEnabled, [true, false])
        XCTAssertEqual(restores, 1)
        engine.handleSocketDisconnected()
        engine.leaveCall()
        XCTAssertEqual(restores, 1)
        XCTAssertTrue(signals.events.isEmpty)
    }

    func testJoinWithoutRosterTimesOutAndReleasesMedia() async throws {
        let media = Media(), signals = Signals()
        media.lendAudio = true
        var restores = 0
        let engine = engine(media, signals, restore: { restores += 1 })
        try await join(engine, media)
        XCTAssertEqual(engine.uiState, .joining)
        engine.tick(now: Date().addingTimeInterval(31))
        XCTAssertEqual(engine.uiState, .ended)
        XCTAssertEqual(signals.events, ["gcall:join", "gcall:leave"])
        XCTAssertEqual(media.audioEnabled.last, false)
        XCTAssertEqual(restores, 1)
    }

    func testOtherRoomAndOldCallSignalsCannotReplaceOrEndCurrentRoster() async throws {
        let media = Media(), signals = Signals()
        media.lendAudio = true
        let engine = engine(media, signals)
        try await join(engine, media)
        engine.handleGroupCallSignal(event: "gcall:state", raw: roster())
        engine.setActiveConversation("other-room")
        engine.handleGroupCallSignal(event: "gcall:state", raw: roster("other-room", call: "other", ids: ["me", "ghost"]))
        engine.handleGroupCallSignal(event: "gcall:ended", raw: ["conversationId": "room", "callId": "old-call"])
        engine.handleGroupCallSignal(event: "gcall:ended", raw: ["conversationId": "other-room", "callId": "current"])
        XCTAssertEqual(engine.uiState, .active)
        XCTAssertEqual(engine.members.map(\.id), ["me"])
        engine.handleGroupCallSignal(event: "gcall:state", raw: roster(ids: ["someone-else"]))
        XCTAssertEqual(engine.uiState, .ended)
        XCTAssertEqual(media.audioEnabled.last, false)
    }

    func testOldPeerSDPAndBridgeCannotMutateRejoinedRoom() async throws {
        let media = Media(), signals = Signals(), oldPeer = Peer()
        media.lendAudio = true
        media.peer = oldPeer
        let engine = engine(media, signals)
        let offer = expectation(description: "old offer suspended")
        oldPeer.offerRequested = { offer.fulfill() }
        try await join(engine, media)
        engine.handleGroupCallSignal(event: "gcall:state", raw: roster(ids: ["me", "z-peer"]))
        await fulfillment(of: [offer], timeout: 1)
        engine.leaveCall()
        XCTAssertTrue(oldPeer.closed)
        engine.dismissSummary()
        try await join(engine, media)
        engine.handleGroupCallSignal(event: "gcall:state", raw: roster(call: "replacement"))
        let noLocal = expectation(description: "closed peer must not set local SDP")
        noLocal.isInverted = true
        oldPeer.onSetLocal = { noLocal.fulfill() }
        let noState = expectation(description: "closed bridge must not publish into replacement call")
        noState.isInverted = true
        let observer = engine.$peerStates.sink { if !$0.isEmpty { noState.fulfill() } }
        oldPeer.offer?.resume(returning: "old-offer")
        oldPeer.delegate?.peerConnectionStateDidChange(.failed)
        oldPeer.delegate?.peerDidProduceLocalCandidate(candidate: "old-ice", sdpMid: "0", sdpMLineIndex: 0)
        await fulfillment(of: [noLocal, noState], timeout: 0.2)
        XCTAssertFalse(signals.events.contains("gcall:offer"))
        XCTAssertFalse(signals.events.contains("gcall:ice"))
        XCTAssertEqual(engine.uiState, .active)
        observer.cancel()
        engine.leaveCall()
    }
    func testIncomingRingExpiresAtServerDeadlineAndOnlyMatchingEndDismissesIt() {
        let e = engine(Media(), Signals())
        defer { e.stop() }
        let deadline = Date().addingTimeInterval(10)
        let raw: [String: Any] = ["conversationId": "room", "callId": "call", "kind": "voice",
            "caller": ["id": "peer"], "expiresAt": ISO8601DateFormatter().string(from: deadline)]
        e.handleGroupCallSignal(event: "gcall:ring", raw: raw)
        XCTAssertEqual(e.ring?.callId, "call")
        var competing = raw; competing["callId"] = "another-call"; competing["caller"] = ["id": "other"]
        e.handleGroupCallSignal(event: "gcall:ring", raw: competing)
        XCTAssertEqual(e.ring?.callId, "call", "The answer surface must not silently switch callers")
        e.handleGroupCallSignal(event: "gcall:ended", raw: ["conversationId": "room", "callId": "old"])
        XCTAssertNotNil(e.ring)
        e.tick(now: deadline.addingTimeInterval(1))
        XCTAssertNil(e.ring)
        e.handleGroupCallSignal(event: "gcall:ring", raw: raw)
        e.handleGroupCallSignal(event: "gcall:ended", raw: ["conversationId": "room", "callId": "call"])
        XCTAssertNil(e.ring)
    }
    func testAnswerPublishesJoiningBeforeRemovingRingForCallKitObservers() {
        let e = engine(Media(), Signals())
        defer { e.stop() }
        e.handleGroupCallSignal(event: "gcall:ring", raw: ["conversationId": "room", "callId": "call", "kind": "voice",
            "caller": ["id": "peer"], "expiresAt": ISO8601DateFormatter().string(from: Date().addingTimeInterval(30))])
        var inCallAtRemoval: Bool?
        let observation = e.$ring.dropFirst().sink { ring in
            if ring == nil { inCallAtRemoval = e.isInCall }
        }
        e.joinCall()
        XCTAssertEqual(inCallAtRemoval, true)
        withExtendedLifetime(observation) {}
    }
    func testEndedInvitationDuringPermissionCannotCreateAnotherCallAfterApproval() async throws {
        let media = Media(), signals = Signals(), prompt = expectation(description: "invitation permission")
        let e = engine(media, signals)
        defer { e.stop() }
        media.requested = { prompt.fulfill() }
        e.handleGroupCallSignal(event: "gcall:ring", raw: ["conversationId": "room", "callId": "invited", "kind": "voice",
            "caller": ["id": "peer"], "expiresAt": ISO8601DateFormatter().string(from: Date().addingTimeInterval(30))])
        e.joinCall()
        let preparation = try XCTUnwrap(e.preparationTask)
        await fulfillment(of: [prompt], timeout: 1)
        e.handleGroupCallSignal(event: "gcall:ended", raw: ["conversationId": "room", "callId": "different"])
        XCTAssertEqual(e.uiState, .joining)
        e.handleGroupCallSignal(event: "gcall:ended", raw: ["conversationId": "room", "callId": "invited"])
        XCTAssertEqual(e.uiState, .ended)
        media.prompts.removeFirst().resume(returning: true)
        await preparation.value
        XCTAssertEqual(media.tracks, 0)
        XCTAssertFalse(signals.events.contains("gcall:join"))
    }

    func testOngoingJoinCarriesItsObservedCallAndRefusesReplacementRoster() async throws {
        let media = Media(), signals = Signals(), prompt = expectation(description: "ongoing permission")
        media.lendAudio = true
        let e = engine(media, signals)
        defer { e.stop() }
        e.setActiveConversation("room")
        e.handleGroupCallSignal(event: "gcall:state", raw: roster(call: "observed", ids: ["peer"]))
        XCTAssertTrue(e.ongoingElsewhere)
        media.requested = { prompt.fulfill() }
        e.joinOngoing()
        let preparation = try XCTUnwrap(e.preparationTask)
        await fulfillment(of: [prompt], timeout: 1)
        media.prompts.removeFirst().resume(returning: true)
        await preparation.value
        let payload = try XCTUnwrap(signals.payloads.first { $0.0 == "gcall:join" }?.1)
        XCTAssertEqual(payload["expectedCallId"] as? String, "observed")
        e.handleGroupCallSignal(event: "gcall:state", raw: roster(call: "replacement", ids: ["me", "peer"]))
        XCTAssertEqual(e.uiState, .joining)
        e.handleGroupCallSignal(event: "gcall:state", raw: roster(call: "observed", ids: ["me"]))
        XCTAssertEqual(e.uiState, .active)
    }
    func testLeaveCapturesTheAttemptBeforeTeardownAndEachNewJoinHasAnotherAttempt() async throws {
        let media = Media(), signals = Signals()
        media.lendAudio = true
        let e = engine(media, signals)
        defer { e.stop() }
        try await join(e, media)
        let first = try XCTUnwrap(signals.payloads.last { $0.0 == "gcall:join" }?.1["joinAttemptId"] as? String)
        XCTAssertFalse(first.isEmpty)
        e.leaveCall() // no roster was read yet, so there is no admitted call ID
        let cancelled = try XCTUnwrap(signals.payloads.last { $0.0 == "gcall:leave" }?.1)
        XCTAssertEqual(cancelled["joinAttemptId"] as? String, first)
        XCTAssertNil(cancelled["callId"])
        e.dismissSummary()
        try await join(e, media)
        let second = try XCTUnwrap(signals.payloads.last { $0.0 == "gcall:join" }?.1["joinAttemptId"] as? String)
        XCTAssertNotEqual(first, second)
        e.handleGroupCallSignal(event: "gcall:state", raw: roster())
        e.leaveCall()
        let left = try XCTUnwrap(signals.payloads.last { $0.0 == "gcall:leave" }?.1)
        XCTAssertEqual(left["joinAttemptId"] as? String, second)
        XCTAssertEqual(left["callId"] as? String, "current")
        XCTAssertEqual(cancelled["joinAttemptId"] as? String, first)
    }
}
