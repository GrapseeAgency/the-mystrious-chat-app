import Foundation
import UIKit
import UserNotifications

// ─────────────────────────────────────────────────────────────
// Pulse — remote push client wiring (3-d; server transport = R8-web).
//
// Three honest layers:
//   1. Authorization + APNs registration — UNUserNotificationCenter
//      requestAuthorization (alert/sound/badge) →
//      UIApplication.registerForRemoteNotifications(). The hex device
//      token arrives through the AppDelegate (didRegister/ didFail) —
//      failures are LOGGED + SURFACED, never faked (the simulator and
//      unsigned builds cannot get an APNs token at all).
//   2. Token forwarding — POST /api/push/register { userId, platform:
//      'ios', token } (server upserts by unique token; DELETE removes).
//      Re-bound on every identity start and every token refresh (APNs
//      tokens rotate; the launch path re-registers each run).
//   3. Tap routing — the ALREADY-REGISTERED UNUserNotificationCenter
//      delegate (PulseReminderNotificationDelegate, PulseApp.init) maps
//      any userInfo carrying `conversationId` (message | gcall | call
//      pushes) to PulseDeepLink.room → the RootView linked-room bridge.
//      willPresent already keeps banners visible while foregrounded, so
//      remote pushes surface in-app exactly like the local reminders.
//
// Credential gate (honest): delivery arms ONLY when Apple-side
// credentials exist — the aps-environment entitlement + a signed build
// + the server's PULSE_APNS_* env (transport.ts sendIos). None of that
// is present in this sandbox; the client wiring above is complete and
// the failure states stay truthful about it.
// ─────────────────────────────────────────────────────────────

public enum PulsePushNotifications {

    /// Ask once per install (idempotent — a determined verdict returns
    /// immediately without a prompt); on grant, register with APNs. The
    /// verdict is logged + published, never asserted. @MainActor: the
    /// registration center lives on the main actor (every caller — session
    /// start, RootView, Settings — is main-actor context already).
    @MainActor
    public static func activate() {
        PulsePushRegistrationCenter.shared.beginAuthorization()
    }

    /// Identity teardown (Task 5-d) — the mirror of [activate()]: on
    /// sign-out / "Forget this viewer" the registry row (token → THAT user)
    /// must die with the identity, or the device keeps receiving the
    /// signed-out account's pushes. Unregisters the stored APNs token
    /// (DELETE /api/push/register { token }) fire-and-forget + clears the
    /// stored token. Called from PulseSession.stop() — the only path where
    /// the viewer becomes nil. @MainActor: same center, same rules.
    @MainActor
    public static func deactivate() {
        PulsePushRegistrationCenter.shared.deactivate()
    }
}

/// The SwiftUI-lifecycle AppDelegate — captures the APNs token lifecycle
/// (didRegister / didFail). Wired via @UIApplicationDelegateAdaptor in
/// PulseApp; registration itself is requested by activate() above.
public final class PulseAppDelegate: NSObject, UIApplicationDelegate {

    public func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 3-d — arm the PushKit VoIP registry at launch. Arming needs NO
        // credentials; DELIVERY needs the voips entitlement + a server VoIP
        // transport that does not exist yet (the alert-push path is the live
        // one). The report-a-call branch runs ONLY when a real VoIP push
        // arrives (PulseVoIPRegistry), so this stays dormant by construction.
        PulseVoIPRegistry.shared.arm()
        return true
    }

    /// R10-b — home-screen quick actions: install PulseSceneDelegate so the
    /// shortcut delivery hooks exist (cold launch reads
    /// connectionOptions.shortcutItem, warm launch rides
    /// windowScene(_:performActionFor:)). The delegate owns NO window —
    /// SwiftUI's WindowGroup keeps providing the UI — it only observes the
    /// shortcuts into PulseQuickActions.pendingRoute, which RootView
    /// consumes. This is the minimal scene-configuration touch that keeps
    /// the SwiftUI lifecycle intact.
    public func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = PulseSceneDelegate.self
        return configuration
    }

    /// The APNs device token — converted to the lowercase hex string the
    /// server transport addresses pushes with (transport.ts sendIos).
    public func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        PulsePushRegistrationCenter.shared.noteDeviceToken(deviceToken)
    }

    /// Registration refused (simulator, unsigned build, missing entitlement,
    /// provisioning mismatch) — the honest failure path, never faked.
    public func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        PulsePushRegistrationCenter.shared.noteRegistrationFailed(error)
    }
}

