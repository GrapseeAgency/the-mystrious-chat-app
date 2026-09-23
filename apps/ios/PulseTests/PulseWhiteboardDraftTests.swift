import XCTest
@testable import Pulse

/// R1-W2G D44 — the whiteboard pending-draft store (UserDefaults-backed,
/// JSON-encoded [WireWhiteboardStrokePost]). Pins the crash-safety
/// contract: draw-time write-through, batch verdict removal from the
/// front, restore-time dedupe against the server snapshot, and per-
/// conversation isolation.
final class PulseWhiteboardDraftTests: XCTestCase {
    private let suiteName = "PulseWhiteboardDraftTests"
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        try super.setUpWithError()
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
    }

    private func stroke(_ color: String, _ width: Double, _ points: [[Double]]) -> WireWhiteboardStrokePost {
        WireWhiteboardStrokePost(color: color, width: width, points: points)
    }

    func testAppendPersistsTheStrokeImmediatelyAndRoundTrips() {
        let drawn = stroke("#22c55e", 3, [[0.1, 0.2], [0.3, 0.4]])
        PulseWhiteboardDraft.append(drawn, conversationId: "conv-1", defaults: defaults)
        // A crash right here must still find the stroke on the next open.
        let pending = PulseWhiteboardDraft.strokes(conversationId: "conv-1", defaults: defaults)
        XCTAssertEqual(pending, [drawn])
        XCTAssertEqual(PulseWhiteboardDraft.key("conv-1"), "whiteboard.draft:conv-1")
    }

    func testVerdictRemovalDropsTheBatchFromTheFront() {
        PulseWhiteboardDraft.append(stroke("a", 1, [[0, 0]]), conversationId: "conv-2", defaults: defaults)
        PulseWhiteboardDraft.append(stroke("b", 2, [[0.1, 0.1]]), conversationId: "conv-2", defaults: defaults)
        PulseWhiteboardDraft.append(stroke("c", 3, [[0.2, 0.2]]), conversationId: "conv-2", defaults: defaults)
        // The sheet POSTs the oldest ≤40 batch; the verdict clears exactly it.
        PulseWhiteboardDraft.removeFirst(conversationId: "conv-2", count: 2, defaults: defaults)
        XCTAssertEqual(PulseWhiteboardDraft.strokes(conversationId: "conv-2", defaults: defaults).map(\.color), ["c"])
        // Over-removal is clamped, never a crash.
        PulseWhiteboardDraft.removeFirst(conversationId: "conv-2", count: 99, defaults: defaults)
        XCTAssertTrue(PulseWhiteboardDraft.strokes(conversationId: "conv-2", defaults: defaults).isEmpty)
    }

    func testRestoreDedupeDropsOnlyExactSnapshotMatches() throws {
        let landed = stroke("#22c55e", 3, [[0.1, 0.2], [0.3, 0.4]]) // reached the server pre-crash
        let unsynced = stroke("#0ea5e9", 5, [[0.5, 0.5]]) // never reached the server
        let draft = [landed, unsynced]
        let snapshot = [
            WireWhiteboardStroke(id: "srv-1", userId: "me", color: "#22c55e", width: 3, points: [[0.1, 0.2], [0.3, 0.4]], createdAt: nil),
        ]
        XCTAssertEqual(PulseWhiteboardDraft.droppingSynced(draft, snapshot: snapshot), [unsynced])
        // A width mismatch is NOT the same stroke — nothing is dropped.
        let narrower = PulseWhiteboardDraft.droppingSynced(draft, snapshot: [
            WireWhiteboardStroke(id: "srv-1", userId: "me", color: "#22c55e", width: 4, points: [[0.1, 0.2], [0.3, 0.4]], createdAt: nil),
        ])
        XCTAssertEqual(narrower, draft)
        // An empty snapshot keeps everything; an empty draft stays empty.
        XCTAssertEqual(PulseWhiteboardDraft.droppingSynced(draft, snapshot: []), draft)
        XCTAssertTrue(PulseWhiteboardDraft.droppingSynced([], snapshot: snapshot).isEmpty)
    }

    func testClearWipesOnlyTheConversationDraft() {
        PulseWhiteboardDraft.append(stroke("a", 1, [[0, 0]]), conversationId: "conv-3", defaults: defaults)
        PulseWhiteboardDraft.append(stroke("b", 1, [[0, 0]]), conversationId: "conv-4", defaults: defaults)
        PulseWhiteboardDraft.clear(conversationId: "conv-3", defaults: defaults)
        XCTAssertTrue(PulseWhiteboardDraft.strokes(conversationId: "conv-3", defaults: defaults).isEmpty)
        XCTAssertEqual(PulseWhiteboardDraft.strokes(conversationId: "conv-4", defaults: defaults).count, 1)
    }
}
