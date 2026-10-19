import XCTest
@testable import Pulse

@MainActor
final class VoiceRoomOwnershipTests: XCTestCase {
    private final class Audio: VoiceRoomAudioDriving {
        var onChunk: ((VoicePcmChunkOut) -> Void)?
        var onSamples: (([Int16]) -> Void)?
        var onError: ((String) -> Void)?
        var permissionRequested: (() -> Void)?
        var requests: [CheckedContinuation<Bool, Never>] = []
        var starts = 0
        var stops = 0
        func requestMicPermission() async -> Bool {
            await withCheckedContinuation { continuation in
                requests.append(continuation)
                permissionRequested?()
            }
        }
        func startCapture(micGranted: Bool) -> String? { starts += 1; return nil }
        func stopCapture() { stops += 1 }
        func resetChunker() {}
        func setGate(transmitting: Bool, muted: Bool) {}
        func flushPartial() {}
        func resetPeer(_ userId: String) {}
        func play(userId: String, base64: String, atMs: Double, nowMs: Double) {}
    }

    func testPermissionGrantedAfterLeaveCannotRestartCapture() async throws {
        let suite = "VoiceRoomOwnershipTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = PulseSession()
        let audio = Audio()
        let model = VoiceRoomSessionModel(session: session, defaults: defaults, engine: audio)
        let permission = expectation(description: "permission requested")
        audio.permissionRequested = { permission.fulfill() }
        model.joinVoice(conversationId: "room-a")
        let attempt = try XCTUnwrap(model.audioJoinTask)
        await fulfillment(of: [permission], timeout: 1)
        model.leaveVoice()
        audio.requests[0].resume(returning: true)
        await attempt.value
        XCTAssertEqual(audio.starts, 0)
        XCTAssertEqual(model.voice.status, .idle)
        XCTAssertNil(model.voice.conversationId)
    }

    func testLeaveAndRejoinSameRoomDoesNotReviveOldPermissionAttempt() async throws {
        let suite = "VoiceRoomOwnershipTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = PulseSession()
        let audio = Audio()
        let model = VoiceRoomSessionModel(session: session, defaults: defaults, engine: audio)
        let first = expectation(description: "first permission")
        audio.permissionRequested = { first.fulfill() }
        model.joinVoice(conversationId: "room-a")
        let old = try XCTUnwrap(model.audioJoinTask)
        await fulfillment(of: [first], timeout: 1)
        model.leaveVoice()
        let second = expectation(description: "second permission")
        audio.permissionRequested = { second.fulfill() }
        model.joinVoice(conversationId: "room-a")
        let current = try XCTUnwrap(model.audioJoinTask)
        await fulfillment(of: [second], timeout: 1)
        audio.requests[0].resume(returning: true)
        await old.value
        XCTAssertEqual(audio.starts, 0)
        XCTAssertEqual(model.voice.status, .joining)
        audio.requests[1].resume(returning: true)
        await current.value
        XCTAssertEqual(audio.starts, 1)
        XCTAssertEqual(model.voice.status, .joined)
        model.leaveVoice()
    }
}
