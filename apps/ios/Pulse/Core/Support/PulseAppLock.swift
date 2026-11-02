import SwiftUI
import LocalAuthentication

// R10-b - biometric app lock (LAContext.deviceOwnerAuthentication).
//
// Honest model:
//  • Arm - leaving the foreground (scenePhase → .background) while the
//    PulsePrefs toggle is on marks `needsLock`; the RootView full-screen
//    cover presents when the scene is active again.
//  • Unlock - ONE evaluatePolicy(.deviceOwnerAuthentication): the system
//    owns the Face ID / Touch ID prompt and the automatic passcode
//    fallback (that is the policy's contract). Success clears the gate.
//  • Honest error mapping - .passcodeNotSet surfaces "Set a passcode to
//    use App lock."; user-cancelled attempts stay SILENT (the user
//    aborted, that is not an error to shout); everything else prints the
//    system copy verbatim. Never a fake success.
//  • The Settings enable path runs ONE real evaluatePolicy before the
//    toggle persists - a device that cannot enforce the lock never keeps
//    the setting on (honest toast + the toggle stays off).
//  • Interplay - while the gate is up, notification quick-reply REFUSES
//    to send (PulseQuickReplyCoordinator falls back to opening the room,
//    which shows this cover) so the lock cannot be bypassed from a
//    banner.
//
// Persistence rides PulsePrefs ("pulse.appLock.enabled" - the local-only
// device-security key; there is no web/account equivalent to sync with,
// same precedent as the per-conversation screenPrivacy veil).
//
// Section 13 item 13 - acceptance hardening:
//  (a) an evaluation already in flight is never doubled: a second unlock()
//      while one is running is a no-op, and a background/foreground cycle
//      re-prompts exactly once per activation (the gate view listens for
//      didBecomeActive). The gate cannot go stale without a live prompt.
//  (b) LAError.userCancel (and the other user-driven cancels) map to nil -
//      NO error UI, the manual Unlock button is the honest retry.
//  (c) the quick-reply refusal stays the durable behavior: a gated reply is
//      retained in Drafts & outbox and NEVER sent while needsLock is up.
//  (d) .passcodeNotSet is UNSATISFIABLE for this gate: the honest copy is
//      shown AND the escape hatch (Turn off App lock) clears the persisted
//      flag + the gate, so the user can never be trapped inside.

@MainActor
public final class PulseAppLock: ObservableObject {

    public static let shared = PulseAppLock()

