import Foundation
import AVFoundation
import Combine

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 call audio session (AVFoundation).
//
// Shared design: .playAndRecord + .voiceChat (+ .defaultToSpeaker,
// .allowBluetooth) on call start/accept; speaker toggle via
// overrideOutputAudioPort; SAVE the prior category/options/mode and RESTORE
// them on call end; observe route changes.
//
// Hardware-gated (documented honestly): the actual earpiece/speaker switch,
// bluetooth SCO pickup and the pre-call session restoration behavior can
// only be PROVEN on a physical device — the simulator answers these APIs
// with defaults and never exhibits real routes.
// ─────────────────────────────────────────────────────────────

@MainActor
public final class PulseCallAudioSession: ObservableObject {
    public static let shared = PulseCallAudioSession()

    private struct SavedSession {
        let category: AVAudioSession.Category
        let options: AVAudioSession.CategoryOptions
        let mode: AVAudioSession.Mode
    }

    /// The pre-call session snapshot — restored exactly once, on call end.
    private var saved: SavedSession?
    private var routeObserver: NSObjectProtocol?

    /// Whether a call currently owns the session (engine drives this).
    public private(set) var isActiveCallSession: Bool = false

    /// Last observed output route description (route-change observation).
    @Published public private(set) var currentOutputRoute: String = ""
    /// Whether the loudspeaker override is active (CallView toggle state).
    @Published public private(set) var speakerOverride: Bool = true

    private init() {
        observeRouteChanges()
    }

    // ── call lifecycle ───────────────────────────────────────

    /// Call start / accept — snapshot the pre-call session once, then apply
    /// the voice-chat configuration. Nested calls (accept while already
    /// active — cannot happen, the machine guards it) would keep the FIRST
    /// snapshot so the original session always wins the restore.
    public func activateForCall() {
        guard !isActiveCallSession else { return }
        let session = AVAudioSession.sharedInstance()
        saved = SavedSession(category: session.category, options: session.categoryOptions, mode: session.mode)
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetooth, .defaultToSpeaker]
            )
            try session.setActive(true)
            isActiveCallSession = true
            speakerOverride = true
            refreshRouteDescription()
        } catch {
            // Audio hardware refused (simulator edge, hardened device policy).
            // The call still rings/talks over the default route — honest
            // degradation, never a crash.
            isActiveCallSession = true
        }
    }

    /// Call end — restore the saved category/mode/options and deactivate with
    /// notifyOthers so background audio (music, voice-note playback) resumes.
    public func restore() {
        let session = AVAudioSession.sharedInstance()
        // Loudspeaker override must be cleared before the category flips back.
        try? session.overrideOutputAudioPort(.none)
        if let saved {
            try? session.setCategory(saved.category, mode: saved.mode, options: saved.options)
        }
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        isActiveCallSession = false
        speakerOverride = false
        currentOutputRoute = ""
    }

    // ── speaker toggle ───────────────────────────────────────

    /// .defaultToSpeaker is set at activation, so the default is speaker-on;
    /// toggling overrides the output port between .speaker and .none
    /// (receiver/earpiece). Requires the .playAndRecord category — which the
    /// activation above guarantees while a call is live.
    public func setSpeaker(_ enabled: Bool) {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.overrideOutputAudioPort(enabled ? .speaker : .none)
            speakerOverride = enabled
            refreshRouteDescription()
        } catch {
            // Route override refused — keep the published state honest.
            refreshRouteDescription()
        }
    }

    // ── mic permission ───────────────────────────────────────

    /// The honest gate before any call: asks when undetermined, reports the
    /// real verdict otherwise. Denied → the engine shows the error card.
    public func requestMicPermission() async -> Bool {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                session.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }

    // ── route observation ────────────────────────────────────

    private func observeRouteChanges() {
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refreshRouteDescription()
            }
        }
    }

    private func refreshRouteDescription() {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        currentOutputRoute = outputs.map(\.portName).joined(separator: ", ")
    }
}
