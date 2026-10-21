import XCTest
import WebRTC
@testable import Pulse

@MainActor
final class GroupCallPreparationOwnershipTests: XCTestCase {
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
    private final class Media: PulseGroupCallMediaProviding {
        var requested: (() -> Void)?
        var prompts: [CheckedContinuation<Bool, Never>] = []
        var tracks = 0
        func requestMicPermission() async -> Bool {
            await withCheckedContinuation { prompts.append($0); requested?() }
        }
        func requestCameraPermission() async -> Bool { false }
        func canCaptureVideo() -> Bool { false }
        func makeMeshPeerConnection(delegate: (any PulseCallPeerDelegate)?) -> (any PulseGroupCallPeerConnecting)? { nil }
        func makeSharedAudioTrack() -> RTCAudioTrack? { tracks += 1; return nil }
        func startSharedVideoCapture() -> RTCVideoTrack? { nil }
        func stopSharedVideoCapture() {}
        func setSharedAudioEnabled(_ enabled: Bool) {}
        func setSharedVideoEnabled(_ enabled: Bool) {}
        func switchSharedCamera() {}
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
}
