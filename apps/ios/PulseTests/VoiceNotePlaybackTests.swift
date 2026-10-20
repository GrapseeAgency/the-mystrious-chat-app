import XCTest
@testable import Pulse

final class VoiceNotePlaybackTests: XCTestCase {
    private final class Player: VoiceNotePlaying {
        var enableRate = false
        var rate: Float = 1
        var currentTime: TimeInterval = 0
        var duration: TimeInterval = 10
        var isPlaying = false
        var prepared = true
        var permitted = true
        var plays = 0
        var stops = 0
        func prepareToPlay() -> Bool { prepared }
        func play() -> Bool { plays += 1; isPlaying = permitted; return permitted }
        func pause() { isPlaying = false }
        func stop() { stops += 1; isPlaying = false }
    }
    private let url = URL(fileURLWithPath: "/test-note.m4a")

    func testPreparationRefusalDoesNotPublishPlayingOrTryPlay() {
        let player = Player()
        player.prepared = false
        let manager = VoicePlaybackManager(makePlayer: { _ in player })
        XCTAssertFalse(manager.start(messageId: "note", localURL: url, rate: 1))
        XCTAssertNil(manager.state)
        XCTAssertEqual(player.plays, 0)
        XCTAssertEqual(player.stops, 1)
    }

    func testPlayRefusalDoesNotPublishPlaying() {
        let player = Player()
        player.permitted = false
        let manager = VoicePlaybackManager(makePlayer: { _ in player })
        XCTAssertFalse(manager.start(messageId: "note", localURL: url, rate: 1))
        XCTAssertNil(manager.state)
        XCTAssertFalse(manager.resume())
    }

    func testResumeAndReplayReflectActualResult() {
        let player = Player()
        let manager = VoicePlaybackManager(makePlayer: { _ in player })
        XCTAssertTrue(manager.start(messageId: "note", localURL: url, rate: 1.5))
        manager.pause()
        player.permitted = false
        XCTAssertFalse(manager.resume())
        XCTAssertEqual(manager.state?.playing, false)
        player.currentTime = 10
        XCTAssertFalse(manager.replay())
        XCTAssertEqual(manager.state?.playing, false)
        XCTAssertEqual(player.currentTime, 0)
        player.permitted = true
        XCTAssertTrue(manager.resume())
        XCTAssertEqual(manager.state?.rate, 1.5)
        manager.stop()
    }

    func testSwitchStopsPreviousPlayerBeforePreparingSuccessor() {
        let first = Player(), second = Player()
        var count = 0
        let manager = VoicePlaybackManager(makePlayer: { _ in
            count += 1
            if count == 1 { return first }
            XCTAssertFalse(first.isPlaying)
            return second
        })
        XCTAssertTrue(manager.start(messageId: "first", localURL: url, rate: 1))
        XCTAssertTrue(manager.start(messageId: "second", localURL: url, rate: 1))
        XCTAssertEqual(first.stops, 1)
        XCTAssertEqual(manager.state?.messageId, "second")
        manager.stop()
    }
}
