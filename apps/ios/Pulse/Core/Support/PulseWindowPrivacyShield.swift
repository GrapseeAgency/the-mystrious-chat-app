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
    private let cover = UIView()
    var isEmpty: Bool { owners.isEmpty }
    var isCovering: Bool { cover.superview != nil }

    init(window: UIWindow, inactive: Bool) {
        self.window = window; self.inactive = inactive
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
            window.bringSubviewToFront(cover)
        }
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
    private func received(_ note: Notification) {
        for case let state as PulseWindowPrivacyState in states.objectEnumerator()?.allObjects ?? [] {
            guard let window = state.window else { continue }
            if note.name == UIApplication.willResignActiveNotification { state.setInactive(true) }
            else if note.name == UIApplication.didBecomeActiveNotification {
                state.setInactive(window.windowScene.map { $0.activationState != .foregroundActive } ?? false)
            } else if let scene = note.object as? UIWindowScene, scene === window.windowScene {
                state.setInactive(note.name == UIScene.willDeactivateNotification)
            }
        }
    }
    func update(window: UIWindow, owner: UUID, policy: @escaping PulseWindowPrivacyPolicy) {
        let state: PulseWindowPrivacyState
        if let existing = states.object(forKey: window) { state = existing }
        else {
            let inactive = UIApplication.shared.applicationState != .active ||
                (window.windowScene.map { $0.activationState != .foregroundActive } ?? false)
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
            // UIKit may temporarily remove the presenting view for a full-
            // screen child. Keep its lease until reattachment or SwiftUI teardown.
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
