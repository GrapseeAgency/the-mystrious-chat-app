import XCTest
@testable import Pulse

/// W5-f — Wave 5 rooms PURE state machines (no device, no sockets, no
/// AVAudioEngine instantiation). Deterministic clocks: every time-driven
/// API takes nowMs, so tests never sleep.
///
/// Covered (spec §1.1–§1.3):
///   · VoicePcmChunker — 4000-sample blocks, seq from 1, proportional
///     partial flush, reset (WEB DEFECT FIX #1 proof).
///   · VoiceRoomModel playback playhead — stale/dup drop, max(now+85, nextAt)
///     chaining, roster-drop reset (WEB DEFECT FIX #2), roster replace +
///     speaking prune, mute force-stop, 2 s resync, honest status line.
///   · StageModel — role derivation, hand raise/reconcile, FIX #3 seat
///     re-arm on demotion + forced removal, two-tap end (2600 ms), claim
///     host eligibility, 2500 ms resync, ended teardown.
///   · SpaceModel — 80 ms throttle + clamp, 300 ms reconcile (FIX #4),
///     proximity 0.18, honest error after 6 attempts (FIX #5), full-state
///     replace self-heal.
///   · CaptionWindowAccumulator — 64000 flush, 16000 min tail, single
///     flight, clear-on-off.
///   · WavEncoder — the exact 44-byte RIFF header.
final class VoiceRoomMachineTests: XCTestCase {
    // ── helpers ──────────────────────────────────────────

    private func peer(_ id: String) -> WireVoicePeer {
        WireVoicePeer(id: id, name: id, username: nil, color: "emerald", joinedAt: nil)
    }

    private func person(_ id: String) -> WireStagePerson {
        WireStagePerson(id: id, name: id, color: "emerald")
    }

    private func stageState(
        host: WireStagePerson? = nil,
        speakers: [WireStagePerson] = [],
        hands: [WireStagePerson] = [],
        listeners: [WireStagePerson] = [],
        listenerCount: Int? = nil,
    ) -> WireStageState {
        WireStageState(
            conversationId: "conv",
            host: host,
            speakers: speakers,
            hands: hands,
            listeners: listeners,
            listenerCount: listenerCount,
        )
    }

    private func spaceState(_ entries: (String, Double, Double)...) -> WireSpaceState {
        WireSpaceState(
            conversationId: "conv",
            players: entries.map { WireSpacePlayer(id: $0.0, name: $0.0, color: "emerald", x: $0.1, y: $0.2) },
        )
    }

    /// base64(Int16LE) → samples (the chunker's wire encoding, decoded
    /// independently so the test is its own oracle).
    private func decodeSamples(_ base64: String) -> [Int16] {
        let data = Data(base64Encoded: base64)!
        return stride(from: 0, to: data.count, by: 2).map { offset in
            let low = UInt16(data[data.startIndex + offset])
            let high = UInt16(data[data.startIndex + offset + 1]) << 8
            return Int16(bitPattern: high | low)
        }
    }

    private func joinedVoice() -> VoiceRoomModel {
        var voice = VoiceRoomModel()
        voice.beginJoin(conversationId: "conv")
        voice.markJoined()
        return voice
    }

    // ════════════════════════════════════════════════════
    // VoicePcmChunker (VR-4 / FIX #1)
    // ════════════════════════════════════════════════════

    func testChunkerEmits4000SampleBlocksInSeqOrder() {
        var chunker = VoicePcmChunker()
        XCTAssertEqual(chunker.upcomingSeq, 1)

        let out = chunker.feed(Array(repeating: Int16(1), count: 8001))
        XCTAssertEqual(out.count, 2)
        XCTAssertEqual(out.map(\.seq), [1, 2])
        XCTAssertEqual(decodeSamples(out[0].base64), Array(repeating: Int16(1), count: 4000))
        XCTAssertEqual(decodeSamples(out[1].base64).count, 4000)
        XCTAssertEqual(chunker.pendingCount, 1)
        XCTAssertFalse(chunker.hasFullChunk)
    }

