package app.pulse.protocol

/**
 * R4-B item 1 — URL auto-linking segmenter. Verbatim port of the web ground
 * truth splitUrlSegments (src/lib/pulse-utils.ts:163-175):
 *
 *   const URL_RE = /(https?:\/\/[^\s<]+|www\.[^\s<]+)/gi
 *   → non-overlapping left-to-right scan, plain gaps between matches.
 *
 * Web-parity rules kept EXACTLY:
 *  · the regex is copied character-for-character (with the `i` flag —
 *    "HTTP://" and "WWW." match too) — no trailing-punctuation trimming,
 *    no scheme synthesis here (the render layer synthesizes https:// for
 *    www. values, mirroring the web href exactly);
 *  · JS `\s` spans [\f\n\r\t\v \u00a0\u1680\u2000-\u200a\u2028\u2029
 *    \u202f\u205f\u3000\ufeff] while Java's `\s` only knows [\t\n\x0B\f\r],
 *    so the whitespace class is spelled out to keep byte-identical segment
 *    boundaries (a URL glued to a non-breaking space must not swallow it);
 *  · `<` always ends a URL (the [^\s<] leg).
 */
object UrlSegments {

    /** One segment in string order — [Url] values are the RAW match (no rewrite). */
    sealed interface Segment {
        val value: String

        data class Text(override val value: String) : Segment
        data class Url(override val value: String) : Segment
    }

    /** The exact JS `\s` set, as a regex character-class body. */
    private const val JS_WHITESPACE = " \\t\\n\\u000B\\f\\r\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** (https?:\/\/[^\s<]+|www\.[^\s<]+) with the JS \s class + `i` flag. */
    private val URL_RE = Regex(
        "(https?://[^$JS_WHITESPACE<]+|www\\.[^$JS_WHITESPACE<]+)",
        RegexOption.IGNORE_CASE,
    )

    /** [text, url, text, …] — consecutive non-URL stretches stay single Text pieces. */
    fun split(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Segment>()
        var last = 0
        for (match in URL_RE.findAll(text)) {
            val start = match.range.first
            if (start > last) out += Segment.Text(text.substring(last, start))
            out += Segment.Url(match.value)
            last = match.range.last + 1
        }
        if (last < text.length) out += Segment.Text(text.substring(last))
        return out
    }
}
