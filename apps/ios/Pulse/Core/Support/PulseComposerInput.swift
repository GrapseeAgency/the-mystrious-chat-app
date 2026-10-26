import SwiftUI
import UIKit

/// The multiline native composer, with public UITextView edit-menu lifecycle
/// callbacks. SwiftUI's TextField does not expose these callbacks; scene/focus
/// changes alone cannot detect a floating Copy/Paste menu.
struct PulseComposerInput: UIViewRepresentable {
    @Binding var text: String
    @Binding var focused: Bool
    let placeholder: String
    let session: PulseSession
    var textColor: UIColor = .label
    var placeholderColor: UIColor = .placeholderText
    var font: UIFont = .preferredFont(forTextStyle: .body)
    @Environment(\.isEnabled) private var enabled

    init(text: Binding<String>, focused: Binding<Bool>, placeholder: String, session: PulseSession,
         textColor: UIColor = .label, placeholderColor: UIColor = .placeholderText,
         font: UIFont = .preferredFont(forTextStyle: .body)) {
        self._text = text; self._focused = focused
        self.placeholder = placeholder; self.session = session
        self.textColor = textColor; self.placeholderColor = placeholderColor; self.font = font
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PulseComposerTextView {
        let view = PulseComposerTextView(frame: .zero, textContainer: nil)
        view.delegate = context.coordinator
        view.backgroundColor = .clear
        view.tintColor = UIColor(PulseTheme.emberGlowTop)
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.isScrollEnabled = false
        view.adjustsFontForContentSizeCategory = true
        view.autocapitalizationType = .sentences
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }

    func updateUIView(_ view: PulseComposerTextView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        coordinator.focusRevision &+= 1
        coordinator.updatingView = true
        defer { coordinator.updatingView = false }
        if view.text != text { view.text = text }
        if view.font != font { view.font = font }
        if view.textColor != textColor { view.textColor = textColor }
        view.placeholder.text = placeholder
        view.placeholder.font = font
        view.placeholder.textColor = placeholderColor
        view.placeholder.isHidden = !text.isEmpty
        view.accessibilityLabel = placeholder
        view.isEditable = enabled
        view.isSelectable = enabled
        view.desiredFocus = focused && enabled
        if view.desiredFocus && view.window != nil && !view.isFirstResponder { view.becomeFirstResponder() }
        if !view.desiredFocus && view.isFirstResponder { view.resignFirstResponder() }
        if !enabled && focused { coordinator.updateFocus(false) }
        view.setNeedsLayout()
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PulseComposerTextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite, width > 0 else { return nil }
        let full = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let line = (uiView.font ?? font).lineHeight
        let height = Self.height(content: full.height, line: line)
        uiView.isScrollEnabled = full.height > height + 1
        return CGSize(width: width, height: height)
    }

    static func height(content: CGFloat, line: CGFloat) -> CGFloat { min(max(line, content), line * 5) }

    static func dismantleUIView(_ view: PulseComposerTextView, coordinator: Coordinator) {
        coordinator.finishMenu(coordinator.menu.ticket)
        coordinator.finishWritingTools()
        view.delegate = nil
        view.resignFirstResponder()
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: PulseComposerInput
        var updatingView = false
        var focusRevision: UInt64 = 0
        private(set) var menu = PulseMenuVisibility()
        private let menuID = UUID()
        private weak var menuSession: PulseSession?
        private let writingToolsID = UUID()
        private weak var writingToolsSession: PulseSession?
        private(set) var writingToolsActive = false
        init(_ parent: PulseComposerInput) { self.parent = parent }

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text
            (textView as? PulseComposerTextView)?.placeholder.isHidden = !textView.text.isEmpty
            textView.invalidateIntrinsicContentSize()
        }
        func textViewDidBeginEditing(_ textView: UITextView) { updateFocus(true) }
        func textViewDidEndEditing(_ textView: UITextView) { updateFocus(false) }
        func updateFocus(_ focused: Bool) {
            guard parent.focused != focused else { return }
            if updatingView {
                let binding = parent.$focused, revision = focusRevision
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.focusRevision == revision else { return }
                    binding.wrappedValue = focused
                }
            } else { parent.focused = focused }
        }

        func textView(_ textView: UITextView, willPresentEditMenuWithAnimator animator: UIEditMenuInteractionAnimating) {
            if menuSession !== parent.session {
                menuSession?.setTransientReadObscured(menuID, false)
                menuSession = parent.session
            }
            menu.begin()
            menuSession?.setTransientReadObscured(menuID, true)
        }
        func textView(_ textView: UITextView, willDismissEditMenuWithAnimator animator: UIEditMenuInteractionAnimating) {
            let ticket = menu.ticket
            animator.addCompletion { [weak self] in self?.finishMenu(ticket) }
        }
        @available(iOS 18.0, *)
        func textViewWritingToolsWillBegin(_ textView: UITextView) {
            if writingToolsSession !== parent.session {
                writingToolsSession?.setTransientReadObscured(writingToolsID, false)
                writingToolsSession = parent.session
            }
            writingToolsActive = true
            writingToolsSession?.setTransientReadObscured(writingToolsID, true)
        }
        @available(iOS 18.0, *)
        func textViewWritingToolsDidEnd(_ textView: UITextView) {
            if parent.text != textView.text { textViewDidChange(textView) }
            finishWritingTools()
        }
        func finishWritingTools() {
            guard writingToolsActive else { return }
            writingToolsActive = false
            writingToolsSession?.setTransientReadObscured(writingToolsID, false)
        }

        func finishMenu(_ ticket: UUID?) {
            guard menu.end(ticket) else { return }
            menuSession?.setTransientReadObscured(menuID, false)
        }
    }
}

final class PulseComposerTextView: UITextView {
    let placeholder = UILabel()
    var desiredFocus = false

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        placeholder.isUserInteractionEnabled = false
        placeholder.isAccessibilityElement = false
        addSubview(placeholder)
    }
    required init?(coder: NSCoder) { fatalError("Use init(frame:textContainer:)") }
    override func layoutSubviews() {
        super.layoutSubviews()
        placeholder.frame = CGRect(x: 0, y: 0, width: bounds.width, height: (font ?? .preferredFont(forTextStyle: .body)).lineHeight)
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil && desiredFocus && !isFirstResponder { becomeFirstResponder() }
    }
    // Keep the plain-text behavior of the original TextField. Rich clipboard
    // attributes must not replace the autumn composer font/colors.
    override func paste(_ sender: Any?) {
        if let text = UIPasteboard.general.string ?? UIPasteboard.general.url?.absoluteString { insertText(text) }
    }
}
