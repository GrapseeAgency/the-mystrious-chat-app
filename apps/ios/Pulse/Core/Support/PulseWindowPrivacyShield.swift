import SwiftUI
import UIKit

typealias PulseWindowPrivacyPolicy = @MainActor () -> Bool

/// A room lease protects its entire existing window, not only message rows:
/// drafts, recap cards and already-presented room sheets must not be left in
/// the inactive snapshot. This does not prevent user screenshots on iOS.
@MainActor
final class PulseWindowPrivacyState {
    private(set) weak var window: UIWindow?
    private var owners: [UUID: PulseWindowPrivacyPolicy] = [:]
    private var inactive: Bool
    private let cover: PulseWindowPrivacyCoverView
    var isEmpty: Bool { owners.isEmpty }
    var isCovering: Bool { cover.superview != nil }

    init(window: UIWindow, inactive: Bool) {
        self.window = window; self.inactive = inactive
        cover = PulseWindowPrivacyCoverView()
        cover.backgroundColor = UIColor(PulseTheme.emberChrome)
        cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        cover.accessibilityIdentifier = "pulse.windowPrivacyCover"
        cover.isAccessibilityElement = true
        cover.accessibilityViewIsModal = true
        cover.accessibilityLabel = "Screen security is on. Conversation content is hidden."
        let label = UILabel()
        label.text = "Screen security is on"
        label.textColor = UIColor(PulseTheme.emberGlowTop)
        label.font = .preferredFont(forTextStyle: .headline)
        label.translatesAutoresizingMaskIntoConstraints = false
        cover.addSubview(label)
        NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: cover.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: cover.centerYAnchor)])
    }
    func update(owner: UUID, required: Bool) { update(owner: owner, policy: { required }) }
    func update(owner: UUID, policy: @escaping PulseWindowPrivacyPolicy) { owners[owner] = policy; refresh() }
    func remove(owner: UUID) { owners[owner] = nil; refresh() }
    func setInactive(_ value: Bool) { inactive = value; refresh() }
    private func refresh() {
        guard let window else { cover.removeFromSuperview(); return }
        guard inactive, owners.values.contains(where: { $0() }) else { cover.removeFromSuperview(); return }
        UIView.performWithoutAnimation {
            cover.frame = window.bounds
            if cover.superview !== window { window.addSubview(cover) }
            // Section 13 item 12(a) - the cover must be the TOP subview at
            // the moment it matters; the re-fronting view class keeps it
            // above content that lands later (see PulseWindowPrivacyCoverView).
            window.bringSubviewToFront(cover)
        }
    }
}

/// Section 13 item 12(a) - late presentations. A fullScreenCover or sheet
/// presented while the app is already inactive wraps its content in a
/// container added to the SAME window ABOVE whatever subviews exist - which
/// would paint over a static cover. The window-level shield is genuinely
/// window-level (the Anchor leases the WINDOW, not the view - every window
/// of the scene that hosts a leased anchor gets the cover), so the only
/// remaining hole is z-order. This cover re-fronts itself whenever UIKit
/// runs a layout pass after the insertion (didMoveToSuperview + every
/// layoutSubviews while attached), synchronously before the app-switcher
/// snapshot can be taken - no Task, no run-loop hop. RootView already owns
/// the only scene window in this app (single-scene); presented controllers
/// reuse that window, so the re-front covers every presentation.
final class PulseWindowPrivacyCoverView: UIView {
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        refront()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        refront()
    }
    private func refront() {
        guard let superview, superview.subviews.last !== self else { return }
        superview.bringSubviewToFront(self)
    }
}