    func testChunkerWireBytesAreInt16LittleEndian() {
        var chunker = VoicePcmChunker()
        let out = chunker.feed([1, -2, 257])
        XCTAssertTrue(out.isEmpty) // below one block → buffered, not emitted
        let partial = chunker.flushPartial()!
        XCTAssertEqual(partial.seq, 1)
        XCTAssertEqual([UInt8](Data(base64Encoded: partial.base64)!), [0x01, 0x00, 0xFE, 0xFF, 0x01, 0x01])
    }

    func testChunkerPartialFlushIsProportionalAndTerminal() {
        var chunker = VoicePcmChunker()
        _ = chunker.feed(Array(repeating: Int16(7), count: 4500))
        let partial = chunker.flushPartial()
        XCTAssertNotNil(partial)
        XCTAssertEqual(partial?.seq, 2) // continues the burst's seq
        XCTAssertEqual(decodeSamples(partial!.base64), Array(repeating: Int16(7), count: 500))
        XCTAssertNil(chunker.flushPartial()) // a block boundary flushes nothing
    }

    func testChunkerResetRestartsSeqAtOne() {
        var chunker = VoicePcmChunker()
        _ = chunker.feed(Array(repeating: Int16(0), count: 4000))
        _ = chunker.feed(Array(repeating: Int16(0), count: 4000))
        chunker.reset()
        XCTAssertEqual(chunker.upcomingSeq, 1)
        XCTAssertEqual(chunker.pendingCount, 0)
        let fresh = chunker.feed(Array(repeating: Int16(3), count: 4000))
        XCTAssertEqual(fresh.map(\.seq), [1])
    }

    // ════════════════════════════════════════════════════
    // VoiceRoomModel playback (VR-5 / FIX #2)
    // ════════════════════════════════════════════════════

    func testPlaybackSchedulesAtNowPlusJitterAndChains() {
        var voice = VoiceRoomModel()
        XCTAssertEqual(voice.decide(seq: 1, userId: "p", nowMs: 1000), .schedule(atMs: 1085))
        XCTAssertEqual(voice.playback["p"]?.nextAtMs, 1335)
        // The next chunk chains behind the queue even arriving instantly.
        XCTAssertEqual(voice.decide(seq: 2, userId: "p", nowMs: 1001), .schedule(atMs: 1335))
        XCTAssertEqual(voice.playback["p"]?.nextAtMs, 1585)
    }

    func testPlaybackDropsStaleAndDuplicateSeqs() {
        var voice = VoiceRoomModel()
        _ = voice.decide(seq: 2, userId: "p", nowMs: 1000)
        XCTAssertEqual(voice.decide(seq: 2, userId: "p", nowMs: 1001), .drop) // dup
        XCTAssertEqual(voice.decide(seq: 1, userId: "p", nowMs: 1002), .drop) // stale
        XCTAssertEqual(voice.decide(seq: 3, userId: "p", nowMs: 1003), .schedule(atMs: 1335))
    }

