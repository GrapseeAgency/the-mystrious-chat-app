import XCTest
import Combine
@testable import Pulse

@MainActor
final class CallPreparationOwnershipTests: XCTestCase {
    private final class Signals: PulseCallSignalingSending {
        var isReady = true
        var events: [String] = []
        @discardableResult
        func send(event: String, payload: [String: Any]) -> Bool {
            guard isReady else { return false }
            events.append(event)
            return true
        }
    }
    private final class Media: PulseCallMediaProviding {
        var cameraRequests = 0
        var cameraRequested: (() -> Void)?
        var camera: CheckedContinuation<Bool, Never>?
        var micRequests = 0
        var peerRequests = 0
        var holdMic = false
        var micRequested: (() -> Void)?
        var micReturned: (() -> Void)?
        var mic: CheckedContinuation<Bool, Never>?
        func requestMicPermission() async -> Bool {
            micRequests += 1
            defer { micReturned?() }
            guard holdMic else { return false }
            return await withCheckedContinuation { continuation in
                mic = continuation
                micRequested?()
            }
        }
        func requestCameraPermission() async -> Bool {
            cameraRequests += 1
            return await withCheckedContinuation { continuation in
                camera = continuation
                cameraRequested?()
            }
        }
        func canCaptureVideo() -> Bool { true }
        func makePeerConnection(delegate: (any PulseCallPeerDelegate)?) -> (any PulseCallPeerConnecting)? {
            peerRequests += 1
            return nil
        }
    }

    func testEndingWhileCameraPromptIsOpenCannotStartALateCall() async throws {
        let signals = Signals(), media = Media()
        let engine = PulseCallEngine(
            store: try PulseStore(), viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter()
        )
        let prompt = expectation(description: "camera prompt")
        media.cameraRequested = { prompt.fulfill() }
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room", kind: .video)
        let attempt = try XCTUnwrap(engine.outgoingPreparationTask)
        await fulfillment(of: [prompt], timeout: 1)
        engine.endCall() // also used by session teardown; machine is still idle here
        media.camera?.resume(returning: true)
        await attempt.value
        XCTAssertEqual(engine.state, .idle)
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
    }

