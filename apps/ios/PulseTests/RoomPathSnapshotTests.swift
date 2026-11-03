import XCTest
@testable import Pulse

/// Section 14 items 1+3 - the ID-only back-stack snapshot (encode/decode/
/// owner-scoped restore, corrupted payloads retained) and the user-route
/// navigation yield (a stale route never displaces a newer intent).
final class RoomPathSnapshotTests: XCTestCase {

    private let suiteName = "roompath-tests-\(UUID().uuidString)"
    private var suite: UserDefaults!

    override func setUp() {
        super.setUp()
        suite = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        suite.removePersistentDomain(forSuiteName: suiteName)
        suite = nil
        super.tearDown()
    }

    func testRoundTripPersistsIDsOnly() {
        let entries = [
            RoomPathEntry(roomId: "room-1", jumpMessageId: "msg-9"),
            RoomPathEntry(roomId: "room-2", jumpMessageId: nil),
        ]
        RoomPathSnapshot.write(entries, owner: "owner-a", defaults: suite)
        let loaded = RoomPathSnapshot.read(owner: "owner-a", defaults: suite)
        XCTAssertEqual(loaded, entries)
        // owner-scoped: another owner reads nothing
        XCTAssertEqual(RoomPathSnapshot.read(owner: "owner-b", defaults: suite), [])
    }

    func testARoomlessSnapshotIsEmptyAndAnEmptyWriteClearsTheKey() {
        RoomPathSnapshot.write([], owner: "owner-a", defaults: suite)
        XCTAssertEqual(RoomPathSnapshot.read(owner: "owner-a", defaults: suite), [])
        RoomPathSnapshot.write([RoomPathEntry(roomId: "room-1", jumpMessageId: nil)], owner: "owner-a", defaults: suite)
        RoomPathSnapshot.write([], owner: "owner-a", defaults: suite)
        XCTAssertEqual(RoomPathSnapshot.read(owner: "owner-a", defaults: suite), [])
    }

    func testRestoreDropsForeignAndUnresolvedEntries() {
        let saved = [
            RoomPathEntry(roomId: "room-1", jumpMessageId: nil),
            RoomPathEntry(roomId: "room-gone", jumpMessageId: nil),
        ]
        // a different owner never restores the snapshot
        XCTAssertEqual(RoomPathSnapshot.restore(saved: saved, snapshotOwner: "owner-a", currentOwner: "owner-b", loadedRoomIds: ["room-1"]), [])
        // the owning owner restores only what still resolves (no stale models)
        XCTAssertEqual(
            RoomPathSnapshot.restore(saved: saved, snapshotOwner: "owner-a", currentOwner: "owner-a", loadedRoomIds: ["room-1"]),
            [RoomPathEntry(roomId: "room-1", jumpMessageId: nil)],
        )
    }

    func testACorruptedSnapshotReadsEmptyAndIsRetainedForRecovery() {
        let key = RoomPathSnapshot.key(owner: "owner-a")
        suite.setData(Data("totally not json".utf8), forKey: key)
        XCTAssertEqual(RoomPathSnapshot.read(owner: "owner-a", defaults: suite), [])
        // the corrupt raw value was NOT discarded by the read
        XCTAssertEqual(suite.data(forKey: key), Data("totally not json".utf8))
    }

    func testOwnerKeyIsNamespacedPerOwner() {
        XCTAssertEqual(RoomPathSnapshot.key(owner: "a"), "pulse.roompath.v1.<a>")
        XCTAssertNotEqual(RoomPathSnapshot.key(owner: "a"), RoomPathSnapshot.key(owner: "b"))
    }

    // ── item 3: the user-route yield ────────────────────────────────

    func testAUserRouteWithoutAGenerationDeliversForLegacyCallers() {
        let route = UserRoute(userId: "user-1", name: "Profile")
        XCTAssertNil(route.navGeneration)
        XCTAssertTrue(route.shouldDeliver(currentGeneration: UUID()))
    }

    func testAUserRouteRequestedBeforeANewerIntentYields() {
        let olderGeneration = UUID()
        let route = UserRoute(userId: "user-1", name: "Profile", navGeneration: olderGeneration)
        XCTAssertTrue(route.shouldDeliver(currentGeneration: olderGeneration))
        XCTAssertFalse(route.shouldDeliver(currentGeneration: UUID()), "a newer navigation choice must win")
    }

    func testMemberwiseInitStaysSourceCompatible() {
        // every pre-Section-14 call site constructs with exactly this shape
        let legacy = UserRoute(userId: "user-1", name: "Profile")
        XCTAssertEqual(legacy.userId, "user-1")
        XCTAssertEqual(legacy.name, "Profile")
    }
}