    func testRosterDropResetsPeerPlaybackSoRejoinRestartsAudio() {
        var voice = joinedVoice()
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("a"), peer("b")]))
        XCTAssertEqual(voice.decide(seq: 5, userId: "a", nowMs: 0), .schedule(atMs: 85))
        XCTAssertNotNil(voice.playback["a"])

        // "a" vanishes (drop / forced removal) → bookkeeping cleared (FIX #2).
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("b")]))
        XCTAssertNil(voice.playback["a"])

        // "a" rejoins → its seq restarts at 1 and SCHEDULES (the web
        // blackholed this behind the stale lastSeq).
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("a"), peer("b")]))
        XCTAssertEqual(voice.decide(seq: 1, userId: "a", nowMs: 1000), .schedule(atMs: 1085))
    }

    // ════════════════════════════════════════════════════
    // VoiceRoomModel roster / ptt / mute / resync (VR-2/3/6/8)
    // ════════════════════════════════════════════════════

    func testRosterReplacesWholesaleAndPrunesSpeaking() {
        var voice = joinedVoice()
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("a"), peer("b")]))
        voice.applyPtt(userId: "a", on: true)
        voice.applyPtt(userId: "b", on: true)
        voice.applyPtt(userId: "ghost", on: true)
        XCTAssertEqual(voice.speakingIds, ["a", "b", "ghost"])

        // The next roster is the wholesale truth — gone peers lose the glow.
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("a")]))
        XCTAssertEqual(voice.speakingIds, ["a"])
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: []))
        XCTAssertTrue(voice.speakingIds.isEmpty)
        XCTAssertEqual(voice.roster, [])
    }

    func testMuteWhileTransmittingForceStopsPTT() {
        var voice = joinedVoice()
        voice.transmitting = true
        XCTAssertTrue(voice.setMicMuted(true)) // reports the force-stop…
        XCTAssertFalse(voice.transmitting) // …and stops it (VR-6)
        XCTAssertTrue(voice.micMuted)
        XCTAssertFalse(voice.setMicMuted(false))
        XCTAssertFalse(voice.micMuted)
    }

    func testMissingMeResyncIsRateLimitedToTwoSeconds() {
        var voice = VoiceRoomModel()
        XCTAssertTrue(voice.mayResync(nowMs: 0))
        voice.noteResync(nowMs: 0)
        XCTAssertFalse(voice.mayResync(nowMs: 1999))
        XCTAssertTrue(voice.mayResync(nowMs: 2000))
    }

    func testStatusLineIsHonestThroughTheLifecycle() {
        var voice = VoiceRoomModel()
        XCTAssertEqual(voice.statusLine, "Standby")
        voice.beginJoin(conversationId: "conv")
        XCTAssertEqual(voice.statusLine, "Connecting…")
        voice.markJoined()
        XCTAssertEqual(voice.statusLine, "Reconnecting…") // not connected yet
        voice.setConnected(true)
        XCTAssertEqual(voice.statusLine, "Syncing roster…") // roster still empty
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("a")]))
        XCTAssertEqual(voice.statusLine, "Connected")
        voice.fail("Microphone access is off")
        XCTAssertEqual(voice.statusLine, "Microphone access is off")
    }

    func testVoiceLeaveClearsState() {
        var voice = joinedVoice()
        voice.setConnected(true)
        voice.applyRoster(WireVoiceRoster(conversationId: "conv", peers: [peer("a")]))
        voice.transmitting = true
        voice.leave()
        XCTAssertEqual(voice.status, VoiceRoomModel.Status.idle)
        XCTAssertNil(voice.conversationId)
        XCTAssertFalse(voice.userJoinedVoice)
        XCTAssertTrue(voice.roster.isEmpty)
        XCTAssertFalse(voice.transmitting)
        XCTAssertTrue(voice.playback.isEmpty)
    }

    // ════════════════════════════════════════════════════
    // StageModel (ST-1…ST-8 / FIX #3)
    // ════════════════════════════════════════════════════

    func testStageRoleDerivation() {
        var stage = StageModel(myId: "me")
        XCTAssertEqual(stage.myRole, .audience) // nothing yet

        stage.beginJoin(conversationId: "conv", asHost: false)
        XCTAssertEqual(stage.myRole, .audience) // syncing, no state
        XCTAssertTrue(stage.syncing)

        stage.apply(state: stageState(listeners: [person("me")], listenerCount: 1))
        XCTAssertEqual(stage.myRole, .listener)
        XCTAssertFalse(stage.syncing)

        stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        stage.apply(state: stageState(host: person("host"), speakers: [person("me")]))
        XCTAssertEqual(stage.myRole, .speaker)
        XCTAssertTrue(stage.canSpeak)
        XCTAssertFalse(stage.canEnd)

        stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        stage.apply(state: stageState(host: person("me")))
        XCTAssertEqual(stage.myRole, .host)
        XCTAssertTrue(stage.isHost)
        XCTAssertTrue(stage.canEnd)
        XCTAssertFalse(stage.canRaiseHand) // host never queues a hand
    }

    func testStageHandRaiseReconcilesWithServerTruth() {
        var stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        XCTAssertTrue(stage.canRaiseHand)

        stage.setHandRaised(true)
        XCTAssertTrue(stage.handRaised) // optimistic

        // The server never confirmed (no hand in the state) → reconciled DOWN.
        stage.apply(state: stageState(listeners: [person("me")]))
        XCTAssertFalse(stage.handRaised)

        // Raised again, server confirms → FIFO queue position visible.
        stage.setHandRaised(true)
        stage.apply(state: stageState(hands: [person("other"), person("me")], listeners: [person("me")]))
        XCTAssertTrue(stage.handRaised)
        XCTAssertEqual(stage.handQueue.map(\.id), ["other", "me"])
        XCTAssertEqual(stage.listenerTotal, 1)

        // Approved → speaker: mic arms on PTT, hand leaves the queue.
        stage.apply(state: stageState(host: person("host"), speakers: [person("me")]))
        XCTAssertEqual(stage.myRole, .speaker)
        XCTAssertFalse(stage.canRaiseHand)
        XCTAssertTrue(stage.canSpeak)
    }

    func testFix3VoiceSeatReArmsOnDemotionAndForcedRemoval() {
        var stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        XCTAssertTrue(stage.needsVoiceSeat) // FIX #3 — the join claims a seat
        XCTAssertTrue(stage.consumeVoiceSeatRequest())
        XCTAssertFalse(stage.consumeVoiceSeatRequest())

        // Approved to speaker — the seat request stays consumed.
        stage.apply(state: stageState(host: person("host"), speakers: [person("me")]))
        XCTAssertFalse(stage.needsVoiceSeat)

        // Demoted (stage:mute force-removed the voice seat server-side) →
        // the seat request re-arms so the demoted member keeps HEARING.
        stage.apply(state: stageState(host: person("host"), listeners: [person("me")]))
        XCTAssertTrue(stage.needsVoiceSeat)
        XCTAssertTrue(stage.consumeVoiceSeatRequest())

        // A voice roster that dropped me while stage-joined → re-arm.
        stage.markVoiceSeatRemoved()
        XCTAssertTrue(stage.needsVoiceSeat)

        // Left the stage — no seat requests anymore.
        stage.leave()
        stage.markVoiceSeatRemoved()
        XCTAssertFalse(stage.consumeVoiceSeatRequest())
    }

    func testStageEndConfirmTwoTapWithin2600ms() {
        var confirm = StageEndConfirm()
        XCTAssertFalse(confirm.tap(nowMs: 1000))
        XCTAssertTrue(confirm.armed)
        XCTAssertTrue(confirm.tap(nowMs: 1000 + 2600)) // inclusive window
        XCTAssertFalse(confirm.armed)

        // Past the window the first tap decays — the next tap re-arms.
        XCTAssertFalse(confirm.tap(nowMs: 5000))
        XCTAssertFalse(confirm.tap(nowMs: 5000 + StageEndConfirm.resetMs + 1))
        XCTAssertTrue(confirm.tap(nowMs: 5000 + StageEndConfirm.resetMs + 11))

        confirm.reset()
        XCTAssertFalse(confirm.armed)
    }

    func testClaimHostEligibilityFollowsTheHostSeat() {
        var stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        stage.apply(state: stageState(listeners: [person("me")])) // host seat null
        XCTAssertTrue(stage.canClaimHost) // ST-7 — empty seat, no auto-promotion

        stage.apply(state: stageState(host: person("other"), listeners: [person("me")]))
        XCTAssertFalse(stage.canClaimHost)

        // The host left → the seat is claimable again.
        stage.apply(state: stageState(listeners: [person("me")]))
        XCTAssertTrue(stage.canClaimHost)

        stage.leave()
        XCTAssertFalse(stage.canClaimHost)
    }

    func testWasHostRememberedAcrossStatesForResync() {
        var stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        stage.apply(state: stageState(host: person("me")))
        XCTAssertTrue(stage.wasHost)
        // A later state without the host seat does NOT forget the role —
        // the ST-8 resync re-join rides asHost: wasHost.
        stage.apply(state: stageState(listeners: [person("me")]))
        XCTAssertTrue(stage.wasHost)
        stage.leave()
        XCTAssertFalse(stage.wasHost)
    }

    func testStageResyncIsRateLimitedTo2500ms() {
        var stage = StageModel(myId: "me")
        XCTAssertTrue(stage.mayResync(nowMs: 100))
        stage.noteResync(nowMs: 100)
        XCTAssertFalse(stage.mayResync(nowMs: 2599))
        XCTAssertTrue(stage.mayResync(nowMs: 2600))
    }

    func testStageEndedPerformsFullLocalTeardown() {
        var stage = StageModel(myId: "me")
        stage.beginJoin(conversationId: "conv", asHost: false)
        stage.setHandRaised(true)
        stage.apply(state: stageState(host: person("me")))
        stage.applyEnded()
        XCTAssertTrue(stage.ended)
        XCTAssertFalse(stage.joined)
        XCTAssertFalse(stage.syncing)
        XCTAssertNil(stage.state)
        XCTAssertFalse(stage.needsVoiceSeat)
        XCTAssertFalse(stage.wasHost)
        XCTAssertFalse(stage.handRaised)
    }

    // ════════════════════════════════════════════════════
    // SpaceModel (SP-3/SP-4/SP-5 / FIX #4 / FIX #5)
    // ════════════════════════════════════════════════════

    func testSpaceMoveClampsAndThrottlesAt80ms() {
        var space = SpaceModel(myId: "me")
        space.beginJoin()

        XCTAssertEqual(space.localMove(x: 0.5, y: 0.5, nowMs: 0), .emit(x: 0.5, y: 0.5))
        // Inside the 80 ms window → throttled, but the optimistic target
        // still moves AND clamps 0..1 (finger dragged past the border).
        XCTAssertEqual(space.localMove(x: 2, y: -1, nowMs: 40), .throttle)
        XCTAssertEqual(space.targetX, 1)
        XCTAssertEqual(space.targetY, 0)
        XCTAssertEqual(space.localMove(x: -0.5, y: 0.25, nowMs: 79), .throttle)
        XCTAssertEqual(space.localMove(x: 0.2, y: 0.3, nowMs: 80), .emit(x: 0.2, y: 0.3))
    }

    func testSpaceReconcileAdoptsServerPositionAfter300msIdle() {
        var space = SpaceModel(myId: "me")
        space.beginJoin()

        // No local move ever → the first server self-position adopts at once.
        XCTAssertEqual(space.apply(state: spaceState(("me", 0.1, 0.1), ("a", 0.8, 0.8)), nowMs: 0), .adopt(x: 0.1, y: 0.1))

        // The finger moves (optimistic target 0.9/0.9) and the server still
        // echoes the OLD position while fresh (< 300 ms) → keep the target.
        _ = space.localMove(x: 0.9, y: 0.9, nowMs: 1000)
        XCTAssertEqual(space.apply(state: spaceState(("me", 0.1, 0.1)), nowMs: 1100), .keep)
        XCTAssertEqual(space.targetX, 0.9)
        XCTAssertEqual(space.targetY, 0.9)

        // Finger idle ≥ 300 ms → the server self-position wins (FIX #4).
        XCTAssertEqual(space.apply(state: spaceState(("me", 0.1, 0.1)), nowMs: 1300), .adopt(x: 0.1, y: 0.1))
        XCTAssertEqual(space.targetX, 0.1)
        XCTAssertEqual(space.targetY, 0.1)
    }

    func testSpaceProximityIsEuclideanAt018AndExcludesSelf() {
        var space = SpaceModel(myId: "me")
        space.beginJoin()
        _ = space.apply(
            state: spaceState(
                ("me", 0.5, 0.5),
                ("close-x", 0.62, 0.5), // dx = 0.12 ≤ 0.18
                ("close-y", 0.5, 0.679), // dy = 0.179 ≤ 0.18
                ("far", 0.681, 0.5), // dx = 0.181 > 0.18
                ("diagonal", 0.6274, 0.6274), // dist ≈ 0.1801 > 0.18
            ),
            nowMs: 0,
        )
        XCTAssertEqual(
            Set(space.nearbyPlayers.map(\.id)),
            ["close-x", "close-y"],
        )
        // The anchor form used by the chip rail: the anchor itself never
        // appears (self excluded), and anchoring at a peer's position puts
        // ME in ITS nearby ring.
        XCTAssertTrue(space.nearby(x: 0.5, y: 0.5, excluding: "me").allSatisfy { $0.id != "me" })
        XCTAssertEqual(space.nearby(x: 0.5, y: 0.5, excluding: "close-x").map(\.id), ["me", "close-y"])
    }

    func testSpaceErrorIsReachableAfterSixFailedAttempts() {
        var space = SpaceModel(myId: "me")
        space.beginJoin()
        for _ in 1...5 {
            space.noteDisconnected()
        }
        XCTAssertEqual(space.status, .connecting) // still honest-connecting
        XCTAssertEqual(space.reconnectAttempts, 5)
        space.noteDisconnected() // the 6th
        XCTAssertEqual(space.status, .error) // FIX #5 — web spun forever here
        XCTAssertEqual(space.errorText, "Can't reach the room right now.")

        // A reconnect does NOT silently clear the error — the retry re-joins.
        space.noteConnected()
        XCTAssertEqual(space.status, .error)

        space.leave()
        space.beginJoin()
        space.noteConnected()
        XCTAssertEqual(space.status, .connected)

        // Disconnects while not joined never count as attempts.
        space.leave()
        space.noteDisconnected()
        XCTAssertEqual(space.reconnectAttempts, 0)
    }

    func testSpaceFullStateReplaceSelfHealsStalePlayers() {
        var space = SpaceModel(myId: "me")
        space.beginJoin()
        _ = space.apply(state: spaceState(("me", 0.1, 0.1), ("a", 0.2, 0.2), ("b", 0.3, 0.3)), nowMs: 0)
        XCTAssertEqual(space.players.map(\.id), ["me", "a", "b"])
        // The next state IS the truth — the stale "a" heals away (server
        // prunes 5-minute idlers; native mirrors by full replace).
        _ = space.apply(state: spaceState(("me", 0.1, 0.1), ("c", 0.4, 0.4)), nowMs: 1000)
        XCTAssertEqual(space.players.map(\.id), ["me", "c"])
        XCTAssertEqual(space.players[1].x, 0.4)
    }

    func testSpaceClamp01Helper() {
        XCTAssertEqual(SpaceModel.clamp01(-0.5), 0)
        XCTAssertEqual(SpaceModel.clamp01(0.42), 0.42)
        XCTAssertEqual(SpaceModel.clamp01(1.5), 1)
    }

    // ════════════════════════════════════════════════════
    // CaptionWindowAccumulator (VR-7)
    // ════════════════════════════════════════════════════

    func testCaptionWindowFlushesAt64000SamplesSingleFlight() {
        var acc = CaptionWindowAccumulator(enabled: true)
        XCTAssertNil(acc.feed(Array(repeating: Int16(1), count: 63_999)))
        let window = acc.feed(Array(repeating: Int16(2), count: 1))
        XCTAssertEqual(window?.count, 64_000) // 4 s @ 16 kHz
        XCTAssertEqual(window?.last, Int16(2)) // the newest sample closes the window
        XCTAssertTrue(acc.isBusy)

        // Single-flight: while the ASR call is out, samples KEEP accumulating.
        XCTAssertNil(acc.feed(Array(repeating: Int16(3), count: 64_000)))
        XCTAssertEqual(acc.pendingCount, 64_000)

        // The call settled → the very next sample completes the next window.
        acc.endFlush()
        let second = acc.feed([Int16(4)])
        XCTAssertEqual(second?.count, 64_000)
        XCTAssertEqual(second?.first, Int16(3))
        XCTAssertEqual(acc.pendingCount, 1)
    }

    func testCaptionTailRequires16000SamplesAndClears() {
        var acc = CaptionWindowAccumulator(enabled: true)
        _ = acc.feed(Array(repeating: Int16(1), count: 15_999))
        XCTAssertNil(acc.takeTail()) // below the ~1 s minimum → honest silence
        XCTAssertEqual(acc.pendingCount, 0)

        _ = acc.feed(Array(repeating: Int16(1), count: 16_000))
        let tail = acc.takeTail()
        XCTAssertEqual(tail?.count, 16_000)
        XCTAssertEqual(acc.pendingCount, 0) // tail is terminal for the burst
    }

    func testCaptionToggleClearsPendingAndDisablesFeeds() {
        var acc = CaptionWindowAccumulator(enabled: true)
        _ = acc.feed(Array(repeating: Int16(1), count: 30_000))
        acc.setEnabled(false) // clear-on-off
        XCTAssertEqual(acc.pendingCount, 0)
        XCTAssertFalse(acc.isBusy)
        XCTAssertNil(acc.feed([Int16(1)]))

        var off = CaptionWindowAccumulator(enabled: false)
        XCTAssertNil(off.feed(Array(repeating: Int16(1), count: 70_000)))
        XCTAssertEqual(off.pendingCount, 0)
    }

    // ════════════════════════════════════════════════════
    // WavEncoder (VR-7 — the exact 44-byte header)
    // ════════════════════════════════════════════════════

    func testWavHeaderIsByteExactForMono16k16bit() {
        let data = WavEncoder.wavData(samples: [1, -2])
        XCTAssertEqual(data.count, 48) // 44-byte header + 4 bytes of PCM
        XCTAssertEqual(WavEncoder.headerByteCount, 44)
        XCTAssertEqual(Array(data[0..<4]), Array("RIFF".utf8))
        XCTAssertEqual(Array(data[4..<8]), [40, 0, 0, 0]) // chunk size = 36 + data
        XCTAssertEqual(Array(data[8..<12]), Array("WAVE".utf8))
        XCTAssertEqual(Array(data[12..<16]), Array("fmt ".utf8))
        XCTAssertEqual(Array(data[16..<20]), [16, 0, 0, 0]) // fmt chunk size
        XCTAssertEqual(Array(data[20..<22]), [1, 0]) // PCM
        XCTAssertEqual(Array(data[22..<24]), [1, 0]) // mono
        XCTAssertEqual(Array(data[24..<28]), [128, 62, 0, 0]) // 16000 Hz LE
        XCTAssertEqual(Array(data[28..<32]), [0, 125, 0, 0]) // byteRate 32000 LE
        XCTAssertEqual(Array(data[32..<34]), [2, 0]) // block align
        XCTAssertEqual(Array(data[34..<36]), [16, 0]) // bits per sample
        XCTAssertEqual(Array(data[36..<40]), Array("data".utf8))
        XCTAssertEqual(Array(data[40..<44]), [4, 0, 0, 0]) // data size LE
        XCTAssertEqual(Array(data[44..<48]), [1, 0, 0xFE, 0xFF]) // Int16LE payload
    }

    func testWavEmptySamplesStillCarriesAWellFormedHeader() {
        let data = WavEncoder.wavData(samples: [])
        XCTAssertEqual(data.count, 44)
        XCTAssertEqual(Array(data[4..<8]), [36, 0, 0, 0])
        XCTAssertEqual(Array(data[40..<44]), [0, 0, 0, 0])
        XCTAssertFalse(WavEncoder.base64Wav(samples: []).isEmpty)
    }
}
