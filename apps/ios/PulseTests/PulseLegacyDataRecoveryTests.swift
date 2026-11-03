import XCTest
import GRDB
@testable import Pulse

/// Section 14 item 10 - the legacy unowned data gate: pure verdict contract
/// plus real GRDB fixtures (member-proofed adopt, unprovable refuse with
/// retention, garbage refuse, stamp idempotence, outbox never imported).
final class PulseLegacyDataRecoveryTests: XCTestCase {

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

    // ── pure verdict ───────────────────────────────────────────────

    func testPureVerdictUnreadableRefuses() {
        var scan = PulseLegacyDataRecovery.Scan()
        scan.conversations = nil
        if case .refuse = PulseLegacyDataRecovery.verdict(owner: "viewer-a", scan: scan) {} else {
            XCTFail("unreadable must refuse")
        }
    }

    func testPureVerdictEmptyLegacyAdoptsNothingAndRefusesNothing() {
        if case .adopt(let imported) = PulseLegacyDataRecovery.verdict(owner: "viewer-a", scan: .init()) {
            XCTAssertTrue(imported.isEmpty)
        } else {
            XCTFail("empty legacy must adopt")
        }
    }

    func testPureVerdictOldSchemaRowsAreUnprovableAndRefuse() {
        var scan = PulseLegacyDataRecovery.Scan()
        scan.conversations = 3
        scan.messages = 10
        scan.memberProof = false
        if case .refuse(let reason) = PulseLegacyDataRecovery.verdict(owner: "viewer-a", scan: scan) {
            XCTAssertTrue(reason.contains("no provable account ownership"))
        } else {
            XCTFail("unprovable rows must refuse")
        }
    }

    func testPureVerdictForeignConversationOrOrphanAuthorRefuses() {
        var foreign = PulseLegacyDataRecovery.Scan()
        foreign.conversations = 1
        foreign.messages = 1
        foreign.memberProof = true
        foreign.foreignConversation = true
        if case .refuse = PulseLegacyDataRecovery.verdict(owner: "viewer-a", scan: foreign) {} else {
            XCTFail("foreign conversation must refuse")
        }
        var orphan = foreign
        orphan.foreignConversation = false
        orphan.orphanAuthor = true
        if case .refuse = PulseLegacyDataRecovery.verdict(owner: "viewer-a", scan: orphan) {} else {
            XCTFail("orphan author must refuse")
        }
    }

    func testPureVerdictProofedCacheAdoptsButNeverTheOutbox() {
        var scan = PulseLegacyDataRecovery.Scan()
        scan.conversations = 2
        scan.messages = 9
        scan.outbox = 4
        scan.drafts = 1
        scan.memberProof = true
        if case .adopt(let imported) = PulseLegacyDataRecovery.verdict(owner: "viewer-a", scan: scan) {
            XCTAssertEqual(imported["conversation"], 2)
            XCTAssertEqual(imported["message"], 9)
            XCTAssertEqual(imported["draft"], 1)
            XCTAssertNil(imported["outbox"], "legacy queued sends are never imported")
        } else {
            XCTFail("proofed cache must adopt")
        }
    }

    // ── real fixtures ─────────────────────────────────────────────

    /// A v11-era legacy with member-bearing wireJson rows: proofable.
    private func writeMemberProofedLegacy(owner: String, peer: String, at path: String) throws {
        let queue = try DatabaseQueue(path: path)
        try PulseStore.buildMigrator(through: 11).migrate(queue)
        let summaryJSON = """
        {"id":"conv-1","isGroup":true,"name":"Room","members":[
        {"id":"\(owner)","name":"Owner"},{"id":"\(peer)","name":"Peer"}]}
        """
        try queue.write { db in
            try db.execute(
                sql: "INSERT INTO conversation(id, kind, title, unreadCount, isPinned, isMuted, isArchived, wireJson) VALUES('conv-1','GROUP','Room',0,0,0,0,?)",
                arguments: [summaryJSON],
            )
            try db.execute(
                sql: "INSERT INTO message(id, conversationId, authorId, authorName, kind, body, createdAt) VALUES('m-1','conv-1',?,'Owner','TEXT','cached body','2026-01-01T00:00:00Z')",
                arguments: [owner],
            )
            try db.execute(
                sql: "INSERT INTO message(id, conversationId, authorId, authorName, kind, body, createdAt) VALUES('m-2','conv-1',?,'Peer','TEXT','peer body','2026-01-01T00:00:00Z')",
                arguments: [peer],
            )
            try db.execute(
                sql: "INSERT INTO draft(conversationId, text, updatedAt) VALUES('conv-1','unsent draft','2026-01-01T00:00:00Z')",
            )
            try db.execute(
                sql: "INSERT INTO outbox(conversationId, clientId, content, kind, createdAt, attempts) VALUES('conv-1','queue-1','queued body','text','2026-01-01T00:00:00Z',1)",
            )
        }
    }

