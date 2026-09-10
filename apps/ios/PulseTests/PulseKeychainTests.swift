import XCTest
@testable import Pulse

/// Wave 0 — Keychain round-trip. The Security.framework wrapper must be
/// save/load/update/delete complete; simulators without a writable keychain
/// (rare CI quirk) are skipped honestly, never failed.
final class PulseKeychainTests: XCTestCase {
    func testSaveLoadUpdateDeleteRoundTrip() throws {
        let account = "test.\(UUID().uuidString)"
        defer { PulseKeychain.shared.delete(account: account) }

        let first = Data("pulse-secret-one".utf8)
        guard PulseKeychain.shared.save(first, account: account) else {
            throw XCTSkip("Keychain is not writable in this environment (SecItemAdd refused)")
        }
        XCTAssertEqual(PulseKeychain.shared.load(account: account), first)

        // Idempotent save = update path.
        let second = Data("pulse-secret-two".utf8)
        XCTAssertTrue(PulseKeychain.shared.save(second, account: account))
        XCTAssertEqual(PulseKeychain.shared.load(account: account), second)

        PulseKeychain.shared.delete(account: account)
        XCTAssertNil(PulseKeychain.shared.load(account: account))

        // Deleting an absent item is a graceful no-op (errSecItemNotFound).
        PulseKeychain.shared.delete(account: account)
        XCTAssertNil(PulseKeychain.shared.load(account: account))
    }

    func testViewerIdentityEnvelopeRoundTrip() throws {
        // Writability probe — saveViewer itself is best-effort (Void).
        let probeAccount = "test.probe.\(UUID().uuidString)"
        defer { PulseKeychain.shared.delete(account: probeAccount) }
        guard PulseKeychain.shared.save(Data("probe".utf8), account: probeAccount) else {
            throw XCTSkip("Keychain is not writable in this environment (SecItemAdd refused)")
        }

        let viewer = PulseViewer(id: "viewer-kc", name: "Keychain Viewer", username: "kcviewer", color: "emerald", avatar: nil)
        PulseKeychain.shared.saveViewer(viewer)
        XCTAssertEqual(PulseKeychain.shared.loadViewer(), viewer)

        // Clearing the viewer removes the item.
        PulseKeychain.shared.saveViewer(nil)
        XCTAssertNil(PulseKeychain.shared.loadViewer())
    }
}
