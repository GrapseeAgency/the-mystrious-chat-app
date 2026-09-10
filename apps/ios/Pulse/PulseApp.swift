// Pulse iOS — app entry (SwiftUI lifecycle, iOS 17+).
// Native per the blueprint: SwiftUI UI, Clean Architecture folders,
// GRDB (SQLite) local store, URLSession networking — no cross-platform shell.

import SwiftUI
import BackgroundTasks

@main
struct PulseApp: App {
    /// Outbox background-refresh identifier — must match
    /// project.yml → BGTaskSchedulerPermittedIdentifiers.
    static let outboxTaskID = "app.pulse.chat.outbox"

    @Environment(\.scenePhase) private var scenePhase

    init() {
        // BGAppRefreshTask for the outbox. Registration returns false (and
        // logs) on the simulator / when the identifier is missing from the
        // Info.plist — tolerated, foreground flushing still covers it.
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.outboxTaskID,
            using: nil,
        ) { task in
            Self.handleOutboxTask(task)
        }
        if !registered, Self.bgTaskVerbose {
            print("[Pulse] BGTaskScheduler registration failed for \(Self.outboxTaskID)")
        }
    }

    /// Simulator chatter guard — flip to true while debugging background tasks.
    private static let bgTaskVerbose = false

    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(PulseTheme.emerald)
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .background:
                        // BGAppRefreshTask requests must be submitted while
                        // backgrounded (re-submitted after each run).
                        Self.scheduleOutboxRefresh()
                    default:
                        break
                    }
                }
        }
    }

    // ── BGAppRefreshTask plumbing (static — no view graph here) ──

    /// Chains the next refresh and flushes the outbox through the live engine.
    static func handleOutboxTask(_ task: BGTask) {
        scheduleOutboxRefresh()
        let work = Task { @MainActor in
            await PulseOutboxEngine.active?.flush()
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
        }
    }

    /// Submits the background-refresh request (15 min earliest). Failures —
    /// simulator, missing permission, over-quota — are honest no-ops.
    static func scheduleOutboxRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: outboxTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Simulator / missing Info.plist entry — foreground triggers cover it.
        }
    }
}
