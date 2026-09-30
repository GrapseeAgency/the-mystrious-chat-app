import XCTest
@testable import Pulse

/// 3-d - the PURE group-call contract (web useGroupCallSession parity,
/// src/components/chat/group-call-overlay.tsx + the relay's gcall:* family):
///   • the DETERMINISTIC mesh rule: between any two members the one with the
///     lexicographically SMALLER id creates the offer - pinned with the
///     EXACT JS rule (UTF-16 code-unit order, "10" < "2");
///   • the roster diff (departing peers / offers I own);
///   • the wire shapes: C→S builders the relay validates + tolerant S→C
///     decodes with the web fallbacks ('Someone' / 'emerald' / null avatar).
/// Pure Foundation - no WebRTC, no relay, no device hardware.
final class PulseGroupCallPolicyTests: XCTestCase {

    // offer direction: the JS-lexicographic id rule

    /// Web `me < member.id` - JS compares strings by UTF-16 code units.
    /// "10" < "2" because '1' (0x31) < '2' (0x32), even though the numeric
    /// read would say otherwise. This is the exact pinned rule.
    func testJsLexicographicOrderTenBeforeTwo() {
        XCTAssertTrue(PulseGroupCallPolicy.isLowerId("10", than: "2"))
        XCTAssertFalse(PulseGroupCallPolicy.isLowerId("2", than: "10"))
        XCTAssertTrue(PulseGroupCallPolicy.shouldOffer(myId: "10", remoteId: "2"))
        XCTAssertFalse(PulseGroupCallPolicy.shouldOffer(myId: "2", remoteId: "10"))
    }

    func testSmallerAlphabeticalIdOffersAndLargerAnswers() {
        XCTAssertTrue(PulseGroupCallPolicy.shouldOffer(myId: "alice", remoteId: "bob"))
        XCTAssertFalse(PulseGroupCallPolicy.shouldOffer(myId: "bob", remoteId: "alice"))
    }

    /// A strict prefix sorts BEFORE its extension (both in JS and here).
    func testPrefixIdSortsBeforeExtension() {
        XCTAssertTrue(PulseGroupCallPolicy.isLowerId("ab", than: "abc"))
        XCTAssertFalse(PulseGroupCallPolicy.isLowerId("abc", than: "ab"))
    }

    /// Equal ids NEVER offer (the relay refuses self-targeted signaling
    /// anyway) and empty ids are refused defensively.
    func testEqualAndEmptyIdsNeverOffer() {
        XCTAssertFalse(PulseGroupCallPolicy.shouldOffer(myId: "same", remoteId: "same"))
        XCTAssertFalse(PulseGroupCallPolicy.shouldOffer(myId: "", remoteId: "peer"))
        XCTAssertFalse(PulseGroupCallPolicy.shouldOffer(myId: "me", remoteId: ""))
        XCTAssertFalse(PulseGroupCallPolicy.shouldOffer(myId: "", remoteId: ""))
    }

    /// Exactly one side owns the offer for every pair - both views agree.
    func testBothSidesAgreeOnExactlyOneOfferer() {
        let pairs = [("user-1", "user-2"), ("10", "9"), ("aa", "a"), ("b7", "b71")]
        for (a, b) in pairs {
            let aOffers = PulseGroupCallPolicy.shouldOffer(myId: a, remoteId: b)
            let bOffers = PulseGroupCallPolicy.shouldOffer(myId: b, remoteId: a)
            XCTAssertNotEqual(aOffers, bOffers, "pair \(a)/\(b) must have exactly one offerer")
        }
    }

    // roster plan (web applyRoster diff)

