import XCTest
@testable import Pulse

/// Section 13 item 7 (provenance) - the personal screen-security journal
/// stamps WHERE each row's value came from: "intent" for an explicit local
/// choice, "migration-cache" for rows upgraded from the v1 cache (they cannot
/// prove the explicit user intent that produced them), "server" for values
/// adopted from a server snapshot. Old blobs (no origin key) keep decoding
/// and surface honestly as migration-cache provenance.
@MainActor
final class ScreenPrivacyProvenanceTests: XCTestCase {
    private var suiteName: String?

    override func setUp() {
        super.setUp()
        suiteName = "veil-provenance-" + UUID().uuidString
    }
    override func tearDown() {
        if let suiteName { UserDefaults().removePersistentDomain(forName: suiteName) }
        suiteName = nil
        super.tearDown()
    }

    private func freshJournal() -> (journal: PulseScreenPrivacyJournal, defaults: UserDefaults) {
        let defaults = UserDefaults(suiteName: suiteName!)!
        defaults.removePersistentDomain(forName: suiteName!)
        return (PulseScreenPrivacyJournal(defaults: defaults), defaults)
    }

    func testExplicitWritesStampIntentAndAcknowledgementPreservesIt() throws {
        let (journal, _) = freshJournal()
        let owner = "me"
        let ticket = try journal.stage(owner: owner, room: "room", on: true)
        XCTAssertEqual(try journal.entries(owner: owner)["room"]?.origin, "intent",
            "a fresh local choice is an INTENT row")
        try journal.acknowledge(ticket, on: true)
        XCTAssertEqual(try journal.entries(owner: owner)["room"]?.origin, "intent",
            "the confirmation retires the token, the provenance survives")
        // A server snapshot adoption is a different provenance.
        let read = PulseScreenPrivacyRead(owner: owner, room: "room",
            revision: try XCTUnwrap(try journal.entries(owner: owner)["room"]?.revision), activation: UUID())
        try journal.mergeSnapshot(read, on: true)
        XCTAssertEqual(try journal.entries(owner: owner)["room"]?.origin, "server")
    }

    func testV1MigrationStampsMigrationCacheBecauseCacheRowsCarryNoIntentProof() throws {
        let (journal, defaults) = freshJournal()
        let owner = "me"
        // The v1 scoped cache: plain { room: on } booleans, no revision, no
        // pending token and no proof of explicit user intent.
        defaults.set(try JSONEncoder().encode(["room": true]), forKey: "pulse.settings.veil.v1." + owner)
        let migrated = try journal.entries(owner: owner)
        XCTAssertEqual(migrated["room"]?.on, true)
        XCTAssertEqual(migrated["room"]?.origin, "migration-cache",
            "migrated rows can never claim first-hand local intent")
        XCTAssertNil(migrated["room"]?.pending)
        // The migration persisted to the v2 key and the value keeps working.
        XCTAssertNil(try journal.pending(owner: owner, room: "room"),
            "migrated rows carry no pending token")
    }

    func testOldV2BlobWithoutOriginDecodesAndReadsAsMigrationCacheHonesty() throws {
        let (journal, defaults) = freshJournal()
        let owner = "me"
        // Prefs scopes every per-owner surface by the ACCOUNT+GATEWAY owner
        // key, not the raw viewer id - the prefs half of this test seeds the
        // blob under the exact key the owner refresh will read.
        let ownerKey = try XCTUnwrap(PulsePrefs.settingsOwnerKey(for: owner, gateway: PulseEndpoints.gatewayURL))
        // A pre-provenance v2 blob: Entry JSON without the origin key.
        let legacyEntry: [String: Any] = ["on": false, "revision": UUID().uuidString, "pending": UUID().uuidString]
        let legacyBlob = try JSONSerialization.data(withJSONObject: ["room": legacyEntry])
        defaults.set(legacyBlob, forKey: "pulse.settings.veil.v2." + owner)
        let entries = try journal.entries(owner: owner)
        XCTAssertEqual(entries["room"]?.on, false)
        XCTAssertNil(entries["room"]?.origin, "old blobs decode with nil origin (tolerant)")
        // The prefs-level exposure maps unknown provenance honestly.
        defaults.set(legacyBlob, forKey: "pulse.settings.veil.v2." + ownerKey)
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: owner, name: "Me"))
        XCTAssertEqual(prefs.screenPrivacyOrigins["room"], "migration-cache")
        XCTAssertEqual(prefs.screenPrivacy["room"], false)
    }
}
