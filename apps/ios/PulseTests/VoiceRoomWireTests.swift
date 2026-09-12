import XCTest
@testable import Pulse

/// W5-f — Wave 5 voice rooms / stage / space WIRE tests.
///
/// Proves the wire, not the UI:
///   · PulseSocketEvents names byte-match the relay (14 C→S + 7 S→C,
///     mini-services/pulse-socket/index.ts + contracts.ts);
///   · the VoiceRoomWire payload builders produce the EXACT dictionaries
///     the relay validates (user {id,name,username,color}, asHost,
///     hand {user:{id},raised}, approve/mute {byUserId,targetUserId},
///     move {x,y});
///   · the Wire* DTOs decode tolerantly (missing fields → nil/defaults,
///     unknown keys ignored, listenerCount falls back to listeners.count,
///     malformed roster entries are dropped instead of poisoning the room).
/// Pure XCTest — no device, no sockets, no AVAudioEngine.
final class VoiceRoomWireTests: XCTestCase {
    // ── helpers ──────────────────────────────────────────

    /// Deep-equality for the builder dictionaries (Any payloads compare
    /// field-by-field through NSObject bridging, recursively for dicts).
    private func assertPayload(
        _ actual: [String: Any],
        equals expected: [String: Any],
        file: StaticString = #filePath,
        line: UInt = #line,
    ) {
        XCTAssertEqual(actual.count, expected.count, "payload key count", file: file, line: line)
        for (key, value) in expected {
            guard let actualValue = actual[key] else {
                XCTFail("missing key \(key)", file: file, line: line)
                continue
            }
            if let actualDict = actualValue as? [String: Any], let expectedDict = value as? [String: Any] {
                assertPayload(actualDict, equals: expectedDict, file: file, line: line)
            } else if let actualNS = actualValue as? NSObject, let expectedNS = value as? NSObject {
                XCTAssertEqual(actualNS, expectedNS, "key \(key)", file: file, line: line)
            } else {
                XCTFail("type mismatch for key \(key)", file: file, line: line)
            }
        }
    }

    // ── event names (the 21 wire strings) ────────────────

    func testClientToServerEventNamesMatchTheRelay() {
        XCTAssertEqual(PulseSocketEvents.clientToServer.map(\.rawValue), [
            "voice:join", "voice:leave", "voice:ptt", "voice:chunk", "voice:transcript",
            "stage:join", "stage:hand", "stage:approve", "stage:mute", "stage:end", "stage:leave",
            "space:join", "space:move", "space:leave",
        ])
    }

    func testServerToClientEventNamesMatchTheRelay() {
        XCTAssertEqual(PulseSocketEvents.serverToClient.map(\.rawValue), [
            "voice:roster", "voice:ptt", "voice:chunk", "voice:transcript",
            "stage:state", "stage:ended", "space:state",
        ])
    }

    // ── C→S payload builders (exact relay shapes) ────────

    func testVoiceJoinPayloadCarriesPublicSafeUserDict() {
        assertPayload(
            VoiceRoomWire.voiceJoin(conversationId: "c1", userId: "u1", name: "Ada", username: "ada", color: "rose"),
            equals: [
                "conversationId": "c1",
                "user": ["id": "u1", "name": "Ada", "username": "ada", "color": "rose"],
            ],
        )
    }

    func testUserDictFallsBackOnMissingUsernameAndColor() {
        assertPayload(
            VoiceRoomWire.user(id: "u1", name: "Ada", username: nil, color: nil),
            equals: ["id": "u1", "name": "Ada", "username": "", "color": "emerald"],
        )
    }

    func testVoiceLeavePttChunkTranscriptPayloadShapes() {
        assertPayload(
            VoiceRoomWire.voiceLeave(conversationId: "c1"),
            equals: ["conversationId": "c1"],
        )
        assertPayload(
            VoiceRoomWire.voicePtt(conversationId: "c1", userId: "u1", on: true),
            equals: ["conversationId": "c1", "userId": "u1", "on": true],
        )
        assertPayload(
            VoiceRoomWire.voiceChunk(conversationId: "c1", userId: "u1", seq: 7, data: "AAAA"),
            equals: ["conversationId": "c1", "userId": "u1", "seq": 7, "data": "AAAA"],
        )
        assertPayload(
            VoiceRoomWire.voiceTranscript(conversationId: "c1", userId: "u1", text: "hello"),
            equals: ["conversationId": "c1", "userId": "u1", "text": "hello"],
        )
    }

