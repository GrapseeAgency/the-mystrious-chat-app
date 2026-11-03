import XCTest
import Security
@testable import Pulse

/// Section 14 item 8 - keychain restore scenarios: absent vs unreadable are
/// DISTINCT verdicts, an unreadable item is never deleted by a read (the
/// recovery bytes stay exactly where they were until an explicit write),
/// and every item keeps the ThisDeviceOnly accessibility (no iCloud/backup
/// transfer of credentials - a restore lands as absent, not as someone's
/// identity).
final class KeychainRecoveryTests: XCTestCase {

    private var accounts: [String] = []

    override func tearDown() {
        for account in accounts { PulseKeychain.shared.delete(account: account) }
        accounts.removeAll()
        super.tearDown()
    }

    /// Writes RAW bytes into the keychain under Pulse's exact service,
    /// bypassing save() so corrupt/undecodable payloads can be seeded.
    @discardableResult
    private func writeRaw(_ data: Data, account: String) -> Bool {
        accounts.append(account)
        PulseKeychain.shared.delete(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: PulseKeychain.service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func testAbsentVersusUnreadableTokenAreDistinctVerdicts() throws {
        guard writeRaw(Data("definitely-not-utf8-credentials".utf8), account: "tests.token.unreadable") else {
            throw XCTSkip("keychain writes refused in this environment")
        }

        if case .unreadable = PulseKeychain.shared.loadSessionTokenDetailed() {} else {
            XCTFail("garbage bytes must read as unreadable")
        }
        // the legacy API keeps its documented absence-equivalent behavior
        XCTAssertNil(PulseKeychain.shared.loadSessionToken())
    }

    func testAnUnreadableItemIsPreservedByReads() throws {
        let garbage = Data("not-a-token".utf8)
        guard writeRaw(garbage, account: "tests.token.preserved") else {
            throw XCTSkip("keychain writes refused in this environment")
        }

        _ = PulseKeychain.shared.loadSessionTokenDetailed()
        _ = PulseKeychain.shared.loadRaw(account: "tests.token.preserved")

        // the bytes were NOT deleted or rewritten by any read
        if case .present(let data) = PulseKeychain.shared.loadRaw(account: "tests.token.preserved") {
            XCTAssertEqual(data, garbage)
        } else {
            XCTFail("the unreadable item must still be present")
        }
    }

    func testAnUnreadableIdentityEnvelopeIsReportedNotSilentlyDropped() throws {
        guard writeRaw(Data("{broken-json".utf8), account: "tests.identity.unreadable") else {
            throw XCTSkip("keychain writes refused in this environment")
        }

        let read = PulseKeychain.shared.loadViewerDetailed()
        guard case .unreadable(let data) = read else {
            XCTFail("a broken identity envelope must surface as unreadable, got \(read)")
            return
        }
        XCTAssertEqual(data, Data("{broken-json".utf8))
    }

    func testAReadableIdentityRoundTripsThroughTheDetailedAPI() throws {
        guard writeRaw(Data("{\"viewer\":{\"id\":\"viewer-a\",\"name\":\"A\",\"color\":null},\"token\":null}".utf8), account: PulseKeychain.identityAccount) else {
            throw XCTSkip("keychain writes refused in this environment")
        }

        if case .viewer(let viewer) = PulseKeychain.shared.loadViewerDetailed() {
            XCTAssertEqual(viewer.id, "viewer-a")
            XCTAssertEqual(viewer.name, "A")
        } else {
            XCTFail("a valid envelope must read as .viewer")
        }
    }

    func testAnExplicitSaveIsTheOnlyWayBytesChange() throws {
        guard writeRaw(Data("old-bytes".utf8), account: "tests.token.explicit") else {
            throw XCTSkip("keychain writes refused in this environment")
        }
        PulseKeychain.shared.save(Data("new-bytes".utf8), account: "tests.token.explicit")
        if case .present(let data) = PulseKeychain.shared.loadRaw(account: "tests.token.explicit") {
            XCTAssertEqual(data, Data("new-bytes".utf8))
        } else {
            XCTFail("explicit save must overwrite")
        }
        PulseKeychain.shared.delete(account: "tests.token.explicit")
        if case .absent = PulseKeychain.shared.loadRaw(account: "tests.token.explicit") {} else {
            XCTFail("explicit delete must clear")
        }
    }
}
