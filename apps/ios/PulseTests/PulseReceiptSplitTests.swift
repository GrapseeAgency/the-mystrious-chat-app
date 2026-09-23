import XCTest
@testable import Pulse

/// R2-D ITEM 7 — the message-info receipt split ("Seen by" vs "Delivered
/// to"). Web ground truth: chat-room.tsx:5893-5960 (readMs >= msgMs, viewer
/// excluded, NaN watermarks delivered) + Android MessageSheets.kt:379-424.
/// Fixture watermarks are the CURRENT wire ISO shapes (fractional seconds).
final class PulseReceiptSplitTests: XCTestCase {

    private func member(_ id: String, readAt: String?) -> PulseRoomParityLogic.ReceiptMember {
        PulseRoomParityLogic.ReceiptMember(id: id, lastReadAtIso: readAt)
    }

    func testSeenRequiresWatermarkAtOrAfterCreatedAt() {
        let split = PulseRoomParityLogic.receiptSplit(
            members: [
                member("u1", readAt: "2026-01-05T10:00:05.000Z"), // after → seen
                member("u2", readAt: "2026-01-05T10:00:00.000Z"), // equal → seen (>=)
                member("u3", readAt: "2026-01-05T09:59:59.999Z"), // before → delivered
            ],
            viewerId: "me",
            createdAtIso: "2026-01-05T10:00:00.000Z",
        )
        XCTAssertEqual(split.seenBy.map(\.id), ["u1", "u2"])
        XCTAssertEqual(split.deliveredTo.map(\.id), ["u3"])
    }

    func testViewerIsNeverARecipient() {
        let split = PulseRoomParityLogic.receiptSplit(
            members: [
                member("me", readAt: "2026-01-05T23:59:00.000Z"),
                member("u2", readAt: nil),
            ],
            viewerId: "me",
            createdAtIso: "2026-01-05T10:00:00.000Z",
        )
        XCTAssertTrue(split.seenBy.allSatisfy { $0.id != "me" })
        XCTAssertTrue(split.deliveredTo.allSatisfy { $0.id != "me" })
        XCTAssertEqual(split.deliveredTo.map(\.id), ["u2"])
    }

    func testMissingAndUnparseableWatermarksStayDelivered() {
        let split = PulseRoomParityLogic.receiptSplit(
            members: [
                member("u1", readAt: nil),
                member("u2", readAt: ""),
                member("u3", readAt: "not-a-date"),
            ],
            viewerId: "me",
            createdAtIso: "2026-01-05T10:00:00.000Z",
        )
        XCTAssertTrue(split.seenBy.isEmpty)
        XCTAssertEqual(split.deliveredTo.map(\.id), ["u1", "u2", "u3"])
    }

    func testUnparseableCreatedAtDeliversEveryone() {
        // The web guards Number.isNaN on BOTH sides — a broken message stamp
        // can never mark anyone as seen.
        let split = PulseRoomParityLogic.receiptSplit(
            members: [
                member("u1", readAt: "2026-01-05T10:00:05.000Z"),
            ],
            viewerId: "me",
            createdAtIso: "garbage",
        )
        XCTAssertTrue(split.seenBy.isEmpty)
        XCTAssertEqual(split.deliveredTo.map(\.id), ["u1"])
    }

    func testNilViewerKeepsEveryMember() {
        let split = PulseRoomParityLogic.receiptSplit(
            members: [
                member("me", readAt: nil),
                member("u2", readAt: "2026-01-05T10:00:05.000Z"),
            ],
            viewerId: nil,
            createdAtIso: "2026-01-05T10:00:00.000Z",
        )
        XCTAssertEqual(split.seenBy.map(\.id), ["u2"])
        XCTAssertEqual(split.deliveredTo.map(\.id), ["me"])
    }

    func testEmptyMembersProduceEmptySplits() {
        let split = PulseRoomParityLogic.receiptSplit(
            members: [],
            viewerId: "me",
            createdAtIso: "2026-01-05T10:00:00.000Z",
        )
        XCTAssertTrue(split.seenBy.isEmpty)
        XCTAssertTrue(split.deliveredTo.isEmpty)
    }

    func testSplitPartitionsEveryMemberExactlyOnce() {
        let members = (1...12).map { index in
            member("u\(index)", readAt: index % 3 == 0 ? nil : "2026-01-05T1\(index % 10):00:0\(index % 10).000Z")
        }
        let split = PulseRoomParityLogic.receiptSplit(
            members: members,
            viewerId: nil,
            createdAtIso: "2026-01-05T10:00:00.000Z",
        )
        let total = split.seenBy.count + split.deliveredTo.count
        XCTAssertEqual(total, members.count)
        let seenIds = Set(split.seenBy.map(\.id))
        let deliveredIds = Set(split.deliveredTo.map(\.id))
        XCTAssertTrue(seenIds.isDisjoint(with: deliveredIds))
    }
}
