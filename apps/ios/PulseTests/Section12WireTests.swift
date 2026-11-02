import XCTest
@testable import Pulse

/// Section 12 wire decode acceptance: the integration list envelopes carry
/// the monotonic revision + the shared keyset page verdict, webhook rows
/// know their last rotation, and the mutation receipts parse replayed/
/// revision/rotatedAt - all tolerantly (missing fields decode nil, so the
/// legacy single-call shapes stay legal).
final class Section12WireTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    // Section 12 item 2 - the list envelopes.

    func testAutomationsPageDecodesRevisionAndSharedPageVerdict() throws {
        let page = try decode(WireAutomationsPage.self,
            #"{"automations":[{"id":"a1","conversationId":"c1","trigger":"pricing","reply":"ok","enabled":true,"hits":2,"createdAt":"2026-02-01T09:30:00.000Z"}],"revision":3,"page":{"nextCursor":"1770000000001.a1","hasMore":true}}"#)
        XCTAssertEqual(page.automations?.first?.id, "a1")
        XCTAssertEqual(page.revision, 3)
        XCTAssertEqual(page.page?.nextCursor, "1770000000001.a1")
        XCTAssertEqual(page.page?.hasMore, true)
        // A null cursor with hasMore=false is the closed-walk verdict.
        let closed = try decode(WireAutomationsPage.self,
            #"{"automations":[],"revision":0,"page":{"nextCursor":null,"hasMore":false}}"#)
        XCTAssertEqual(closed.revision, 0, "revision 0 = never bumped")
        XCTAssertNil(closed.page?.nextCursor)
        XCTAssertEqual(closed.page?.hasMore, false)
    }

    func testWebhooksPageDecodesRevisionPageAndRotatedAt() throws {
        let page = try decode(WireWebhooksPage.self,
            #"{"webhooks":[{"id":"w1","name":"Deploys","token":"tok_123","avatarColor":"rose","url":"/api/webhooks/tok_123","createdAt":"2026-02-01T09:30:00.000Z","createdBy":"u1","rotatedAt":"2026-02-02T10:00:00.000Z"}],"revision":9,"page":{"nextCursor":"1770000000002.w1","hasMore":false}}"#)
        XCTAssertEqual(page.webhooks?.first?.rotatedAt, "2026-02-02T10:00:00.000Z")
        XCTAssertEqual(page.revision, 9)
        XCTAssertEqual(page.page?.hasMore, false)
        // A never-rotated capability carries rotatedAt: null.
        let fresh = try decode(WireWebhook.self,
            #"{"id":"w2","name":"CI","token":"tok_9","url":"/api/webhooks/tok_9","rotatedAt":null}"#)
        XCTAssertNil(fresh.rotatedAt)
    }

    // Section 12 items 1/9 - the mutation receipts.

    func testWebhookMutationReceiptDecodesCreateRotateAndDeleteShapes() throws {
        // Rotate: the fresh capability spread + revision + replayed.
        let rotate = try decode(WireWebhookMutation.self,
            #"{"id":"w1","name":"Deploys","token":"fresh-token","avatarColor":"rose","url":"/api/webhooks/fresh-token","createdAt":"2026-02-01T09:30:00.000Z","createdBy":"u1","rotatedAt":"2026-02-02T10:00:00.000Z","revision":11,"replayed":true}"#)
        XCTAssertEqual(rotate.token, "fresh-token")
        XCTAssertEqual(rotate.url, "/api/webhooks/fresh-token")
        XCTAssertEqual(rotate.rotatedAt, "2026-02-02T10:00:00.000Z")
        XCTAssertEqual(rotate.revision, 11)
        XCTAssertEqual(rotate.replayed, true)
        // Delete: the SAME-verdict receipt (ok/deleted/token), no DTO fields.
        let deletion = try decode(WireWebhookMutation.self,
            #"{"ok":true,"deleted":true,"token":"tok_123","userId":"u1","revision":12,"replayed":false}"#)
        XCTAssertEqual(deletion.ok, true)
        XCTAssertEqual(deletion.deleted, true)
        XCTAssertEqual(deletion.token, "tok_123")
        XCTAssertEqual(deletion.revision, 12)
        XCTAssertEqual(deletion.replayed, false)
        XCTAssertNil(deletion.id)
        XCTAssertNil(deletion.url)
    }

    // Tolerant house decode: legacy shapes without the Section 12 fields
    // decode nil, never crash.

    func testLegacyShapesDecodeWithoutTheSection12Fields() throws {
        let legacyPage = try decode(WireAutomationsPage.self, #"{"automations":[{"id":"a1","trigger":"hi"}]}"#)
        XCTAssertNil(legacyPage.revision)
        XCTAssertNil(legacyPage.page)
        XCTAssertNil(legacyPage.automations?.first?.conversationId)
        let legacyHooks = try decode(WireWebhooksPage.self, #"{"webhooks":[{"id":"w1","name":"Deploys","token":"tok_123","url":"/api/webhooks/tok_123"}]}"#)
        XCTAssertNil(legacyHooks.revision)
        XCTAssertNil(legacyHooks.page)
        XCTAssertNil(legacyHooks.webhooks?.first?.rotatedAt)
        let bareEnvelope = try decode(WireAutomationEnvelope.self, #"{"automation":{"id":"a1","trigger":"hi"}}"#)
        XCTAssertNil(bareEnvelope.revision)
        XCTAssertNil(bareEnvelope.replayed)
        let bareMutation = try decode(WireWebhookMutation.self, #"{}"#)
        XCTAssertNil(bareMutation.token)
        XCTAssertNil(bareMutation.replayed)
    }
}
