import XCTest
import SwiftUI
import UIKit
@testable import Pulse

@MainActor
final class ComposerAttentionTests: XCTestCase {
    private final class Animator: NSObject, UIEditMenuInteractionAnimating {
        var completions: [() -> Void] = []
        func addAnimations(_ animations: @escaping () -> Void) { animations() }
        func addCompletion(_ completion: @escaping () -> Void) { completions.append(completion) }
        func finish() { let pending = completions; completions = []; pending.forEach { $0() } }
    }

    func testEditingKeepsTheDraftBindingAndFiveLineSizing() {
        var draft = "previous", focus = false
        let input = PulseComposerInput(text: Binding(get: { draft }, set: { draft = $0 }),
            focused: Binding(get: { focus }, set: { focus = $0 }), placeholder: "Type here", session: PulseSession())
        let coordinator = input.makeCoordinator()
        let text = PulseComposerTextView(frame: .zero, textContainer: nil)
        text.text = "First line\nবাংলা reply"
        coordinator.textViewDidChange(text)
        XCTAssertEqual(draft, text.text)
        XCTAssertTrue(text.placeholder.isHidden)
        coordinator.textViewDidBeginEditing(text)
        XCTAssertTrue(focus)
        coordinator.textViewDidEndEditing(text)
        XCTAssertFalse(focus)
        text.text = ""
        coordinator.textViewDidChange(text)
        XCTAssertEqual(draft, "")
        XCTAssertFalse(text.placeholder.isHidden)
        XCTAssertEqual(PulseComposerInput.height(content: 0, line: 20), 20)
        XCTAssertEqual(PulseComposerInput.height(content: 60, line: 20), 60)
        XCTAssertEqual(PulseComposerInput.height(content: 300, line: 20), 100)
    }

    func testHostedComposerKeepsAutumnColorAndScrollsAfterFiveLines() throws {
        let content = (1...12).map { "Line \($0)" }.joined(separator: "\n")
        let input = PulseComposerInput(text: .constant(content), focused: .constant(false),
            placeholder: "Type here", session: PulseSession(), textColor: .white)
        let host = UIHostingController(rootView: input.frame(width: 240).fixedSize(horizontal: false, vertical: true))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true }
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        func findInput(_ view: UIView) -> PulseComposerTextView? {
            if let input = view as? PulseComposerTextView { return input }
            return view.subviews.lazy.compactMap { findInput($0) }.first
        }
        let view = try XCTUnwrap(findInput(host.view))
        let line = try XCTUnwrap(view.font).lineHeight
        XCTAssertEqual(view.text, content)
        XCTAssertEqual(view.textColor, UIColor.white)
        XCTAssertGreaterThan(view.bounds.width, 200)
        XCTAssertGreaterThan(view.bounds.height, line)
        XCTAssertLessThanOrEqual(view.bounds.height, ceil(line * 5) + 1)
        XCTAssertTrue(view.isScrollEnabled)
    }

    func testEditMenuBlocksThroughDismissalAndOldCompletionCannotReleaseNewMenu() throws {
        let input = PulseComposerInput(text: .constant("draft"), focused: .constant(false),
            placeholder: "Reply", session: PulseSession())
        let coordinator = input.makeCoordinator()
        let text = PulseComposerTextView(frame: .zero, textContainer: nil)
        let old = Animator(), current = Animator()
        // Dispatch through the UIKit protocol, not our concrete helper: an
        // accidentally misspelled optional delegate method must fail this test.
        let delegate: UITextViewDelegate = coordinator
        delegate.textView?(text, willPresentEditMenuWithAnimator: old)
        let oldTicket = try XCTUnwrap(coordinator.menu.ticket)
        delegate.textView?(text, willDismissEditMenuWithAnimator: old)
        XCTAssertEqual(coordinator.menu.ticket, oldTicket, "Dismissal animation still obscures the room")
        delegate.textView?(text, willPresentEditMenuWithAnimator: current)
        let currentTicket = try XCTUnwrap(coordinator.menu.ticket)
        old.finish()
        XCTAssertEqual(coordinator.menu.ticket, currentTicket)
        delegate.textView?(text, willDismissEditMenuWithAnimator: current)
        current.finish()
        XCTAssertNil(coordinator.menu.ticket)
        delegate.textView?(text, willPresentEditMenuWithAnimator: current)
        PulseComposerInput.dismantleUIView(text, coordinator: coordinator)
        XCTAssertNil(coordinator.menu.ticket)
        XCTAssertNil(text.delegate)
    }
}
