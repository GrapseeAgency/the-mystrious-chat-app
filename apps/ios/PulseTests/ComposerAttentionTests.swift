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

    func testEditMenuBlocksThroughDismissalAndOldCompletionCannotReleaseNewMenu() throws {
        let input = PulseComposerInput(text: .constant("draft"), focused: .constant(false),
            placeholder: "Reply", session: PulseSession())
        let coordinator = input.makeCoordinator()
        let text = PulseComposerTextView(frame: .zero, textContainer: nil)
        let old = Animator(), current = Animator()
        coordinator.textView(text, willPresentEditMenuWith: old)
        let oldTicket = try XCTUnwrap(coordinator.menu.ticket)
        coordinator.textView(text, willDismissEditMenuWith: old)
        XCTAssertEqual(coordinator.menu.ticket, oldTicket, "Dismissal animation still obscures the room")
        coordinator.textView(text, willPresentEditMenuWith: current)
        let currentTicket = try XCTUnwrap(coordinator.menu.ticket)
        old.finish()
        XCTAssertEqual(coordinator.menu.ticket, currentTicket)
        coordinator.textView(text, willDismissEditMenuWith: current)
        current.finish()
        XCTAssertNil(coordinator.menu.ticket)
        coordinator.textView(text, willPresentEditMenuWith: current)
        PulseComposerInput.dismantleUIView(text, coordinator: coordinator)
        XCTAssertNil(coordinator.menu.ticket)
        XCTAssertNil(text.delegate)
    }
}
