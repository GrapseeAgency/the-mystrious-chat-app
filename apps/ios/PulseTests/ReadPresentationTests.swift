import XCTest
import UIKit
@testable import Pulse

final class ReadPresentationTests: XCTestCase {
    @MainActor
    private final class Controller: UIViewController {
        var panel: UIViewController?
        override var presentedViewController: UIViewController? { panel }
    }

    @MainActor
    func testAnchorReplacementAndWindowMovesRetireDwellButStaleCleanupDoesNot() {
        let session = PulseSession(notificationCenter: NotificationCenter())
        let owner = UUID(), inactiveOwner = UUID()
        session.showRoomReadVisit(conversationId: "room", entryID: owner)
        let initial = session.readAttentionRevision
        let inactive = UIView(), first = UIView(), replacement = UIView()
        session.setNativeReadAnchor(inactive, owner: inactiveOwner)
        session.nativeReadAnchorWindowChanged(inactive, owner: inactiveOwner)
        XCTAssertEqual(session.readAttentionRevision, initial)
        session.setNativeReadAnchor(first, owner: owner)
        let attached = session.readAttentionRevision
        XCTAssertGreaterThan(attached, initial)
        session.setNativeReadAnchor(first, owner: owner)
        XCTAssertEqual(session.readAttentionRevision, attached)
        session.setNativeReadAnchor(replacement, owner: owner)
        let replaced = session.readAttentionRevision
        XCTAssertGreaterThan(replaced, attached)
        session.removeNativeReadAnchor(first, owner: owner)
        session.nativeReadAnchorWindowChanged(first, owner: owner)
        XCTAssertEqual(session.readAttentionRevision, replaced)
        session.nativeReadAnchorWindowChanged(replacement, owner: owner)
        XCTAssertGreaterThan(session.readAttentionRevision, replaced)
        session.stop()
    }

    @MainActor
    func testDetachedAnchorDoesNotGrantReadingAuthority() {
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(UIView()))
    }

    @MainActor
    func testUIKitPanelAboveReadingControllerBlocksEvenWithoutSwiftUISheetState() {
        let reader = Controller()
        let anchor = UIView()
        reader.view.addSubview(anchor)
        XCTAssertFalse(PulseReadPresentation.hasPresentationAbove(anchor))
        reader.panel = UIViewController()
        // Do not load the presented panel's view: a pending presentation must
        // already close the gate, not briefly grant reading while it loads.
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
        reader.panel = nil
        XCTAssertFalse(PulseReadPresentation.hasPresentationAbove(anchor))
    }

    @MainActor
    func testPresentationOnContainerAlsoCoversNestedReader() {
        let container = Controller(), reader = Controller()
        container.addChild(reader)
        container.view.addSubview(reader.view)
        reader.didMove(toParent: container)
        let anchor = UIView()
        reader.view.addSubview(anchor)
        XCTAssertFalse(PulseReadPresentation.hasPresentationAbove(anchor))
        container.panel = UIViewController()
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
    }

    @MainActor
    func testThreadIsNotObscuredByTheSheetThatContainsIt() {
        let parent = Controller(), thread = Controller()
        parent.addChild(thread)
        parent.view.addSubview(thread.view)
        thread.didMove(toParent: parent)
        parent.panel = thread
        let primaryAnchor = UIView()
        parent.view.addSubview(primaryAnchor)
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(primaryAnchor))
        let anchor = UIView()
        thread.view.addSubview(anchor)
        XCTAssertFalse(PulseReadPresentation.hasPresentationAbove(anchor))
        thread.panel = UIViewController()
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
        thread.panel = nil
        XCTAssertFalse(PulseReadPresentation.hasPresentationAbove(anchor))
    }
}