@MainActor
private final class PulseWindowPrivacyRegistry {
    static let shared = PulseWindowPrivacyRegistry()
    private let states = NSMapTable<UIWindow, PulseWindowPrivacyState>(keyOptions: .weakMemory, valueOptions: .strongMemory)
    private var observers: [NSObjectProtocol] = []
    private init() {
        for name in [UIApplication.willResignActiveNotification, UIApplication.didBecomeActiveNotification,
                     UIScene.willDeactivateNotification, UIScene.didActivateNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                // UIKit lifecycle notifications arrive on main; do not enqueue a
                // later Task that might paint only after the snapshot is taken.
                MainActor.assumeIsolated { self?.received(note) }
            })
        }
    }
    /// Every window the registry knows about, across ALL connected scenes.
    /// Section 13 item 12(b) - the registry must never assume one keyed
    /// window: leases are keyed per window, and this sweep iterates the full
    /// set (multi-scene included) so a system-owned or secondary window with
    /// a lease gets the same lifecycle posture as the primary one.
    private var knownStates: [PulseWindowPrivacyState] {
        (states.objectEnumerator()?.allObjects as? [PulseWindowPrivacyState]) ?? []
    }
    private func received(_ note: Notification) {
        for state in knownStates {
            guard let window = state.window else { continue }
            if note.name == UIApplication.willResignActiveNotification {
                // Section 13 item 12(c) - app-switcher snapshot timing: this
                // path is SYNCHRONOUS (notification → setInactive → addSubview
                // → bringSubviewToFront, no Task/enqueue), so the cover is in
                // the window before the snapshot render pass.
                state.setInactive(true)
            } else if note.name == UIApplication.didBecomeActiveNotification {
                // Section 13 item 12(b) - scene state first, the PROCESS state
                // as the honest fallback for windows without a scene yet (a
                // window created for a late presentation before scene attach);
                // previously the fallback claimed "active" and could drop a
                // cover from a deactivation the window never saw.
                let inactive = window.windowScene.map { $0.activationState != .foregroundActive }
                    ?? (UIApplication.shared.applicationState != .active)
                state.setInactive(inactive)
            } else if let scene = note.object as? UIScene, scene === window.windowScene {
                state.setInactive(note.name == UIScene.willDeactivateNotification)
            }
        }
    }
    func update(window: UIWindow, owner: UUID, policy: @escaping PulseWindowPrivacyPolicy) {
        let state: PulseWindowPrivacyState
        if let existing = states.object(forKey: window) { state = existing }
        else {
            // Section 13 item 12(b) - same honest fallback at lease creation:
            // a window without a scene yet defers to the process state instead
            // of assuming active (fail-closed, the OR-policy keeps covering).
            let inactive = window.windowScene.map { $0.activationState != .foregroundActive }
                ?? (UIApplication.shared.applicationState != .active)
            state = PulseWindowPrivacyState(window: window, inactive: inactive)
            states.setObject(state, forKey: window)
        }
        state.update(owner: owner, policy: policy)
    }
    func remove(window: UIWindow, owner: UUID) {
        guard let state = states.object(forKey: window) else { return }
        state.remove(owner: owner)
        if state.isEmpty { states.removeObject(forKey: window) }
    }
}

struct PulseWindowPrivacyShield: UIViewRepresentable {
    let policy: PulseWindowPrivacyPolicy
    final class Anchor: UIView {
        private let owner = UUID()
        private weak var attachedWindow: UIWindow?
        var policy: PulseWindowPrivacyPolicy = { true } { didSet { updateLease() } }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            // Section 13 item 12(a) - UIKit may temporarily remove the
            // presenting view for a full-screen child. The lease rides the
            // WINDOW (never the anchor view), so it survives reattachment and
            // any window SwiftUI swaps in for a presentation; the lease is
            // kept until reattachment or SwiftUI teardown.
            if let window, attachedWindow !== window { detach(); attachedWindow = window }
            updateLease()
        }
        private func updateLease() {
            if let attachedWindow { PulseWindowPrivacyRegistry.shared.update(window: attachedWindow, owner: owner, policy: policy) }
        }
        func detach() {
            if let attachedWindow { PulseWindowPrivacyRegistry.shared.remove(window: attachedWindow, owner: owner) }
            attachedWindow = nil
        }
    }
    func makeUIView(context: Context) -> Anchor {
        let view = Anchor()
        view.isUserInteractionEnabled = false
        view.accessibilityElementsHidden = true
        view.policy = policy
        return view
    }
    func updateUIView(_ view: Anchor, context: Context) { view.policy = policy }
    static func dismantleUIView(_ view: Anchor, coordinator: ()) { view.detach() }
}
