import XCTest
@testable import Pulse

@MainActor
final class VoiceRoomOwnershipTests: XCTestCase {
    private func isolatedDefaults() -> UserDefaults {
        let suite = "VoiceRoomOwnershipTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        return defaults
    }

    private final class Audio: VoiceRoomAudioDriving {
        var onChunk: ((VoicePcmChunkOut) -> Void)?
        var onSamples: (([Int16]) -> Void)?
        var onError: ((String) -> Void)?
        var permissionRequested: (() -> Void)?
        var requests: [CheckedContinuation<Bool, Never>] = []
        var starts = 0
        var stops = 0
        var playbackStarts = 0
        var micStops = 0
        var gate = false
        func startPlayback() -> String? { playbackStarts += 1; return nil }
        func stopMicrophone() -> String? { micStops += 1; gate = false; return nil }
        func requestMicPermission() async -> Bool {
            await withCheckedContinuation { continuation in
                requests.append(continuation)
                permissionRequested?()
            }
        }
        func startCapture(micGranted: Bool) -> String? { starts += 1; return nil }
        func stopCapture() { stops += 1 }
        func resetChunker() {}
        func setGate(transmitting: Bool, muted: Bool) { gate = transmitting && !muted }
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
    func testStageListenerStartsPlaybackWithoutRequestingOrOpeningMic() {
        let session = PulseSession(), audio = Audio()
        let model = VoiceRoomSessionModel(session: session, defaults: isolatedDefaults(), engine: audio)
        model.joinStage(conversationId: "stage")
        XCTAssertEqual(audio.playbackStarts, 1)
        XCTAssertTrue(audio.requests.isEmpty)
        XCTAssertEqual(audio.starts, 0)
        XCTAssertEqual(model.voice.conversationId, "stage")
        XCTAssertFalse(model.voice.userJoinedVoice)
        XCTAssertFalse(model.microphoneReady)
        model.setPtt(on: true)
        XCTAssertFalse(audio.gate)
        model.leaveStage()
        XCTAssertEqual(audio.stops, 1)
        XCTAssertEqual(model.voice.status, .idle)
    }

    func testStageDemotionStopsCaptureAndPTTEvenWithAnIndependentVoiceSeat() async throws {
        let session = PulseSession(), audio = Audio()
        let model = VoiceRoomSessionModel(session: session, defaults: isolatedDefaults(), engine: audio)
        model.handle(.connectionState(connected: true))
        model.joinStage(conversationId: "room")
        model.joinVoice(conversationId: "room") // explicit voice membership shares the seat
        // The fixture session has no signed-in viewer; its local identity is "".
        model.handle(.stageState(conversationId: "room", raw: ["host": ["id": "", "name": "Me"]]))
        let prompt = expectation(description: "explicit microphone permission")
        audio.permissionRequested = { prompt.fulfill() }
        let attempt = Task { await model.retryMicAccess() }
        await fulfillment(of: [prompt], timeout: 1)
        audio.requests[0].resume(returning: true)
        await attempt.value
        XCTAssertTrue(model.microphoneReady)
        model.setPtt(on: true)
        XCTAssertTrue(audio.gate)
        model.handle(.stageState(conversationId: "room", raw: ["listeners": [["id": "", "name": "Me"]]]))
        XCTAssertFalse(model.microphoneReady)
        XCTAssertFalse(model.voice.transmitting)
        XCTAssertFalse(audio.gate)
        XCTAssertEqual(audio.micStops, 1)
        model.leaveStage()
        XCTAssertEqual(audio.stops, 0) // independent listener membership survives
        XCTAssertEqual(model.voice.status, .joined)
        model.leaveVoice()
        XCTAssertEqual(audio.stops, 1)
    }

    func testVoiceLeavePreservesStageListenerAndPermissionCannotReviveIt() async throws {
        let session = PulseSession(), audio = Audio()
        let model = VoiceRoomSessionModel(session: session, defaults: isolatedDefaults(), engine: audio)
        let prompt = expectation(description: "permission")
        audio.permissionRequested = { prompt.fulfill() }
        model.joinVoice(conversationId: "room")
        let old = try XCTUnwrap(model.audioJoinTask)
        await fulfillment(of: [prompt], timeout: 1)
        model.joinStage(conversationId: "room")
        audio.requests[0].resume(returning: true)
        await old.value
        XCTAssertEqual(audio.starts, 0)
        model.leaveVoice()
        XCTAssertTrue(model.stage.joined)
        XCTAssertEqual(model.voice.status, .joined)
        XCTAssertFalse(model.voice.userJoinedVoice)
        XCTAssertEqual(audio.stops, 0)
        model.leaveStage()
        XCTAssertEqual(audio.stops, 1)
    }

    func testLateSpaceStateCannotRepopulateAfterLeaveOrWrongRoom() {
        let session = PulseSession(), audio = Audio()
        let model = VoiceRoomSessionModel(session: session, defaults: isolatedDefaults(), engine: audio)
        model.joinSpace(conversationId: "space-a")
        model.handle(.spaceState(conversationId: "space-a", raw: ["players": [["id": "peer", "x": 0.2, "y": 0.3]]]))
        XCTAssertEqual(model.space.players.count, 1)
        model.leaveSpace()
        model.handle(.spaceState(conversationId: "space-a", raw: ["players": [["id": "late"]]]))
        XCTAssertTrue(model.space.players.isEmpty)
        model.joinSpace(conversationId: "space-b")
        model.handle(.spaceState(conversationId: "space-a", raw: ["players": [["id": "wrong"]]]))
        XCTAssertTrue(model.space.players.isEmpty)
        model.leaveSpace()
    }

    func testAudioLeaseRejectsConcurrentAndStaleOwners() {
        let gate = PulseAudioOwnership(), room = UUID(), call = UUID(), recorder = UUID()
        XCTAssertTrue(gate.acquire(room))
        XCTAssertTrue(gate.acquire(room))
        XCTAssertFalse(gate.acquire(call))
        gate.release(call)
        XCTAssertFalse(gate.acquire(call))
        gate.release(room)
        XCTAssertTrue(gate.acquire(call))
        gate.release(room)
        XCTAssertFalse(gate.acquire(recorder))
    }

}
