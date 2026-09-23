import XCTest
@testable import Pulse

/// R3-A — pure bubble-body logic tests (web ground truth:
/// chat-room.tsx buildMentionRuns :6836-6856 + BubbleText :6858-6985,
/// slash-palette.tsx fuzzyMatch :127-140). Fixtures mirror the web
/// examples verbatim ("/ef co" → /effects confetti, longest-name-first,
/// case-insensitive @tokens).
final class PulseBubbleTextLogicTests: XCTestCase {

    // ── mention runs (web buildMentionRuns parity) ────────────

    func testMentionRunSplitsAroundTheToken() {
        let runs = PulseBubbleTextLogic.mentionRuns(in: "hi @Alice welcome", memberNames: ["Alice"])
        XCTAssertEqual(runs, [
            PulseBubbleTextLogic.Run("hi ", .plain),
            PulseBubbleTextLogic.Run("@Alice", .mention),
            PulseBubbleTextLogic.Run(" welcome", .plain),
        ])
    }

    func testMentionMatchIsCaseInsensitive() {
        let runs = PulseBubbleTextLogic.mentionRuns(in: "ping @alice now", memberNames: ["Alice"])
        XCTAssertEqual(runs.count, 3)
        XCTAssertEqual(runs[1], PulseBubbleTextLogic.Run("@alice", .mention))
    }

    func testLongestNameWinsOverPrefixName() {
        let runs = PulseBubbleTextLogic.mentionRuns(in: "@Alice Chen x", memberNames: ["Alice", "Alice Chen"])
        XCTAssertEqual(runs, [
            PulseBubbleTextLogic.Run("@Alice Chen", .mention),
            PulseBubbleTextLogic.Run(" x", .plain),
        ])
    }

    func testEmptyRosterYieldsOnePlainRun() {
        let runs = PulseBubbleTextLogic.mentionRuns(in: "@nobody", memberNames: [])
        XCTAssertEqual(runs, [PulseBubbleTextLogic.Run("@nobody", .plain)])
    }

    func testBlankRosterNamesAreIgnored() {
        let runs = PulseBubbleTextLogic.mentionRuns(in: "@Alice", memberNames: ["", "  ", "Alice"])
        XCTAssertEqual(runs.count, 1)
        XCTAssertEqual(runs[0].style, .mention)
    }

    func testRegexMetacharactersInNamesStayLiteral() {
        let runs = PulseBubbleTextLogic.mentionRuns(in: "call @Ann(e)!", memberNames: ["Ann(e)"])
        XCTAssertEqual(runs, [
            PulseBubbleTextLogic.Run("call ", .plain),
            PulseBubbleTextLogic.Run("@Ann(e)", .mention),
            PulseBubbleTextLogic.Run("!", .plain),
        ])
    }

    // ── bubble runs (FORMAT_RE styles + mention order) ────────

    func testFormattingTokensMapToStyles() {
        let runs = PulseBubbleTextLogic.bubbleRuns(
            in: "**bold** and _italic_ ~~gone~~ `c`",
            memberNames: [],
        )
        XCTAssertEqual(runs, [
            PulseBubbleTextLogic.Run("bold", .bold),
            PulseBubbleTextLogic.Run(" and ", .plain),
            PulseBubbleTextLogic.Run("italic", .italic),
            PulseBubbleTextLogic.Run(" ", .plain),
            PulseBubbleTextLogic.Run("gone", .strike),
            PulseBubbleTextLogic.Run(" ", .plain),
            PulseBubbleTextLogic.Run("c", .code),
        ])
    }

    func testUnderlineAndSpoilerStyles() {
        let runs = PulseBubbleTextLogic.bubbleRuns(in: "__u__ ||shh||", memberNames: [])
        XCTAssertEqual(runs, [
            PulseBubbleTextLogic.Run("u", .underline),
            PulseBubbleTextLogic.Run(" ", .plain),
            PulseBubbleTextLogic.Run("shh", .spoiler),
        ])
    }

    func testPreBlockStyleKeepsNewlines() {
        let runs = PulseBubbleTextLogic.bubbleRuns(in: "```\nlet x = 1\n```", memberNames: [])
        XCTAssertEqual(runs, [PulseBubbleTextLogic.Run("\nlet x = 1\n", .pre)])
    }

    func testMentionSplitsBeforeFormatting() {
        let runs = PulseBubbleTextLogic.bubbleRuns(in: "hi @Alice **wow**", memberNames: ["Alice"])
        XCTAssertEqual(runs, [
            PulseBubbleTextLogic.Run("hi ", .plain),
            PulseBubbleTextLogic.Run("@Alice", .mention),
            PulseBubbleTextLogic.Run(" ", .plain),
            PulseBubbleTextLogic.Run("wow", .bold),
        ])
    }

    func testPlainTextPassthroughStaysWhole() {
        let runs = PulseBubbleTextLogic.bubbleRuns(in: "just words — nothing special", memberNames: ["Zed"])
        XCTAssertEqual(runs, [PulseBubbleTextLogic.Run("just words — nothing special", .plain)])
    }

    func testEmptyContentYieldsNoRuns() {
        XCTAssertEqual(PulseBubbleTextLogic.bubbleRuns(in: "", memberNames: ["Alice"]), [])
    }

    // ── cache (key = message id + roster signature) ───────────

    func testCacheRoundTripReturnsIdenticalRuns() {
        let first = PulseBubbleTextLogic.cachedRuns(key: "m1", content: "**hot** @Alice", memberNames: ["Alice"])
        let second = PulseBubbleTextLogic.cachedRuns(key: "m1", content: "**hot** @Alice", memberNames: ["Alice"])
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.first?.style, .bold)
    }

    func testCacheServesDifferentKeysIndependently() {
        let a = PulseBubbleTextLogic.cachedRuns(key: "m-a", content: "one", memberNames: [])
        let b = PulseBubbleTextLogic.cachedRuns(key: "m-b", content: "two", memberNames: [])
        XCTAssertEqual(a, [PulseBubbleTextLogic.Run("one", .plain)])
        XCTAssertEqual(b, [PulseBubbleTextLogic.Run("two", .plain)])
    }

    func testRosterSignatureChangesWithTheRoster() {
        XCTAssertEqual(
            PulseBubbleTextLogic.rosterSignature(["A", "B"]),
            PulseBubbleTextLogic.rosterSignature(["A", "B"]),
        )
        XCTAssertNotEqual(
            PulseBubbleTextLogic.rosterSignature(["A", "B"]),
            PulseBubbleTextLogic.rosterSignature(["A"]),
        )
    }
}
