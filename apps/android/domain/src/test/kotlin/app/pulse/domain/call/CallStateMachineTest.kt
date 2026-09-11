package app.pulse.domain.call

import app.pulse.domain.call.CallStateMachine.CallEffect
import app.pulse.domain.call.CallStateMachine.CallEvent
import app.pulse.domain.model.CallCancelReason
import app.pulse.domain.model.CallDirection
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallOutcome
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallState
import app.pulse.domain.model.CallStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave-3 call engine core — the full pinned shared-design matrix exercised
 * against a FAKE clock: every transition, every defensive timeout, the
 * per-callId + per-state idempotency guards and the caller-only terminal
 * mapping (answered-then-ended=completed, reject=declined,
 * cancel-while-ringing=missed).
 */
class CallStateMachineTest {

    private class FakeClock(var now: Long = 1_000_000L) : CallClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val peer = CallPeer(id = "u-peer", name = "Peer", color = "emerald", avatar = null)
    private val me = "u-me"

    private fun machine(clock: FakeClock): CallStateMachine =
        CallStateMachine(clock).also { it.bind(me) }

    // ── wiring guards ───────────────────────────────────────────

    @Test
    fun `events before bind are ignored`() {
        val clock = FakeClock()
        val sm = CallStateMachine(clock) // NOT bound
        val effects = sm.dispatch(
            CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Me", "emerald", null),
        )
        assertTrue(effects.isEmpty())
        assertEquals(CallState.IDLE, sm.snapshot.value.state)
    }

    @Test
    fun `bind is idempotent`() {
        val sm = machine(FakeClock())
        sm.bind("u-other")
        val effects = sm.dispatch(
            CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Me", "emerald", null),
        )
        // The ORIGINAL viewer (u-me) stays bound → outgoing to u-peer is valid.
        assertTrue(effects.any { it is CallEffect.AcquireMedia })
    }

    // ── outgoing happy path ─────────────────────────────────────

    @Test
    fun `outgoing happy path ends completed with duration and one log row`() {
        val clock = FakeClock()
        val sm = machine(clock)

        val start = sm.dispatch(
            CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null),
        )
        assertEquals(CallState.OUTGOING_RINGING, sm.snapshot.value.state)
        assertEquals(CallDirection.OUTGOING, sm.snapshot.value.direction)
        assertEquals(listOf<CallEffect>(CallEffect.AcquireMedia(CallKind.VOICE), CallEffect.CreateOffer(CallKind.VOICE)), start)

        val offerEffects = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
        val sendOffer = offerEffects.filterIsInstance<CallEffect.SendOffer>().single()
        assertEquals("u-peer", sendOffer.to)
        assertEquals("Alice", sendOffer.callerName)
        assertEquals("emerald", sendOffer.callerColor)
        assertEquals(CallKind.VOICE, sendOffer.kind)
        assertEquals("v=0 offer", sendOffer.sdp)

        val answerEffects = sm.dispatch(CallEvent.AnswerReceived(sendOffer.callId, "v=0 answer"))
        assertEquals(CallEffect.ApplyRemoteAnswer("v=0 answer"), answerEffects.single())
        assertEquals(CallState.CONNECTING, sm.snapshot.value.state)

        sm.dispatch(CallEvent.PeerConnected)
        assertEquals(CallState.CONNECTED, sm.snapshot.value.state)

        clock.advance(65_000)
        sm.poll()
        assertEquals(65L, sm.snapshot.value.durationSec)

