import Foundation
import CallKit
import PushKit
import AVFoundation
import Combine

// ─────────────────────────────────────────────────────────────
// Pulse — CallKit OS call integration (3-d).
//
// What this file owns:
//   • PulseCallKitCoordinator — observes the EXISTING call engines
//     (PulseCallEngine 1:1 + PulseGroupCallEngine mesh) and reports every
//     call to the system CXProvider:
//       incoming ring  → reportNewIncomingCall (caller name, hasVideo)
//       dial           → CXStartCallAction + reportOutgoingCall(startedConnectingAt)
//       answer arrival → reportOutgoingCall(startedConnectingAt) / connected
//       end            → reportCall(endedAt:reason:)
//     The CXProviderDelegate ACTIONS drive the engines (answer → accept /
//     join, end → decline/end/leave, mute → track flip, hold → honest
//     mute-based hold), so the system call UI and the in-app UI can never
//     disagree.
//
//   • No double-ring: while CallKit owns the INCOMING presentation
//     (`ownsIncomingPresentation`), the in-app ring surfaces (CallView
//     ring + the group ring banner) are suppressed. If CallKit REFUSES
//     the report (unsigned build / simulator), the flag lifts and the
//     in-app ring is the fallback presenter — exactly one presenter, always.
//
//   • PulseVoIPRegistry — PushKit VoIP path. It only ARMS at launch; the
//     report-a-call branch runs ONLY when a real VoIP push arrives. The
//     server does NOT send VoIP pushes today (src/lib/push/transport.ts
//     sends alert pushes only) — this path is dormant by construction and
//     the VoIP token is deliberately NOT posted to /api/push/register (a
//     VoIP token is useless for alert pushes). In-app foreground rings
//     keep working through the existing engine path regardless.
//
// Honest limits (cannot be proven in this sandbox): CallKit presentation,
// audio-session activation and the system mute/hold interplay are
// device-gated — CI compiles this, only a real handset can soak it.
// ─────────────────────────────────────────────────────────────

@MainActor
public final class PulseCallKitCoordinator: NSObject, ObservableObject {

    public static let shared = PulseCallKitCoordinator()

    /// True while CallKit is presenting the incoming call — the in-app ring
    /// surfaces suppress themselves for that call (no double-ring).
    @Published public private(set) var ownsIncomingPresentation = false

    struct LiveCall {
        let uuid: UUID
        let isGroup: Bool
        let conversationId: String?
        let outgoing: Bool
        var reportedConnecting = false
        var reportedConnected = false
        /// Tri-state for the honest hold approximation (nil = untouched).
        var mutedForHold = false
    }

    private var provider: CXProvider?
    private let controller = CXCallController()
    private var live: LiveCall?

    private weak var engine: PulseCallEngine?
    private weak var groupEngine: PulseGroupCallEngine?
    private var engineCancellables: Set<AnyCancellable> = []
    private var groupCancellables: Set<AnyCancellable> = []
    private var observedEngine: PulseCallEngine?
    private var observedGroupEngine: PulseGroupCallEngine?

    private override init() {
        super.init()
    }

    private func ensureProvider() -> CXProvider {
        if let provider { return provider }
        let configuration = CXProviderConfiguration(localizedName: "Pulse")
        configuration.supportsVideo = true
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.includesCallsInRecents = true
        configuration.supportedHandleTypes = [.generic]
        // ringtoneSound stays nil → the system default ringtone (no custom
        // asset is bundled; a custom one is a distribution-time task).
        let provider = CXProvider(configuration: configuration)
        let delegate = PulseCallKitProviderDelegate()
        delegate.coordinator = self
        provider.setDelegate(delegate, queue: nil)
        self.provider = provider
        return provider
    }

    // ── engine attachment (RootView, idempotent per instance) ──

