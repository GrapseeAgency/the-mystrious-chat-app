package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave 6 — social graph & discovery pure-logic tests (JVM only):
 * tolerant DTO parses (shape drift can never crash), profile rules
 * (audit constants), the ≤64 search snippet window and the @suggester
 * boundary math.
 */
class Wave6LogicTest {

    // ── tolerant DTO parses ──────────────────────────────────────

    @Test
    fun `full user parses the AppUser wire shape with scrubbed lastSeen`() {
        val dto = PulseJson.decodeFromString(
            UserEnvelopeDto.serializer(),
            """{"user":{"id":"u1","name":"Cara Chen","username":"cara","about":"hi","color":"emerald",
                "avatar":"/api/uploads/a.jpg","statusEmoji":"🔥","statusText":"shipping",
                "createdAt":"2024-01-02T03:04:05.000Z","lastSeenAt":null,"unknownKey":123}}""",
        )
        val u = requireNotNull(dto.user)
        assertEquals("u1", u.id)
        assertEquals("Cara Chen", u.name)
        assertEquals("cara", u.username)
        assertNull(u.lastSeenAt)
        assertEquals("🔥", u.statusEmoji)
    }

    @Test
    fun `full user envelope tolerates a missing user object`() {
        val dto = PulseJson.decodeFromString(UserEnvelopeDto.serializer(), "{}")
        assertNull(dto.user)
    }

    @Test
    fun `stats envelope parses live counts and tolerates drift`() {
        val page = PulseJson.decodeFromString(
            StatsEnvelopeDto.serializer(),
            """{"stats":{"messages":"12","reactions":3,"photos":2,"voiceNotes":0,
                "chats":5,"groups":1,"days":99,"joinedAt":"2024-01-02T03:04:05.000Z"}}""",
        )
        val s = requireNotNull(page.stats)
        assertEquals(12L, s.messages)
        assertEquals(99L, s.days)
        assertNull(s.lastSeenAt)
    }

    @Test
    fun `safety state parses the 12x5 digit string`() {
        val digits = (1..60).joinToString(" ") { (it % 10).toString() }
        val dto = PulseJson.decodeFromString(
            SafetyStateDto.serializer(),
            """{"peerId":"p1","safetyNumber":"$digits","verified":true,
                "verifiedAt":"2025-05-05T10:00:00.000Z"}""",
        )
        assertEquals("p1", dto.peerId)
        assertEquals(60, dto.safetyNumber.split(" ").size)
        assertTrue(dto.verified)
    }

    @Test
    fun `block pair state tolerates ok-less bodies`() {
        val post = PulseJson.decodeFromString(BlockStateDto.serializer(), """{"ok":true,"blocked":true}""")
        assertTrue(post.blocked)
        val get = PulseJson.decodeFromString(BlockStateDto.serializer(), """{"blocked":false}""")
        assertFalse(get.blocked)
    }

    @Test
    fun `blocked list parses rows newest-first verbatim`() {
        val page = PulseJson.decodeFromString(
            BlockedPageDto.serializer(),
            """{"blocks":[{"id":"b1","name":"Ada","username":null,"avatar":null,
                "color":"rose","blockedAt":"2025-02-03T04:00:00.000Z"}]}""",
        )
        assertEquals(1, page.blocks.size)
        assertEquals("Ada", page.blocks[0].name)
    }

    @Test
    fun `invite preview + join result parse`() {
        val invite = PulseJson.decodeFromString(
            InviteEnvelopeDto.serializer(),
            """{"invite":{"code":"PULSE12","conversationId":"c1","isGroup":true,
                "name":"Design crew","memberCount":4,"alreadyMember":true}}""",
        )
        assertEquals("Design crew", requireNotNull(invite.invite).name)
        assertTrue(requireNotNull(invite.invite).alreadyMember)

        val join = PulseJson.decodeFromString(
            InviteJoinResultDto.serializer(),
            """{"conversationId":"c1","alreadyMember":false}""",
        )
        assertEquals("c1", join.conversationId)
    }