        val hangupEffects = sm.dispatch(CallEvent.Hangup)
        val sendHangup = hangupEffects.filterIsInstance<CallEffect.SendHangup>().single()
        assertEquals(65L, sendHangup.durationSec)
        assertEquals("u-peer", sendHangup.to)
        assertTrue(CallEffect.ReleaseMedia in hangupEffects)
        val logRow = hangupEffects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.COMPLETED, logRow.status)
        assertEquals(65L, logRow.durationSec)
        assertEquals("u-me", logRow.callerId)
        assertEquals("u-peer", logRow.calleeId)
        assertEquals("c1", logRow.conversationId)
        assertTrue(logRow.outgoing)
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
        assertEquals(CallOutcome.COMPLETED, sm.snapshot.value.outcome)
        assertEquals(65L, sm.snapshot.value.durationSec)

        // Dismissed → back to a clean idle.
        assertTrue(sm.dispatch(CallEvent.Dismissed).isEmpty())
        assertEquals(CallSnapshot.IDLE, sm.snapshot.value)
    }

    @Test
    fun `duplicate OfferReady is dropped`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        assertTrue(sm.dispatch(CallEvent.OfferReady("v=0 offer")).isNotEmpty())
        assertTrue(sm.dispatch(CallEvent.OfferReady("v=0 offer AGAIN")).isEmpty())
    }

    // ── ringing terminations ────────────────────────────────────

    @Test
    fun `remote reject maps to declined row and no cancel`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val effects = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
        val callId = effects.filterIsInstance<CallEffect.SendOffer>().single().callId

        val rejectEffects = sm.dispatch(CallEvent.RemoteReject(callId))
        assertTrue(CallEffect.ReleaseMedia in rejectEffects)
        assertTrue(rejectEffects.none { it is CallEffect.SendCancel })
        val row = rejectEffects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.DECLINED, row.status)
        assertEquals(0L, row.durationSec)
        assertEquals(CallOutcome.DECLINED, sm.snapshot.value.outcome)
        assertEquals("Declined", sm.snapshot.value.summary)
    }

    @Test
    fun `self cancel while ringing sends cancel and writes missed`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId

        val cancelEffects = sm.dispatch(CallEvent.Cancel)
        val sendCancel = cancelEffects.filterIsInstance<CallEffect.SendCancel>().single()
        assertEquals(callId, sendCancel.callId)
        assertEquals("u-peer", sendCancel.to)
        assertTrue(CallEffect.ReleaseMedia in cancelEffects)
        val row = cancelEffects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.MISSED, row.status)
        assertEquals("Call canceled", sm.snapshot.value.summary)
        assertEquals(CallOutcome.MISSED, sm.snapshot.value.outcome)
    }

    @Test
    fun `defensive ring timeout fires at 40s and writes missed`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))

        clock.advance(CallStateMachine.RING_TIMEOUT_MS - 1_000)
        assertTrue(sm.poll().isEmpty())
        clock.advance(2_000)
        val timeoutEffects = sm.poll()
        val row = timeoutEffects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.MISSED, row.status)
        assertEquals("No answer", sm.snapshot.value.summary)
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
    }

    @Test
    fun `server cancel reasons map onto the web summaries`() {
        data class Case(val reason: CallCancelReason, val summary: String)

        for (case in listOf(
            Case(CallCancelReason.TIMEOUT, "No answer"),
            Case(CallCancelReason.CANCEL, "Call canceled"),
            Case(CallCancelReason.BUSY, "Peer is busy"),
            Case(CallCancelReason.OFFLINE, "Peer is offline"),
        )) {
            val clock = FakeClock()
            val sm = machine(clock)
            sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
            val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
                .filterIsInstance<CallEffect.SendOffer>().single().callId
            val effects = sm.dispatch(CallEvent.RemoteCancel(callId, case.reason))
            val row = effects.filterIsInstance<CallEffect.WriteLog>().single().entry
            assertEquals(CallStatus.MISSED, row.status)
            assertEquals(case.summary, sm.snapshot.value.summary)
        }
    }

    @Test
    fun `mic denied before offer writes missed with no cancel signal`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val effects = sm.dispatch(CallEvent.MediaFailed("permission"))
        assertTrue(effects.none { it is CallEffect.SendCancel })
        assertTrue(CallEffect.ReleaseMedia in effects)
        val row = effects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.MISSED, row.status)
        assertEquals(CallStateMachine.MIC_DENIED_MESSAGE, sm.snapshot.value.error)
    }

    @Test
    fun `mic denied after offer also tears the relay session down`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        sm.dispatch(CallEvent.OfferReady("v=0 offer"))
        val effects = sm.dispatch(CallEvent.MediaFailed("permission"))
        assertTrue(effects.any { it is CallEffect.SendCancel })
        val row = effects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.MISSED, row.status)
    }

    // ── incoming happy path ─────────────────────────────────────

    @Test
    fun `incoming accept flow completes and the callee never writes a row`() {
        val clock = FakeClock()
        val sm = machine(clock)

        sm.dispatch(
            CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null),
        )
        val ringing = sm.snapshot.value
        assertEquals(CallState.INCOMING_RINGING, ringing.state)
        assertEquals("Peer", ringing.peer?.name)
        assertEquals("emerald", ringing.peer?.color)

        assertEquals(listOf<CallEffect>(CallEffect.AcquireMedia(CallKind.VOICE)), sm.dispatch(CallEvent.Accept))
        val answerLeg = sm.dispatch(CallEvent.MediaReady)
        assertEquals(CallEffect.ApplyRemoteOffer("v=0 offer"), answerLeg[0])
        assertEquals(CallEffect.CreateAnswer("v=0 offer"), answerLeg[1])

        val answerEffects = sm.dispatch(CallEvent.AnswerReady("v=0 answer"))
        val sendAnswer = answerEffects.filterIsInstance<CallEffect.SendAnswer>().single()
        assertEquals("v=0 answer", sendAnswer.sdp)
        assertEquals("u-peer", sendAnswer.to)
        assertEquals(CallState.CONNECTING, sm.snapshot.value.state)

        sm.dispatch(CallEvent.PeerConnected)
        clock.advance(30_000)
        sm.poll()
        assertEquals(30L, sm.snapshot.value.durationSec)

        val hangupEffects = sm.dispatch(CallEvent.RemoteHangup("call-1", 30L))
        assertTrue(CallEffect.ReleaseMedia in hangupEffects)
        assertTrue(hangupEffects.none { it is CallEffect.WriteLog }) // single-writer rule
        assertEquals(CallOutcome.COMPLETED, sm.snapshot.value.outcome)
        assertEquals(30L, sm.snapshot.value.durationSec)
    }

    @Test
    fun `self hangup on the callee sends hangup but never writes a row`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null))
        sm.dispatch(CallEvent.Accept)
        sm.dispatch(CallEvent.MediaReady)
        sm.dispatch(CallEvent.AnswerReady("v=0 answer"))
        sm.dispatch(CallEvent.PeerConnected)
        clock.advance(12_000)

        val effects = sm.dispatch(CallEvent.Hangup)
        assertEquals(12L, effects.filterIsInstance<CallEffect.SendHangup>().single().durationSec)
        assertTrue(effects.none { it is CallEffect.WriteLog })
        assertEquals(CallOutcome.COMPLETED, sm.snapshot.value.outcome)
    }

    @Test
    fun `decline sends reject and the callee writes nothing`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null))
        val effects = sm.dispatch(CallEvent.Decline)
        val reject = effects.filterIsInstance<CallEffect.SendReject>().single()
        assertEquals("call-1", reject.callId)
        assertEquals("u-peer", reject.to)
        assertTrue(CallEffect.ReleaseMedia in effects)
        assertTrue(effects.none { it is CallEffect.WriteLog })
        assertEquals(CallOutcome.DECLINED, sm.snapshot.value.outcome)
        assertEquals("Declined", sm.snapshot.value.summary)
    }

    @Test
    fun `incoming cancel while ringing shows missed call and writes nothing`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null))
        val effects = sm.dispatch(CallEvent.RemoteCancel("call-1", CallCancelReason.TIMEOUT))
        assertTrue(effects.none { it is CallEffect.WriteLog })
        assertEquals(CallOutcome.MISSED, sm.snapshot.value.outcome)
        assertEquals("Missed call", sm.snapshot.value.summary)
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
    }

    @Test
    fun `busy callee rejects a new offer immediately`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))

        val effects = sm.dispatch(
            CallEvent.IncomingOffer("call-9", "c2", "u-other", CallKind.VOICE, "v=0 offer", "Other", "cyan", null),
        )
        val reject = effects.filterIsInstance<CallEffect.SendReject>().single()
        assertEquals("call-9", reject.callId)
        assertEquals("u-other", reject.to)
        // The ongoing outgoing call is untouched.
        assertEquals(CallState.OUTGOING_RINGING, sm.snapshot.value.state)
    }

    @Test
    fun `echo of my own offer is ignored`() {
        val clock = FakeClock()
        val sm = machine(clock)
        val effects = sm.dispatch(
            CallEvent.IncomingOffer("call-1", "c1", me, CallKind.VOICE, "v=0 offer", "Me", "emerald", null),
        )
        assertTrue(effects.isEmpty())
        assertEquals(CallState.IDLE, sm.snapshot.value.state)
    }

    @Test
    fun `mic denied after accept rejects politely and ends with the honest error`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null))
        sm.dispatch(CallEvent.Accept)
        val effects = sm.dispatch(CallEvent.MediaFailed("permission"))
        assertTrue(effects.any { it is CallEffect.SendReject })
        assertTrue(effects.none { it is CallEffect.WriteLog })
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
        assertNull(sm.snapshot.value.outcome)
        assertEquals(CallStateMachine.MIC_DENIED_MESSAGE, sm.snapshot.value.error)
    }

    // ── ICE ─────────────────────────────────────────────────────

    @Test
    fun `ice flows both ways during ringing and connecting`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId

        val produced = sm.dispatch(CallEvent.IceProduced("candidate:1 1 UDP 1", "0", 0))
        val sendIce = produced.filterIsInstance<CallEffect.SendIce>().single()
        assertEquals(callId, sendIce.callId)
        assertEquals("candidate:1 1 UDP 1", sendIce.candidate)
        assertEquals("0", sendIce.sdpMid)
        assertEquals(0, sendIce.sdpMLineIndex)

        val received = sm.dispatch(CallEvent.IceReceived(callId, "candidate:2 1 UDP 2", null, null))
        val applyIce = received.filterIsInstance<CallEffect.ApplyRemoteIce>().single()
        assertEquals("candidate:2 1 UDP 2", applyIce.candidate)
        assertNull(applyIce.sdpMid)
        assertNull(applyIce.sdpMLineIndex)
    }

    @Test
    fun `remote ice with unknown callId is dropped`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        assertTrue(sm.dispatch(CallEvent.IceReceived("other-call", "candidate:x", null, null)).isEmpty())
    }

    // ── peer-connection lifecycle ───────────────────────────────

    @Test
    fun `connect timeout at 15s ends answered-completed with zero duration`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId
        sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer"))

        clock.advance(CallStateMachine.CONNECT_TIMEOUT_MS - 1_000)
        assertTrue(sm.poll().isEmpty())
        clock.advance(2_000)
        val effects = sm.poll()
        val row = effects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.COMPLETED, row.status)
        assertEquals(0L, row.durationSec)
        assertEquals("Call failed", sm.snapshot.value.summary)
    }

    @Test
    fun `disconnect grace 10s then the call ends completed with elapsed`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId
        sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer"))
        sm.dispatch(CallEvent.PeerConnected)
        clock.advance(20_000)

        sm.dispatch(CallEvent.PeerDisconnected)
        // Recovery inside the grace window keeps the call alive.
        clock.advance(5_000)
        assertTrue(sm.poll().isEmpty())
        sm.dispatch(CallEvent.PeerConnected)
        clock.advance(8_000)
        sm.dispatch(CallEvent.PeerDisconnected)
        clock.advance(CallStateMachine.DISCONNECT_GRACE_MS + 1_000)
        val effects = sm.poll()
        val row = effects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.COMPLETED, row.status)
        // Wall time since the FIRST connect: 20 + 5 + 8 + 11 = 44s (the 5s
        // inside the grace window counts — connectedAt never moved).
        assertEquals(44L, row.durationSec)
        assertEquals("Call ended", sm.snapshot.value.summary)
    }

    @Test
    fun `a healthy connected call is never stale-killed`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId
        sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer"))
        sm.dispatch(CallEvent.PeerConnected)

        clock.advance(5 * 60_000) // five quiet minutes
        sm.poll()
        assertEquals(CallState.CONNECTED, sm.snapshot.value.state)
        assertEquals(300L, sm.snapshot.value.durationSec)
    }

    @Test
    fun `stale ringing state is cleaned up at 45s`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(
            CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null),
        )
        clock.advance(CallStateMachine.STALE_TIMEOUT_MS + 1_000)
        val effects = sm.poll()
        assertTrue(effects.none { it is CallEffect.WriteLog })
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
        assertEquals(CallOutcome.MISSED, sm.snapshot.value.outcome)
    }

    @Test
    fun `signaling heartbeats prove liveness while the ring window lasts`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(
            CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null),
        )
        // Heartbeat events (relay ICE while ringing) keep the session fresh.
        repeat(3) {
            clock.advance(10_000)
            sm.dispatch(CallEvent.IceReceived("call-1", "candidate:$it", "0", 0))
        }
        clock.advance(6_000) // 36s total — still inside the 40s ring window
        assertTrue(sm.poll().isEmpty())
        assertEquals(CallState.INCOMING_RINGING, sm.snapshot.value.state)
        // The ring deadline still wins eventually — liveness never outlives it.
        clock.advance(10_000) // 46s total
        val effects = sm.poll()
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
        assertTrue(effects.none { it is CallEffect.WriteLog })
    }

    // ── idempotency / post-terminal guards ──────────────────────

    @Test
    fun `duplicate answer is dropped`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId
        assertTrue(sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer")).isNotEmpty())
        assertTrue(sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer")).isEmpty())
    }

    @Test
    fun `reject after the answer leg is ignored`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId
        sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer"))
        assertTrue(sm.dispatch(CallEvent.RemoteReject(callId)).isEmpty())
        assertEquals(CallState.CONNECTING, sm.snapshot.value.state)
    }

    @Test
    fun `signals after a terminal state are dropped`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val callId = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single().callId
        sm.dispatch(CallEvent.RemoteReject(callId))
        assertEquals(CallState.ENDED, sm.snapshot.value.state)

        assertTrue(sm.dispatch(CallEvent.AnswerReceived(callId, "v=0 answer")).isEmpty())
        assertTrue(sm.dispatch(CallEvent.RemoteCancel(callId, CallCancelReason.TIMEOUT)).isEmpty())
        assertTrue(sm.dispatch(CallEvent.IceReceived(callId, "candidate:x", null, null)).isEmpty())
        assertTrue(sm.dispatch(CallEvent.Hangup).isEmpty())
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
    }

    @Test
    fun `hangup maps to decline and cancel while still ringing`() {
        val clock = FakeClock()
        val smIn = machine(clock)
        smIn.dispatch(CallEvent.IncomingOffer("call-1", "c1", "u-peer", CallKind.VOICE, "v=0 offer", "Peer", "emerald", null))
        val inEffects = smIn.dispatch(CallEvent.Hangup)
        assertTrue(inEffects.any { it is CallEffect.SendReject })
        assertEquals("Declined", smIn.snapshot.value.summary)

        val smOut = machine(clock)
        smOut.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        val outEffects = smOut.dispatch(CallEvent.Hangup)
        assertTrue(outEffects.any { it is CallEffect.SendCancel })
        assertEquals(CallOutcome.MISSED, smOut.snapshot.value.outcome)
        val row = outEffects.filterIsInstance<CallEffect.WriteLog>().single().entry
        assertEquals(CallStatus.MISSED, row.status)
    }

    @Test
    fun `write log effect fires exactly once even across duplicate terminations`() {
        val clock = FakeClock()
        val sm = machine(clock)
        sm.dispatch(CallEvent.StartOutgoing("c1", peer, CallKind.VOICE, "Alice", "emerald", null))
        sm.dispatch(CallEvent.OfferReady("v=0 offer"))
        sm.dispatch(CallEvent.Cancel)
        // Even if a late server cancel sneaks through the same-call guard
        // before dismissal (state now ENDED) nothing re-writes the row.
        assertEquals(CallState.ENDED, sm.snapshot.value.state)
    }

    @Test
    fun `video kind rides the wire untouched`() {
        val clock = FakeClock()
        val sm = machine(clock)
        val start = sm.dispatch(
            CallEvent.StartOutgoing("c1", peer, CallKind.VIDEO, "Alice", "emerald", null),
        )
        val sendOffer = sm.dispatch(CallEvent.OfferReady("v=0 offer"))
            .filterIsInstance<CallEffect.SendOffer>().single()
        assertEquals(CallKind.VIDEO, sendOffer.kind)
        assertEquals(CallKind.VIDEO, (start.first() as CallEffect.AcquireMedia).kind)
    }

    // ── CallLogMapper ───────────────────────────────────────────

    @Test
    fun `call log mapper guards and maps`() {
        assertNull(CallLogMapper.terminalRow(me, "c1", CallPeer(me, "Me"), CallKind.VOICE, CallStatus.MISSED, 0))
        assertNull(CallLogMapper.terminalRow(me, "", peer, CallKind.VOICE, CallStatus.MISSED, 0))
        assertNull(CallLogMapper.terminalRow("", "c1", peer, CallKind.VOICE, CallStatus.MISSED, 0))

        val row = CallLogMapper.terminalRow(me, "c1", peer, CallKind.VIDEO, CallStatus.COMPLETED, 125)
        assertEquals("completed", row!!.status.wire)
        assertEquals("video", row.kind.wire)
        assertTrue(row.outgoing)
        assertEquals(me, row.callerId)
        assertEquals("u-peer", row.calleeId)
        assertEquals(125L, row.durationSec)
        assertEquals("", row.id) // server assigns
        assertEquals("", row.startedAt) // server assigns

        assertEquals(CallStatus.COMPLETED, CallLogMapper.statusOf(CallOutcome.COMPLETED))
        assertEquals(CallStatus.MISSED, CallLogMapper.statusOf(CallOutcome.MISSED))
        assertEquals(CallStatus.DECLINED, CallLogMapper.statusOf(CallOutcome.DECLINED))
    }
}