    public func attach(engine: PulseCallEngine) {
        guard observedEngine !== engine else { return }
        observedEngine = engine
        self.engine = engine
        engineCancellables.removeAll()
        engine.$state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in self?.engineStateChanged(state) }
            .store(in: &engineCancellables)
    }

    public func attachGroup(engine: PulseGroupCallEngine) {
        guard observedGroupEngine !== engine else { return }
        observedGroupEngine = engine
        self.groupEngine = engine
        groupCancellables.removeAll()
        engine.$ring
            .receive(on: DispatchQueue.main)
            .sink { [weak self] ring in self?.groupRingChanged(ring) }
            .store(in: &groupCancellables)
        engine.$uiState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in self?.groupStateChanged(state) }
            .store(in: &groupCancellables)
    }

    // ── 1:1 engine observation ───────────────────────────────

    private func engineStateChanged(_ state: CallState) {
        guard let engine else { return }
        switch state {
        case .incomingRinging:
            guard live == nil, let peer = engine.activePeer else { return }
            reportIncoming(
                callerName: peer.name,
                hasVideo: engine.activeKind == .video,
                isGroup: false,
                conversationId: engine.activeCallConversationId,
                outgoing: false,
            )
        case .outgoingRinging:
            guard live == nil, let peer = engine.activePeer else { return }
            startOutgoingReport(
                callerName: peer.name,
                hasVideo: engine.activeKind == .video,
                isGroup: false,
                conversationId: engine.activeCallConversationId,
            )
        case .connecting:
            guard let info = live else { return }
            if info.outgoing, !info.reportedConnecting {
                live?.reportedConnecting = true
                ensureProvider().reportOutgoingCall(with: info.uuid, startedConnectingAt: Date())
            }
            ownsIncomingPresentation = false
        case .connected:
            guard let info = live else { return }
            if info.outgoing, !info.reportedConnecting {
                live?.reportedConnecting = true
                ensureProvider().reportOutgoingCall(with: info.uuid, startedConnectingAt: Date())
            }
            if !info.reportedConnected {
                live?.reportedConnected = true
                ensureProvider().reportOutgoingCall(with: info.uuid, connectedAt: Date())
            }
            ownsIncomingPresentation = false
        case .ended:
            if let info = live {
                endLiveCall(reason: info.reportedConnected ? .remoteEnded : .unanswered)
            }
        case .idle:
            break
        }
    }

    // ── group engine observation ─────────────────────────────

    private func groupRingChanged(_ ring: PulseGroupCallEngine.Ring?) {
        if let ring {
            guard live == nil || live?.isGroup == true else { return }
            if live == nil {
                reportIncoming(
                    callerName: ring.caller.name,
                    hasVideo: ring.kind == .video,
                    isGroup: true,
                    conversationId: ring.conversationId,
                    outgoing: false,
                )
            }
        } else {
            // Ring cleared: dismissed, timed out, or answered (join keeps
            // the CallKit call alive through joining/active).
            if let info = live, info.isGroup, !info.outgoing,
               groupEngine?.isInCall != true {
                endLiveCall(reason: .unanswered)
            }
        }
    }

    private func groupStateChanged(_ state: PulseGroupCallEngine.UiState) {
        guard let info = live, info.isGroup else { return }
        switch state {
        case .joining, .active:
            // Answered (CallKit or banner) — the system call continues; the
            // in-app group UI is the presenter from here on.
            ownsIncomingPresentation = false
            if state == .active, !info.reportedConnected {
                live?.reportedConnected = true
            }
        case .ended, .idle:
            endLiveCall(reason: (live?.reportedConnected ?? false) ? .remoteEnded : .unanswered)
        }
    }

    // ── reporting ────────────────────────────────────────────

    private func reportIncoming(
        callerName: String,
        hasVideo: Bool,
        isGroup: Bool,
        conversationId: String,
        outgoing: Bool
    ) {
        let uuid = UUID()
        live = LiveCall(
            uuid: uuid,
            isGroup: isGroup,
            conversationId: conversationId,
            outgoing: outgoing,
        )
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerName)
        update.localizedCallerName = callerName
        update.hasVideo = hasVideo
        ownsIncomingPresentation = true
        ensureProvider().reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    // CallKit refused (simulator / unsigned build) — lift the
                    // suppression so the IN-APP ring stays the presenter.
                    NSLog("Pulse CallKit: incoming report failed %@", error.localizedDescription)
                    if self.live?.uuid == uuid {
                        self.live = nil
                    }
                    self.ownsIncomingPresentation = false
                }
            }
        }
    }

    private func startOutgoingReport(
        callerName: String,
        hasVideo: Bool,
        isGroup: Bool,
        conversationId: String
    ) {
        let uuid = UUID()
        live = LiveCall(
            uuid: uuid,
            isGroup: isGroup,
            conversationId: conversationId,
            outgoing: true,
        )
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .generic, value: callerName))
        action.isVideo = hasVideo
        controller.request(CXTransaction(action: action)) { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    NSLog("Pulse CallKit: start transaction failed %@", error.localizedDescription)
                    if self.live?.uuid == uuid { self.live = nil }
                    return
                }
                // The system accepted the start — the call is dialing.
                self.ensureProvider().reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                if self.live?.uuid == uuid {
                    self.live?.reportedConnecting = true
                }
            }
        }
    }

    private func endLiveCall(reason: CXCallEndedReason) {
        guard let info = live else { return }
        live = nil
        ownsIncomingPresentation = false
        ensureProvider().reportCall(with: info.uuid, endedAt: Date(), reason: reason)
    }

    /// PushKit wake (PulseVoIPRegistry) — a VoIP push MUST report a call
    /// (Apple policy). No engine is attached to these yet (the app was just
    /// woken); answering is bookkeeping + log until a VoIP transport exists
    /// server-side. The report self-clears after the ring window.
    func reportVoIPIncoming(callerName: String, hasVideo: Bool, conversationId: String) {
        guard live == nil else { return }
        reportIncoming(
            callerName: callerName,
            hasVideo: hasVideo,
            isGroup: false,
            conversationId: conversationId,
            outgoing: false,
        )
        guard let uuid = live?.uuid else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + PulseGroupCallPolicy.ringTimeoutSec) { [weak self] in
            Task { @MainActor in
                if self?.live?.uuid == uuid, self?.engine == nil, self?.groupEngine == nil {
                    self?.endLiveCall(reason: .unanswered)
                }
            }
        }
    }

    // ── CXProviderDelegate effects (hopped from the bridge) ──

    func performAnswerAction(uuid: UUID) {
        guard let info = live, info.uuid == uuid else { return }
        ownsIncomingPresentation = false
        if info.isGroup {
            groupEngine?.joinCall()
        } else {
            engine?.acceptIncoming()
        }
    }

    func performEndAction(uuid: UUID) {
        guard let info = live, info.uuid == uuid else { return }
        live = nil
        ownsIncomingPresentation = false
        if info.isGroup {
            if groupEngine?.isInCall == true {
                groupEngine?.leaveCall()
            } else {
                groupEngine?.dismissRing()
            }
            return
        }
        guard let engine else { return }
        switch engine.state {
        case .incomingRinging:
            engine.declineIncoming()
        case .outgoingRinging, .connecting, .connected:
            engine.endCall()
        default:
            break
        }
    }

    func performMuteAction(muted: Bool) {
        guard let info = live else { return }
        if info.isGroup {
            guard let group = groupEngine else { return }
            // Toggle when micEnabled == muted → lands on micEnabled == !muted.
            if group.micEnabled == muted { group.toggleMic() }
        } else if let engine {
            if engine.micEnabled == muted { engine.toggleMute() }
        }
    }

    /// Honest hold approximation: the engines have no media pause, so hold
    /// mutes the mic (remembering the pre-hold state) and unhold restores it.
    func performHoldAction(onHold: Bool) {
        guard var info = live else { return }
        if onHold {
            guard !info.mutedForHold else { return }
            let micEnabledNow: Bool = info.isGroup
                ? (groupEngine?.micEnabled ?? true)
                : (engine?.micEnabled ?? true)
            if micEnabledNow {
                if info.isGroup { groupEngine?.toggleMic() } else { engine?.toggleMute() }
                info.mutedForHold = true
            }
        } else if info.mutedForHold {
            if info.isGroup { groupEngine?.toggleMic() } else { engine?.toggleMute() }
            info.mutedForHold = false
        }
        live = info
    }

    func providerDidReset() {
        // The system tore the provider down (another provider, crash
        // recovery) — drop bookkeeping; the engines keep their own state
        // and their UI remains the honest presenter.
        live = nil
        ownsIncomingPresentation = false
    }
}