/// Owns the push-registration state machine + the token → server handoff.
/// Main-actor singleton (UIKit notification callbacks hop via the shared
/// instance's MainActor methods).
@MainActor
public final class PulsePushRegistrationCenter: ObservableObject {

    public static let shared = PulsePushRegistrationCenter()

    public enum Status: Equatable {
        case idle
        case authorizing
        case permissionDenied
        case tokenCaptured
        case registering
        case registered
        case gatewayUnconfigured
        case failed(String)
    }

    @Published public private(set) var status: Status = .idle

    private var deviceTokenHex: String?
    private var apiProvider: (() -> PulseAPIClient?)?
    private var lastRegisteredUserId: String?
    private var lastRegisteredToken: String?
    private var registrationTask: Task<Void, Never>?

    private static let tokenKey = "push.apnsTokenHex"

    private init() {
        // A token captured by a previous launch is reloaded so a launch-
        // path re-registration never depends on callback ordering.
        if let stored = UserDefaults.standard.string(forKey: Self.tokenKey), !stored.isEmpty {
            deviceTokenHex = stored
            status = .tokenCaptured
        }
    }

    // ── honest status copy (Settings row + logs) ─────────────

    public var statusLabel: String {
        switch status {
        case .idle: return "Not active"
        case .authorizing: return "Asking…"
        case .permissionDenied: return "Denied"
        case .tokenCaptured: return "Ready to register"
        case .registering: return "Registering…"
        case .registered: return "On"
        case .gatewayUnconfigured: return "No server"
        case .failed: return "Failed"
        }
    }

    public var statusNote: String {
        switch status {
        case .idle:
            return "Remote push activates once notifications are allowed."
        case .authorizing:
            return "Waiting for the notification permission answer."
        case .permissionDenied:
            return "Enable notifications for Pulse in Settings to receive pushes."
        case .tokenCaptured:
            return "Device token captured — it registers as soon as a server connection is configured."
        case .registering:
            return "Registering this device for pushes…"
        case .registered:
            return "This device receives pushes while the app is closed."
        case .gatewayUnconfigured:
            return "Configure a server in Settings → Connection first."
        case .failed(let message):
            return message
        }
    }

    // ── lifecycle entry points ───────────────────────────────

