import XCTest
@testable import Pulse

/// R2-B — pure room-parity logic + wire DTO tests (web ground truth:
/// chat-room.tsx missed machine + unread divider, chats-tab.tsx anchor
/// freeze, automations/webhooks/recap/screen-privacy routes). Fixtures use
/// the CURRENT object shapes (mapAutomation / WebhookDTO verbatim).
final class PulseRoomParityTests: XCTestCase {

    // ── fixtures ─────────────────────────────────────────────

    /// Full memberwise build (the wire struct has defaulted trailing vars,
    /// everything before payload is required — mirrors the store tests).
    private func makeMessage(
        id: String,
        senderId: String,
        createdAt: String,
        deletedAt: String? = nil,
    ) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: "c1",
            senderId: senderId,
            content: "body \(id)",
            kind: "text",
            createdAt: createdAt,
            editedAt: nil,
            deletedAt: deletedAt,
            sender: nil,
            reactions: nil,
            replyTo: nil,
            parentId: nil,
            imagePath: nil,
            audioPath: nil,
            durationMs: nil,
            filePath: nil,
            fileName: nil,
            fileSize: nil,
            pinnedAt: nil,
            viewOnce: nil,
            anon: nil,
            anonAlias: nil,
            viewedAt: nil,
            viewedBy: nil,
            transcript: nil,
            transcribedAt: nil,
            topicId: nil,
            linkUrl: nil,
            linkPreview: nil,
            poll: nil,
        )
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    // ── R26 — the missed-count machine (chat-room.tsx:1594-1612) ──

    func testMissedStepGrowsBadgeWhileAway() {
        let step = PulseRoomParityLogic.missedStep(
            previousMissed: 3, previousLastSeenLen: 10, newLen: 13, tailVisible: false,
        )
        XCTAssertEqual(step.missed, 6)
        XCTAssertEqual(step.lastSeenLen, 13)
        XCTAssertTrue(step.arrived)
        XCTAssertFalse(step.reset)
    }

    func testMissedStepResetsAtTheTail() {
        let step = PulseRoomParityLogic.missedStep(
            previousMissed: 9, previousLastSeenLen: 4, newLen: 7, tailVisible: true,
        )
        XCTAssertEqual(step.missed, 0)
        XCTAssertEqual(step.lastSeenLen, 7)
        XCTAssertFalse(step.arrived)
        XCTAssertTrue(step.reset)
    }

    func testMissedStepResetsOnShrink() {
        // Fewer rows than last seen = room switch / cache reset.
        let step = PulseRoomParityLogic.missedStep(
            previousMissed: 9, previousLastSeenLen: 40, newLen: 12, tailVisible: false,
        )
        XCTAssertEqual(step.missed, 0)
        XCTAssertEqual(step.lastSeenLen, 12)
        XCTAssertTrue(step.reset)
    }

    func testMissedStepNoopWhenLengthUnchanged() {
        let step = PulseRoomParityLogic.missedStep(
            previousMissed: 2, previousLastSeenLen: 12, newLen: 12, tailVisible: false,
        )
        XCTAssertEqual(step.missed, 2)
        XCTAssertEqual(step.lastSeenLen, 12)
        XCTAssertFalse(step.arrived)
        XCTAssertFalse(step.reset)
    }

    func testMissedBadgeCapsAtNinetyNinePlus() {
        XCTAssertEqual(PulseRoomParityLogic.missedBadgeText(1), "1")
        XCTAssertEqual(PulseRoomParityLogic.missedBadgeText(99), "99")
        XCTAssertEqual(PulseRoomParityLogic.missedBadgeText(148), "99+")
        // Every count below the cap renders digits — no "0" badge ever.
        let counts = [1, 5, 24, 99]
        XCTAssertTrue(counts.allSatisfy { PulseRoomParityLogic.missedBadgeText($0) != "99+" })
    }

    // ── R30-c — the tap-time anchor (chats-tab.tsx:431-435) ──

    func testUnreadAnchorRequiresUnreadBadgeAndWatermark() {
        let anchor = PulseRoomParityLogic.unreadAnchorMs(
            myLastReadAtIso: "2026-01-15T09:00:00.000Z",
            unreadCount: 4,
        )
        XCTAssertNotNil(anchor)
        // No badge / no watermark / unparsable stamp → no divider anchor.
        let none = [
            PulseRoomParityLogic.unreadAnchorMs(myLastReadAtIso: "2026-01-15T09:00:00.000Z", unreadCount: 0),
            PulseRoomParityLogic.unreadAnchorMs(myLastReadAtIso: nil, unreadCount: 4),
            PulseRoomParityLogic.unreadAnchorMs(myLastReadAtIso: "", unreadCount: 2),
            PulseRoomParityLogic.unreadAnchorMs(myLastReadAtIso: "not-a-date", unreadCount: 2),
        ]
        XCTAssertTrue(none.allSatisfy { $0 == nil })
    }

    // ── R30-c — divider placement (chat-room.tsx:1395-1420) ──

    func testDividerPlacesBeforeFirstForeignLiveNewerRow() {
        let anchor = PulseRoomParityLogic.unreadAnchorMs(
            myLastReadAtIso: "2026-01-15T10:00:00.000Z",
            unreadCount: 3,
        )
        let river = [
            makeMessage(id: "m1", senderId: "u1", createdAt: "2026-01-15T10:30:00.000Z"), // own, newer — skipped
            makeMessage(id: "m2", senderId: "u2", createdAt: "2026-01-15T10:40:00.000Z", deletedAt: "2026-01-15T10:41:00.000Z"), // deleted — skipped
            makeMessage(id: "m3", senderId: "u2", createdAt: "2026-01-15T10:45:00.000Z"), // FIRST qualifier
            makeMessage(id: "m4", senderId: "u3", createdAt: "2026-01-15T11:00:00.000Z"),
        ]
        let index = PulseRoomParityLogic.unreadDividerIndex(messages: river, viewerId: "u1", anchorMs: anchor)
        XCTAssertEqual(index, 2)
        // Everything before the divider was skipped for a reason: own row,
        // then a deleted row (web skips both when scanning for the anchor).
        XCTAssertEqual(river[0].senderId, "u1")
        XCTAssertNotNil(river[1].deletedAt)
        XCTAssertEqual(river[2].senderId, "u2")
        XCTAssertNil(river[2].deletedAt)
    }

    func testDividerAbsentWithoutAnchorOrQualifyingRow() {
        // anchor nil (nothing unread) → never places.
        XCTAssertNil(PulseRoomParityLogic.unreadDividerIndex(
            messages: [makeMessage(id: "m1", senderId: "u2", createdAt: "2026-01-15T10:30:00.000Z")],
            viewerId: "u1",
            anchorMs: nil,
        ))
        // All rows older than the watermark → nothing qualifies.
        let river = [
            makeMessage(id: "m1", senderId: "u2", createdAt: "2026-01-15T09:30:00.000Z"),
            makeMessage(id: "m2", senderId: "u2", createdAt: "2026-01-15T09:40:00.000Z"),
        ]
        XCTAssertNil(PulseRoomParityLogic.unreadDividerIndex(
            messages: river,
            viewerId: "u1",
            anchorMs: PulseRoomParityLogic.unreadAnchorMs(myLastReadAtIso: "2026-01-15T10:00:00.000Z", unreadCount: 1),
        ))
    }

    // ── R39 — validation mirrors (automations + webhooks routes) ──

    func testAutomationValidationBounds() {
        XCTAssertTrue(PulseRoomParityLogic.automationValid(trigger: "pricing", reply: "Here you go"))
        // Trim counts toward the bounds, exactly like the server.
        XCTAssertTrue(PulseRoomParityLogic.automationValid(trigger: "  hi  ", reply: " yo "))
        // Trigger below 2 / above 40 (trimmed) fails.
        XCTAssertFalse(PulseRoomParityLogic.automationValid(trigger: "a", reply: "ok"))
        XCTAssertFalse(PulseRoomParityLogic.automationValid(trigger: String(repeating: "x", count: 41), reply: "ok"))
        // Empty reply / reply over 500 fails.
        XCTAssertFalse(PulseRoomParityLogic.automationValid(trigger: "pricing", reply: "   "))
        XCTAssertFalse(PulseRoomParityLogic.automationValid(trigger: "pricing", reply: String(repeating: "x", count: 501)))
        XCTAssertTrue(PulseRoomParityLogic.automationValid(trigger: "pricing", reply: String(repeating: "x", count: 500)))
    }

    func testWebhookNameBounds() {
        XCTAssertTrue(PulseRoomParityLogic.webhookNameValid("Deploys"))
        XCTAssertTrue(PulseRoomParityLogic.webhookNameValid(String(repeating: "w", count: 32)))
        XCTAssertFalse(PulseRoomParityLogic.webhookNameValid(""))
        XCTAssertFalse(PulseRoomParityLogic.webhookNameValid("   "))
        XCTAssertFalse(PulseRoomParityLogic.webhookNameValid(String(repeating: "w", count: 33)))
    }

    // ── R34-b — the recap gate (requestRecap) ──

    func testRecapGateNeedsFiveLive() {
        let counts = [0, 1, 4]
        XCTAssertTrue(counts.allSatisfy { !PulseRoomParityLogic.recapGatePassed(liveCount: $0) })
        XCTAssertTrue(PulseRoomParityLogic.recapGatePassed(liveCount: 5))
        XCTAssertTrue(PulseRoomParityLogic.recapGatePassed(liveCount: 30))
    }

    // ── wire decodes (current route shapes) ─────────────────

    func testAutomationPageDecodesCurrentShape() throws {
        let page = try decode(
            WireAutomationsPage.self,
            #"{"automations":[{"id":"a1","conversationId":"c1","trigger":"pricing","reply":"Here is the pricing","enabled":true,"hits":7,"lastFiredAt":"2026-02-03T09:30:00.000Z","createdAt":"2026-02-01T09:30:00.000Z","createdBy":{"id":"u1","name":"Ada","color":"emerald","avatar":null}}]}"#,
        )
        let rows = page.automations ?? []
        XCTAssertEqual(rows.count, 1)
        let row = rows[0]
        XCTAssertEqual(row.id, "a1")
        XCTAssertEqual(row.trigger, "pricing")
        XCTAssertEqual(row.hits, 7)
        XCTAssertEqual(row.enabled, true)
        XCTAssertEqual(row.createdBy?.name, "Ada")
        // The optimistic flip helper keeps every other field.
        let flipped = row.withEnabled(false)
        XCTAssertEqual(flipped.enabled, false)
        XCTAssertEqual(flipped.trigger, "pricing")
        XCTAssertEqual(flipped.createdBy?.name, "Ada")
        // Tolerant: absent optional fields decode nil, not crash.
        let bare = try decode(WireAutomation.self, #"{"id":"a2","trigger":"hi"}"#)
        XCTAssertNil(bare.enabled)
        XCTAssertNil(bare.createdBy)
    }

    func testAutomationEnvelopeDecodesPatchEcho() throws {
        let envelope = try decode(
            WireAutomationEnvelope.self,
            #"{"automation":{"id":"a1","conversationId":"c1","trigger":"pricing","reply":"ok","enabled":false,"hits":0,"lastFiredAt":null,"createdAt":"2026-02-01T09:30:00.000Z","createdBy":null}}"#,
        )
        XCTAssertEqual(envelope.automation?.enabled, false)
        XCTAssertNil(envelope.automation?.lastFiredAt)
    }

    func testWebhookPageDecodesCurrentShape() throws {
        let page = try decode(
            WireWebhooksPage.self,
            #"{"webhooks":[{"id":"w1","name":"Deploys","token":"tok_123","avatarColor":"rose","url":"/api/webhooks/tok_123","createdAt":"2026-02-01T09:30:00.000Z","createdBy":"u1"}]}"#,
        )
        let rows = page.webhooks ?? []
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].name, "Deploys")
        XCTAssertEqual(rows[0].token, "tok_123")
        XCTAssertEqual(rows[0].url, "/api/webhooks/tok_123")
        // Empty list is a valid shape (fresh conversations).
        let empty = try decode(WireWebhooksPage.self, #"{"webhooks":[]}"#)
        XCTAssertTrue((empty.webhooks ?? []).isEmpty)
    }

    func testRecapAndScreenPrivacyResultsDecode() throws {
        let recap = try decode(
            WireRecapResult.self,
            #"{"recap":"• Shipped the beta\n• Pricing still open","basedOn":12,"cached":false}"#,
        )
        XCTAssertEqual(recap.recap, "• Shipped the beta\n• Pricing still open")
        XCTAssertEqual(recap.basedOn, 12)
        XCTAssertEqual(recap.cached, false)

        let privacy = try decode(WireScreenPrivacyResult.self, #"{"ok":true,"screenPrivacy":true}"#)
        XCTAssertEqual(privacy.ok, true)
        XCTAssertEqual(privacy.screenPrivacy, true)
    }

    func testConversationSummaryCarriesScreenPrivacyFlags() throws {
        let summary = try decode(
            WireConversationSummary.self,
            #"{"id":"c1","isGroup":true,"name":"Room","members":[],"screenPrivacy":true,"myScreenPrivacy":false}"#,
        )
        XCTAssertEqual(summary.screenPrivacy, true)
        XCTAssertEqual(summary.myScreenPrivacy, false)
        // Older relays omit both → veil off.
        let legacy = try decode(WireConversationSummary.self, #"{"id":"c2","isGroup":false,"members":[]}"#)
        XCTAssertNil(legacy.screenPrivacy)
        XCTAssertNil(legacy.myScreenPrivacy)
    }

    // ── F-MS-22 — /recap rides the slash outcome ────────────

    func testSlashRecapOutcome() {
        XCTAssertEqual(PulseRemediationLogic.applySlash("/recap"), .recap)
        XCTAssertEqual(PulseRemediationLogic.applySlash("  /recap  "), .recap)
        // A non-command never turns into a recap request.
        XCTAssertNotEqual(PulseRemediationLogic.applySlash("recap"), .recap)
    }

    // ── R3-A — palette fuzzy match (slash-palette.tsx:127-140) ──

    func testFuzzyMatchWebExamples() {
        // The web doc-string example: "/ef co" finds "/effects confetti …".
        let command = PulseRemediationLogic.slashCommands.first { $0.cmd == "/effects confetti" }
        XCTAssertNotNil(command)
        XCTAssertTrue(PulseRemediationLogic.slashFuzzyMatch(
            haystack: "\(command?.cmd ?? "") \(command?.args ?? "") \(command?.help ?? "")",
            needle: "/ef co",
        ))
    }

    func testFuzzyMatchReordersWhitespaceAndCase() {
        XCTAssertTrue(PulseRemediationLogic.slashFuzzyMatch(haystack: "/schedule  Schedule this message", needle: "/ SCHEDULE"))
        // Whitespace in the needle is stripped before the in-order walk.
        XCTAssertTrue(PulseRemediationLogic.slashFuzzyMatch(haystack: "/sticker Open the packs", needle: "/ s t i c ker"))
    }

    func testFuzzyMatchRejectsOutOfOrderAndMissing() {
        // "zz" appears nowhere in the /help row.
        XCTAssertFalse(PulseRemediationLogic.slashFuzzyMatch(haystack: "/help Show every command", needle: "/zz"))
        // Reverse order breaks the in-order requirement (no wrap-around).
        XCTAssertFalse(PulseRemediationLogic.slashFuzzyMatch(haystack: "/poll", needle: "/llpo"))
    }

    func testFuzzyMatchEmptyNeedleMatchesEverything() {
        XCTAssertTrue(PulseRemediationLogic.slashFuzzyMatch(haystack: "/me Send an italic action line", needle: ""))
        XCTAssertTrue(PulseRemediationLogic.slashFuzzyMatch(haystack: "/me", needle: "   "))
    }
}
