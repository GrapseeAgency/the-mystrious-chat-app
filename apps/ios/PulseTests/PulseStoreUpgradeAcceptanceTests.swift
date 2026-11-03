import XCTest
import GRDB
@testable import Pulse

/// Section 14 item 7 - real database upgrades: EVERY historical version
/// v1...v15 must migrate cleanly to head through the production init path,
/// the migration list stays contiguous, and a corrupted database file FAILS
/// the open with the bytes retained (no destructive fallback exists; the
/// on-disk chain is additive + tolerant by design, so the honest failure
/// injection is the corrupt-file open path).
final class PulseStoreUpgradeAcceptanceTests: XCTestCase {

    private var tempPaths: [String] = []

    override func tearDown() {
        for path in tempPaths { try? FileManager.default.removeItem(atPath: path) }
        tempPaths.removeAll()
        super.tearDown()
    }

    private func tempPath(_ name: String) -> String {
        let path = NSTemporaryDirectory() + "\(name)-\(UUID().uuidString).sqlite"
        tempPaths.append(path)
        return path
    }

    func testMigrationListStaysContiguousV1ThroughV16() {
        XCTAssertEqual(PulseStore.migrations.map(\.name), (1...16).map { "v\($0)" })
    }

    func testEveryHistoricalVersionUpgradesToHeadThroughTheProductionInit() throws {
        for version in 1...15 {
            let path = tempPath("history")
            // Seed a database AT the historical version...
            let seeded = try DatabaseQueue(path: path)
            try PulseStore.buildMigrator(through: version).migrate(seeded)
            // ...then upgrade it through the exact production open path.
            let store = try PulseStore(path: path)
            _ = store

            let reopened = try DatabaseQueue(path: path)
            XCTAssertEqual(try PulseStore.buildMigrator().currentVersion(reopened), "v16",
                "historical v\(version) database did not reach head")
            // the head schema is fully usable (spot check core tables)
            _ = try reopened.read { db in
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM conversation") ?? 0
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM message") ?? 0
            }
        }
    }

    func testACorruptedDatabaseFileFailsTheOpenAndBytesAreRetained() throws {
        let path = tempPath("corrupt")
        let garbage = Data((0..<4096).map { _ in UInt8.random(in: 0...255) })
        try garbage.write(to: URL(fileURLWithPath: path))

        XCTAssertThrowsError(try PulseStore(path: path))

        let after = try Data(contentsOf: URL(fileURLWithPath: path))
        XCTAssertEqual(after, garbage, "a failed open must not rewrite the corrupt file")
    }
}
