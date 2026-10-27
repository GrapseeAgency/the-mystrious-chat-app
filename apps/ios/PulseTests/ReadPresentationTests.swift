import XCTest
import UIKit
import SwiftUI
@testable import Pulse

final class ReadPresentationTests: XCTestCase {
    @MainActor
    private final class Controller: UIViewController {
        var panel: UIViewController?
        var entering = false, leaving = false, attaching = false, detaching = false
        override var presentedViewController: UIViewController? { panel }
        override var isBeingPresented: Bool { entering }
        override var isBeingDismissed: Bool { leaving }
        override var isMovingToParent: Bool { attaching }
        override var isMovingFromParent: Bool { detaching }
    }

    @MainActor
    private final class AppearingHost<Content: View>: UIHostingController<Content> {
        private(set) var hasAppeared = false
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            hasAppeared = true
        }
    }

    @MainActor
    private func awaitPresentation(_ description: String, timeout: TimeInterval,
                                   file: StaticString = #filePath, line: UInt = #line,
                                   diagnostics: () -> String = { "" }, ready: () -> Bool) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        while !ready(), ProcessInfo.processInfo.systemUptime < deadline {
            // Let UIKit advance its real hierarchy/transition state. A hosting
            // controller callback is not the condition under test, and can be
            // omitted for a secondary hosting window even after attachment.
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(ready(), description + "; " + diagnostics(), file: file, line: line)
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
        let primary = AppearingHost(rootView: Text("Primary reader")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PulseReadPresentationAnchor(session: session, owner: visit)))
        window.rootViewController = primary
        window.makeKeyAndVisible()
        defer { window.isHidden = true; previousKey?.makeKey(); session.stop() }
        window.layoutIfNeeded()
        primary.view.layoutIfNeeded()
        // Attachment/layout can complete synchronously before the root's first
        // appearance. Do not present a sheet from a still-appearing test root.
        // Poll the actual lifecycle state on the main actor; no synthetic
        // appearance calls, longer timeout, or weakened reading assertion.
        try await awaitPresentation("Primary SwiftUI reader appeared in its active window", timeout: 5, diagnostics: { "appeared=\(primary.hasAppeared); " + session.nativeReadPresentationDiagnostics() }) {
            primary.hasAppeared && primary.viewIfLoaded?.window === window && primary.transitionCoordinator == nil &&
                !session.nativePresentationBlockedNow
        }
        XCTAssertFalse(session.nativePresentationBlockedNow, "The real SwiftUI background must resolve to its reading controller")

        let owner = try XCTUnwrap(session.beginReadSurface(conversationId: "room", rootId: "root"))
        let thread = UIHostingController(rootView: Text("Thread reader")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PulseReadPresentationAnchor(session: session, owner: owner.id)))
        thread.modalPresentationStyle = .pageSheet
        let threadDiagnostics = {
            "expectedThread=\(ObjectIdentifier(thread)); expectedPrimary=\(ObjectIdentifier(primary)); expectedOwner=\(owner.id); " + session.nativeReadPresentationDiagnostics()
        }
        // Exercise the animated sheet lifecycle used by the SwiftUI surface.
        // The secondary-window, non-animated hosting fixture was observed with
        // presenting=true and a zero-duration coordinator still at progress 0.
        // That is correctly blocked, not permission to bypass the runtime gate.
        primary.present(thread, animated: true)
        XCTAssertTrue(session.nativePresentationBlockedNow, "Presentation must interrupt reading")
        try await awaitPresentation("Thread sheet attached, settled, and owns its real reading anchor", timeout: 5, diagnostics: threadDiagnostics) {
            primary.presentedViewController === thread && thread.viewIfLoaded?.window === window &&
                !thread.isBeingPresented && thread.transitionCoordinator == nil &&
                primary.transitionCoordinator == nil && !session.nativePresentationBlockedNow
        }
        XCTAssertTrue(primary.presentedViewController === thread)
        XCTAssertTrue(thread.view.window === window)
        thread.view.layoutIfNeeded()
        XCTAssertFalse(session.nativePresentationBlockedNow, "The active thread must not be blocked by its own sheet; " + threadDiagnostics())

        let revision = session.readAttentionRevision
        center.post(name: UIScene.willDeactivateNotification, object: scene)
        XCTAssertTrue(session.nativePresentationBlockedNow)
        center.post(name: UIScene.didActivateNotification, object: scene)
        XCTAssertFalse(session.nativePresentationBlockedNow, threadDiagnostics())
        XCTAssertGreaterThan(session.readAttentionRevision, revision)

        let panel = UIViewController()
        thread.present(panel, animated: false)
        try await awaitPresentation("Cover panel attached above the thread", timeout: 2) {
            thread.presentedViewController === panel && panel.viewIfLoaded?.window === window &&
                !panel.isBeingPresented && panel.transitionCoordinator == nil
        }
        XCTAssertTrue(session.nativePresentationBlockedNow)
        await session.markReadIfVisible(conversationId: "room") // samples the real pre-qualification guard; this fixture is not authenticated
        let coveredRevision = session.readAttentionRevision
        panel.dismiss(animated: false)
        try await awaitPresentation("Cover panel detached and thread transition settled", timeout: 2) {
            thread.presentedViewController == nil && panel.viewIfLoaded?.window == nil &&
                thread.transitionCoordinator == nil && !thread.isBeingDismissed
        }
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
    func testLifecycleTransitionsBlockEvenWithoutCoordinatorOrCoverPanel() {
        let container = Controller(), reader = Controller()
        container.addChild(reader)
        container.view.addSubview(reader.view)
        reader.didMove(toParent: container)
        let anchor = UIView()
        reader.view.addSubview(anchor)
        XCTAssertNil(reader.transitionCoordinator)
        XCTAssertNil(container.transitionCoordinator)
        XCTAssertFalse(PulseReadPresentation.hasPresentationAbove(anchor))
        reader.entering = true
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
        reader.entering = false
        reader.leaving = true
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
        reader.leaving = false
        container.attaching = true
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
        container.attaching = false
        container.detaching = true
        XCTAssertTrue(PulseReadPresentation.hasPresentationAbove(anchor))
        container.detaching = false
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
