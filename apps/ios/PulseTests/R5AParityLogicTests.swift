import XCTest
@testable import Pulse

/// R5-A — the six web-parity gap closures. PURE LOGIC tests for the new
/// kernels: the A–Z directory grouping (PulseAZIndex), the streak-nudge
/// verdict (PulseStreakVerdict), the scheduled-chip next-dispatch pick
/// (PulseScheduledChip), the 24-emoji picker byte parity, the hub relative
/// stamps and the send-response wire decode (streak sibling + bare-row
/// fallback). Deterministic — no network, no device, no clocks but injected.
final class R5AParityLogicTests: XCTestCase {

    // ── Item 1 — emoji picker byte parity (web pulse-utils.ts:146-149) ──

    func testEmojiPickerChoicesCountIsExactly24() {
        XCTAssertEqual(PulseEmojiChoices.picker.count, 24, "web grid is 3 rows × 8")
    }

    func testEmojiPickerChoicesAreUnique() {
        XCTAssertEqual(Set(PulseEmojiChoices.picker).count, PulseEmojiChoices.picker.count)
    }

    func testEmojiPickerChoicesMatchWebRequestOrder() {
        // Spot-check every row boundary + the glyphs the byte-parity grep pins.
        XCTAssertEqual(PulseEmojiChoices.picker[0], "😀")
        XCTAssertEqual(PulseEmojiChoices.picker[1], "😂")
        XCTAssertEqual(PulseEmojiChoices.picker[2], "🥹")
        XCTAssertEqual(PulseEmojiChoices.picker[7], "🥳")
        XCTAssertEqual(PulseEmojiChoices.picker[8], "👍")
        XCTAssertEqual(PulseEmojiChoices.picker[10], "👏")
        XCTAssertEqual(PulseEmojiChoices.picker[15], "🎉")
        XCTAssertEqual(PulseEmojiChoices.picker[16], "🚀")
        XCTAssertEqual(PulseEmojiChoices.picker[22], "🎂")
        XCTAssertEqual(PulseEmojiChoices.picker[23], "⚽")
    }

    // ── Item 2 — A–Z grouping kernel (web indexLetterOf + sections) ──

    func testAZLetterBuckets() {
        XCTAssertEqual(PulseAZIndex.letter(for: "ada"), "A")
        XCTAssertEqual(PulseAZIndex.letter(for: "Bob Chen"), "B")
        XCTAssertEqual(PulseAZIndex.letter(for: "  cara"), "C", "leading whitespace trimmed")
        XCTAssertEqual(PulseAZIndex.letter(for: "3milio"), "#", "digits land in the hash bucket")
        XCTAssertEqual(PulseAZIndex.letter(for: "émile"), "#", "non-Latin letters land in the hash bucket")
        XCTAssertEqual(PulseAZIndex.letter(for: "🤖 bot"), "#")
        XCTAssertEqual(PulseAZIndex.letter(for: ""), "#")
        XCTAssertEqual(PulseAZIndex.letter(for: "   "), "#")
    }

    func testAZSectionsSortLettersWithHashLast() {
        let names = ["Zoe", "ada", "3milio", "Bob", "émile", "Cara"]
        let sections = PulseAZIndex.sections(names, nameOf: { $0 })
        XCTAssertEqual(sections.map(\.letter), ["A", "B", "C", "Z", "#"])
        XCTAssertEqual(sections[0].items, ["ada"])
        XCTAssertEqual(sections[3].items, ["Zoe"])
        XCTAssertEqual(sections[4].items, ["3milio", "émile"], "hash bucket keeps input order, sorts LAST")
    }

    func testAZSectionsPreserveWithinBucketOrder() {
        let names = ["Bob", "Bella", "Ben"]
        let sections = PulseAZIndex.sections(names, nameOf: { $0 })
        XCTAssertEqual(sections.count, 1)
        XCTAssertEqual(sections[0].items, ["Bob", "Bella", "Ben"])
    }

    func testAZSectionsEmptyInput() {
        XCTAssertTrue(PulseAZIndex.sections([String](), nameOf: { $0 }).isEmpty)
    }

    // ── Item 5 — streak verdict (web chat-room.tsx:1736-1751 verbatim) ──

    func testStreakToastSecondDayVerbatim() {
        XCTAssertEqual(PulseStreakVerdict.toastLine(count: 2, continued: true), "2-day streak — keep it alive")
    }

    func testStreakToastLongerStreaks() {
        XCTAssertEqual(PulseStreakVerdict.toastLine(count: 3, continued: true), "3-day streak")
        XCTAssertEqual(PulseStreakVerdict.toastLine(count: 99, continued: true), "99-day streak")
    }

    func testStreakToastStaysSilentOnFirstDay() {
        XCTAssertNil(PulseStreakVerdict.toastLine(count: 1, continued: true))
        XCTAssertNil(PulseStreakVerdict.toastLine(count: 0, continued: true))
    }

    func testStreakToastStaysSilentOnRestart() {
        // Web fires ONLY on grown streaks — continued=false restarts are silent.
        XCTAssertNil(PulseStreakVerdict.toastLine(count: 7, continued: false))
    }

    func testStreakToastStaysSilentOnMissingOrJunkStreak() {
        // Same-day re-sends carry NO streak sibling at all (streak == nil).
        XCTAssertNil(PulseStreakVerdict.toastLine(count: nil, continued: true))
        XCTAssertNil(PulseStreakVerdict.toastLine(count: 4, continued: nil))
        XCTAssertNil(PulseStreakVerdict.toastLine(count: nil, continued: nil))
    }

    // ── Item 5 — wire decode: streak sibling + bare-row fallback ──

