import XCTest
@testable import Pulse

/// Section 12 journal acceptance (iOS data layer): the durable integration
/// intent journal persists BEFORE the wire, a lost ACK keeps the row pending
/// with the SAME clientId on the retry body, only a confirmed receipt removes
/// the row, and everything survives a process restart (a brand-new journal
/// over the same UserDefaults suite sees the pending intents).
@MainActor
final class PulseSection12JournalTests: XCTestCase {
    override func setUp() {
        super.setUp()
        suiteName = "section12-journal-" + UUID().uuidString
    }

    override func tearDown() {
        if let suiteName { UserDefaults().removePersistentDomain(forName: suiteName) }
        suiteName = nil
        super.tearDown()
    }

    private var suiteName: String?

    /// A fresh journal on an isolated suite (the Section 8 pattern: create the
    /// suite, clear it, hand it to the journal).
    private func freshJournal() -> (journal: PulseSection12Journal, defaults: UserDefaults) {
        let defaults = UserDefaults(suiteName: suiteName ?? "section12-journal-fallback")!
        defaults.removePersistentDomain(forName: suiteName ?? "section12-journal-fallback")
        return (PulseSection12Journal(defaults: defaults), defaults)
    }

    // Section 12 item 1 - enqueue → lost ACK → pending() → the SAME clientId
    // on the retry body → confirm → pending() empty.

    func testEnqueueLostACKKeepsPendingAndRetryBodyCarriesTheSameClientId() {
        let (journal, _) = freshJournal()
        let owner = "me"
        let intent = journal.enqueue(owner: owner, kind: "automation-create", targetId: "room",
            payload: ["trigger": "pricing", "reply": "Here you go"])
        // Lost ACK: the wire never confirmed - the intent stays pending.
        let pending = journal.pending(owner: owner)
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending.first?.kind, "automation-create")
        XCTAssertEqual(pending.first?.targetId, "room")
        XCTAssertEqual(pending.first?.clientId.count, 32, "the replay identity is 128 bits of hex")
        // The retry body reconstructs the SAME instructions + identity.
        let body = journal.automationCreateBody(pending[0], userId: owner)
        XCTAssertEqual(body["clientId"] as? String, intent.clientId)
        XCTAssertEqual(body["trigger"] as? String, "pricing")
        XCTAssertEqual(body["reply"] as? String, "Here you go")
        XCTAssertEqual(body["conversationId"] as? String, "room")
        // Confirmed receipts are the ONLY way a row leaves the journal.
        journal.confirm(owner: owner, clientId: intent.clientId)
        XCTAssertTrue(journal.pending(owner: owner).isEmpty)
    }

    // The journal survives a restart: a NEW journal over the same defaults
    // suite replays every kind with its own body builder.

    func testJournalSurvivesRestartAndEveryKindReplaysItsOwnBody() {
        let (journal, defaults) = freshJournal()
        let owner = "s12-owner"
        let create = journal.enqueue(owner: owner, kind: "automation-create", targetId: "room",
            payload: ["trigger": "pricing", "reply": "ok"])
        let removal = journal.enqueue(owner: owner, kind: "automation-delete", targetId: "rule-1")
        let hookCreate = journal.enqueue(owner: owner, kind: "webhook-create", targetId: "room",
            payload: ["name": "Deploys"])
        let hookDelete = journal.enqueue(owner: owner, kind: "webhook-delete", targetId: "token-1")
        let hookRotate = journal.enqueue(owner: owner, kind: "webhook-rotate", targetId: "token-1")
        XCTAssertEqual(journal.pending(owner: owner).count, 5)

        // "Restart": a brand-new journal over the same suite sees the rows.
        let revived = PulseSection12Journal(defaults: defaults)
        let pending = revived.pending(owner: owner)
        XCTAssertEqual(pending.map(\.kind),
            ["automation-create", "automation-delete", "webhook-create", "webhook-delete", "webhook-rotate"])

        // The delete/rotate bodies always carry the SAME clientId next to the
        // owner; the create bodies carry the original instructions.
        XCTAssertEqual(revived.automationDeleteBody(pending[1], userId: owner)["clientId"] as? String, removal.clientId)
        XCTAssertEqual(revived.webhookCreateBody(pending[2], requesterId: owner)["clientId"] as? String, hookCreate.clientId)
        XCTAssertEqual(revived.webhookCreateBody(pending[2], requesterId: owner)["name"] as? String, "Deploys")
        XCTAssertEqual(revived.webhookDeleteBody(pending[3], requesterId: owner)["requesterId"] as? String, owner)
        XCTAssertEqual(revived.webhookDeleteBody(pending[3], requesterId: owner)["clientId"] as? String, hookDelete.clientId)
        XCTAssertEqual(revived.webhookRotateBody(pending[4], requesterId: owner)["clientId"] as? String, hookRotate.clientId)

        // Confirm removes only its own row; another owner never sees these.
        revived.confirm(owner: owner, clientId: create.clientId)
        XCTAssertEqual(revived.pending(owner: owner).map(\.clientId),
            [removal.clientId, hookCreate.clientId, hookDelete.clientId, hookRotate.clientId])
        XCTAssertTrue(revived.pending(owner: "someone-else").isEmpty)
    }
}
