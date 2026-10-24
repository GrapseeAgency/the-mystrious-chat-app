import XCTest
@testable import Pulse

@MainActor
final class ConversationActionLeaseTests: XCTestCase {
    func testOverlapsClaimNothingAndDisjointBatchesKeepTheirOwnPendingFlags() throws {
        let model = ChatsViewModel(), epoch = UUID()
        let first = try XCTUnwrap(model.beginConversationActions(["a", "b"], epoch: epoch, batch: .archive))
        XCTAssertNil(model.beginConversationActions(["b", "c"], epoch: epoch, batch: .mute))
        XCTAssertFalse(model.batchMutePending)
        let second = try XCTUnwrap(model.beginConversationActions(["c"], epoch: epoch, batch: .archive))
        XCTAssertTrue(model.batchArchivePending)
        model.finishConversationActions(first)
        XCTAssertTrue(model.batchArchivePending)
        XCTAssertTrue(model.isConversationActionCurrent(second))
        model.finishConversationActions(second)
        XCTAssertFalse(model.batchArchivePending)
        XCTAssertNotNil(model.beginConversationActions(["b", "c"], epoch: epoch))
    }

    func testOwnerRetirementAndReopeningCannotReleaseSuccessorTickets() throws {
        let model = ChatsViewModel(), oldEpoch = UUID(), nextEpoch = UUID()
        let old = try XCTUnwrap(model.beginConversationActions(["room"], epoch: oldEpoch, batch: .read))
        let fresh = try XCTUnwrap(model.beginConversationActions(["room"], epoch: nextEpoch, batch: .mute))
        XCTAssertFalse(model.isConversationActionCurrent(old))
        XCTAssertFalse(model.batchReadPending); XCTAssertTrue(model.batchMutePending)
        model.finishConversationActions(old)
        XCTAssertTrue(model.isConversationActionCurrent(fresh)); XCTAssertTrue(model.batchMutePending)
        model.stop()
        XCTAssertFalse(model.batchMutePending)
        let reopened = try XCTUnwrap(model.beginConversationActions(["room"], epoch: nextEpoch, batch: .mute))
        model.finishConversationActions(fresh)
        XCTAssertTrue(model.isConversationActionCurrent(reopened)); XCTAssertTrue(model.batchMutePending)
        model.finishConversationActions(reopened)
        XCTAssertFalse(model.batchMutePending)
    }
}