    func testStageJoinPayloadCarriesUserAndAsHost() {
        assertPayload(
            VoiceRoomWire.stageJoin(conversationId: "c1", userId: "u1", name: "Ada", username: nil, color: "rose", asHost: true),
            equals: [
                "conversationId": "c1",
                "user": ["id": "u1", "name": "Ada", "username": "", "color": "rose"],
                "asHost": true,
            ],
        )
    }

    func testStageHandUsesTheMinimalUserDict() {
        assertPayload(
            VoiceRoomWire.stageHand(conversationId: "c1", userId: "u1", raised: true),
            equals: ["conversationId": "c1", "user": ["id": "u1"], "raised": true],
        )
    }

    func testStageApproveMuteEndLeavePayloadShapes() {
        assertPayload(
            VoiceRoomWire.stageApprove(conversationId: "c1", byUserId: "host", targetUserId: "u1"),
            equals: ["conversationId": "c1", "byUserId": "host", "targetUserId": "u1"],
        )
        assertPayload(
            VoiceRoomWire.stageMute(conversationId: "c1", byUserId: "host", targetUserId: "u1"),
            equals: ["conversationId": "c1", "byUserId": "host", "targetUserId": "u1"],
        )
        assertPayload(
            VoiceRoomWire.stageEnd(conversationId: "c1", byUserId: "host"),
            equals: ["conversationId": "c1", "byUserId": "host"],
        )
        assertPayload(
            VoiceRoomWire.stageLeave(conversationId: "c1"),
            equals: ["conversationId": "c1"],
        )
    }

    func testSpaceJoinMoveLeavePayloadShapes() {
        assertPayload(
            VoiceRoomWire.spaceJoin(conversationId: "c1", userId: "u1", name: "Ada", username: "ada", color: "rose"),
            equals: [
                "conversationId": "c1",
                "user": ["id": "u1", "name": "Ada", "username": "ada", "color": "rose"],
            ],
        )
        assertPayload(
            VoiceRoomWire.spaceMove(conversationId: "c1", x: 0.25, y: 0.75),
            equals: ["conversationId": "c1", "x": 0.25, "y": 0.75],
        )
        assertPayload(
            VoiceRoomWire.spaceLeave(conversationId: "c1"),
            equals: ["conversationId": "c1"],
        )
    }

    // ── S→C tolerant decode ──────────────────────────────

