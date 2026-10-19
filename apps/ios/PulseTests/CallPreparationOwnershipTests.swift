import XCTest
@testable import Pulse

@MainActor
final class CallPreparationOwnershipTests: XCTestCase {
    private final class Signals: PulseCallSignalingSending {
        var events: [String] = []
        func send(event: String, payload: [String: Any]) { events.append(event) }
    }
    private final class Media: PulseCallMediaProviding {
        var cameraRequested: (() -> Void)?
        var camera: CheckedContinuation<Bool, Never>?
        var micRequests = 0
        var peerRequests = 0
        func requestMicPermission() async -> Bool { micRequests += 1; return false }
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
}
