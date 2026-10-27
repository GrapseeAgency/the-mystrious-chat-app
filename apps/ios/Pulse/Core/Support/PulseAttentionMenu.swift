import SwiftUI
import UIKit

/// Keep the native menu and its dismissal animation, while exposing the
/// presentation lifetime SwiftUI.Menu does not publish to a reading surface.
struct PulseAttentionMenu: UIViewRepresentable {
    let symbol: String
    let enabled: Bool
    let makeMenu: () -> UIMenu
    let visibilityChanged: (Bool) -> Void

    func makeUIView(context: Context) -> PulseAttentionMenuButton {
        let button = PulseAttentionMenuButton(frame: .zero)
        button.showsMenuAsPrimaryAction = true
        button.tintColor = .white
        button.backgroundColor = UIColor.white.withAlphaComponent(0.08)
        button.layer.cornerRadius = 24
        button.layer.borderWidth = 1
        button.layer.borderColor = UIColor.white.withAlphaComponent(0.10).cgColor
        button.accessibilityLabel = "Attach"
        return button
    }

    func updateUIView(_ button: PulseAttentionMenuButton, context: Context) {
        button.visibilityChanged = visibilityChanged
        button.setImage(UIImage(systemName: symbol,
            withConfiguration: UIImage.SymbolConfiguration(pointSize: 22, weight: .medium)), for: .normal)
        button.menu = makeMenu()
        button.isEnabled = enabled
        if !enabled { button.contextMenuInteraction?.dismissMenu() }
    }

    static func dismantleUIView(_ button: PulseAttentionMenuButton, coordinator: ()) {
        button.contextMenuInteraction?.dismissMenu()
        button.endVisibility()
    }
}

final class PulseAttentionMenuButton: UIButton {
    var visibilityChanged: ((Bool) -> Void)?
    private var visibility = PulseMenuVisibility()

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
        willDisplayMenuFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
        visibility.begin()
        visibilityChanged?(true)
    }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
        willEndFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
        let ticket = visibility.ticket
        let finish: () -> Void = { [weak self] in
            guard let self, self.visibility.end(ticket) else { return }
            self.visibilityChanged?(false)
        }
        if let animator { animator.addCompletion(finish) } else { finish() }
    }

    func endVisibility() {
        if visibility.end(visibility.ticket) { visibilityChanged?(false) }
    }
}

struct PulseMenuVisibility {
    private(set) var ticket: UUID?
    mutating func begin() { ticket = UUID() }
    mutating func end(_ ended: UUID?) -> Bool {
        guard let ended, ticket == ended else { return false }
        ticket = nil
        return true
    }
}
