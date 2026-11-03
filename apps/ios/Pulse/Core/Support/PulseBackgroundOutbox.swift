import Foundation
import BackgroundTasks

/// Section 14 item 6 - the BGAppRefresh contract extracted from PulseApp so
/// OS scheduling, expiration, suspended execution and deferred dispatch are
/// unit-testable without a device. The coordinator:
///  - always re-submits the NEXT refresh before doing any work (the chain
///    never starves, and a submit failure is tolerated - foreground triggers
///    remain the fallback);
///  - drives the flush as a cancellable Task so the OS expiration handler
///    cancels it (a suspended flush keeps its journal entries pending - the
///    queue is only drained by a COMPLETED pass);
///  - reports success ONLY when the flush completed AND was not cancelled.
///
/// HONEST LIMIT: actual OS scheduling behavior (wake budget, deferred
/// dispatch timing) is device-level evidence; this contract pins the code
/// paths the device executes. The launch handler runs on the main queue
/// (BGTaskScheduler contract); the expiration handler may arrive on any
/// thread and only cancels - the exact semantics of the previous inline
/// PulseApp implementation.
final class PulseBackgroundOutboxCoordinator: @unchecked Sendable {
    struct Deps {
        /// Re-submits the next BGAppRefresh request (tolerates submit failure).
        let schedule: () -> Void
        /// Performs the flush; must honor Task cancellation promptly.
        let flush: () async -> Bool
    }

    private var work: Task<Void, Never>?

    /// Handles one OS background task. Closures stand in for BGTask so the
    /// contract is testable: `setCompleted` receives the final verdict and
    /// `installExpiration` installs the OS cancel hook.
    func handle(
        taskIdentifier: String,
        setCompleted: @escaping (Bool) -> Void,
        installExpiration: @escaping (@escaping () -> Void) -> Void,
        deps: Deps,
    ) {
        // Chain the next refresh FIRST: even if this pass expires early the
        // schedule survives (and submit failures are tolerated inside).
        deps.schedule()
        let flush = deps.flush
        let work = Task { @MainActor in
            let succeeded = await flush()
            setCompleted(succeeded && !Task.isCancelled)
        }
        self.work = work
        installExpiration { [weak self] in
            guard let self else { return }
            self.work?.cancel()
            self.work = nil
        }
    }
}