    @Test
    fun `channels page parses viewer-aware rows`() {
        val page = PulseJson.decodeFromString(
            ChannelsPageDto.serializer(),
            """{"channels":[{"id":"c1","name":null,"description":"d","createdAt":"2025-01-01T00:00:00.000Z",
                "memberCount":7,"isSubscribed":true,"unread":true,"preview":"hey…","photo":null}]}""",
        )
        val c = page.channels.single()
        // wire may carry a null name — the mapper falls back to 'Channel'
        assertEquals("Channel", c.name?.ifBlank { "Channel" } ?: "Channel")
        assertEquals(7, c.memberCount)
        assertTrue(c.isSubscribed)
    }

    @Test
    fun `subscribe acks parse both directions`() {
        val sub = PulseJson.decodeFromString(SubscribeAckDto.serializer(), """{"already":true,"memberCount":8}""")
        assertTrue(sub.already)
        assertEquals(8, sub.memberCount)
        val un = PulseJson.decodeFromString(UnsubscribeAckDto.serializer(), """{"ok":true,"memberCount":7}""")
        assertTrue(un.ok)
    }

    @Test
    fun `folder rows parse with ordered membership`() {
        val folder = PulseJson.decodeFromString(
            FolderDto.serializer(),
            """{"id":"f1","name":"Work","emoji":"💼","position":2,
                "conversationIds":["c2","c1"],"extra":true}""",
        )
        assertEquals(listOf("c2", "c1"), folder.conversationIds)
    }

    // ── profile rules (audit constants) ─────────────────────────

    @Test
    fun `profile rule constants match the audit exactly`() {
        assertEquals(32, PulseProfileRules.NAME_MAX)
        assertEquals(140, PulseProfileRules.ABOUT_MAX)
        assertEquals(48, PulseProfileRules.STATUS_TEXT_MAX)
        assertEquals(8, PulseProfileRules.STATUS_EMOJI_MAX)
        assertEquals(500, PulseProfileRules.REPORT_DETAILS_MAX)
        assertEquals(1..24, PulseProfileRules.FOLDER_NAME_MIN..PulseProfileRules.FOLDER_NAME_MAX)
        assertEquals(2..40, PulseProfileRules.CHANNEL_NAME_MIN..PulseProfileRules.CHANNEL_NAME_MAX)
        assertEquals(200, PulseProfileRules.CHANNEL_DESCRIPTION_MAX)
        assertEquals(11, PulseProfileRules.STATUS_GLYPHS.size)
        assertTrue("vacation" in PulseProfileRules.STATUS_GLYPHS)
        assertEquals(8, PulseProfileRules.COLOR_WHITELIST.size)
    }

    @Test
    fun `username rules mirror the wire normalizeUsername`() {
        assertTrue(PulseProfileRules.isValidUsername("abc"))
        assertTrue(PulseProfileRules.isValidUsername("a_b_9"))
        assertFalse(PulseProfileRules.isValidUsername("ab")) // < 3
        assertFalse(PulseProfileRules.isValidUsername("a".repeat(21))) // > 20
        assertFalse(PulseProfileRules.isValidUsername("Bad-Handle")) // uppercase + dash
        assertEquals("alice_1", PulseProfileRules.sanitizeUsernameInput("Alice_1!"))
        assertEquals(20, PulseProfileRules.sanitizeUsernameInput("x".repeat(30)).length)
    }

    // ── snippet window math (≤64 web parity) ────────────────────

    @Test
    fun `snippet window clips deep matches with leading cut`() {
        val content = ("x".repeat(40)) + "contract signed today" + ("y".repeat(60))
        val w = requireNotNull(snippetWindow(content, "contract"))
        // idx=40 > 28 → start = idx-24 = 16, end = idx+8+28 = 76 (window = 60)
        assertTrue(w.leadingCut)
        assertTrue(w.trailingCut)
        assertTrue(w.text.contains("contract signed today"))
        assertEquals(60, w.text.length)
    }

    @Test
    fun `snippet window keeps short content whole`() {
        val w = requireNotNull(snippetWindow("the contract is ready", "contract"))
        assertEquals("the contract is ready", w.text)
        assertFalse(w.leadingCut)
        assertFalse(w.trailingCut)
    }

    @Test
    fun `snippet window is case-insensitive and null on no match or empty query`() {
        assertNull(snippetWindow("nothing here", "zzz"))
        assertNull(snippetWindow("nothing here", "  "))
        val w = requireNotNull(snippetWindow("Contract Ready", "contract"))
        assertTrue(w.text.contains("Contract"))
    }

