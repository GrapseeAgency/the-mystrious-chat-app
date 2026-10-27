import XCTest
@testable import Pulse

final class ReactionNormalizationTests: XCTestCase {
    func testEveryLegacyGlyphAndPresentationSelectorMapsToItsStableReaction() {
        for value in PulseReactionId.allCases { XCTAssertEqual(PulseReactionId.normalize(value.rawValue), value) }
        for (glyph, value) in PulseReactionId.legacy {
            XCTAssertEqual(PulseReactionId.normalize(glyph), value)
            XCTAssertEqual(PulseReactionId.normalize(glyph + "\u{FE0E}"), value)
            XCTAssertEqual(PulseReactionId.normalize(glyph + "\u{FE0F}"), value)
        }
        XCTAssertEqual(PulseReactionId.normalize("unknown"), .heart)
    }
    func testCachedAliasesDoNotDuplicateChipsOrInflateTheirMemberCount() {
        let groups = PulseReactionId.groups([
            WireReactionGroup(emoji: "\u{1F525}\u{FE0F}", userIds: ["me"], count: 1),
            WireReactionGroup(emoji: "fire", userIds: ["me", "peer"], count: 2),
            WireReactionGroup(emoji: "\u{2764}", userIds: ["me"], count: 1),
        ])
        XCTAssertEqual(groups.map(\.emoji), ["fire", "heart"])
        XCTAssertEqual(groups.first?.count, 2)
        XCTAssertEqual(groups.first?.userIds, ["me", "peer"])
    }

}