    /// True while the lock gate must cover the app. Publicly settable so
    /// the RootView fullScreenCover(isPresented:) binding can dismiss it
    /// after a successful unlock.
    @Published public var needsLock = false
    /// Honest failure copy for the last attempt (nil = nothing to show -
    /// user-cancelled attempts stay silent by design).
    @Published public private(set) var statusMessage: String?
    /// Section 13 item 13(d) - the last failure can never be satisfied on
    /// this device (e.g. the passcode was removed while the lock was armed).
    /// The gate view uses it to expose the escape hatch.
    @Published public private(set) var unsatisfiable = false
    /// RootView attaches - flips the PERSISTED flag through PulsePrefs when
    /// the escape hatch fires (the in-memory toggle must follow the disk).
    public var onEmergencyDisable: (() -> Void)?
    /// Section 13 item 13(a) - an evaluatePolicy in flight marks this; a
    /// second concurrent unlock() call is a no-op (no double system prompt).
    private var unlockInFlight = false
    /// Internal for the gate-state tests: the gate must always agree with
    /// the persisted flag it was seeded from.
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        needsLock = defaults.bool(forKey: PulsePrefs.appLockEnabledKey)
    }

    /// RootView scenePhase → .background handler. Arming WITHOUT the
    /// toggle explicitly clears any stale gate (idempotent either way).
    /// Section 13 item 13 - the gate ALWAYS follows the persisted flag:
    /// a toggle whose write could not be persisted stays off here too.
    public func appDidEnterBackground(appLockEnabled: Bool) {
        needsLock = appLockEnabled
        if !appLockEnabled { statusMessage = nil; unsatisfiable = false }
    }

    // availability probe (no UI, used by the Settings toggle)

    /// canEvaluatePolicy never prompts. Returns nil when the device can
    /// enforce the lock, otherwise the honest reason it cannot.
    public static func availabilityProblem() -> String? {
        let context = LAContext()
        var error: NSError?
        let ok = context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
        if ok { return nil }
        return describe(error: error as? LAError, fallback: "This device can't enforce App lock.")
    }

    // unlock gate (the cover's Unlock button + auto-prompt)

    /// One real evaluatePolicy(.deviceOwnerAuthentication) - biometry
    /// first, passcode fallback automatic. Failure maps honestly into
    /// `statusMessage` (user-cancel → silent) and the gate stays up.
    /// Section 13 item 13(a) - concurrent calls collapse into the running
    /// evaluation, so rapid background/foreground cycles and the cover's
    /// auto-prompt can never stack two system dialogs.
    public func unlock() async {
        guard !unlockInFlight, needsLock else { return }
        unlockInFlight = true
        defer { unlockInFlight = false }
        let context = LAContext()
        do {
            try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock Pulse")
            needsLock = false
            statusMessage = nil
            unsatisfiable = false
        } catch {
            statusMessage = Self.failureCopy(for: error)
            unsatisfiable = Self.isUnsatisfiable(error)
        }
    }

    /// The Settings enable path - ONE real evaluate confirms the device
    /// can actually enforce the lock before the toggle persists. True
    /// only when the policy genuinely passed.
    public func runEnableConfirmation() async -> Bool {
        let context = LAContext()
        do {
            try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Confirm to enable App lock")
            statusMessage = nil
            return true
        } catch {
            statusMessage = Self.failureCopy(for: error)
            return false
        }
    }

    /// Section 13 item 13(d) - the escape hatch: a gate this device can no
    /// longer satisfy must not trap the user. Clears the persisted flag
    /// (through the RootView-attached prefs path), the gate and the copy.
    public func emergencyDisable() {
        defaults.set(false, forKey: PulsePrefs.appLockEnabledKey)
        unsatisfiable = false
        statusMessage = nil
        needsLock = false
        onEmergencyDisable?()
    }

    /// Shared verdict mapping: nil = the user aborted (silent), otherwise
    /// the honest message the surfaces print. Internal so the gate state
    /// machine is pinned by tests (the real evaluatePolicy cannot be).
    static func failureCopy(for error: Error) -> String? {
        switch (error as? LAError)?.code {
        case .userCancel, .userFallback, .systemCancel, .appCancel:
            return nil
        default:
            return describe(error: error as? LAError, fallback: "Authentication failed - try again.")
        }
    }

    /// Section 13 item 13(d) - a failure the policy can never recover from
    /// on this device. The passcode NOT existing makes every future
    /// evaluatePolicy(.deviceOwnerAuthentication) fail: the gate would be
    /// a trap without the escape hatch.
    static func isUnsatisfiable(_ error: Error) -> Bool {
        (error as? LAError)?.code == .passcodeNotSet
    }

    private static func describe(error: LAError?, fallback: String) -> String {
        switch error?.code {
        case .passcodeNotSet:
            return "Set a passcode to use App lock."
        case .biometryNotAvailable:
            return "Biometric authentication is unavailable on this device."
        case .biometryNotEnrolled:
            return "Enroll Face ID or Touch ID (or keep your passcode) to use App lock."
        case .biometryLockout:
            return "Biometry is locked out - unlock with your passcode first."
        default:
            if let message = error?.localizedDescription, !message.isEmpty {
                return message
            }
            return fallback
        }
    }
}

/// The full-screen lock gate (RootView fullScreenCover). Follows the app's
/// overlay grammar - page wash, one centered column, emerald accent. The
/// system prompt auto-fires on appear (banking-app behavior); cancelling
/// leaves the manual Unlock button as the honest retry.
///
/// Section 13 item 13(a) - a background dismissal of the system prompt
/// (app switcher over the gate) would otherwise leave a STALE gate without
/// a live prompt: every didBecomeActive re-fires exactly one evaluation
/// (the in-flight guard collapses the auto-prompt + notification overlap).
@MainActor
struct PulseAppLockView: View {
    @ObservedObject private var lock = PulseAppLock.shared

    var body: some View {
        ZStack {
            PulseTheme.pageWash
                .ignoresSafeArea()

            VStack(spacing: 18) {
                Image(systemName: "faceid")
                    .font(.system(size: 56, weight: .light))
                    .foregroundStyle(PulseTheme.accent)
                Text("Pulse is locked")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text("Unlock with Face ID, Touch ID or your device passcode to continue.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                if let message = lock.statusMessage {
                    Text(message)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(PulseTheme.amber)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }
                Button {
                    Task { await lock.unlock() }
                } label: {
                    Label("Unlock", systemImage: "lock.open")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 28)
                        .frame(minHeight: 46)
                        .background(Capsule().fill(PulseTheme.brandGradient))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Unlock Pulse")
                // Section 13 item 13(d) - the honest escape hatch: a gate the
                // device can never satisfy (no passcode) offers its removal.
                if lock.unsatisfiable {
                    Button(role: .destructive) {
                        lock.emergencyDisable()
                    } label: {
                        Text("Turn off App lock")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(PulseTheme.amber)
                            .padding(.horizontal, 20)
                            .frame(minHeight: 40)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Turn off App lock")
                }
            }
        }
        .task {
            // Auto-prompt once the cover is up - the user asked for the
            // lock; asking again on every return IS the feature.
            await lock.unlock()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            // Section 13 item 13(a) - a background/foreground cycle during an
            // evaluation dismisses the system prompt; this re-fires ONE
            // evaluation per activation (unlock() collapses concurrent calls).
            guard lock.needsLock else { return }
            Task { await lock.unlock() }
        }
    }
}
