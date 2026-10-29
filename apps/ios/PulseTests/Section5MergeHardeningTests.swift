import XCTest
import GRDB
@testable import Pulse

/// Section-5 merge hardening, parity with Android's Section5MergeHardeningTest:
/// "Old Android expiry loss" (sentinel-aware deadlines on both platforms),
/// "Uncached child-root provenance" (learned bounds survive re-delivery), and
/// "Machine-sender semantics" (machine rows are never the member's outgoing
/// action).
final class Section5MergeHardeningTests: XCTestCase {
    private func message(_ id: String, extra: [String: Any] = [:]) throws -> WireChatMessage {
        var json: [String: Any] = ["id":id,"conversationId":"room","senderId":"peer","kind":"text",
            "content":"secret","createdAt":"2090-01-01T00:00:00Z","anon":false,"viewOnce":false,
            "sender":["id":"peer","name":"Real Name"]]
        json.merge(extra) { _, new in new }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: json))
    }
    private func quote(_ id: String, source: String = "source") throws -> WireChatMessage {
        var result = try message(id, extra:["content":"reply"])
        result.replyTo = WireReplySnippet(id:source,content:"newer server quote",senderName:"Quiet Raven",deleted:false,
                                          quoteVersion:1,expiresAt:nil,viewOnce:false)
        return result
    }

    func testMalformedIncomingDeadlineNeverOverwritesAParseablePrior() throws {
        let prior = try message("m1", extra:["expiresAt":"2090-01-01T00:00:00Z"])
        let malformed = try message("m1", extra:["expiresAt":"not-a-date"])
        XCTAssertEqual(PulseMessageMerge.merge(prior, incoming:malformed).expiresAt, "2090-01-01T00:00:00Z")
        // Parity with Android: a fresh authoritative value heals a poisoned prior.
        let poisoned = try message("m1", extra:["expiresAt":"not-a-date"])
        let fresh = try message("m1", extra:["expiresAt":"2090-06-01T00:00:00Z"])
        XCTAssertEqual(PulseMessageMerge.merge(poisoned, incoming:fresh).expiresAt, "2090-06-01T00:00:00Z")
    }

    func testEnvelopeWithoutCertifiableSnippetDoesNotDowngradeCertifiedBounds() throws {
        let certified = try quote("owner")
        var stripped = try quote("owner")
        stripped.replyTo = nil
        let merged = PulseMessageMerge.merge(certified, incoming: stripped)
        XCTAssertNotNil(merged.replyTo)
        XCTAssertEqual(merged.replyTo?.quoteVersion, 1)
        XCTAssertEqual(merged.replyTo?.content, "newer server quote")
        // An uncertified (v0/legacy) incoming snippet cannot downgrade either.
        var legacy = try quote("owner")
        legacy.replyTo = WireReplySnippet(id:"source",content:"legacy",senderName:"Legacy",deleted:nil)
        let kept = PulseMessageMerge.merge(certified, incoming: legacy)
        XCTAssertEqual(kept.replyTo?.quoteVersion, 1)
        XCTAssertEqual(kept.replyTo?.content, "newer server quote")
    }

    func testLearnedProvenanceSurvivesV1RedeliveryForRootOnlyRevocation() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let path = directory.appendingPathComponent("provenance-redelivery.sqlite").path
        let root = try message("root")
        let child = try message("child", extra:["parentId":"root"])
        var learned = try quote("owner", source:"child")
        learned.replyTo = WireReplySnippet(id:"child",content:"newer server quote",senderName:"Quiet Raven",deleted:false,
            quoteVersion:1,expiresAt:nil,viewOnce:false,
            sourceEditedAt:"2026-10-25T00:00:00Z",sourceRootId:"root")
        // A later envelope re-delivers the same quote WITHOUT the learned
        // v2 provenance (v1 gate pass or legacy relay).
        var legacy = try quote("owner", source:"child")
        legacy.replyTo = WireReplySnippet(id:"child",content:"newer server quote",senderName:"Quiet Raven",deleted:false,
            quoteVersion:1,expiresAt:nil,viewOnce:false)
        let store = try PulseStore(path:path)
        try store.mergeMessages([root,child,learned])
        try store.mergeMessages([legacy])
        let redelivered = try store.message(id:"owner")?.replyTo
        XCTAssertEqual(redelivered?.sourceRootId, "root")
        XCTAssertEqual(redelivered?.sourceEditedAt, "2026-10-25T00:00:00Z")
        // The root-only revocation scan still finds the quote after the child
        // row is evicted and the root is tombstoned.
        try DatabaseQueue(path:path).write { db in
            try db.execute(sql:"DELETE FROM message WHERE id = 'child'")
        }
        try store.mergeMessages([root.deletedCopy()])
        XCTAssertEqual(try store.message(id:"owner")?.replyTo?.deleted, true)
    }

    func testMachineMarkedRowsFollowTheServerFlagNotClientMetadata() throws {
        let automation = try message("m1", extra:["senderId":"me","viaAutomation":true])
        XCTAssertFalse(automation.isOutgoingFor("me"))
        // Pinned contract: legacy client-writable payload flags are display-only
        // ("Unverified integration") and keep the member's own rows outgoing.
        let legacy = try message("m1", extra:["senderId":"me","payload":"{\"bot\":true}"])
        XCTAssertEqual(legacy.integrationLabel, "Unverified integration")
        XCTAssertTrue(legacy.isOutgoingFor("me"))
        // A plain human row still counts as outgoing for its author.
        let human = try message("m1", extra:["senderId":"me"])
        XCTAssertTrue(human.isOutgoingFor("me"))
    }
}
