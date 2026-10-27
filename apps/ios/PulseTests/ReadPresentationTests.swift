import XCTest
import UIKit
import SwiftUI
@testable import Pulse

final class ReadPresentationTests: XCTestCase {
    @MainActor
    private final class Controller: UIViewController {
        var panel: UIViewController?
        override var presentedViewController: UIViewController? { panel }
    }

    @MainActor
    private final class AppearingHost<Content: View>: UIHostingController<Content> {
        var didAppear: (() -> Void)?
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            let completion = didAppear
            didAppear = nil
            completion?()
        }
    }

    @MainActor
    func testHostedSwiftUIRoomAndThreadHaveIndependentUnobscuredAnchors() async throws {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) else {
            throw XCTSkip("Hosted reading-surface coverage requires an active UIKit scene")
        }
        let previousKey = scene.windows.first(where: { $0.isKeyWindow })
        let center = NotificationCenter(), session = PulseSession(notificationCenter: center)
        let visit = UUID()
        session.showRoomReadVisit(conversationId: "room", entryID: visit)
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        let primaryReady = expectation(description: "primary SwiftUI content appeared")
        let primary = AppearingHost(rootView: Text("Primary reader")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PulseReadPresentationAnchor(session: session, owner: visit)))
        primary.didAppear = { primaryReady.fulfill() }
        window.rootViewController = primary
        window.makeKeyAndVisible()
        defer { window.isHidden = true; previousKey?.makeKey(); session.stop() }
        window.layoutIfNeeded()
        primary.view.layoutIfNeeded()
        await fulfillment(of: [primaryReady], timeout: 5)
        XCTAssertFalse(session.nativePresentationBlockedNow, "The real SwiftUI background must resolve to its reading controller")

        let owner = try XCTUnwrap(session.beginReadSurface(conversationId: "room", rootId: "root"))
        let threadReady = expectation(description: "thread presentation completed")
        let thread = AppearingHost(rootView: Text("Thread reader")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PulseReadPresentationAnchor(session: session, owner: owner.id)))
        thread.modalPresentationStyle = .pageSheet
        // A secondary hosting window can attach the sheet without delivering a
        // second hosting-controller appearance callback. Synchronize on UIKit's
        // presentation completion, then assert the real window and anchor below.
        primary.present(thread, animated: false) { threadReady.fulfill() }
        await fulfillment(of: [threadReady], timeout: 5)
        XCTAssertTrue(primary.presentedViewController === thread)
        XCTAssertTrue(thread.view.window === window)
        thread.view.layoutIfNeeded()
        XCTAssertFalse(session.nativePresentationBlockedNow, "The active thread must not be blocked by its own sheet")

        let revision = session.readAttentionRevision
        center.post(name: UIScene.willDeactivateNotification, object: scene)
        XCTAssertTrue(session.nativePresentationBlockedNow)
        center.post(name: UIScene.didActivateNotification, object: scene)
        XCTAssertFalse(session.nativePresentationBlockedNow)
        XCTAssertGreaterThan(session.readAttentionRevision, revision)

        let panel = UIViewController()
        let covered = expectation(description: "UIKit panel presented")
        thread.present(panel, animated: false) { covered.fulfill() }
        await fulfillment(of: [covered], timeout: 2)
        XCTAssertTrue(session.nativePresentationBlockedNow)
        await session.markReadIfVisible(conversationId: "room") // samples the real pre-qualification guard; this fixture is not authenticated
        let coveredRevision = session.readAttentionRevision
        let closed = expectation(description: "UIKit panel dismissed")
        panel.dismiss(animated: false) { closed.fulfill() }
        await fulfillment(of: [closed], timeout: 2)
        session.refreshNativeReadPresentation()
        XCTAssertFalse(session.nativePresentationBlockedNow)
        XCTAssertGreaterThan(session.readAttentionRevision, coveredRevision)
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
