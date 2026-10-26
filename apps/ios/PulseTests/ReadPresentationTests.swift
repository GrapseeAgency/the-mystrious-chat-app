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
