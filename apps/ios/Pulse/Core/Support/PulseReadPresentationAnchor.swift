import SwiftUI
import UIKit

/// A passive anchor in OUR reading surface, not a modification of a system
/// controller or its delegates. UIKit-owned presentations need not change any
/// SwiftUI sheet binding (for example a panel opened from a text edit menu).
@MainActor
enum PulseReadPresentation {
    static func hasPresentationAbove(_ view: UIView) -> Bool {
        var responder: UIResponder? = view
        while let next = responder, !(next is UIViewController) { responder = next.next }
        guard var controller = responder as? UIViewController else { return true }
        while true {
            // Keep transitions covered too, including the interval where UIKit
            // clears presentedViewController before the dismissal has finished.
            if controller.transitionCoordinator != nil { return true }
            if let presented = controller.presentedViewController,
               presented.viewIfLoaded.map({ !view.isDescendant(of: $0) }) ?? true { return true }
            guard let parent = controller.parent else { return false }
            controller = parent
        }
    }
}

struct PulseReadPresentationAnchor: UIViewRepresentable {
    let session: PulseSession
    let owner: UUID

    final class Anchor: UIView {
        weak var session: PulseSession?
        var owner: UUID?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let owner { session?.nativeReadAnchorWindowChanged(self, owner: owner) }
        }
    }

    func makeUIView(context: Context) -> Anchor {
        let view = Anchor()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        updateUIView(view, context: context)
        return view
    }

    func updateUIView(_ view: Anchor, context: Context) {
        if view.owner != owner || view.session !== session {
            if let previous = view.owner { view.session?.removeNativeReadAnchor(view, owner: previous) }
            view.owner = owner
            view.session = session
            session.setNativeReadAnchor(view, owner: owner)
        }
    }

    static func dismantleUIView(_ view: Anchor, coordinator: ()) {
        if let owner = view.owner { view.session?.removeNativeReadAnchor(view, owner: owner) }
        view.session = nil
        view.owner = nil
    }
}
