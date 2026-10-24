import XCTest
@testable import Pulse

@MainActor
final class RoomOperationOrderingTests: XCTestCase {
    func testCarrierAcceptedFromChildSheetInvalidatesEarlierRoomRead() {
        let operations = PulseRoomOperations()
        let stale = operations.beginRead()!
        operations.invalidateReads()
        XCTAssertFalse(operations.accepts(stale))
        XCTAssertTrue(operations.accepts(operations.beginRead()!))
    }
    func testFailedCreationKeepsDraftAndReleasesSynchronousGate() async {
        let operations = PulseRoomOperations()
        var draft = "Meeting at six"
        let task = operations.run(key: "create", operation: { false }, onSuccess: { draft = "" })!
        XCTAssertNil(operations.run(key: "create", operation: { true }))
        XCTAssertNil(operations.beginRead())
        await task.value
        XCTAssertEqual(draft, "Meeting at six")
        XCTAssertTrue(operations.pending.isEmpty)
        XCTAssertEqual(operations.settledRevision, 1)
    }
    func testPollStartedBeforeMutationCannotRestoreOldRows() async {
        let operations = PulseRoomOperations()
        let stale = operations.beginRead()!
        let task = operations.run(key: "card", operation: { true })!
        await task.value
        XCTAssertFalse(operations.accepts(stale))
        XCTAssertTrue(operations.accepts(operations.beginRead()!))
    }
    func testOverlappingWritesDeferPollingUntilBothFinish() async {
        let operations = PulseRoomOperations()
        let started = expectation(description: "both started"); started.expectedFulfillmentCount = 2
        var a: CheckedContinuation<Bool, Never>?, b: CheckedContinuation<Bool, Never>?
        let first = operations.run(key: "a", operation: { started.fulfill(); return await withCheckedContinuation { a = $0 } })!
        let second = operations.run(key: "b", operation: { started.fulfill(); return await withCheckedContinuation { b = $0 } })!
        await fulfillment(of: [started], timeout: 2)
        a?.resume(returning: true); await first.value
        XCTAssertNil(operations.beginRead())
        b?.resume(returning: false); await second.value
        XCTAssertNotNil(operations.beginRead())
    }
    func testDismissalInvalidatesReadAndLateMutationWithoutClearingDraft() async {
        let operations = PulseRoomOperations()
        let stale = operations.beginRead()!
        var draft = "keep"
        var continuation: CheckedContinuation<Bool, Never>?
        let started = expectation(description: "started")
        let task = operations.run(key: "create", operation: {
            started.fulfill(); return await withCheckedContinuation { continuation = $0 }
        }, onSuccess: { draft = "" })!
        await fulfillment(of: [started], timeout: 2)
        operations.stop()
        XCTAssertFalse(operations.accepts(stale))
        continuation?.resume(returning: true); await task.value
        XCTAssertEqual(draft, "keep")
        XCTAssertTrue(operations.pending.isEmpty)
    }
    func testNewestPhraseReadAndRoomClosureRejectCancellationIgnoringOlderResponses() async throws {
        let operations = PulseRoomOperations()
        var visible = ["confirmed"]
        let oldTicket = try XCTUnwrap(operations.beginRead())
        var reply: CheckedContinuation<[String], Never>?
        let started = expectation(description: "old read suspended")
        let oldRead = Task { @MainActor in
            let rows = await withCheckedContinuation { (continuation: CheckedContinuation<[String], Never>) in
                reply = continuation; started.fulfill()
            }
            if operations.accepts(oldTicket) { visible = rows }
        }
        await fulfillment(of: [started], timeout: 1)
        let newest = try XCTUnwrap(operations.beginRead())
        if operations.accepts(newest) { visible = ["new confirmed phrase"] }
        reply?.resume(returning: ["stale phrase"])
        await oldRead.value
        XCTAssertEqual(visible, ["new confirmed phrase"])
        operations.stop()
        XCTAssertFalse(operations.accepts(newest))
        XCTAssertTrue(operations.accepts(try XCTUnwrap(operations.beginRead())))
    }
    func testReplacingAStoryPhotoInvalidatesTheOlderUploadAndItsCleanup() async throws {
        let operations = PulseRoomOperations()
        let started = expectation(description: "old photo suspended")
        var reply: CheckedContinuation<Bool, Never>?
        var preview = "previous confirmed photo"
        let old = try XCTUnwrap(operations.run(key: "photo", operation: {
            started.fulfill()
            let accepted = await withCheckedContinuation { reply = $0 }
            guard !Task.isCancelled else { return false }
            preview = "old photo"
            return accepted
        }))
        await fulfillment(of: [started], timeout: 2)
        operations.stop()
        let next = try XCTUnwrap(operations.run(key: "photo", operation: { true }, onSuccess: { preview = "new photo" }))
        await next.value
        reply?.resume(returning: true); await old.value
        XCTAssertEqual(preview, "new photo")
        XCTAssertTrue(operations.pending.isEmpty)
    }
    func testRetiredStoryModelCannotPublishThroughAReplacementSession() async {
        let session = PulseSession()
        let stories = StoriesSessionModel(session: session)
        session.stop()
        let error = await stories.publish(caption: "old draft", background: "emerald", imagePath: nil)
        XCTAssertEqual(error, "The original story session ended.")
    }
}
