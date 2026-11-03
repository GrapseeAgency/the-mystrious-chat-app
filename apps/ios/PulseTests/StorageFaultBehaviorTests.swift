import XCTest
@testable import Pulse

/// Section 14 item 9 - storage fault behavior on the durable journals:
/// corrupted raw payloads read as empty BUT ARE RETAINED (a read never
/// deletes; recovery stays possible), the write-fault flags are observable,
/// and an ack is the only row-removal authority.
@MainActor
final class StorageFaultBehaviorTests: XCTestCase {

    private var suite: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "storagefault-tests-\(UUID().uuidString)"
        suite = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        suite.removePersistentDomain(forName: suiteName)
        suite = nil
        super.tearDown()
    }

    func testACorruptSection8JournalReadsEmptyAndIsNeverDeletedByTheRead() {
        let journal = PulseSection8Journal(defaults: suite)
        let key = "pulse.section8.pending.v1.owner-a"
        suite.set(Data("{not json".utf8), forKey: key)

        XCTAssertEqual(journal.pending(owner: "owner-a").map(\.clientId), [])

        // the raw corrupt value is STILL THERE - not traded away by any read
        XCTAssertEqual(suite.data(forKey: key), Data("{not json".utf8))
    }

    func testSection8JournalWriteRetainsEveryPreviousRowByContract() {
        let journal = PulseSection8Journal(defaults: suite)
        let first = journal.enqueue(owner: "owner-a", kind: "block", targetId: "user-b")
        let second = journal.enqueue(owner: "owner-a", kind: "report", targetId: "user-c")

        XCTAssertEqual(journal.pending(owner: "owner-a").map(\.clientId), [first.clientId, second.clientId])
        XCTAssertFalse(journal.lastWriteFailed)

        // a confirmed receipt removes EXACTLY its own row - the ack is the
        // only removal authority, so a crash between send and ack replays
        // once with the same identity.
        journal.confirm(owner: "owner-a", clientId: first.clientId)
        XCTAssertEqual(journal.pending(owner: "owner-a").map(\.clientId), [second.clientId])
    }

    func testSection8OwnerScopingKeepsAccountsIsolated() {
        let journal = PulseSection8Journal(defaults: suite)
        let a = journal.enqueue(owner: "owner-a", kind: "block", targetId: "user-b")
        let b = journal.enqueue(owner: "owner-b", kind: "block", targetId: "user-c")

        XCTAssertEqual(journal.pending(owner: "owner-a").map(\.clientId), [a.clientId])
        XCTAssertEqual(journal.pending(owner: "owner-b").map(\.clientId), [b.clientId])
        journal.confirm(owner: "owner-a", clientId: a.clientId)
        XCTAssertEqual(journal.pending(owner: "owner-b").count, 1, "a foreign owner's ack cannot remove rows")
    }

    func testACorruptSection12JournalReadsEmptyAndIsRetained() {
        let journal = PulseSection12Journal(defaults: suite)
        let key = "section12-intent:v1|owner-a"
        suite.set(Data("garbage".utf8), forKey: key)

        XCTAssertEqual(journal.pending(owner: "owner-a").map(\.clientId), [])
        XCTAssertEqual(suite.data(forKey: key), Data("garbage".utf8))
    }

    func testSection12JournalFaultFlagStartsCleanAndAcksRetainOthers() {
        let journal = PulseSection12Journal(defaults: suite)
        XCTAssertFalse(journal.lastWriteFailed)
        let first = journal.enqueue(owner: "owner-a", kind: "automation-create", targetId: "room")
        let second = journal.enqueue(owner: "owner-a", kind: "webhook-create", targetId: "room")
        journal.confirm(owner: "owner-a", clientId: first.clientId)
        XCTAssertEqual(journal.pending(owner: "owner-a").map(\.clientId), [second.clientId])
    }

    func testACorruptProfileDraftReadsEmptyAndIsRetained() {
        let journal = PulseProfileDraftJournal(defaults: suite)
        let key = "pulse.profile.draft.v1.owner-a"
        suite.set(Data("garbage".utf8), forKey: key)

        // unreadable draft reads as empty (nothing staged) and the raw value
        // is retained for recovery
        let draft = journal.draft(owner: "owner-a")
        XCTAssertNil(draft.name)
        XCTAssertNil(draft.about)
        XCTAssertNil(draft.username)
        XCTAssertNil(draft.photo)
        XCTAssertEqual(suite.data(forKey: key), Data("garbage".utf8))
    }
}
