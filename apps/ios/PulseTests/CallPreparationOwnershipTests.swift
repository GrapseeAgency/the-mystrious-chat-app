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
            await withCheckedContinuation { continuation in
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
}
