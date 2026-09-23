import XCTest
@testable import Pulse

/// Wave 2 UI-logic tests — pure functions only (no network, no mic, no TCC):
/// voice duration rounding, the unfurl trigger, and the topic self-heal.
final class Wave2UITests: XCTestCase {
    // ── VoiceMath — the web rounding contract (spec §1 row 11) ──

    func testRoundedDurationQuantizesTo100msAndFloorsAtOne() {
        XCTAssertEqual(VoiceMath.roundedDurationMs(fromElapsedMs: 647), 600)
        XCTAssertEqual(VoiceMath.roundedDurationMs(fromElapsedMs: 651), 700)
        XCTAssertEqual(VoiceMath.roundedDurationMs(fromElapsedMs: 1000), 1000)
        // Never send 0 — 40ms quantizes to 0 → clamped to 1.
        XCTAssertEqual(VoiceMath.roundedDurationMs(fromElapsedMs: 40), 1)
        XCTAssertEqual(VoiceMath.roundedDurationMs(fromElapsedMs: 0), 1)
    }

    func testTooShortTakesAreDiscarded() {
        XCTAssertFalse(VoiceMath.isTooShort(600)) // exactly the floor sends
        XCTAssertTrue(VoiceMath.isTooShort(599))
        XCTAssertTrue(VoiceMath.isTooShort(0))
    }

    func testPlaybackRateCycleMatchesWeb() {
        XCTAssertEqual(VoicePlaybackManager.nextRate(after: 1.0), 1.5)
        XCTAssertEqual(VoicePlaybackManager.nextRate(after: 1.5), 2.0)
        XCTAssertEqual(VoicePlaybackManager.nextRate(after: 2.0), 1.0)
        // Unknown stored value falls back to 1x, not a garbage rate.
        XCTAssertEqual(VoicePlaybackManager.nextRate(after: 3.7), 1.5)
    }

    // ── VoiceWaveform — the web voiceBars LCG (D31) ─────────────

    func testVoiceBubbleBarsMatchTheWebLCG() {
        // Vectors generated from the web implementation (chat-room.tsx:6232 +
        // pulse-utils.ts:59 hashString) via node — the same message id must
        // render the SAME decorative bars on web, Android and iOS.
        XCTAssertEqual(
            VoiceWaveform.bars(for: "abc123"),
            [52, 65, 97, 88, 98, 78, 36, 47, 73, 38, 43, 55, 93, 84, 41, 51, 64, 66, 85, 34, 46, 87, 38, 33, 99, 75],
        )
        XCTAssertEqual(
            VoiceWaveform.bars(for: "local-temp"),
            [60, 76, 30, 95, 55, 45, 58, 49, 30, 48, 46, 93, 92, 66, 50, 98, 40, 77, 80, 68, 68, 76, 85, 38, 36, 61],
        )
        // 26 bars by default (web count), heights always in the 28…100 band.
        XCTAssertEqual(VoiceWaveform.bars(for: "any-id").count, 26)
        XCTAssertTrue(VoiceWaveform.bars(for: "any-id").allSatisfy { (28...100).contains($0) })
    }

    // ── UnfurlTrigger — http(s):// or bare www. ─────────────────

    func testUnfurlTriggerDetectsLinks() {
        XCTAssertTrue(UnfurlTrigger.matches("look at https://example.com/x"))
        XCTAssertTrue(UnfurlTrigger.matches("http://plain.org"))
        XCTAssertTrue(UnfurlTrigger.matches("see www.pulse.chat now"))
        XCTAssertTrue(UnfurlTrigger.matches("HTTPS://UPPER.CASE"))
        XCTAssertFalse(UnfurlTrigger.matches("no links here"))
        XCTAssertFalse(UnfurlTrigger.matches("htp://typo.example"))
        // The word "www" alone without the dot is NOT a link.
        XCTAssertFalse(UnfurlTrigger.matches("awww no"))
    }

    // ── TopicHeal — deleted active topic resets to General ──────

    func testTopicHealResetsGhostActiveTopic() {
        let topics = [
            WireTopic(id: "t1", name: "Design", emoji: "🎨", lastMessageAt: nil, messageCount: 3),
            WireTopic(id: "t2", name: "Launch", emoji: "🚀", lastMessageAt: nil, messageCount: 0),
        ]
        XCTAssertEqual(TopicHeal.healed("t1", topics: topics), "t1")
        XCTAssertNil(TopicHeal.healed("t-deleted", topics: topics))
        // nil (General) stays General even with a healthy rail.
        XCTAssertNil(TopicHeal.healed(nil, topics: topics))
        // Empty rail (all topics deleted) always heals to General.
        XCTAssertNil(TopicHeal.healed("t1", topics: []))
    }
}