    /// Authorization → registerForRemoteNotifications. Honest verdicts.
    /// Idempotent: a determined permission verdict returns immediately
    /// (no prompt), and registration is re-requested every launch so a
    /// rotated APNs token re-binds.
    func beginAuthorization() {
        if status == .registered {
            // Already authorized + bound — just refresh the token.
            UIApplication.shared.registerForRemoteNotifications()
            return
        }
        status = .authorizing
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.status = .failed("Notifications failed: \(error.localizedDescription)")
                    NSLog("Pulse push: authorization error %@", error.localizedDescription)
                    return
                }
                guard granted else {
                    self.status = .permissionDenied
                    NSLog("Pulse push: authorization denied — remote pushes stay silent")
                    return
                }
                NSLog("Pulse push: authorization granted — registering for remote notifications")
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }

    /// APNs answered with a token — hex-encode, persist, forward.
    public func noteDeviceToken(_ token: Data) {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        guard !hex.isEmpty else {
            status = .failed("The push token arrived empty.")
            return
        }
        deviceTokenHex = hex
        UserDefaults.standard.set(hex, forKey: Self.tokenKey)
        status = .tokenCaptured
        NSLog("Pulse push: device token captured (%@…)", String(hex.prefix(8)))
        scheduleRegistration()
    }

    /// APNs refused registration — honest, logged, surfaced.
    public func noteRegistrationFailed(_ error: Error) {
        status = .failed("Push registration failed: \(error.localizedDescription)")
        NSLog("Pulse push: registration FAILED %@", error.localizedDescription)
    }

    /// RootView/session handoff — the API client provider (rebuilt per
    /// gateway override + identity). Triggers a pending registration.
    public func attach(apiProvider: @escaping () -> PulseAPIClient?) {
        self.apiProvider = apiProvider
        scheduleRegistration()
    }

    /// A new identity started — the token must re-bind to THIS user.
    public func noteViewerChanged() {
        lastRegisteredUserId = nil
        scheduleRegistration()
    }

    /// Task 5-d — identity teardown (sign-out). Order matters and is the
    /// point: the stored token is cleared SYNCHRONOUSLY (so a racing
    /// registration can never re-bind the stale token after a re-login —
    /// the next activation fetches a fresh APNs token), then the
    /// DELETE /api/push/register { token } fires fire-and-forget. Sign-out
    /// NEVER blocks on the network and NEVER fails because of it — failures
    /// are logged, honestly (fail-closed in the privacy-safe direction:
    /// local state is dead either way; a failed DELETE can only leave the
    /// SERVER row behind, which the next successful register upserts away).
    public func deactivate() {
        registrationTask?.cancel()
        registrationTask = nil
        let hex = deviceTokenHex
        deviceTokenHex = nil
        lastRegisteredUserId = nil
        lastRegisteredToken = nil
        UserDefaults.standard.removeObject(forKey: Self.tokenKey)
        status = .idle
        guard let hex, !hex.isEmpty else {
            NSLog("Pulse push: deactivate — no stored token, nothing to unregister")
            return
        }
        guard PulseEndpoints.isConfigured else {
            NSLog("Pulse push: deactivate — no server configured; a stale registry row (if any) cannot be removed from here")
            return
        }
        guard let api = apiProvider?() else {
            NSLog("Pulse push: deactivate — no API client attached; a stale registry row (if any) cannot be removed from here")
            return
        }
        Task {
            do {
                try await api.unregisterPushToken(token: hex)
                NSLog("Pulse push: device token unregistered (sign-out)")
            } catch {
                NSLog("Pulse push: unregister DELETE failed — the server row may linger until the next register: %@", error.localizedDescription)
            }
        }
    }

    private func scheduleRegistration() {
        registrationTask?.cancel()
        registrationTask = Task { [weak self] in
            await self?.registerCurrentToken()
        }
    }

    private func registerCurrentToken() async {
        guard let hex = deviceTokenHex, !hex.isEmpty else { return }
        guard let apiProvider else { return } // no session attached yet — waits
        guard PulseEndpoints.isConfigured else {
            status = .gatewayUnconfigured
            return
        }
        guard let api = apiProvider() else { return }
        let userId = api.userId
        guard !userId.isEmpty else {
            // No identity yet — registers on noteViewerChanged.
            status = .tokenCaptured
            return
        }
        if lastRegisteredUserId == userId, lastRegisteredToken == hex, status == .registered {
            return // already bound
        }
        status = .registering
        do {
            try await api.registerPushToken(userId: userId, platform: "ios", token: hex)
            if Task.isCancelled {
                // Task 5-d — deactivate() (sign-out) or a re-schedule
                // superseded this run AFTER the request may have landed: the
                // server row may exist again, but the identity it belonged to
                // is gone. Log the residue honestly, never fake a
                // "registered" state for a signed-out device — the next
                // successful register rebinds, the next sign-out deletes.
                NSLog("Pulse push: register landed after cancellation — server row may exist; local state stays reset")
                return
            }
            lastRegisteredUserId = userId
            lastRegisteredToken = hex
            status = .registered
            NSLog("Pulse push: device registered for user %@", userId)
        } catch {
            if Task.isCancelled {
                // Superseded/deactivated mid-flight — no state writes after
                // deactivate() has already reset everything.
                NSLog("Pulse push: register cancelled (superseded/deactivated)")
                return
            }
            status = .failed("Push registration failed: \(PulsePushRegistrationCenter.describe(error))")
            NSLog("Pulse push: register POST failed %@", error.localizedDescription)
        }
    }

    private static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return error.localizedDescription
    }
}