    func testOfflineStartDoesNotRequestPermissionsCreatePeerOrInventHistory() throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        signals.isReady = false
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room", kind: .video)
        XCTAssertEqual(engine.state, .idle)
        XCTAssertNil(engine.outgoingPreparationTask)
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
        XCTAssertTrue(try store.callLogQueueAll().isEmpty)
    }

    func testDisconnectedCameraPromptCannotStartLateCallAfterReconnect() async throws {
        let signals = Signals(), media = Media()
        let engine = PulseCallEngine(store: try PulseStore(), viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        let prompt = expectation(description: "camera prompt")
        media.cameraRequested = { prompt.fulfill() }
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room", kind: .video)
        let attempt = try XCTUnwrap(engine.outgoingPreparationTask)
        await fulfillment(of: [prompt], timeout: 1)
        signals.isReady = false
        engine.handleSocketDisconnected()
        signals.isReady = true
        media.camera?.resume(returning: true)
        await attempt.value
        XCTAssertEqual(engine.state, .idle)
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
    }

    func testDisconnectDuringMicPermissionDoesNotAcquireAudioOrSendAfterReconnect() async throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        media.holdMic = true
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        let prompt = expectation(description: "mic prompt"), returned = expectation(description: "mic returned")
        media.micRequested = { prompt.fulfill() }
        media.micReturned = { returned.fulfill() }
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room")
        await fulfillment(of: [prompt], timeout: 1)
        signals.isReady = false
        engine.handleSocketDisconnected()
        signals.isReady = true
        media.mic?.resume(returning: true)
        await fulfillment(of: [returned], timeout: 1)
        if case .ended = engine.state {} else { XCTFail("Disconnected setup must end") }
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
        XCTAssertTrue(try store.callLogQueueAll().isEmpty)
        engine.dismissError()
        XCTAssertEqual(engine.state, .idle)
    }

    func testIncomingCallWinsBeforeQueuedCameraSetupWithoutOpeningCameraPrompt() async throws {
        let signals = Signals(), media = Media()
        let engine = PulseCallEngine(store: try PulseStore(), viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        defer { engine.endCall() }
        // If the stale request is reached, return immediately so the regression
        // fails on the request count rather than hanging on a fake permission.
        media.cameraRequested = { [weak media] in media?.camera?.resume(returning: true) }
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room", kind: .video)
        let queued = try XCTUnwrap(engine.outgoingPreparationTask)
        engine.handleCallSignal(event: "call:offer", raw: [
            "callId": "incoming-winner", "conversationId": "other-room", "from": "other-peer", "to": "me",
            "kind": "voice", "sdp": "offer-sdp", "callerName": "Other Peer",
        ])
        await queued.value
        XCTAssertEqual(engine.state, .incomingRinging)
        XCTAssertEqual(media.cameraRequests, 0)
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
    }

    func testCancelledBeforeQueuedVoiceSetupDoesNotOpenMicPrompt() async throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room")
        let queued = try XCTUnwrap(engine.mediaPreparationTask)
        // No suspension before cancellation: setup has not entered the actor yet.
        engine.endCall()
        engine.dismissError()
        await queued.value
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
        XCTAssertTrue(try store.callLogQueueAll().isEmpty)
        XCTAssertEqual(engine.state, .idle)
    }

    func testDisconnectedBeforeQueuedVoiceSetupDoesNotPromptAfterReconnect() async throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room")
        let queued = try XCTUnwrap(engine.mediaPreparationTask)
        signals.isReady = false
        engine.handleSocketDisconnected()
        signals.isReady = true
        await queued.value
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
        XCTAssertTrue(try store.callLogQueueAll().isEmpty)
    }

    func testCancelledBeforeQueuedAnswerDoesNotOpenMicPrompt() async throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        engine.handleCallSignal(event: "call:offer", raw: [
            "callId": "queued-answer", "conversationId": "room", "from": "peer", "to": "me",
            "kind": "voice", "sdp": "offer-sdp", "callerName": "Peer",
        ])
        XCTAssertEqual(engine.state, .incomingRinging)
        engine.acceptIncoming()
        let queued = try XCTUnwrap(engine.mediaPreparationTask)
        engine.endCall()
        let terminalEvents = signals.events
        engine.dismissError()
        await queued.value
        XCTAssertEqual(media.micRequests, 0)
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertEqual(signals.events, terminalEvents, "Retired answer must not dispatch another signal")
        XCTAssertTrue(try store.callLogQueueAll().isEmpty)
        XCTAssertEqual(engine.state, .idle)
    }

    func testMicDenialEndsImmediatelyAndClosingDoesNotWritePhantomMissedCall() async throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        let ended = expectation(description: "permission failure ended")
        let observer = engine.$state.sink { state in if case .ended = state { ended.fulfill() } }
        engine.startOutgoing(to: CallPeer(id: "peer", name: "Peer"), conversationId: "room")
        await fulfillment(of: [ended], timeout: 1)
        XCTAssertEqual(engine.errorText, PulseCallEngine.micDeniedMessage)
        engine.dismissError()
        XCTAssertEqual(engine.state, .idle)
        XCTAssertTrue(try store.callLogQueueAll().isEmpty)
        XCTAssertEqual(media.peerRequests, 0)
        XCTAssertTrue(signals.events.isEmpty)
        observer.cancel()
    }

    func testReplayedOfferForTheRingAlreadyActiveIsANoOpNotABusyReject() throws {
        let signals = Signals(), media = Media(), store = try PulseStore()
        let engine = PulseCallEngine(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            signaling: signals, media: media, apiProvider: { nil }, toasts: ToastCenter())
        let raw: [String: Any] = [
            "callId": "call-r56", "conversationId": "room", "from": "peer", "to": "me",
            "kind": "voice", "sdp": "v=0 offer", "callerName": "Peer",
        ]
        engine.handleCallSignal(event: "call:offer", raw: raw)
        guard case .incomingRinging = engine.state else {
            return XCTFail("expected incomingRinging, got \(engine.state)")
        }
        // R56 (item 1): the relay replays un-acked offers and re-delivers the
        // journaled offer to late-joining devices. The SAME callId while
        // ringing must be a no-op - a busy reject here would decline the ring.
        engine.handleCallSignal(event: "call:offer", raw: raw)
        XCTAssertFalse(signals.events.contains("call:reject"))
        guard case .incomingRinging = engine.state else {
            return XCTFail("ring disturbed by a replayed offer")
        }
        // A DIFFERENT callId while ringing is still a busy reject.
        var other = raw
        other["callId"] = "call-other"
        other["from"] = "stranger"
        engine.handleCallSignal(event: "call:offer", raw: other)
        XCTAssertTrue(signals.events.contains("call:reject"))
    }
}
