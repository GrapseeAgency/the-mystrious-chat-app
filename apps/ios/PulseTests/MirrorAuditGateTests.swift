import XCTest
@testable import Pulse

/// R105 - the artboard mirror audit gate contract. The gate must default
/// OFF (the native shell ships), round-trip through the exact documented
/// "ui.mirrorAudit" key, and reset cleanly - a fresh domain always reads
/// false, which is also the trapped-user escape guarantee: the preview
/// is an explicit opt-in, never a persisted surprise.
final class MirrorAuditGateTests: XCTestCase {

    /// A real UserDefaults domain per test - never .standard (no shared
    /// state between tests, no pollution of the host's real defaults).
    private func freshDomain() throws -> UserDefaults {
        let name = "mirror-audit-gate-tests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: name) else {
            throw XCTSkip("UserDefaults suite unavailable on this runner")
        }
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        return defaults
    }

    func testDocumentedKeyIsExact() {
        // The R59-documented contract string, pinned so no writer drifts
        // into a silent second spelling (a drifted key would strand the
        // user in the preview - the exit row must flip the SAME flag).
        XCTAssertEqual(PulseMirrorAuditGate.key, "ui.mirrorAudit")
    }

    func testFreshDomainDefaultsOff() throws {
        // A clean install / cleared domain = the native feature shell.
        let defaults = try freshDomain()
        XCTAssertFalse(PulseMirrorAuditGate.read(from: defaults))
        XCTAssertEqual(defaults.bool(forKey: "ui.mirrorAudit"), PulseMirrorAuditGate.defaultValue)
    }

    func testTurnOnWritesAndReadsBackThroughTheDocumentedKey() throws {
        let defaults = try freshDomain()
        PulseMirrorAuditGate.write(true, to: defaults)
        XCTAssertTrue(defaults.bool(forKey: "ui.mirrorAudit"))
        XCTAssertTrue(PulseMirrorAuditGate.read(from: defaults))
    }

    func testExitResetsToOff() throws {
        // The mirror's exit row writes false; the next read must be the
        // native shell again.
        let defaults = try freshDomain()
        PulseMirrorAuditGate.write(true, to: defaults)
        PulseMirrorAuditGate.write(false, to: defaults)
        XCTAssertFalse(PulseMirrorAuditGate.read(from: defaults))
    }
}
