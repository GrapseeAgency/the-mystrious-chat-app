package app.pulse.feature.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.protocol.UrlSegments

/**
 * F-MS-02 / F-MS-03 — bubble text formatting + jumbo-emoji detection.
 *
 * Kotlin port of the web ground truth:
 *  · FORMAT_RE + BubbleText — src/components/chat/chat-room.tsx:6583-6768
 *  · isJumboEmoji + JUMBO_EMOJI_RE — src/lib/pulse-utils.ts:152-160
 *
 * Segment order matches the web regex exactly: multi-char tokens first so
 * ** wins over *, pre/code span newlines, the rest don't.
 */
object MessageTextParser {

    /** Web FORMAT_RE (chat-room.tsx:6583) — group order is load-bearing. */
    private val FORMAT_RE = Regex(
        "```([\\s\\S]+?)```|`([^`\\n]+)`|\\*\\*([^*\\n]+?)\\*\\*|__([^_\\n]+?)__|~~([^~\\n]+?)~~|\\|\\|([^|\\n]+?)\\|\\||\\*([^*\\n]+?)\\*|_([^_\\n]+?)_|~([^~\\n]+?)~",
    )

    sealed interface Segment {
        /** Rendered length — Pre segments never join the inline run. */
        val length: Int

        data class Plain(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** ```pre``` — block-level mono card (spans newlines). */
        data class Pre(val text: String) : Segment {
            override val length: Int get() = 0
        }

        /** `code` — inline mono chip. */
        data class Code(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** **bold** and *bold* collapse to one style (web parity). */
        data class Bold(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** __underline__ */
        data class Underline(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** ~~strike~~ and ~strike~ */
        data class Strike(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** ||spoiler|| — tap-to-reveal on the client (server stores raw). */
        data class Spoiler(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** _italic_ */
        data class Italic(val text: String) : Segment {
            override val length: Int get() = text.length
        }

        /** R4-B item 1 — a bare URL piece (protocol UrlSegments split). */
        data class Url(val text: String) : Segment {
            override val length: Int get() = text.length
        }
    }

    /**
     * Non-overlapping, left-to-right scan with plain gaps between matches
     * (JS matchAll(/…/g) parity — group 1 pre · 2 code · 3 bold · 4 underline
     * · 5 strike · 6 spoiler · 7 bold-single · 8 italic · 9 strike-single).
     */
    fun parse(content: String): List<Segment> {
        if (content.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (match in FORMAT_RE.findAll(content)) {
            val start = match.range.first
            if (start > cursor) segments += Segment.Plain(content.substring(cursor, start))
            val g = { i: Int -> match.groupValues.getOrNull(i).orEmpty() }
            when {
                g(1).isNotEmpty() -> segments += Segment.Pre(g(1))
                g(2).isNotEmpty() -> segments += Segment.Code(g(2))
                g(3).isNotEmpty() -> segments += Segment.Bold(g(3))
                g(4).isNotEmpty() -> segments += Segment.Underline(g(4))
                g(5).isNotEmpty() -> segments += Segment.Strike(g(5))
                g(6).isNotEmpty() -> segments += Segment.Spoiler(g(6))
                g(7).isNotEmpty() -> segments += Segment.Bold(g(7))
                g(8).isNotEmpty() -> segments += Segment.Italic(g(8))
                g(9).isNotEmpty() -> segments += Segment.Strike(g(9))
                else -> segments += Segment.Plain(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < content.length) segments += Segment.Plain(content.substring(cursor))
        return segments
    }

    // ── R3-B item 2 — @mention runs (web buildMentionRuns port) ──────────

    /** One run of the mention split — [mention] is the matched member name (no @). */
    data class MentionRun(val text: String, val mention: String?)

    /**
     * Splits text into [plain, @mention, plain, …] runs against the REAL
     * roster names (web chat-room.tsx:6837-6856): longest names first so
     * "Alice Chen" wins over a hypothetical "Alice", case-insensitive match.
     * No roster → one run, zero regex work.
     */
    fun buildMentionRuns(content: String, memberNames: List<String>): List<MentionRun> {
        val names = memberNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
        if (names.isEmpty()) return listOf(MentionRun(content, null))
        val mentionRe = Regex("@(${names.joinToString("|") { Regex.escape(it) }})", RegexOption.IGNORE_CASE)
        val runs = mutableListOf<MentionRun>()
        var last = 0
        for (match in mentionRe.findAll(content)) {
            val start = match.range.first
            if (start > last) runs += MentionRun(content.substring(last, start), null)
            runs += MentionRun(match.value, match.groupValues[1])
            last = match.range.last + 1
        }
        if (last < content.length) runs += MentionRun(content.substring(last), null)
        return if (runs.isEmpty()) listOf(MentionRun(content, null)) else runs
    }

    // ── F-MS-03 — jumbo emoji (pulse-utils.ts:152-160 port) ─────────────

    /** Web cap: trimmed UTF-16 length ≤ 24 and the whole-string run {1,9}. */
    private const val MAX_JUMBO_UTF16 = 24
    private const val MAX_JUMBO_CODE_POINTS = 9

    /**
     * Pure-emoji short messages render extra large (WhatsApp-style). Port of
     * the web JUMBO_EMOJI_RE check: every code point is pictographic /
     * emoji-component / whitespace / ZWJ / VS16, the run is 1..9 code points,
     * trimmed UTF-16 length ≤ 24, and at least one REAL pictograph is present.
     *
     * Hand-rolled classifier instead of \p{Extended_Pictographic}: Android's
     * ICU regex only knows that property on newer devices, and minSdk is
     * lower — the conservative range table below keeps identical behaviour
     * for every realistic emoji without a compile-time crash on old runtimes.
     */
    fun isJumboEmoji(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_JUMBO_UTF16) return false
        var codePoints = 0
        var pictographs = 0
        var index = 0
        while (index < trimmed.length) {
            val cp = trimmed.codePointAt(index)
            index += Character.charCount(cp)
            codePoints += 1
            if (codePoints > MAX_JUMBO_CODE_POINTS) return false
            when {
                isPictographic(cp) -> pictographs += 1
                isEmojiComponent(cp) || isJumboFiller(cp) -> Unit
                else -> return false
            }
        }
        return pictographs > 0
    }

    /** \s | \u200d | \ufe0f legs of the web character class. */
    private fun isJumboFiller(cp: Int): Boolean =
        cp == 0x200D || cp == 0xFE0F || Character.isWhitespace(cp)

    /** \p{Emoji_Component}: keycap bases, regional indicators, skin tones. */
    private fun isEmojiComponent(cp: Int): Boolean = when (cp) {
        in 0x30..0x39, 0x23, 0x2A -> true
        in 0x1F1E6..0x1F1FF -> true
        in 0x1F3FB..0x1F3FF -> true
        0x20E3 -> true
        else -> false
    }

    /**
     * Extended_Pictographic core ranges (the blocks chat emoji are drawn
     * from) — the conservative stand-in for the Unicode binary property.
     */
    private fun isPictographic(cp: Int): Boolean = when (cp) {
        0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139 -> true
        in 0x2194..0x21AA -> true
        in 0x231A..0x231B -> true
        0x2328, 0x2388, 0x23CF -> true
        in 0x23E9..0x23F3 -> true
        in 0x23F8..0x23FA -> true
        0x24C2 -> true
        in 0x25AA..0x25AB -> true
        0x25B6, 0x25C0 -> true
        in 0x25FB..0x25FE -> true
        in 0x2600..0x27BF -> true
        in 0x2934..0x2935 -> true
        in 0x2B05..0x2B07 -> true
        in 0x2B1B..0x2B1C -> true
        0x2B50, 0x2B55, 0x3030, 0x303D, 0x3297, 0x3299 -> true
        in 0x1F000..0x1F0FF -> true
        in 0x1F10D..0x1F10F -> true
        0x1F12F -> true
        in 0x1F16C..0x1F171 -> true
        in 0x1F17E..0x1F17F -> true
        0x1F18E -> true
        in 0x1F191..0x1F19A -> true
        in 0x1F1AD..0x1F1FF -> true
        in 0x1F201..0x1F20F -> true
        0x1F21A, 0x1F22F -> true
        in 0x1F232..0x1F23A -> true
        in 0x1F23C..0x1F23F -> true
        in 0x1F249..0x1F3FA -> true
        in 0x1F400..0x1F53D -> true
        in 0x1F546..0x1F64F -> true
        in 0x1F680..0x1F6FF -> true
        in 0x1F774..0x1F77F -> true
        in 0x1F7D5..0x1F7FF -> true
        in 0x1F80C..0x1F80F -> true
        in 0x1F848..0x1F84F -> true
        in 0x1F85A..0x1F85F -> true
        in 0x1F888..0x1F88F -> true
        in 0x1F8AE..0x1F8FF -> true
        in 0x1F90C..0x1F93A -> true
        in 0x1F93C..0x1F945 -> true
        in 0x1F947..0x1FAFF -> true
        in 0x1FC00..0x1FFFD -> true
        else -> false
    }
}

/**
 * One inline piece in string order — the spoiler tap-reveal + link tap hit
 * test model: [spoiler] flags hidden spans, [url] carries the RAW matched
 * URL of a link piece (null everywhere else).
 */
private data class InlineHit(val spoiler: Boolean, val url: String?, val length: Int)

/**
 * F-MS-02 — styled bubble body. Pre blocks render as their own mono cards
 * (web block-level parity); everything else flows in ONE AnnotatedString so
 * bold/italic/underline/strike/code mix inline. Spoilers keep a tap-to-reveal
 * state per occurrence (web SpoilerSpan parity — the 5px blur is approximated
 * by an opaque tint + hidden text, the standard native spoiler treatment).
 * R4-B item 1 — plain stretches split into text/url pieces (web renderPlain
 * parity): URL pieces render accent + underline and open an ACTION_VIEW
 * intent on tap; pre/code/spoiler spans are NEVER linkified (web only runs
 * renderPlain on the format-regex gaps).
 */
@Composable
internal fun FormattedMessageBody(
    body: String,
    mine: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    // R3-B item 2 — the room roster; @Name tokens matching a member highlight.
    memberNames: List<String> = emptyList(),
) {
    if (body.isEmpty()) return

    // Per-message parse cache (body + roster keys) — the LazyColumn river
    // re-composes rows on scroll without re-running either regex pass.
    // R4-B item 1 — the URL segmenter rides the SAME cached pass (web
    // renderPlain composes INSIDE the format gaps: plain segments only —
    // pre/code/spoiler/bold/… content is never linkified), so scrolling
    // never re-runs a regex either.
    val runs = remember(body, memberNames) {
        MessageTextParser.buildMentionRuns(body, memberNames).map { run ->
            if (run.mention != null) {
                null to run
            } else {
                MessageTextParser.parse(run.text).flatMap { segment ->
                    if (segment is MessageTextParser.Segment.Plain) {
                        UrlSegments.split(segment.text).map { piece ->
                            when (piece) {
                                is UrlSegments.Segment.Url -> MessageTextParser.Segment.Url(piece.value)
                                is UrlSegments.Segment.Text -> MessageTextParser.Segment.Plain(piece.value)
                            }
                        }
                    } else {
                        listOf(segment)
                    }
                } to run
            }
        }
    }

    // Web chrome: mine → bg-black/20 code, bg-white/25 spoiler; theirs →
    // zinc-100 / dark:bg-black/40 (surface tint approximates both modes).
    val chrome = if (mine) {
        Color.Black.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    }
    val spoilerBg = if (mine) {
        Color.White.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    }
    // R3-B item 2 — web mention chip colors verbatim (chat-room.tsx:6903):
    // bg-emerald-500/20 + emerald-800 text in light, bg-emerald-400/25 +
    // emerald-200 text in dark — identical on mine and their bubbles.
    val darkChrome = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val mentionBg = if (darkChrome) {
        Color(0xFF34D399).copy(alpha = 0.25f)
    } else {
        Color(0xFF10B981).copy(alpha = 0.20f)
    }
    val mentionText = if (darkChrome) Color(0xFFA7F3D0) else Color(0xFF065F46)

    // R4-B item 1 — web link colors verbatim (chat-room.tsx BubbleText
    // renderPlain): mine → white on the emerald bubble (contentColor);
    // theirs → text-emerald-700 / dark:text-emerald-400, underlined.
    val linkColor = if (mine) {
        contentColor
    } else if (darkChrome) {
        Color(0xFF34D399)
    } else {
        Color(0xFF047857)
    }

    // R4-B item 1 — ACTION_VIEW on a tapped link; https:// is synthesized for
    // bare www. values exactly like the web href, nothing else is rewritten.
    val context = LocalContext.current
    val openLink: (String) -> Unit = { raw ->
        val target = if (raw.startsWith("www.")) "https://$raw" else raw
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    // Spoiler reveal state — occurrence index within the whole message,
    // counted in build order (Pre segments emit nothing inline; mention
    // runs occupy inline space but are never spoilers).
    val revealed = remember(body) { mutableStateListOf<Int>() }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }

    // Inline pieces in string order, cached with the parse — the spoiler
    // tap-reveal + link tap hit test walks exactly this list (flag + url +
    // length per piece).
    val inlineHits = remember(runs) {
        buildList {
            runs.forEach { (segments, run) ->
                if (segments == null) {
                    add(InlineHit(false, null, run.text.length))
                } else {
                    segments.forEach { segment ->
                        if (segment !is MessageTextParser.Segment.Pre) {
                            add(
                                InlineHit(
                                    spoiler = segment is MessageTextParser.Segment.Spoiler,
                                    url = (segment as? MessageTextParser.Segment.Url)?.text,
                                    length = segment.length,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    var spoilerCounter = -1
    val inline = buildAnnotatedString {
        runs.forEach { (segments, run) ->
            if (segments == null) {
                // @mention chip — web renders "@{mention}" semibold on the
                // emerald chip (the run text already carries the @).
                withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, background = mentionBg, color = mentionText),
                ) { append(run.text) }
                return@forEach
            }
            segments.forEach { segment ->
                when (segment) {
                    is MessageTextParser.Segment.Pre -> Unit // rendered as its own card below
                    is MessageTextParser.Segment.Plain -> append(segment.text)
                    is MessageTextParser.Segment.Code -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = chrome, fontSize = 12.5.sp),
                    ) { append(segment.text) }
                    is MessageTextParser.Segment.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
                    is MessageTextParser.Segment.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(segment.text) }
                    is MessageTextParser.Segment.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(segment.text) }
                    is MessageTextParser.Segment.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(segment.text) }
                    is MessageTextParser.Segment.Url -> withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ) { append(segment.text) }
                    is MessageTextParser.Segment.Spoiler -> {
                        spoilerCounter += 1
                        val open = spoilerCounter in revealed
                        withStyle(
                            SpanStyle(
                                background = if (open) Color.Transparent else spoilerBg,
                                color = if (open) contentColor else Color.Transparent,
                            ),
                        ) { append(segment.text) }
                    }
                }
            }
        }
    }
    val hasSpoilers = inlineHits.any { it.spoiler }
    val hasLinks = inlineHits.any { it.url != null }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (inlineHits.isNotEmpty()) {
            Text(
                text = inline,
                style = style.copy(color = contentColor),
                onTextLayout = { layout.value = it },
                modifier = if (hasSpoilers || hasLinks) {
                    Modifier.pointerInput(inlineHits.toList()) {
                        detectTapGestures { position ->
                            val result = layout.value ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                            // Same hit-test walk as the R3 spoiler model — a
                            // URL hit opens the link (never a spoiler reveal;
                            // spoiler text is never linkified), anything else
                            // stays a no-op so the bubble's long-press sheet
                            // keeps working (tap ≠ long-press).
                            var spoilerSeen = -1
                            var cursor = 0
                            var urlHit: String? = null
                            var spoilerHit = -1
                            inlineHits.forEach { piece ->
                                val within = offset >= cursor && offset < cursor + piece.length
                                if (piece.spoiler) {
                                    spoilerSeen += 1
                                    if (within && urlHit == null) spoilerHit = spoilerSeen
                                } else if (within && piece.url != null) {
                                    urlHit = piece.url
                                }
                                cursor += piece.length
                            }
                            when {
                                urlHit != null -> openLink(urlHit)
                                spoilerHit >= 0 && spoilerHit !in revealed -> revealed.add(spoilerHit)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
        }
        runs.forEach { (segments, _) ->
            segments?.forEach { segment ->
                if (segment is MessageTextParser.Segment.Pre) {
                    Text(
                        segment.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = contentColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(chrome, RoundedCornerShape(9.dp))
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