    func testAMemberProofedLegacyIsAdoptedAndTheOriginalFileIsRetained() throws {
        let legacy = tempPath("legacy-adopt")
        try writeMemberProofedLegacy(owner: "viewer-a", peer: "peer-b", at: legacy)
        let before = try Data(contentsOf: URL(fileURLWithPath: legacy))

        let target = try PulseStore(owner: "viewer-a")
        let verdict = target.legacyRecovery(viewerId: "viewer-a", legacyPath: URL(fileURLWithPath: legacy))

        if case .adopt(let imported) = verdict {
            XCTAssertEqual(imported["conversation"], 1)
            XCTAssertEqual(imported["message"], 2)
            XCTAssertEqual(imported["draft"], 1)
            XCTAssertNil(imported["outbox"], "queued sends are never imported (no sender proof)")
        } else {
            XCTFail("member-proofed legacy must adopt, got \(verdict)")
        }
        let after = try Data(contentsOf: URL(fileURLWithPath: legacy))
        XCTAssertEqual(before, after, "the original legacy file must never be written")
    }

    func testAnOldSchemaLegacyRefusesBecauseOwnershipIsUnprovable() throws {
        let legacy = tempPath("legacy-old")
        let queue = try DatabaseQueue(path: legacy)
        try PulseStore.buildMigrator(through: 3).migrate(queue)
        try queue.write { db in
            try db.execute(
                sql: "INSERT INTO conversation(id, kind, title, unreadCount, isPinned, isMuted, isArchived) VALUES('conv-1','GROUP','Room',0,0,0,0)",
            )
            try db.execute(
                sql: "INSERT INTO message(id, conversationId, authorId, authorName, kind, body, createdAt) VALUES('m-1','conv-1','someone','Someone','TEXT','body','2026-01-01T00:00:00Z')",
            )
        }
        let before = try Data(contentsOf: URL(fileURLWithPath: legacy))

        let target = try PulseStore(owner: "viewer-a")
        let verdict = target.legacyRecovery(viewerId: "viewer-a", legacyPath: URL(fileURLWithPath: legacy))

        if case .refuse(let reason) = verdict {
            XCTAssertTrue(reason.contains("no provable account ownership"))
        } else {
            XCTFail("old-schema rows must refuse, got \(verdict)")
        }
        XCTAssertEqual(before, try Data(contentsOf: URL(fileURLWithPath: legacy)))
    }

    func testAGarbageLegacyFileRefusesWithoutTouchingTheBytes() throws {
        let legacy = tempPath("legacy-garbage")
        let garbage = Data("not a sqlite database".utf8)
        try garbage.write(to: URL(fileURLWithPath: legacy))

        let target = try PulseStore(owner: "viewer-a")
        let verdict = target.legacyRecovery(viewerId: "viewer-a", legacyPath: URL(fileURLWithPath: legacy))

        if case .refuse = verdict {} else {
            XCTFail("garbage must refuse, got \(verdict)")
        }
        XCTAssertEqual(garbage, try Data(contentsOf: URL(fileURLWithPath: legacy)))
    }

    func testTheVerdictStampMakesTheDecisionIdempotent() throws {
        let legacy = tempPath("legacy-stamp")
        try writeMemberProofedLegacy(owner: "viewer-a", peer: "peer-b", at: legacy)

        let target = try PulseStore(owner: "viewer-a")
        _ = target.legacyRecovery(viewerId: "viewer-a", legacyPath: URL(fileURLWithPath: legacy))
        // A second run must return the stamped verdict without re-importing.
        let second = target.legacyRecovery(viewerId: "viewer-a", legacyPath: URL(fileURLWithPath: legacy))
        if case .adopt = second {} else {
            XCTFail("stamped adopt must replay as adopt, got \(second)")
        }

        let reopened = try DatabaseQueue(path: legacy)
        XCTAssertEqual(try reopened.read { db in
            try String.fetchOne(db, sql: "SELECT name FROM sqlite_master WHERE type='table' AND name='conversation'")
        }, "conversation", "the legacy file itself must remain a valid untouched database")
    }
}