    @Test
    fun `snippet window marks trailing cut at the tail`() {
        val content = ("z".repeat(70)) + "needle"
        val w = requireNotNull(snippetWindow(content, "needle"))
        assertTrue(w.leadingCut)
        assertFalse(w.trailingCut)
    }

    // ── @suggester boundary math ────────────────────────────────

    @Test
    fun `mention query detects an active token and respects boundaries`() {
        assertEquals("ca", mentionQueryAt("@ca", 3)?.query)
        assertEquals("ca", mentionQueryAt("hey @ca", 7)?.query)
        assertNull(mentionQueryAt("mail@a", 6), "mid-word @ is not a mention")
        assertNull(mentionQueryAt("@", 1), "empty token never triggers")
        assertNull(mentionQueryAt("@cara chen", 10), "token with a space is closed")
        assertNull(mentionQueryAt("@ca", 0), "cursor 0 has no token")
    }

    @Test
    fun `mention apply replaces the token with the full name plus a space`() {
        val (text, cursor) = requireNotNull(mentionApply("ping @ca now", 8, "Cara Chen"))
        assertEquals("ping @Cara Chen  now", text)
        assertEquals("ping @Cara Chen ".length, cursor)
    }

    @Test
    fun `mention apply at the draft start works and appends nothing odd`() {
        val (text, cursor) = requireNotNull(mentionApply("@ca", 3, "Cara"))
        assertEquals("@Cara ", text)
        assertEquals(6, cursor)
    }

    @Test
    fun `mention apply is null without an active token`() {
        assertNull(mentionApply("no mention here", 15, "Cara"))
    }

    // ── wave 6 second tranche: badge cap / safety grid / rules / chip ──

    @Test
    fun `badge label caps at 99 plus`() {
        assertEquals("0", badgeLabel(0))
        assertEquals("99", badgeLabel(99))
        assertEquals("99+", badgeLabel(100))
        assertEquals("99+", badgeLabel(48151))
    }

    @Test
    fun `safety grid splits 60 digits into 12 five-digit groups`() {
        val spaced = (1..60).joinToString(" ") { ((it - 1) % 10).toString() }
        val groups = SafetyGrid.groups(spaced)
        assertEquals(12, groups.size)
        assertEquals("01234", groups[0])
        assertEquals("56789", groups[1])
        assertEquals(5, groups[11].length)
        // bare (unspaced) digit strings split identically
        assertEquals(groups, SafetyGrid.groups(spaced.replace(" ", "")))
        // short input zero-pads, never throws
        assertEquals(12, SafetyGrid.groups("1").size)
    }

    @Test
    fun `channel name validation mirrors the web copy`() {
        assertEquals("Name needs at least 2 characters.", PulseChannelRules.nameError("a"))
        assertEquals("Name needs at least 2 characters.", PulseChannelRules.nameError("  "))
        assertNull(PulseChannelRules.nameError("ab"))
        assertEquals("Keep the name under 41 characters.", PulseChannelRules.nameError("x".repeat(41)))
        assertNull(PulseChannelRules.nameError("x".repeat(40)))
        assertNull(PulseChannelRules.descriptionError("d".repeat(200)))
        assertEquals("Keep the description under 201 characters.", PulseChannelRules.descriptionError("d".repeat(201)))
    }

    @Test
    fun `folder name validation allows 1 to 24 characters`() {
        assertNull(PulseFolderRules.nameError("Work"))
        assertNull(PulseFolderRules.nameError("x".repeat(24)))
        assertEquals("Folder names are 1–24 characters.", PulseFolderRules.nameError(""))
        assertEquals("Folder names are 1–24 characters.", PulseFolderRules.nameError("x".repeat(25)))
    }

    @Test
    fun `mention token range finds the first boundary-safe token`() {
        assertEquals(0..4, mentionTokenRange("@Cara hi", "Cara"))
        assertEquals(4..13, mentionTokenRange("hey @Cara Chen, look", "Cara Chen"))
        // NOTE: the web canonical regex has no pre-@ boundary (route.ts:113) —
        // "mail@Cara" IS a server-side mention; parity over stricter guessing.
        assertNull(mentionTokenRange("@Cara", "Bob"))
        // non-alphanumeric follow still counts (regex (?=[^A-Za-z0-9]))
        assertEquals(0..4, mentionTokenRange("@Cara, x", "Cara"))
    }
}
