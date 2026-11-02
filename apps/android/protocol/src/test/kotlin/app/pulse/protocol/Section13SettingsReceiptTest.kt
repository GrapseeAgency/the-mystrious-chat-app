package app.pulse.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Section 13 - the durable settings receipt on the wire. Every new envelope
 * field is additive + tolerant (an old server sends none of them and the
 * decode still succeeds), and the patch body carries the durable identity
 * (clientId / intentAt / fieldTimes) ONLY when the caller has one, so legacy
 * callers keep the exact pre-Section-13 body.
 */
class Section13SettingsReceiptTest {

    // ── receipt envelope decode ──

    @Test
    fun `patch receipt decodes revision applied superseded and replayed`() {
        val envelope = """{"preferences":{"readReceipts":true},"revision":7,
            "applied":["bubbleRadius"],"superseded":["readReceipts"],"replayed":true}""".decodeSettingsEnvelope()
        assertEquals(7L, envelope.revision)
        assertEquals(listOf("bubbleRadius"), envelope.applied)
        assertEquals(listOf("readReceipts"), envelope.superseded)
        assertEquals(true, envelope.replayed)
        // the winning value rides preferences and decodes as a real boolean
        assertEquals(true, envelope.preferences?.readReceipts)
    }

    @Test
    fun `receipt fields absent or junk keep the pre-section-13 decode`() {
        // an old server sends only preferences
        val old = """{"preferences":{"density":"compact"}}""".decodeSettingsEnvelope()
        assertNull(old.revision)
        assertEquals(emptyList<String>(), old.applied)
        assertEquals(emptyList<String>(), old.superseded)
        assertNull(old.replayed)
        assertEquals("compact", old.preferences?.density)
        // junk receipt fields never break the preferences decode
        val junk = """{"preferences":{"density":"cozy"},"revision":"x","applied":"nope",
            "superseded":[7,null],"replayed":"yes"}""".decodeSettingsEnvelope()
        assertNull(junk.revision)
        assertEquals(emptyList<String>(), junk.applied)
        assertEquals(emptyList<String>(), junk.superseded)
        assertNull(junk.replayed)
        assertEquals("cozy", junk.preferences?.density)
    }

    // ── patch body: the durable identity rides only when present ──

    @Test
    fun `patch body carries clientId intentAt and fieldTimes only when present`() {
        val prefs = WirePulsePrefs(density = "compact")
        // legacy caller: no identity fields in the body at all
        val legacy = prefs.toPatchJson("u1")
        assertEquals(setOf("userId", "preferences"), legacy.keys)
        assertFalse(legacy.containsKey("clientId"))
        assertFalse(legacy.containsKey("intentAt"))
        assertFalse(legacy.containsKey("fieldTimes"))

        val durable = prefs.toPatchJson("u1",
            clientId = "abc123def456abc123def456abc12345",
            intentAtMs = 1_700_000_000_000L,
            fieldTimesMs = mapOf("density" to 1_700_000_000_000L, "wallpaper" to 1_699_999_000_000L),
        )
        assertEquals("abc123def456abc123def456abc12345", (durable["clientId"] as JsonPrimitive).content)
        // ISO-8601 instant of the stage moment (java.time ISO_INSTANT)
        assertEquals("2023-11-14T22:13:20Z", (durable["intentAt"] as JsonPrimitive).content)
        val fieldTimes = durable["fieldTimes"]!!.jsonObject
        assertEquals("2023-11-14T22:13:20Z", (fieldTimes["density"] as JsonPrimitive).content)
        assertEquals("2023-11-14T22:12:40Z", (fieldTimes["wallpaper"] as JsonPrimitive).content)
        // the instructions themselves are unchanged by the identity
        assertEquals(legacy["preferences"], durable["preferences"])
    }

    @Test
    fun `fieldTimes are omitted when the caller has none`() {
        val body = WirePulsePrefs(density = "compact").toPatchJson("u1", clientId = "abc123def456abc123def456abc12345")
        assertTrue(body.containsKey("clientId"))
        assertFalse(body.containsKey("intentAt"))
        assertFalse(body.containsKey("fieldTimes"))
    }

    // ── chat.convThemes rides the SAME staged patch (item 4) ──

    @Test
    fun `conv themes ride the staged patch under the web blob key`() {
        val themes = PulseJson.parseToJsonElement("""{"c1":{"wallpaper":"aurora","tint":"rose"}}""")
        val prefs = WirePulsePrefs(convThemes = themes as JsonObject)
        // settingsFields projects carried fields only - the theme map is one
        val carried = prefs.settingsFields()
        assertEquals(setOf("chat.convThemes"), carried.keys)
        // ...rides the patch body under the EXACT web prefs key...
        val body = prefs.toPatchJson("u1")
        assertEquals(themes, (body["preferences"]!!.jsonObject)["chat.convThemes"])
        // ...and selects back through the same key (stage/acknowledge round trip)
        val selected = prefs.selectSettingsFields(setOf("chat.convThemes"))
        assertEquals(themes, selected.convThemes)
        assertEquals(setOf("chat.convThemes"), selected.settingsFields().keys)
    }

    @Test
    fun `conv themes decode from the server blob and stay absent when untouched`() {
        val blob = """{"preferences":{"chat.convThemes":{"c1":{"wallpaper":"mono"}}}}""".decodeSettingsEnvelope()
        assertEquals("""{"wallpaper":"mono"}""", blob.preferences?.convThemes.toString())
        assertEquals(setOf("chat.convThemes"), blob.preferences!!.settingsFields().keys)
        // absent = untouched (null), never an empty map
        assertNull("""{"preferences":{"density":"cozy"}}""".decodeSettingsEnvelope().preferences?.convThemes)
    }

    // ── settings:changed payload (item 7) ──

    @Test
    fun `settings changed dto decodes tolerantly`() {
        val full = PulseJson.decodeFromString(SettingsChangedDto.serializer(), """{"userId":"u1","revision":9,"future":true}""")
        assertEquals("u1", full.userId)
        assertEquals(9L, full.revision)
        val bare = PulseJson.decodeFromString(SettingsChangedDto.serializer(), """{}""")
        assertEquals("", bare.userId)
        assertNull(bare.revision)
        assertEquals("settings:changed", SocketEvents.SETTINGS_CHANGED)
    }
}
