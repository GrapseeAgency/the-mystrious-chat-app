import XCTest
@testable import Pulse

@MainActor
final class CoalescedRefreshTests: XCTestCase {
    func testContinuousInvalidationsFinishTheCurrentReadThenRunOneLatestPass() async {
        let queue = PulseCoalescedRefresh(initialDelay: 0)
        let started = expectation(description: "first read started")
        var release: CheckedContinuation<Void, Never>?
        var applied: [Int] = []
        defer { release?.resume(); queue.cancel() }
        let work = queue.request {
            await withCheckedContinuation { continuation in
                release = continuation
                started.fulfill()
            }
            XCTAssertFalse(Task.isCancelled, "Ordinary receipt traffic must not cancel the canonical read")
            applied.append(-1)
        }
        await fulfillment(of: [started], timeout: 2)
        for value in 0..<20 {
            queue.request { applied.append(value) }
        }
        XCTAssertTrue(applied.isEmpty)
        guard let continuation = release else { return }
        release = nil
        continuation.resume()
        await work.value
        XCTAssertEqual(applied, [-1, 19])
        queue.cancel()
    }

    func testLateCancelledPassCannotRetireItsReplacement() async {
        let queue = PulseCoalescedRefresh(initialDelay: 0)
        let oldStarted = expectation(description: "old read started")
        let newStarted = expectation(description: "replacement read started")
        var oldRelease: CheckedContinuation<Void, Never>?
        var newRelease: CheckedContinuation<Void, Never>?
        defer { oldRelease?.resume(); newRelease?.resume(); queue.cancel() }
        let old = queue.request {
            await withCheckedContinuation { oldRelease = $0; oldStarted.fulfill() }
        }
        await fulfillment(of: [oldStarted], timeout: 2)
        queue.cancel()
        let replacement = queue.request {
            await withCheckedContinuation { newRelease = $0; newStarted.fulfill() }
        }
        await fulfillment(of: [newStarted], timeout: 2)
        guard let oldContinuation = oldRelease, let newContinuation = newRelease else { return }
        oldRelease = nil
        oldContinuation.resume()
        await old.value // includes cleanup, not merely the old HTTP completion
        var following = 0
        queue.request { following += 1 }
        XCTAssertEqual(following, 0)
        newRelease = nil
        newContinuation.resume()
        await replacement.value
        XCTAssertEqual(following, 1)
        queue.cancel()
    }
}
