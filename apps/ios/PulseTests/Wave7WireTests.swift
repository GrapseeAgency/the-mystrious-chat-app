import XCTest
@testable import Pulse

/// Wave 7 — wire DTO tolerant-decode tests (populated + absent shapes for
/// every Collaboration & Hub family) + payload survival through the store.
final class Wave7WireTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    // ── red packets ──────────────────────────────────────────────

    func testRedPacketCreateResultPopulated() throws {
        let r = try decode(
            WireRedPacketCreateResult.self,
            #"{"message":{"id":"m1","conversationId":"c1","senderId":"u1","content":"🧧 Red packet","kind":"redpacket","createdAt":"2026-01-15T00:00:00.000Z","payload":"{\"packetId\":\"p1\",\"total\":100,\"count\":5,\"note\":\"hi\"}"},"packet":{"id":"p1","total":100,"count":5,"grabbed":0,"expiresAt":"2026-01-16T00:00:00.000Z"}}"#,
        )
        XCTAssertEqual(r.message?.kind, "redpacket")
        XCTAssertEqual(r.packet?.id, "p1")
        XCTAssertEqual(r.packet?.total, 100)
        let payload = PulseWave7Logic.redPacketPayload(r.message?.payload)
        XCTAssertEqual(payload?.packetId, "p1")
    }

    func testRedPacketDetailPopulatedAndAbsent() throws {
        let d = try decode(
            WireRedPacketDetail.self,
            #"{"packet":{"id":"p1","total":100,"count":5,"grabbed":2,"status":"open"},"senderName":"A","grabs":[{"userId":"u2","name":"B","amount":37}],"myGrab":37,"isMine":false}"#,
        )
        XCTAssertEqual(d.packet?.grabbed, 2)
        XCTAssertEqual(d.grabs?.first?.amount, 37)
        XCTAssertEqual(d.myGrab, 37)

        let empty = try decode(WireRedPacketDetail.self, "{}")
        XCTAssertNil(empty.packet)
        XCTAssertTrue((empty.grabs ?? []).isEmpty)
    }

    func testGrabResultDecodes() throws {
        let r = try decode(WireRedPacketGrabResult.self, #"{"amount":37,"grabbed":2,"count":5}"#)
        XCTAssertEqual(r.amount, 37)
        XCTAssertEqual(r.grabbed, 2)
    }

    // ── whiteboard ───────────────────────────────────────────────

    func testWhiteboardPageDecodes() throws {
        let page = try decode(
            WireWhiteboardPage.self,
            ##"{"strokes":[{"id":"s1","userId":"u1","color":"#22c55e","width":3.0,"points":[[0.1,0.2],[0.4,0.5]]}],"serverTime":1700,"resetAt":null}"##,
        )
        XCTAssertEqual(page.strokes?.count, 1)
        XCTAssertEqual(page.strokes?.first?.points?.count, 2)
        XCTAssertEqual(page.serverTime, 1700)
        let empty = try decode(WireWhiteboardPage.self, "{}")
        XCTAssertTrue((empty.strokes ?? []).isEmpty)
    }

    func testWhiteboardPostAndUndoDecode() throws {
        let post = try decode(WireWhiteboardPostResult.self, #"{"ids":["s9"],"created":1,"serverTime":1800}"#)
        XCTAssertEqual(post.ids, ["s9"])
        let undo = try decode(WireWhiteboardUndoResult.self, #"{"success":true,"removedId":"s9"}"#)
        XCTAssertEqual(undo.removedId, "s9")
        let clear = try decode(WireWhiteboardClearResult.self, #"{"success":true,"reset":true,"at":2000,"cleared":3}"#)
        XCTAssertEqual(clear.cleared, 3)
    }

    // ── kanban ───────────────────────────────────────────────────

    func testKanbanCardAndEnvelope() throws {
        let card = try decode(
            WireKanbanCard.self,
            #"{"id":"k1","conversationId":"c1","title":"Ship it","column":"doing","position":4,"assigneeId":"u2","assigneeName":"B","createdById":"u1","createdByName":"A"}"#,
        )
        XCTAssertEqual(card.title, "Ship it")
        XCTAssertEqual(card.column, "doing")

        let fromEnvelope = try decode(WireKanbanCardEnvelope.self, #"{"card":{"id":"k2","title":"T2","column":"todo","position":0}}"#)
        XCTAssertEqual(fromEnvelope.card?.id, "k2")

        let page = try decode(WireKanbanPage.self, #"{"cards":[{"id":"k1","title":"a","column":"todo","position":0},{"id":"k2","title":"b","column":"done","position":1}]}"#)
        XCTAssertEqual(page.cards.count, 2)
    }

    // ── events ───────────────────────────────────────────────────

    func testEventPopulatedAndCounts() throws {
        let e = try decode(
            WireGroupEvent.self,
            #"{"id":"e1","title":"Standup","startsAt":"2026-01-20T10:00:00.000Z","rsvps":[{"userId":"u1","name":"A","status":"going","checkedInAt":null}],"counts":{"going":1,"maybe":0,"no":0},"myStatus":null}"#,
        )
        XCTAssertEqual(e.rsvps?.count, 1)
        XCTAssertEqual(e.counts?.going, 1)

        let page = try decode(WireEventsPage.self, #"{"events":[{"id":"e2","title":"X"}]}"#)
        XCTAssertEqual(page.events.count, 1)
    }

    func testRsvpAndCheckinResults() throws {
        let rsvp = try decode(WireRsvpResult.self, #"{"rsvp":{"id":"r1","eventId":"e1","userId":"u1","status":"going"},"counts":{"going":1,"maybe":0,"no":0}}"#)
        XCTAssertEqual(rsvp.counts?.going, 1)
        let checkin = try decode(WireCheckinResult.self, #"{"checkIn":null,"xpAwarded":false,"checkedInCount":3,"alreadyCheckedIn":true}"#)
        XCTAssertTrue(checkin.alreadyCheckedIn == true)
    }

    // ── reminders ────────────────────────────────────────────────

    func testReminderItemAndPage() throws {
        let item = try decode(
            WireReminderItem.self,
            #"{"id":"rm1","conversationId":"c1","note":"hi","remindAt":"2026-01-15T11:00:00.000Z","conversation":{"id":"c1","name":"DM","isGroup":false},"snippet":"Message text"}"#,
        )
        XCTAssertEqual(item.id, "rm1")
        XCTAssertEqual(item.snippet, "Message text")
        let page = try decode(WireRemindersPage.self, #"{"items":[{"id":"rm2","note":"x","remindAt":"2026-01-15T11:00:00.000Z"}]}"#)
        XCTAssertEqual(page.items.count, 1)
        let resolve = try decode(WireReminderResolve.self, #"{"ok":true,"firedAt":"2026-01-15T11:00:00.000Z"}"#)
        XCTAssertEqual(resolve.ok, true)
    }

    // ── games / tournaments ──────────────────────────────────────

    func testGameDetailWithNullablePlayerO() throws {
        let d = try decode(
            WireGameDetail.self,
            #"{"match":{"id":"m1","board":"XOXOX    ","turn":"O","status":"active","moveCount":5,"winLine":null},"playerX":{"id":"u1","name":"A","color":"emerald"},"playerO":{"id":"u2","name":"B","color":"rose"}}"#,
        )
        XCTAssertEqual(d.match?.board?.count, 9)
        XCTAssertEqual(d.playerO?.id, "u2")
        let solo = try decode(WireGameDetail.self, #"{"match":{"id":"m2"},"playerX":{"id":"u1"}}"#)
        XCTAssertNil(solo.playerO)
    }

    func testGameCreateResultCarriesMessage() throws {
        let r = try decode(
            WireGameMatchCreateResult.self,
            #"{"match":{"id":"m1","status":"active"},"message":{"id":"gm1","conversationId":"c1","senderId":"u1","content":"⚔️ Tic-tac-toe — open challenge","kind":"game","createdAt":"2026-01-15T00:00:00.000Z","payload":"{\"matchId\":\"m1\",\"game\":\"tictactoe\"}"}}"#,
        )
        XCTAssertEqual(r.message?.kind, "game")
        XCTAssertEqual(PulseWave7Logic.gamePayload(r.message?.payload)?.matchId, "m1")
    }

    func testTournamentSummaryWithEntries() throws {
        let t = try decode(
            WireTournamentSummary.self,
            #"{"id":"t1","name":"S1","status":"running","entries":[{"userId":"u1","points":3,"wins":1,"draws":0,"losses":0}],"playerCount":2}"#,
        )
        XCTAssertEqual(t.entries?.count, 1)
        XCTAssertEqual(t.entries?.first?.points, 3)
        let create = try decode(
            WireTournamentCreateResult.self,
            #"{"tournament":{"id":"t1","name":"S1","status":"running"},"message":{"id":"tm1","kind":"tournament","payload":"{\"tournamentId\":\"t1\",\"name\":\"S1\",\"game\":\"tictactoe\"}"}}"#,
        )
        XCTAssertEqual(PulseWave7Logic.tournamentPayload(create.message?.payload)?.name, "S1")
        let join = try decode(WireTournamentJoinResult.self, #"{"entry":{"id":"en1","tournamentId":"t1","userId":"u1","points":0}}"#)
        XCTAssertEqual(join.entry?.userId, "u1")
    }

    // ── leaderboard ──────────────────────────────────────────────

    func testLeaderboardRows() throws {
        let page = try decode(
            WireLeaderboardPage.self,
            #"{"rows":[{"userId":"u1","name":"A","color":"emerald","xp":120,"messageCount":40,"gameWins":2,"tournamentPoints":5}]}"#,
        )
        XCTAssertEqual(page.rows.first?.xp, 120)
        let empty = try decode(WireLeaderboardPage.self, "{}")
        XCTAssertTrue((empty.rows).isEmpty)
    }

    // ── hub economy ──────────────────────────────────────────────

    func testWalletPagePopulatedAndEmpty() throws {
        let page = try decode(
            WireWalletPage.self,
            #"{"wallet":{"userId":"u1","coins":500,"gems":2,"streak":3,"checkedInToday":false},"ledger":[{"id":"l1","kind":"checkin","asset":"PC","amount":27,"note":"Daily check-in · 3-day streak (+4 bonus)"}]}"#,
        )
        XCTAssertEqual(page.wallet?.coins, 500)
        XCTAssertEqual(page.ledger?.count, 1)
        let empty = try decode(WireWalletPage.self, "{}")
        XCTAssertNil(empty.wallet.coins)
    }

    func testCheckinTransferSwapMarketResults() throws {
        let checkin = try decode(WireCheckinWalletResult.self, #"{"wallet":{"userId":"u1","coins":52,"streak":1},"reward":25,"streak":1}"#)
        XCTAssertEqual(checkin.reward, 25)
        let transfer = try decode(WireTransferResult.self, #"{"wallet":{"coins":0},"to":{"id":"u2","name":"B","username":"bob"}}"#)
        XCTAssertEqual(transfer.to?.username, "bob")
        let swap = try decode(WireSwapPage.self, #"{"rates":{"pcPerGemBuy":100,"pcPerGemSell":80},"stats":{"swaps":12}}"#)
        XCTAssertEqual(swap.rates?.pcPerGemBuy, 100)
        let swapResult = try decode(WireSwapResult.self, #"{"wallet":{"coins":0,"gems":2},"note":"Swapped 200 PC → 2 GEM"}"#)
        XCTAssertEqual(swapResult.wallet?.gems, 2)
        let market = try decode(
            WireMarketPage.self,
            #"{"listings":[{"id":"l1","title":"Ship","price":25,"asset":"PC","status":"open","seller":{"id":"u1","name":"A"},"mine":false}]}"#,
        )
        XCTAssertEqual(market.listings.first?.price, 25)
        let buy = try decode(WireMarketBuyResult.self, #"{"ok":true,"wallet":{"userId":"u2","coins":40}}"#)
        XCTAssertEqual(buy.ok, true)
    }

    func testHubLogsAndInstallStateAndCommunity() throws {
        let logs = try decode(
            WireHubLogsPage.self,
            #"{"logs":[{"id":"g1","kind":"transfer","message":"sent 25 PC to @bob","meta":"{\"to\":\"u2\"}","user":{"id":"u1","name":"A"}}]}"#,
        )
        XCTAssertEqual(logs.logs.first?.meta, #"{"to":"u2"}"#)
        let install = try decode(
            WireAppInstallState.self,
            #"{"installed":true,"status":"connected","installedAt":"2026-01-15T00:00:00.000Z","installs":3,"installers":[{"id":"u1","name":"A"}]}"#,
        )
        XCTAssertEqual(install.installs, 3)
        let installResult = try decode(WireAppInstallResult.self, #"{"installed":true,"status":"connected","installs":4}"#)
        XCTAssertEqual(installResult.status, "connected")
        let community = try decode(
            WireAppCommunity.self,
            #"{"conversation":{"id":"cnv1","kind":"group","title":"#011 · Twitch community","isPinned":false,"isMuted":false,"isArchived":false,"unreadCount":0},"memberCount":2,"joined":true}"#,
        )
        XCTAssertEqual(community.conversation?.id, "cnv1")
        XCTAssertEqual(community.joined, true)
    }

    // ── payload survival through the store (v7 payloadJson) ──────

    func testPayloadRoundTripsThroughStore() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("wave7-store-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try PulseStore(path: dir.appendingPathComponent("pulse.db").path)
        let json = #"{"packetId":"p1","total":100,"count":5,"note":"hi"}"#
        let message = WireChatMessage(
            id: "m7", conversationId: "c7", senderId: "u1", content: "🧧 Red packet", kind: "redpacket",
            createdAt: "2026-01-15T00:00:00.000Z", editedAt: nil, deletedAt: nil, sender: nil,
            reactions: nil, replyTo: nil, parentId: nil, imagePath: nil, audioPath: nil,
            durationMs: nil, filePath: nil, fileName: nil, fileSize: nil, pinnedAt: nil,
            viewOnce: nil, anon: nil, anonAlias: nil, viewedAt: nil, viewedBy: nil,
            transcript: nil, transcribedAt: nil, topicId: nil, linkUrl: nil,
            linkPreview: nil, poll: nil, payload: json,
        )
        try store.upsert(messages: [message])
        let loaded = try store.messages(conversationId: "c7")
        XCTAssertEqual(loaded.first?.payload, json)
        XCTAssertEqual(PulseWave7Logic.redPacketPayload(loaded.first?.payload)?.packetId, "p1")
    }
}
