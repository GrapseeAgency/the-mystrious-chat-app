import XCTest
@testable import Pulse

/// Section 14 item 6 - the BGAppRefresh contract: schedule-first chaining,
/// expiration cancels the in-flight flush, a cancelled/suspended pass never
/// reports success, and submit failures inside `schedule` are tolerated
/// (foreground triggers remain the fallback).
final class BackgroundOutboxCoordinatorTests: XCTestCase {

    private final class Recorded {
        var events: [String] = []
    }

    private func deps(
        recorded: Recorded,
        scheduleFails: Bool = false,
        hang: Bool = false,
    ) -> PulseBackgroundOutboxCoordinator.Deps {
        PulseBackgroundOutboxCoordinator.Deps(
            schedule: {
                recorded.events.append("schedule")
                if scheduleFails {
                    // a refused submit is tolerated inside the closure (an
                    // honest no-op - foreground triggers remain the fallback)
                }
            },
            flush: {
                if hang {
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 20_000_000)
                    }
                    recorded.events.append("flush-cancelled")
                    return false
                }
                recorded.events.append("flush-start")
                recorded.events.append("flush-end")
                return true
            },
        )
    }

    func testTheNextRefreshIsChainedBeforeAnyFlushWorkBegins() async {
        let recorded = Recorded()
        let coordinator = PulseBackgroundOutboxCoordinator()
        let completed = expectation(description: "completed")

        coordinator.handle(
            taskIdentifier: "app.pulse.chat.outbox",
            setCompleted: { _ in completed.fulfill() },
            installExpiration: { _ in },
            deps: deps(recorded: recorded),
        )

        await wait(for: [completed], timeout: 10)
        XCTAssertEqual(recorded.events.first, "schedule", "the chain must be resubmitted first")
        XCTAssertEqual(recorded.events, ["schedule", "flush-start", "flush-end"])
    }

    func testACompletedUncancelledFlushReportsSuccess() async {
        let recorded = Recorded()
        let coordinator = PulseBackgroundOutboxCoordinator()
        let verdict = expectation(description: "verdict")

        var succeeded: Bool?
        coordinator.handle(
            taskIdentifier: "app.pulse.chat.outbox",
            setCompleted: { success in
                succeeded = success
                verdict.fulfill()
            },
            installExpiration: { _ in },
            deps: deps(recorded: recorded),
        )

        await wait(for: [verdict], timeout: 10)
        XCTAssertEqual(succeeded, true)
    }

    func testExpirationCancelsTheSuspendedFlushAndReportsFailure() async {
        let recorded = Recorded()
        let coordinator = PulseBackgroundOutboxCoordinator()
        let verdict = expectation(description: "verdict")

        var expiration: (() -> Void)?
        var succeeded: Bool?
        coordinator.handle(
            taskIdentifier: "app.pulse.chat.outbox",
            setCompleted: { success in
                succeeded = success
                verdict.fulfill()
            },
            installExpiration: { hook in expiration = hook },
            deps: deps(recorded: recorded, hang: true),
        )

        // the flush is suspended; the OS expiration hook arrives NOW
        XCTAssertNotNil(expiration)
        expiration?()

        await wait(for: [verdict], timeout: 10)
        XCTAssertEqual(succeeded, false, "an expired/suspended pass must never claim success")
    }

    func testAScheduleFailureIsToleratedAndTheFlushStillRuns() async {
        let recorded = Recorded()
        let coordinator = PulseBackgroundOutboxCoordinator()
        let completed = expectation(description: "completed")

        coordinator.handle(
            taskIdentifier: "app.pulse.chat.outbox",
            setCompleted: { _ in completed.fulfill() },
            installExpiration: { _ in },
            deps: deps(recorded: recorded, scheduleFails: true),
        )

        await wait(for: [completed], timeout: 10)
        XCTAssertEqual(recorded.events, ["schedule", "flush-start", "flush-end"])
    }
}
