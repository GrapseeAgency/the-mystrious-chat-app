import XCTest
@testable import Pulse

@MainActor
final class ProfileMutationTests: XCTestCase {
    func testOneEditorMutationOwnsTheWholeTransferAndLateRepliesLoseAuthority() throws {
        var gate = ProfileMutationGate()
        let first = ProfileMutationGate.Owner(userId: "first", session: UUID())
        gate.bind(first)
        let upload = try XCTUnwrap(gate.begin(current: first))
        XCTAssertNil(gate.begin(current: first)) // remove/save cannot race the transfer
        let next = ProfileMutationGate.Owner(userId: "second", session: UUID())
        XCTAssertFalse(gate.accepts(upload, current: next))
        gate.bind(next)
        let save = try XCTUnwrap(gate.begin(current: next))
        gate.finish(upload)
        XCTAssertTrue(gate.accepts(save, current: next))
        gate.invalidate()
        XCTAssertFalse(gate.accepts(save, current: next))
        XCTAssertNil(gate.begin(current: next))
        gate.bind(next)
        let reopened = try XCTUnwrap(gate.begin(current: next))
        XCTAssertFalse(gate.accepts(save, current: next))
        XCTAssertTrue(gate.accepts(reopened, current: next))
    }

    func testSameUserNewSessionCannotAcceptOldPhoto() throws {
        var gate = ProfileMutationGate()
        let original = ProfileMutationGate.Owner(userId: "same", session: UUID())
        let replacement = ProfileMutationGate.Owner(userId: "same", session: UUID())
        gate.bind(original)
        let ticket = try XCTUnwrap(gate.begin(current: original))
        XCTAssertFalse(gate.accepts(ticket, current: replacement))
        XCTAssertFalse(gate.accepts(ticket, current: nil))
        XCTAssertNil(gate.begin(current: replacement))
    }

    func testUploadReceiptNormalizationAndTextBudgetsMatchTheProfileEndpoint() throws {
        XCTAssertEqual(try PulseAvatarImage.managedPath("known-photo.jpg"), "/api/uploads/known-photo.jpg")
        XCTAssertEqual(try PulseAvatarImage.managedPath("/api/uploads/known-photo.jpg"), "/api/uploads/known-photo.jpg")
        for invalid in ["", "https://foreign.test/p.jpg", "/api/uploads/../secret", "known-photo.jpg\n", "tiny"] {
            XCTAssertThrowsError(try PulseAvatarImage.managedPath(invalid))
        }
        XCTAssertEqual(ProfileTextLimit.clip(String(repeating: "🌿", count: 17), maximum: 32), String(repeating: "🌿", count: 16))
        XCTAssertEqual(ProfileTextLimit.clip("a👨‍👩‍👧‍👦z", maximum: 8), "a")
        XCTAssertEqual(ProfileTextLimit.clip("e\u{301}", maximum: 1), "")
        XCTAssertEqual(ProfileTextLimit.clip("kept", maximum: 32), "kept")
    }

    func testProfileExtrasNeverFollowAnotherAccountGatewayOrDefaultsSuite() throws {
        let suite = "ProfileMutationTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let previousGateway = PulseEndpoints.configuredBase
        defer {
            defaults.removePersistentDomain(forName: suite)
            PulseEndpoints.configuredBase = previousGateway
        }
        defaults.set(try JSONEncoder().encode(["about": "unattributed legacy bio"]), forKey: "pulse.viewer.profile")
        PulseEndpoints.configuredBase = "https://one.profile.test"
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        XCTAssertNil(prefs.viewerAbout)
        prefs.setViewerProfile(about: "first bio", statusEmoji: "coffee", statusText: "busy")
        XCTAssertEqual(prefs.viewerAbout, "first bio")
        prefs.setViewer(PulseViewer(id: "other", name: "Other"))
        XCTAssertNil(prefs.viewerAbout)
        prefs.setViewerProfile(about: "other bio", statusEmoji: nil, statusText: nil)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        XCTAssertEqual(prefs.viewerAbout, "first bio")
        PulseEndpoints.configuredBase = "https://two.profile.test"
        XCTAssertNil(prefs.viewerAbout)
        prefs.setViewerProfile(about: "second gateway bio", statusEmoji: nil, statusText: nil)
        PulseEndpoints.configuredBase = "https://one.profile.test"
        XCTAssertEqual(prefs.viewerAbout, "first bio")
        prefs.setViewer(nil)
        XCTAssertNil(prefs.viewerAbout)
        prefs.setViewerProfile(about: "must not persist without owner", statusEmoji: nil, statusText: nil)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        XCTAssertEqual(prefs.viewerAbout, "first bio")
    }
}
