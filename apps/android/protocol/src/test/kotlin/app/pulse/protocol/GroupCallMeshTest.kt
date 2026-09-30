package app.pulse.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R8 Task 3-c - the GROUP call mesh kernels, pinned web-verbatim against
 * src/components/chat/group-call-overlay.tsx (useGroupCallSession):
 * offer-direction rule, roster sync plan, ICE queue gate, duration format,
 * leave summary copy, ring/ongoing banner copy + gates, and the wire-perfect
 * C→S payload builders.
 */
class GroupCallMeshTest {

    // MESH RULE - deterministic lower-id-offers (:287)

    @Test
    fun `offer direction - the lexicographically smaller id offers`() {
        assertTrue(GroupCallMesh.shouldOffer("alice", "bob"))
        assertFalse(GroupCallMesh.shouldOffer("bob", "alice"))
    }

    @Test
    fun `offer direction - both sides of a pair agree exactly once`() {
        // For every unordered pair exactly one direction offers - glare-free.
        val ids = listOf("u1", "u2", "u10", "zz", "aa")
        for (a in ids) for (b in ids) {
            if (a == b) continue
            assertTrue(GroupCallMesh.shouldOffer(a, b) != GroupCallMesh.shouldOffer(b, a), "pair $a/$b")
        }
    }

    @Test
    fun `offer direction - string sort matches web JS lexicographic order`() {
        // JS string comparison: "10" < "2" (character-wise) - Kotlin `<` on
        // String is the same lexicographic rule, so ids like these still agree.
        assertTrue(GroupCallMesh.shouldOffer("10", "2"))
        assertFalse(GroupCallMesh.shouldOffer("2", "10"))
    }

    // roster sync plan - applyRoster (:252-312)

    @Test
    fun `roster plan - joiner offers to every smaller id it has no pc for`() {
        // Join order b, c, a with me="a": the JOIN ORDER never decides direction
        // - the id sort does, and "a" sorts below "b"/"c" → I offer to BOTH.
        val plan = GroupCallMesh.planRoster(
            meId = "a",
            rosterIdsJoinOrdered = listOf("b", "c", "a"),
            existingPeerIds = emptySet(),
        )
        assertEquals(emptyList<String>(), plan.departedPeerIds)
        assertEquals(listOf("b", "c"), plan.offerToPeerIds)
    }

    @Test
    fun `roster plan - higher-id joiner offers to nobody and waits`() {
        val plan = GroupCallMesh.planRoster(
            meId = "zoe",
            rosterIdsJoinOrdered = listOf("alice", "bob", "zoe"),
            existingPeerIds = emptySet(),
        )
        assertEquals(emptyList<String>(), plan.departedPeerIds)
        assertEquals(emptyList<String>(), plan.offerToPeerIds)
    }

    @Test
    fun `roster plan - departed peers close, kept peers never re-offer`() {
        val plan = GroupCallMesh.planRoster(
            meId = "a",
            rosterIdsJoinOrdered = listOf("a", "b"), // c left
            existingPeerIds = setOf("b", "c"),
        )
        assertEquals(listOf("c"), plan.departedPeerIds)
        assertEquals(emptyList<String>(), plan.offerToPeerIds) // b already has a pc
    }

    @Test
    fun `roster plan - self and duplicates are ignored`() {
        val plan = GroupCallMesh.planRoster(
            meId = "a",
            rosterIdsJoinOrdered = listOf("a", "b", "b", "a"),
            existingPeerIds = emptySet(),
        )
        assertEquals(listOf("b"), plan.offerToPeerIds)
    }

    // ICE queue gate (:555-578)

    @Test
    fun `ice - applies immediately only with pc AND remote description`() {
        assertTrue(GroupCallMesh.applyIceNow(pcExists = true, remoteDescriptionSet = true))
        assertFalse(GroupCallMesh.applyIceNow(pcExists = true, remoteDescriptionSet = false))
        assertFalse(GroupCallMesh.applyIceNow(pcExists = false, remoteDescriptionSet = true))
        assertFalse(GroupCallMesh.applyIceNow(pcExists = false, remoteDescriptionSet = false))
    }