// ── the CXProviderDelegate bridge ────────────────────────────

/// Nonisolated bridge (PulseRTCDelegateBox / reminder-delegate pattern):
/// CallKit invokes these on ITS queue; the engine effects hop to the main
/// actor, the CXActions fulfill synchronously so CallKit never stalls.
private final class PulseCallKitProviderDelegate: NSObject, CXProviderDelegate {
    weak var coordinator: PulseCallKitCoordinator?

    func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor in
            coordinator?.providerDidReset()
        }
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        // The engine already started the call (the report follows the dial);
        // fulfillment just acknowledges the system transaction.
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let uuid = action.callUUID
        Task { @MainActor in
            coordinator?.performAnswerAction(uuid: uuid)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        Task { @MainActor in
            coordinator?.performEndAction(uuid: uuid)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        let muted = action.isMuted
        Task { @MainActor in
            coordinator?.performMuteAction(muted: muted)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        let onHold = action.isOnHold
        Task { @MainActor in
            coordinator?.performHoldAction(onHold: onHold)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        // CallKit activated the audio session for a reported call — apply
        // the voice-chat configuration HERE (idempotent with the engine's
        // own activation; the snapshot/restore pair stays consistent).
        Task { @MainActor in
            PulseCallAudioSession.shared.activateForCall()
        }
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        // Engine teardown already restores the pre-call session; nothing
        // extra here (a double restore would be a no-op but adds risk).
    }
}

// ── PushKit VoIP bridge (dormant until a VoIP transport exists) ──

public final class PulseVoIPRegistry: NSObject, PKPushRegistryDelegate {
    public static let shared = PulseVoIPRegistry()

    private let registry = PKPushRegistry(queue: .main)
    private var armed = false
    private static let tokenKey = "push.voipTokenHex"

    /// Arms the VoIP push registry at launch. No credentials needed to arm;
    /// DELIVERY needs a server transport that does not exist yet (the alert
    /// push path is the live one). In-app foreground rings keep working
    /// through the engines regardless.
    public func arm() {
        guard !armed else { return }
        armed = true
        registry.delegate = self
        registry.setDesiredPushTypes([.voIP])
    }

    public func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        guard type == PKPushType.voIP else { return }
        let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        // Deliberately NOT posted to /api/push/register: the server's APNs
        // transport sends ALERT pushes; a VoIP token registered there would
        // poison the alert registry. Stored for a future VoIP transport.
        UserDefaults.standard.set(hex, forKey: Self.tokenKey)
        NSLog("Pulse VoIP: registry token captured (%@…) — no server transport yet", String(hex.prefix(8)))
    }

    public func pushRegistry(
        _ registry: PKPushRegistry,
        didInvalidatePushTokenFor type: PKPushType
    ) {
        guard type == PKPushType.voIP else { return }
        UserDefaults.standard.removeObject(forKey: Self.tokenKey)
    }

    public func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == PKPushType.voIP else {
            completion()
            return
        }
        // Apple REQUIRES every received VoIP push to report a call to
        // CallKit — this is that report (the ONLY armed branch).
        let dict = payload.dictionaryPayload
        let callerName = (dict["callerName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Pulse call"
        let hasVideo = (dict["callKind"] as? String) == "video"
        let conversationId = (dict["conversationId"] as? String) ?? ""
        Task { @MainActor in
            PulseCallKitCoordinator.shared.reportVoIPIncoming(
                callerName: callerName,
                hasVideo: hasVideo,
                conversationId: conversationId,
            )
        }
        completion()
    }
}
