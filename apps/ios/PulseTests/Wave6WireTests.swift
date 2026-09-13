import XCTest
@testable import Pulse

/// Wave 6 — social-graph WIRE tests (F-CP family). Proves the tolerant
/// Codable DTO layer against the audited gateway JSON (worklog 6-a):
/// users/{stats,safety,block,blocks,report} shapes, the 409/403 error
/// bodies, and the server-computed 60-digit safety number split. Pure
/// XCTest — no sockets, no AVAudio, no network.
final class Wave6WireTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String, file: StaticString = #filePath, line: UInt = #line) throws -> T {
        let data = Data(json.utf8)
        return try JSONDecoder().decode(T.self, from: data)
    }

    // ── stats (GET /api/users/{id}/stats → { stats }) ────────

    func testUserStatsDecodesFullShape() throws {
        let stats = try decode(WireUserStatsPage.self, """
        {"stats":{"messages":12,"reactions":3,"photos":2,"voiceNotes":1,"chats":4,"groups":2,"days":9,"joinedAt":"2026-01-05T10:00:00.000Z","lastSeenAt":"2026-02-01T08:00:00.000Z"}}
        """).stats
        XCTAssertEqual(stats.messages, 12)
        XCTAssertEqual(stats.reactions, 3)
        XCTAssertEqual(stats.photos, 2)
        XCTAssertEqual(stats.voiceNotes, 1)
        XCTAssertEqual(stats.chats, 4)
        XCTAssertEqual(stats.groups, 2)
        XCTAssertEqual(stats.days, 9)
        XCTAssertEqual(stats.joinedAt, "2026-01-05T10:00:00.000Z")
    }

    /// Tolerance: an older gateway may omit any stamp — the page must still
    /// decode (the UserPage renders zeros, never crashes).
    func testUserStatsToleratesMissingFields() throws {
        let stats = try decode(WireUserStatsPage.self, """
        {"stats":{"messages":5},"extra":"ignored"}
        """).stats
        XCTAssertEqual(stats.messages, 5)
        XCTAssertNil(stats.reactions)
        XCTAssertNil(stats.joinedAt)
        XCTAssertNil(stats.lastSeenAt)
    }

    // ── safety (GET/POST/DELETE /api/users/{id}/safety) ──────

    /// The server computes sha256(min|max|pepper) mod 10^60 → 60 digits,
    /// space-joined 12×5 (src/lib/safety.ts). The DTO must carry it intact.
    func testSafetyStateCarriesTheSixtyDigits() throws {
        let digits = Array(repeating: "12345", count: 12).joined(separator: " ")
        let state = try decode(WireSafetyState.self, """
        {"peerId":"u2","safetyNumber":"\(digits)","verified":true,"verifiedAt":"2026-02-01T08:00:00.000Z"}
        """)
        XCTAssertEqual(state.peerId, "u2")
        XCTAssertEqual(state.safetyNumber, digits)
        XCTAssertTrue(state.verified)
        XCTAssertNotNil(state.verifiedAt)
    }

    /// The safety SPLIT: PulseSafetyNumber.groups yields 12 groups of 5 —
    /// the SafetySheet's 3×4 tile grid indexes it directly.
    func testSafetyNumberSplitsIntoTwelveGroupsOfFive() {
        let joined = Array(repeating: "01234", count: 12).joined(separator: " ")
        let groups = PulseSafetyNumber.groups(joined)
        XCTAssertEqual(groups.count, 12)
        XCTAssertTrue(groups.allSatisfy { $0.count == 5 })
        XCTAssertEqual(groups[0], "01234")
        XCTAssertEqual(groups[11], "01234")
    }

    func testSafetyVerdictDecodes() throws {
        let verdict = try decode(WireSafetyVerdict.self, """
        {"verified":true,"verifiedAt":"2026-02-01T08:00:00.000Z"}
        """)
        XCTAssertEqual(verdict.verified, true)
    }

    // ── block pair state + list ───────────────────────────────

    func testBlockStateDecodes() throws {
        XCTAssertTrue(try decode(WireBlockState.self, #"{"blocked":true}"#).blocked)
        XCTAssertFalse(try decode(WireBlockState.self, #"{"blocked":false,"unknown":1}"#).blocked)
    }

    func testBlockedAccountsPageToleratesUnknownKeysAndMissingStamps() throws {
        let page = try decode(WireBlocksPage.self, """
        {"blocks":[{"id":"u3","name":"Bob","username":"bob","color":"rose","blockedAt":"2026-02-02T00:00:00.000Z"},{"id":"u4","name":"Cara"}],"total":2}
        """)
        XCTAssertEqual(page.blocks.count, 2)
        XCTAssertEqual(page.blocks[0].username, "bob")
        XCTAssertEqual(page.blocks[1].blockedAt, nil)
    }

    // ── report verdicts (201 create vs 200 idempotent refresh) ──

    func testReportVerdictCreateShape() throws {
        let verdict = try decode(WireReportVerdict.self, #"{"reported":true}"#)
        XCTAssertEqual(verdict.reported, true)
        XCTAssertNil(verdict.updated)
    }

    func testReportVerdictRefreshShape() throws {
        let verdict = try decode(WireReportVerdict.self, #"{"reported":true,"updated":true}"#)
        XCTAssertEqual(verdict.updated, true)
    }

    // ── 409/403 error bodies (the Failure enrichment path) ────

    /// The handle-clash 409: error copy + machine code + suggestion ride the
    /// body; the client maps username_taken → .validation.
    func testUsernameTakenBodyEnrichment() throws {
        let body = try decode(WireErrorBody.self, """
        {"error":"@ada is already taken.","code":"username_taken","suggestion":"ada_2"}
        """)
        XCTAssertEqual(body.error, "@ada is already taken.")
        XCTAssertEqual(body.code, "username_taken")
        XCTAssertEqual(body.suggestion, "ada_2")
    }

    func testStatusKindMappingCoversTheWave6Routes() {
        XCTAssertEqual(PulseAPIClient.kind(for: 400), .unknown)
        XCTAssertEqual(PulseAPIClient.kind(for: 401), .auth)
        // 403 — the last-admin channel leave + the blocks self-check.
        XCTAssertEqual(PulseAPIClient.kind(for: 403), .forbidden)
        // 404 — dead invite links.
        XCTAssertEqual(PulseAPIClient.kind(for: 404), .notFound)
        // 409 — the handle clash.
        XCTAssertEqual(PulseAPIClient.kind(for: 409), .unknown)
        XCTAssertEqual(PulseAPIClient.kind(for: 429), .rateLimited)
        XCTAssertEqual(PulseAPIClient.kind(for: 500), .server)
    }

    func testFailureCarriesStatusAndSuggestion() {
        let failure = PulseAPIClient.Failure(
            kind: .validation,
            message: "@ada is already taken.",
            code: "username_taken",
            suggestion: "ada_2",
            status: 409,
        )
        XCTAssertEqual(failure.status, 409)
        XCTAssertEqual(failure.suggestion, "ada_2")
        XCTAssertEqual(failure.kind, .validation)
    }

    // ── channels / invite / mentions / folders envelopes ─────

    func testChannelSummaryTolerance() throws {
        let page = try decode(Wave6ChannelProbe.self, """
        {"channels":[{"id":"ch1","conversationId":"c1","name":"Pulse Daily","description":"d","memberCount":7,"isSubscribed":true,"unread":false,"preview":"…","photo":"/api/uploads/x"},{"id":"ch2","name":null}]}
        """)
        XCTAssertEqual(page.channels[0].memberCount, 7)
        XCTAssertEqual(page.channels[0].name, "Pulse Daily")
        XCTAssertNil(page.channels[1].memberCount)
        XCTAssertNil(page.channels[1].conversationId)
    }

    func testInvitePreviewEnvelopeAndJoinResult() throws {
        let preview = try decode(WireInvitePage.self, """
        {"invite":{"code":"abc123","conversationId":"c9","isGroup":true,"name":"Dora's group","memberCount":3,"alreadyMember":false}}
        """).invite
        XCTAssertEqual(preview.name, "Dora's group")
        XCTAssertEqual(preview.memberCount, 3)
        XCTAssertFalse(preview.alreadyMember ?? true)

        let join = try decode(WireInviteJoinResult.self, """
        {"conversationId":"c9","alreadyMember":true}
        """)
        XCTAssertEqual(join.conversationId, "c9")
        XCTAssertEqual(join.alreadyMember, true)
    }

    func testMentionEntriesPageTolerance() throws {
        let page = try decode(WireMentionEntriesPage.self, """
        {"items":[{"messageId":"m1","conversationId":"c1","conversationName":"Room","isGroup":true,"author":{"id":"u1","name":"Ada","color":"rose"},"snippet":"ping @Cara hello","createdAt":"2026-02-03T00:00:00.000Z"}],"meta":"ignored"}
        """)
        XCTAssertEqual(page.items?.count, 1)
        XCTAssertEqual(page.items?.first?.author?.name, "Ada")
        XCTAssertEqual(page.items?.first?.snippet, "ping @Cara hello")
        // Missing items key → nil → the client degrades to [].
        let empty = try decode(WireMentionEntriesPage.self, #"{"ok":true}"#)
        XCTAssertNil(empty.items)
    }

    func testFolderEnvelopeDecodes() throws {
        let envelope = try decode(WireFolderEnvelope.self, """
        {"folder":{"id":"f1","name":"Work","emoji":"💼","position":2,"conversationIds":["c1","c2"]}}
        """)
        XCTAssertEqual(envelope.folder.name, "Work")
        XCTAssertEqual(envelope.folder.emoji, "💼")
        XCTAssertEqual(envelope.folder.conversationIds, ["c1", "c2"])
    }
}

/// Local probe type — mirrors WireChannelsPage's { channels: [...] } envelope
/// (the real one lives behind an internal init; the shape is what's tested).
private struct Wave6ChannelProbe: Decodable {
    let channels: [WireChannelSummary]
}
