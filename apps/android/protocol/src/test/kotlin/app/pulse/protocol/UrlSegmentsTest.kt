package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R4-B item 1 — JVM pins for the URL auto-linking segmenter. The expected
 * segmentations mirror the web splitUrlSegments (pulse-utils.ts:163-175)
 * running the verbatim regex — every case below was chosen to catch a
 * "cleverness" regression (trimming, scheme rewriting, lazy matching).
 */
class UrlSegmentsTest {

    private fun kinds(vararg pairs: Pair<String, String>): List<String> =
        pairs.flatMap { (kind, value) -> listOf(kind, value) }

    private fun text(v: String) = "text" to v
    private fun url(v: String) = "url" to v

    @Test
    fun `mixed text and https url`() {
        // web: splitUrlSegments("see https://pulse.chat/x today")
        val out = UrlSegments.split("see https://pulse.chat/x today")
        assertEquals(
            listOf(
                text("see "), url("https://pulse.chat/x"), text(" today"),
            ).map { (k, _) -> k },
            out.map { seg -> if (seg is UrlSegments.Segment.Url) "url" else "text" },
        )
        assertEquals("https://pulse.chat/x", (out[1] as UrlSegments.Segment.Url).value)
        assertEquals("see ", (out[0] as UrlSegments.Segment.Text).value)
        assertEquals(" today", (out[2] as UrlSegments.Segment.Text).value)
    }

    @Test
    fun `www url without scheme is matched raw`() {
        val out = UrlSegments.split("go to www.example.com now")
        assertEquals(
            kinds(text("go to "), url("www.example.com"), text(" now")),
            out.flatMap { listOf(if (it is UrlSegments.Segment.Url) "url" else "text", it.value) },
        )
    }

    @Test
    fun `multiple urls with plain gaps between`() {
        val out = UrlSegments.split("a https://a.dev b www.b.dev c")
        assertEquals(5, out.size)
        assertEquals("https://a.dev", (out[1] as UrlSegments.Segment.Url).value)
        assertEquals(" b ", (out[2] as UrlSegments.Segment.Text).value)
        assertEquals("www.b.dev", (out[3] as UrlSegments.Segment.Url).value)
    }

    @Test
    fun `no url stays one text piece`() {
        val out = UrlSegments.split("just words, no links here")
        assertEquals(1, out.size)
        assertTrue(out[0] is UrlSegments.Segment.Text)
        assertEquals("just words, no links here", out[0].value)
    }

    @Test
    fun `empty string splits to nothing`() {
        assertEquals(emptyList<UrlSegments.Segment>(), UrlSegments.split(""))
    }

    @Test
    fun `adjacent punctuation is kept INSIDE the url (no trailing trim, web parity)`() {
        // "https://x.com." — the regex [^\s<]+ swallows the trailing dot.
        val out = UrlSegments.split("visit https://x.com.")
        assertEquals(2, out.size)
        assertEquals("visit ", (out[0] as UrlSegments.Segment.Text).value)
        assertEquals("https://x.com.", (out[1] as UrlSegments.Segment.Url).value)
    }

    @Test
    fun `scheme match is case-insensitive and value stays verbatim`() {
        val out = UrlSegments.split("HTTP://EXAMPLE.COM/Path")
        assertEquals(1, out.size)
        assertEquals("HTTP://EXAMPLE.COM/Path", (out[0] as UrlSegments.Segment.Url).value)
        val www = UrlSegments.split("WWW.Example.Dev")
        assertEquals("WWW.Example.Dev", (www[0] as UrlSegments.Segment.Url).value)
    }

    @Test
    fun `angle bracket and whitespace always end a url`() {
        val out = UrlSegments.split("<https://a.dev> https://b.dev,x")
        assertEquals("<", (out[0] as UrlSegments.Segment.Text).value)
        // `[^\s<]+` excludes only whitespace and `<` — the trailing `>` is
        // part of the match, exactly like the web (no clever trimming).
        assertEquals("https://a.dev>", (out[1] as UrlSegments.Segment.Url).value)
        assertEquals(" ", (out[2] as UrlSegments.Segment.Text).value)
        assertEquals("https://b.dev,x", (out[3] as UrlSegments.Segment.Url).value)
    }

    @Test
    fun `non-breaking space ends a url like the web js whitespace class`() {
        val out = UrlSegments.split("https://a.dev\u00A0tail")
        assertEquals(2, out.size)
        assertEquals("https://a.dev", (out[0] as UrlSegments.Segment.Url).value)
        assertEquals("\u00A0tail", (out[1] as UrlSegments.Segment.Text).value)
    }

    @Test
    fun `whole-string url and url at both ends`() {
        val solo = UrlSegments.split("https://solo.dev")
        assertEquals(1, solo.size)
        assertTrue(solo[0] is UrlSegments.Segment.Url)
        assertEquals("https://solo.dev", solo[0].value)
        val out = UrlSegments.split("https://a.dev middle www.z.dev")
        assertEquals(3, out.size)
        assertTrue(out[0] is UrlSegments.Segment.Url && out[2] is UrlSegments.Segment.Url)
        assertEquals(" middle ", (out[1] as UrlSegments.Segment.Text).value)
    }
}
