// Pulse iOS - app entry (SwiftUI lifecycle, iOS 17+).
// Native per the blueprint: SwiftUI UI, Clean Architecture folders,
// GRDB (SQLite) local store, URLSession networking - no cross-platform shell.

import SwiftUI
import BackgroundTasks
import UserNotifications

@main
struct PulseApp: App {
    /// Outbox background-refresh identifier - must match
    /// project.yml → BGTaskSchedulerPermittedIdentifiers.
    static let outboxTaskID = "app.pulse.chat.outbox"

    @Environment(\.scenePhase) private var scenePhase

    // 3-d - the SwiftUI-lifecycle AppDelegate captures the APNs token
    // lifecycle (didRegisterForRemoteNotificationsWithDeviceToken /
    // didFailToRegister…) and arms the PushKit VoIP registry at launch.
    @UIApplicationDelegateAdaptor(PulseAppDelegate.self) private var appDelegate

    init() {
        // R2-D - the notification-tap delegate (reminder taps → room).
        // Registered once at process start so cold-start taps are captured
        // even before RootView attaches its routing closure.
        UNUserNotificationCenter.current().delegate = PulseReminderNotificationDelegate.shared

        // R10-b - the notification quick-reply category ("Reply" text-input
        // action on message-bound notifications). Registered once at process
        // start so cold-start banners already offer it; the same delegate
        // routes UNTextInputNotificationResponse to
        // PulseQuickReplyCoordinator (session send or honest fallback).
        PulseQuickReply.registerCategory()

        // BGAppRefreshTask for the outbox. Registration returns false (and
        // logs) on the simulator / when the identifier is missing from the
        // Info.plist - tolerated, foreground flushing still covers it.
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

    /// Simulator chatter guard - flip to true while debugging background tasks.
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

    // BGAppRefreshTask plumbing (static - no view graph here)

    /// Chains the next refresh and flushes the outbox through the live engine.
    static func handleOutboxTask(_ task: BGTask) {
        scheduleOutboxRefresh()
        let work = Task { @MainActor in
            let succeeded = await Self.flushBackgroundOutbox()
            task.setTaskCompleted(success: succeeded && !Task.isCancelled)
        }
        task.expirationHandler = {
            work.cancel()
        }
    }

    @MainActor
    static func flushBackgroundOutbox(recipientUserId: String? = nil, readStateRoom: String? = nil) async -> Bool {
        if let recipientUserId, PulseKeychain.shared.loadViewer()?.id != recipientUserId { return false }
        if let active = PulseOutboxEngine.active {
            // Invalidate open scopes promptly even if an unrelated upload is slow.
            // The session callback is generation-fenced and fetches canonical counts.
            if let readStateRoom { active.invalidateReadProjections(readStateRoom) }
            return await active.flushBackgroundQueues()
        }
        guard let viewer = PulseKeychain.shared.loadViewer(),
              let token = PulseKeychain.shared.loadSessionToken() else { return false }
        PulseEndpoints.loadPersistedOverride()
        await PulseEndpoints.fetchManifestOverride()
        guard !Task.isCancelled, PulseKeychain.shared.loadViewer()?.id == viewer.id,
              PulseKeychain.shared.loadSessionToken() == token else { return false }
        do {
            let store = try PulseStore.openAccount(gateway: PulseEndpoints.gatewayURL.absoluteString, viewerId: viewer.id)
            let api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id, authToken: token)
            let engine = PulseOutboxEngine(store: store) { api }
            engine.onReadJournalFlush = {
                await PulseReadJournalReplay.flush(store: store, owner: viewer.id,
                    isCurrent: { PulseKeychain.shared.loadViewer()?.id == viewer.id &&
                        PulseKeychain.shared.loadSessionToken() == token &&
                        PulseEndpoints.gatewayURL == api.baseURL },
                    send: { read in
                        try await api.markRead(conversationId: read.room, messageId: read.message,
                            minimumReadAt: read.minimumReadAt, readIntent: read.intent)
                    })
            }
            engine.onBadgeRefresh = {
                await PulsePushNotifications.refreshUnreadBadge(using: api, store: store, isCurrent: {
                    PulseOutboxEngine.active == nil && PulseKeychain.shared.loadViewer()?.id == viewer.id &&
                    PulseKeychain.shared.loadSessionToken() == token && PulseEndpoints.gatewayURL == api.baseURL
                })
            }
            defer { engine.stop() }
            return await engine.flushBackgroundQueues()
        } catch { return false }
    }

    /// Submits the background-refresh request (15 min earliest). Failures -
    /// simulator, missing permission, over-quota - are honest no-ops.
    static func scheduleOutboxRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: outboxTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Simulator / missing Info.plist entry - foreground triggers cover it.
        }
    }
}