    // duration + summaries (formatCallDuration :97, leaveCall :389-392)

    @Test
    fun `duration - M-SS below an hour, H-MM-SS above`() {
        assertEquals("0:00", GroupCallMesh.formatDuration(0))
        assertEquals("0:07", GroupCallMesh.formatDuration(7))
        assertEquals("1:05", GroupCallMesh.formatDuration(65))
        assertEquals("59:59", GroupCallMesh.formatDuration(3599))
        assertEquals("1:00:00", GroupCallMesh.formatDuration(3600))
        assertEquals("2:03:04", GroupCallMesh.formatDuration(2 * 3600 + 3 * 60 + 4))
        assertEquals("0:00", GroupCallMesh.formatDuration(-5))
    }

    @Test
    fun `leave summary - one-second rule with web copy`() {
        assertEquals("You left", GroupCallMesh.leaveSummary(0))
        assertEquals("You left", GroupCallMesh.leaveSummary(-1))
        assertEquals("You left · 0:01", GroupCallMesh.leaveSummary(1))
        assertEquals("You left · 12:34", GroupCallMesh.leaveSummary(754))
    }

    // honest copy constants (web :354/:595)

    @Test
    fun `copy - mic denied and full-call strings are web-verbatim`() {
        assertEquals("That call is full (8 max)", GroupCallMesh.FULL_ERROR)
        assertEquals(
            "Microphone access is needed for calls. Check the browser permissions and try again.",
            GroupCallMesh.MIC_DENIED_ERROR,
        )
        assertEquals(8, GroupCallEvents.MAX_PARTICIPANTS)
    }

    // banners (:442-465 ring, :940-975 ongoing)

    @Test
    fun `ring - title prefers the conversation title with web suffix`() {
        assertEquals("Design crew · group call", GroupCallMesh.ringTitle("Design crew", "Ada"))
        assertEquals("Group call from Ada", GroupCallMesh.ringTitle("", "Ada"))
    }

    @Test
    fun `ring - subtitle names the kind and starter`() {
        assertEquals("Video call · started by Ada", GroupCallMesh.ringSubtitle("video", "Ada"))
        assertEquals("Voice call · started by Ada", GroupCallMesh.ringSubtitle("voice", "Ada"))
        assertEquals("Voice call · started by Someone", GroupCallMesh.ringSubtitle("", ""))
    }

    @Test
    fun `ring gate - idle outsiders only, never the caller's own echo`() {
        assertTrue(GroupCallMesh.shouldShowRing(sessionIdle = true, fromSelf = false))
        assertFalse(GroupCallMesh.shouldShowRing(sessionIdle = false, fromSelf = false))
        assertFalse(GroupCallMesh.shouldShowRing(sessionIdle = true, fromSelf = true))
    }

    @Test
    fun `ongoing - banner headline and first-3 first-names subtitle`() {
        assertEquals("Ongoing group call · 2 in call", GroupCallMesh.ongoingTitle(2))
        val members = listOf(
            GroupCallMemberDto(id = "1", name = "Ada Lovelace"),
            GroupCallMemberDto(id = "2", name = "Grace Hopper"),
            GroupCallMemberDto(id = "3", name = "Alan Turing"),
            GroupCallMemberDto(id = "4", name = "Edsger Dijkstra"),
        )
        assertEquals("Ada, Grace, Alan", GroupCallMesh.ongoingNames(members))
    }

    @Test
    fun `state routing - member syncs, idle outsider gets the banner, else ignore`() {
        assertTrue(GroupCallMesh.shouldSyncRoster(iAmMember = true))
        assertFalse(GroupCallMesh.shouldSyncRoster(iAmMember = false))
        assertTrue(GroupCallMesh.shouldShowOngoing(iAmMember = false, sessionIdle = true, notJoinedElsewhere = true))
        assertFalse(GroupCallMesh.shouldShowOngoing(iAmMember = true, sessionIdle = true, notJoinedElsewhere = true))
        assertFalse(GroupCallMesh.shouldShowOngoing(iAmMember = false, sessionIdle = false, notJoinedElsewhere = true))
        assertFalse(GroupCallMesh.shouldShowOngoing(iAmMember = false, sessionIdle = true, notJoinedElsewhere = false))
    }