    func testRosterPlanOffersToLowerUnconnectedMembers() {
        // Roster join-ordered INCLUDING me; my id 'a' sorts below b and c.
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: "a",
            rosterIds: ["a", "b", "c"],
            connectedPeerIds: [],
        )
        XCTAssertEqual(plan.departing, [])
        XCTAssertEqual(plan.toOffer, ["b", "c"])
    }

    func testRosterPlanSkipsConnectedMembersAndHigherIds() {
        // 'a' already connected (offer/answer in flight or it offered us),
        // 'c' already connected too; 'd' sorts above me - the deterministic
        // rule is `me < member.id` (web applyRoster :287), so I own the
        // b–d offer and d is the only unconnected peer left to offer.
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: "b",
            rosterIds: ["a", "b", "c", "d"],
            connectedPeerIds: ["a", "c"],
        )
        XCTAssertEqual(plan.departing, [])
        // a + c: connected (skip). d: unconnected and above me → I offer.
        XCTAssertEqual(plan.toOffer, ["d"])
    }

    func testRosterPlanStillOffersToLowerUnconnectedMember() {
        // Same roster minus the upper member: b owns the b–c pair offer.
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: "b",
            rosterIds: ["a", "b", "c"],
            connectedPeerIds: ["a"],
        )
        XCTAssertEqual(plan.departing, [])
        XCTAssertEqual(plan.toOffer, ["c"])
    }

    func testRosterPlanClosesDepartedPeersInConnectionOrder() {
        // 'z' survived on the roster (m + q departed); z itself is not
        // connected yet, so my (lower-id) offer for the z pair fires.
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: "a",
            rosterIds: ["a", "z"],
            connectedPeerIds: ["m", "q"],
        )
        // Departing follows the connection iteration order (stable, the
        // engine closes exactly these).
        XCTAssertEqual(plan.departing, ["m", "q"])
        XCTAssertEqual(plan.toOffer, ["z"])
    }

    func testRosterPlanSurvivorThatIsStillConnectedGetsNoSecondOffer() {
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: "a",
            rosterIds: ["a", "z"],
            connectedPeerIds: ["m", "z", "q"],
        )
        XCTAssertEqual(plan.departing, ["m", "q"])
        XCTAssertEqual(plan.toOffer, [], "z is still connected - no duplicate offer")
    }

    /// The 8-member full-call mesh: total offers across all views equals
    /// the number of pairs (C(n,2)) - the deterministic rule needs no glare
    /// protection even under simultaneous joins.
    func testFullMeshOfferCountMatchesPairCount() {
        let ids = (1...8).map { "member-\($0)" }
        var totalOffers = 0
        for me in ids {
            let roster = ids
            let plan = PulseGroupCallPolicy.rosterPlan(myId: me, rosterIds: roster, connectedPeerIds: [])
            totalOffers += plan.toOffer.count
            for peerId in plan.toOffer {
                XCTAssertTrue(PulseGroupCallPolicy.shouldOffer(myId: me, remoteId: peerId))
            }
        }
        XCTAssertEqual(totalOffers, 28, "C(8,2) = 28 - every pair gets exactly one offer")
    }

    /// My own id is never in toOffer (self-connection impossible) even when
    /// the relay echoes me first on the roster.
    func testRosterPlanNeverOffersSelf() {
        let plan = PulseGroupCallPolicy.rosterPlan(
            myId: "me",
            rosterIds: ["me", "me2", "other"],
            connectedPeerIds: [],
        )
        XCTAssertEqual(plan.toOffer, ["me2", "other"])
        XCTAssertFalse(plan.toOffer.contains("me"))
    }

    // leave copy + local ring guard

    /// Web leaveCall summary - 'You left · M:SS' once a second elapsed.
    func testLeaveSummaryFormatsDuration() {
        XCTAssertEqual(PulseGroupCallPolicy.leaveSummary(elapsedSec: 65), "You left · 1:05")
        XCTAssertEqual(PulseGroupCallPolicy.leaveSummary(elapsedSec: 5), "You left · 0:05")
        XCTAssertEqual(PulseGroupCallPolicy.leaveSummary(elapsedSec: 0), "You left")
        XCTAssertEqual(PulseGroupCallPolicy.leaveSummary(elapsedSec: -3), "You left")
    }

    /// The 45s local ring guard - group rings have NO server-side timeout.
    func testRingTimeoutIsPinned() {
        XCTAssertEqual(PulseGroupCallPolicy.ringTimeoutSec, 45)
    }

    // wire: member decode (web fallbacks)

    func testMemberDecodeUsesWebFallbacks() {
        let member = PulseGroupCallMember.decode([
            "id": "u1",
            "name": "",
            "color": "",
            "avatar": NSNull(),
        ])
        XCTAssertEqual(member?.id, "u1")
        XCTAssertEqual(member?.name, "Someone")
        XCTAssertEqual(member?.color, "emerald")
        XCTAssertNil(member?.avatar)
    }

    func testMemberDecodeKeepsRealValuesAndDropsIdlessEntries() {
        let full = PulseGroupCallMember.decode([
            "id": "u2",
            "name": "Ada",
            "color": "rose",
            "avatar": "https://x/y.png",
        ])
        XCTAssertEqual(full, PulseGroupCallMember(id: "u2", name: "Ada", color: "rose", avatar: "https://x/y.png"))

        let list = PulseGroupCallMember.decodeList([
            ["id": "u2", "name": "Ada"],
            ["name": "no id"],
        ])
        XCTAssertEqual(list.count, 1)
        XCTAssertEqual(list.first?.id, "u2")
        XCTAssertNil(PulseGroupCallMember.decode(["name": "Ada"]))
    }

    // wire: the gcall:ring decode

    func testRingDecodeCarriesCallerKindAndTitle() {
        let ring = PulseGroupCallEngine.Ring.decode([
            "conversationId": "conv-1",
            "kind": "video",
            "caller": ["id": "u9", "name": "Ringer", "color": "emerald", "avatar": ""],
            "title": "Design crew",
        ])
        XCTAssertEqual(ring?.conversationId, "conv-1")
        XCTAssertEqual(ring?.kind, .video)
        XCTAssertEqual(ring?.caller.id, "u9")
        XCTAssertEqual(ring?.caller.name, "Ringer")
        XCTAssertEqual(ring?.title, "Design crew")
    }

    func testRingDecodeToleratesUnknownKindAndRequiresCaller() {
        let voice = PulseGroupCallEngine.Ring.decode([
            "conversationId": "conv-1",
            "kind": "hologram",
            "caller": ["id": "u9"],
        ])
        XCTAssertEqual(voice?.kind, .voice, "unknown wire kind falls back to voice")
        XCTAssertEqual(voice?.title, "", "absent title stays empty (banner substitutes)")

        XCTAssertNil(PulseGroupCallEngine.Ring.decode([
            "kind": "voice",
            "caller": ["id": "u9"],
        ]), "no conversationId → no ring")
        XCTAssertNil(PulseGroupCallEngine.Ring.decode([
            "conversationId": "conv-1",
        ]), "no caller → no ring")
    }

    // wire: C→S builders (the relay's validated shapes)

    /// gcall:join { conversationId, kind, user: {id,name,color,avatar} } -
    /// the relay identity-gates user.id against the socket's registered user.
    func testJoinPayloadShape() {
        let payload = GroupCallWire.joinPayload(
            conversationId: "conv-1",
            kind: .video,
            userId: "me",
            userName: "Me",
            userColor: nil,
            userAvatar: nil,
        )
        XCTAssertEqual(payload["conversationId"] as? String, "conv-1")
        XCTAssertEqual(payload["kind"] as? String, "video")
        let user = payload["user"] as? [String: Any]
        XCTAssertEqual(user?["id"] as? String, "me")
        XCTAssertEqual(user?["name"] as? String, "Me")
        XCTAssertEqual(user?["color"] as? String, "emerald", "nil color falls back to the web default")
        XCTAssertEqual(user?["avatar"] as? String, "", "nil avatar degrades to the empty string")
    }

    /// gcall:offer / gcall:answer carry NO callId from the client - the
    /// relay injects the conversation call's id server-side.
    func testOfferAndAnswerPayloadsAreTargetedAndCallIdFree() {
        let offer = GroupCallWire.offerPayload(
            conversationId: "conv-1", from: "me", to: "peer", kind: .voice, sdp: "v=0",
        )
        XCTAssertEqual(offer["conversationId"] as? String, "conv-1")
        XCTAssertEqual(offer["from"] as? String, "me")
        XCTAssertEqual(offer["to"] as? String, "peer")
        XCTAssertEqual(offer["kind"] as? String, "voice")
        XCTAssertEqual(offer["sdp"] as? String, "v=0")
        XCTAssertNil(offer["callId"], "the relay injects callId - the client never sends one")

        let answer = GroupCallWire.answerPayload(
            conversationId: "conv-1", from: "me", to: "peer", sdp: "v=0",
        )
        XCTAssertEqual(answer["conversationId"] as? String, "conv-1")
        XCTAssertEqual(answer["from"] as? String, "me")
        XCTAssertEqual(answer["to"] as? String, "peer")
        XCTAssertEqual(answer["sdp"] as? String, "v=0")
        XCTAssertNil(answer["callId"])
        XCTAssertNil(answer["kind"], "the relay's gcall:answer handler reads no kind")
    }

    /// gcall:ice - the flat candidate triple; nil sdpMid/sdpMLineIndex ride
    /// as NSNull (JSON null), matching the relay's tolerant passthrough.
    func testIcePayloadCarriesCandidateTripleWithNullFallbacks() {
        let full = GroupCallWire.icePayload(
            conversationId: "conv-1", from: "me", to: "peer", kind: .voice,
            candidate: "candidate:1 1 UDP 1 1.1.1.1 5000 typ host",
            sdpMid: "0", sdpMLineIndex: 2,
        )
        XCTAssertEqual(full["candidate"] as? String, "candidate:1 1 UDP 1 1.1.1.1 5000 typ host")
        XCTAssertEqual(full["sdpMid"] as? String, "0")
        XCTAssertEqual(full["sdpMLineIndex"] as? Int, 2)

        let sparse = GroupCallWire.icePayload(
            conversationId: "conv-1", from: "me", to: "peer", kind: .voice,
            candidate: "candidate:2", sdpMid: nil, sdpMLineIndex: nil,
        )
        XCTAssertTrue(sparse["sdpMid"] is NSNull)
        XCTAssertTrue(sparse["sdpMLineIndex"] is NSNull)
    }

    /// gcall:leave { conversationId, from } - exactly two keys.
    func testLeavePayloadShape() {
        let payload = GroupCallWire.leavePayload(conversationId: "conv-1", from: "me")
        XCTAssertEqual(payload["conversationId"] as? String, "conv-1")
        XCTAssertEqual(payload["from"] as? String, "me")
        XCTAssertEqual(payload.count, 2)
    }

    // wire: the REST probe snapshot (GET /api/group-call-state)

    func testGroupCallStateSnapshotDecodesLiveShape() throws {
        let json = """
        {"callId":"gcall-abc","conversationId":"conv-1","kind":"video","hostId":"u1","startedAt":1700000000000,"members":[{"id":"u1","name":"Ada","color":"emerald","avatar":null},{"id":"u2","name":"Ben","color":"rose","avatar":"a.png"}]}
        """
        let snapshot = try JSONDecoder().decode(PulseGroupCallStateSnapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snapshot.callId, "gcall-abc")
        XCTAssertEqual(snapshot.kind, "video")
        XCTAssertEqual(snapshot.startedAtMs, 1_700_000_000_000)
        XCTAssertEqual(snapshot.members.map(\.id), ["u1", "u2"])
        XCTAssertEqual(snapshot.resolvedKind, .video)
    }

    /// The honest-empty shape ({ members: [] }) plus a seconds-form
    /// startedAt and a missing kind - every tolerance the route allows.
    func testGroupCallStateSnapshotToleratesEmptyAndPartialShapes() throws {
        let empty = try JSONDecoder().decode(PulseGroupCallStateSnapshot.self, from: Data(#"{"members":[]}"#.utf8))
        XCTAssertTrue(empty.members.isEmpty)
        XCTAssertNil(empty.callId)
        XCTAssertEqual(empty.resolvedKind, .voice, "absent kind falls back to voice")

        let partial = try JSONDecoder().decode(
            PulseGroupCallStateSnapshot.self,
            from: Data(#"{"callId":"g","kind":"voice","startedAt":1700000000.5,"members":[]}"#.utf8),
        )
        XCTAssertEqual(partial.startedAtMs, 1_700_000_000, "seconds-form startedAt coerces to ms-ish integer")
    }
}