    private let bareMessageJSON = """
    {
        "id": "m1", "conversationId": "c1", "senderId": "u1",
        "content": "hello", "kind": "text",
        "createdAt": "2025-08-27T10:00:00.000Z"
    }
    """

    func testSendResponseDecodeCarriesStreakSibling() throws {
        let json = """
        {
            "message": \(bareMessageJSON),
            "streak": {"count": 3, "best": 9, "continued": true},
            "xpAwarded": 12
        }
        """
        let result = try WireMessageEnvelope.extractSendResult(from: Data(json.utf8))
        XCTAssertEqual(result.message.id, "m1")
        XCTAssertEqual(result.streak?.count, 3)
        XCTAssertEqual(result.streak?.best, 9)
        XCTAssertEqual(result.streak?.continued, true)
    }

    func testSendResponseStreaklessBodyFallsBackToBareRow() throws {
        // Same-day re-send: the server omits the streak key entirely.
        let json = "{ \"message\": \(bareMessageJSON), \"xpAwarded\": 0 }"
        let result = try WireMessageEnvelope.extractSendResult(from: Data(json.utf8))
        XCTAssertEqual(result.message.id, "m1")
        XCTAssertNil(result.streak)
    }

    func testSendResponseBareRowBodyStillDecodes() throws {
        // Older relays answer the bare row — never throw, streak stays nil.
        let result = try WireMessageEnvelope.extractSendResult(from: Data(bareMessageJSON.utf8))
        XCTAssertEqual(result.message.id, "m1")
        XCTAssertNil(result.streak)
    }

    func testWireStreakSummaryShapeStaysTolerant() throws {
        // Conversation summaries ride {count}-only and BARE-INT streaks —
        // the extended decoder must keep both paths (ChatsView contract).
        let summary = try JSONDecoder().decode(WireStreak.self, from: Data(#"{"count": 5}"#.utf8))
        XCTAssertEqual(summary.count, 5)
        XCTAssertNil(summary.best)
        XCTAssertNil(summary.continued)
        let bareInt = try JSONDecoder().decode(WireStreak.self, from: Data("4".utf8))
        XCTAssertEqual(bareInt.count, 4)
        XCTAssertNil(bareInt.best)
    }

    // ── Item 6 — scheduled-chip next-dispatch pick ──

    private func scheduledItem(id: String, iso: String, cancelled: Bool) -> WireScheduledItem {
        WireScheduledItem(
            id: id,
            conversationId: "c1",
            content: "body",
            scheduledAt: iso,
            sentAt: nil,
            cancelledAt: cancelled ? iso : nil,
            cancelledReason: cancelled ? "user" : nil,
        )
    }

    func testScheduledChipPicksSoonestNonCancelled() {
        let items = [
            scheduledItem(id: "s1", iso: "2025-08-27T09:00:00.000Z", cancelled: true),
            scheduledItem(id: "s2", iso: "2025-08-27T18:00:00.000Z", cancelled: false),
            scheduledItem(id: "s3", iso: "2025-08-27T12:00:00.000Z", cancelled: false),
        ]
        XCTAssertEqual(PulseScheduledChip.nextIso(in: items), "2025-08-27T12:00:00.000Z")
    }

    func testScheduledChipNilWhenAllCancelled() {
        let items = [
            scheduledItem(id: "s1", iso: "2025-08-27T09:00:00.000Z", cancelled: true),
            scheduledItem(id: "s2", iso: "2025-08-27T18:00:00.000Z", cancelled: true),
        ]
        XCTAssertNil(PulseScheduledChip.nextIso(in: items))
    }

    func testScheduledChipNilOnEmptyList() {
        XCTAssertNil(PulseScheduledChip.nextIso(in: []))
    }

    func testScheduledChipToleratesUnparseableRows() {
        let items = [
            scheduledItem(id: "s1", iso: "not-a-date", cancelled: false),
            scheduledItem(id: "s2", iso: "2025-08-27T10:00:00.000Z", cancelled: false),
        ]
        XCTAssertEqual(PulseScheduledChip.nextIso(in: items), "2025-08-27T10:00:00.000Z")
    }

    // ── Item 3 — hub day / relative stamps (web formatDay + formatRelative) ──

    func testHubDayStampFormat() {
        XCTAssertEqual(PulseFormat.hubDayStamp("2025-08-27T10:00:00.000Z"), "Aug 27, 2025")
        XCTAssertEqual(PulseFormat.hubDayStamp(nil), "")
        XCTAssertEqual(PulseFormat.hubDayStamp("junk"), "")
    }

    func testHubRelativeStampLadder() {
        // Noon-local anchor so day arithmetic never straddles midnight.
        var components = DateComponents()
        components.year = 2025
        components.month = 8
        components.day = 27
        components.hour = 12
        let base = Calendar(identifier: .gregorian).date(from: components)!
        let iso: (TimeInterval) -> String = { offset in
            ISO8601DateFormatter().string(from: base.addingTimeInterval(offset))
        }
        XCTAssertEqual(PulseFormat.hubRelativeStamp(iso(0), now: base), "just now")
        XCTAssertEqual(PulseFormat.hubRelativeStamp(iso(-180), now: base), "3m ago")
        XCTAssertEqual(PulseFormat.hubRelativeStamp(iso(-3 * 3600), now: base), "3h ago")
        XCTAssertEqual(PulseFormat.hubRelativeStamp(iso(-5 * 86_400), now: base), "5d ago")
        XCTAssertEqual(PulseFormat.hubRelativeStamp(iso(-9 * 86_400), now: base), "Aug 18, 2025")
    }
}