    // C→S wire builders (exact relay expectations)

    @Test
    fun `join payload - kind and user ride top-level like the relay reads them`() {
        val payload: JsonObject = groupCallJoinPayload(
            conversationId = "c1",
            kind = "video",
            user = GroupCallMemberDto(id = "u1", name = "Ada", color = "emerald", avatar = null),
        ).jsonObject
        assertEquals("c1", payload["conversationId"]!!.jsonPrimitive.content)
        assertEquals("video", payload["kind"]!!.jsonPrimitive.content)
        val user = payload["user"]!!.jsonObject
        assertEquals("u1", user["id"]!!.jsonPrimitive.content)
        assertEquals("Ada", user["name"]!!.jsonPrimitive.content)
        assertEquals("emerald", user["color"]!!.jsonPrimitive.content)
        assertEquals("", user["avatar"]!!.jsonPrimitive.content) // null → "" (relay tolerates)
    }

    @Test
    fun `offer answer ice payloads - exact field sets`() {
        val offer = GroupCallOfferDto(
            conversationId = "c1", callId = "g1", from = "a", to = "b", kind = "voice", sdp = "v=0",
        ).toJsonObject().jsonObject
        assertEquals("g1", offer["callId"]!!.jsonPrimitive.content)
        assertEquals("b", offer["to"]!!.jsonPrimitive.content)
        assertEquals("v=0", offer["sdp"]!!.jsonPrimitive.content)

        val answer = GroupCallAnswerDto(conversationId = "c1", callId = "g1", from = "b", to = "a", sdp = "v=1")
            .toJsonObject().jsonObject
        assertEquals("v=1", answer["sdp"]!!.jsonPrimitive.content)

        val ice = GroupCallIceDto(
            conversationId = "c1", callId = "g1", from = "a", to = "b",
            candidate = "candidate:1", sdpMid = "0", sdpMLineIndex = 0,
        ).toJsonObject().jsonObject
        assertEquals("candidate:1", ice["candidate"]!!.jsonPrimitive.content)
        assertEquals("0", ice["sdpMid"]!!.jsonPrimitive.content)

        val leave = groupCallLeavePayload("c1", "a").jsonObject
        assertEquals("c1", leave["conversationId"]!!.jsonPrimitive.content)
        assertEquals("a", leave["from"]!!.jsonPrimitive.content)
    }

    // tolerant S→C decodes (PulseJson ignoreUnknownKeys)

    @Test
    fun `state payload - decodes join-ordered roster and tolerates unknown keys`() {
        val payload = PulseJson.decodeFromString(
            GroupCallStatePayload.serializer(),
            """{"callId":"g1","conversationId":"c1","kind":"video","hostId":"u2","startedAt":123,
                "members":[{"id":"u2","name":"Bob"},{"id":"u1","name":"Ada","color":"cyan","avatar":null}],
                "unknownKey":true}""",
        )
        assertEquals("g1", payload.callId)
        assertEquals("video", payload.kind)
        assertEquals(listOf("u2", "u1"), payload.members.map { it.id })
        assertEquals("emerald", payload.members[0].color) // default fills
        assertEquals("cyan", payload.members[1].color)
    }

    @Test
    fun `ring payload - decodes the HTTP-relay envelope`() {
        val payload = PulseJson.decodeFromString(
            GroupCallRingPayload.serializer(),
            """{"type":"gcall:ring","conversationId":"c1","kind":"video",
                "caller":{"id":"u9","name":"Ada","color":"emerald"},"title":"Design crew"}""",
        )
        assertEquals("c1", payload.conversationId)
        assertEquals("video", payload.kind)
        assertEquals("Ada", payload.caller?.name)
        assertEquals("Design crew", payload.title)
    }
}