    func testWireVoicePeerDecodesWithUnknownKeysAndMissingFields() throws {
        let full = try JSONDecoder().decode(
            WireVoicePeer.self,
            from: Data(#"{"id":"u1","name":"Ada","username":"ada","color":"rose","joinedAt":123.5,"someFutureField":42}"#.utf8),
        )
        XCTAssertEqual(full.id, "u1")
        XCTAssertEqual(full.name, "Ada")
        XCTAssertEqual(full.username, "ada")
        XCTAssertEqual(full.color, "rose")
        XCTAssertEqual(full.joinedAt, 123.5)

        // Missing fields degrade to nil — never a decode crash.
        let sparse = try JSONDecoder().decode(WireVoicePeer.self, from: Data(#"{"id":"u2"}"#.utf8))
        XCTAssertEqual(sparse.id, "u2")
        XCTAssertNil(sparse.name)
        XCTAssertNil(sparse.username)
        XCTAssertNil(sparse.color)
        XCTAssertNil(sparse.joinedAt)
    }

    func testWireRosterDecodeDropsMalformedEntriesAndKeepsGoodOnes() {
        let roster = WireVoiceRoster.decode(
            conversationId: "c1",
            rawPeers: [
                ["id": "u1", "name": "Ada", "username": "ada", "color": "rose"],
                ["name": "NoId"], // malformed — missing the required id
                ["id": 42], // malformed — id of the wrong type
                ["id": "u2"],
            ],
        )
        XCTAssertEqual(roster?.conversationId, "c1")
        XCTAssertEqual(roster?.peers?.map(\.id), ["u1", "u2"])
    }

    func testWireVoicePttDecodePrefersOnAndAcceptsLegacyActive() throws {
        let on = try JSONDecoder().decode(WireVoicePtt.self, from: Data(#"{"conversationId":"c1","userId":"u1","on":true}"#.utf8))
        XCTAssertTrue(on.active)
        let legacy = try JSONDecoder().decode(WireVoicePtt.self, from: Data(#"{"active":true}"#.utf8))
        XCTAssertTrue(legacy.active)
        let off = try JSONDecoder().decode(WireVoicePtt.self, from: Data(#"{"on":false}"#.utf8))
        XCTAssertFalse(off.active)
        let empty = try JSONDecoder().decode(WireVoicePtt.self, from: Data(#"{}"#.utf8))
        XCTAssertFalse(empty.active)
    }

    func testWireVoiceChunkAndTranscriptDecode() throws {
        let chunk = try JSONDecoder().decode(
            WireVoiceChunk.self,
            from: Data(#"{"conversationId":"c1","userId":"u1","seq":3,"data":"AAAA","future":null}"#.utf8),
        )
        XCTAssertEqual(chunk.seq, 3)
        XCTAssertEqual(chunk.data, "AAAA")

        let transcript = try JSONDecoder().decode(
            WireVoiceTranscriptEvent.self,
            from: Data(#"{"conversationId":"c1","userId":"u1","name":"Ada","color":"rose","text":"hi","at":1726000000000,"future":1}"#.utf8),
        )
        XCTAssertEqual(transcript.text, "hi")
        XCTAssertEqual(transcript.name, "Ada")
        XCTAssertEqual(transcript.at, 1_726_000_000_000)

        let result = try JSONDecoder().decode(
            WireVoiceTranscriptResult.self,
            from: Data(#"{"transcript":"hello there"}"#.utf8),
        )
        XCTAssertEqual(result.transcript, "hello there")
    }

    func testWireStageStateListenerCountFallsBackToListenersCount() throws {
        let full = try JSONDecoder().decode(
            WireStageState.self,
            from: Data(
                #"{"conversationId":"c1","host":{"id":"h","name":"Host","color":"amber"},"speakers":[{"id":"s1","name":"S","color":"emerald"}],"hands":[{"id":"h1"}],"listeners":[{"id":"l1"},{"id":"l2"}],"listenerCount":9,"future":1}"#
                    .utf8,
            ),
        )
        XCTAssertEqual(full.listenerTotal, 9) // the wire field wins when present
        XCTAssertEqual(full.speakerList.count, 1)
        XCTAssertEqual(full.handList.count, 1)
        XCTAssertEqual(full.listenerList.count, 2)

        // Old relays without listenerCount → the row count is the truth.
        let minimal = try JSONDecoder().decode(
            WireStageState.self,
            from: Data(#"{"listeners":[{"id":"l1"},{"id":"l2"}]}"#.utf8),
        )
        XCTAssertNil(minimal.host)
        XCTAssertTrue(minimal.speakerList.isEmpty)
        XCTAssertTrue(minimal.handList.isEmpty)
        XCTAssertEqual(minimal.listenerTotal, 2)
    }

    func testWireSpaceStateDecodeTolerance() throws {
        let state = try JSONDecoder().decode(
            WireSpaceState.self,
            from: Data(
                #"{"conversationId":"c1","players":[{"id":"p1","name":"Ada","color":"rose","x":0.25,"y":0.75,"future":1},{"id":"p2"}],"future":true}"#
                    .utf8,
            ),
        )
        XCTAssertEqual(state.players?.count, 2)
        XCTAssertEqual(state.players?.first?.x, 0.25)
        XCTAssertNil(state.players?[1].x) // missing coords → nil, the model clamps to centre
    }
}
