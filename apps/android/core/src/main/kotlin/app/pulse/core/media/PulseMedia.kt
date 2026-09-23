package app.pulse.core.media

/**
 * Wave 1 media helpers — the PURE (JVM-testable) half of the media pipeline.
 * The Android-side bitmap/base64 work lives in feature-chat MediaSupport.kt;
 * everything a unit test must pin (mime mapping, size caps, human sizes,
 * data-URL shape) lives here — spec WAVE1 §1.1 media rules.
 */
object PulseMedia {

    /** Client-side document cap — "docs ≤10MB" (upload data-URL cap is server-side 4.5MB media). */
    const val MAX_DOCUMENT_BYTES: Long = 10L * 1024 * 1024

    /** Wire-accepted document mimes (spec §1.1: pdf/zip/txt/csv). */
    val DOCUMENT_MIMES: List<String> = listOf(
        "application/pdf",
        "application/zip",
        "text/plain",
        "text/csv",
    )

    /** OpenDocument intent filter — same set, as the picker accepts. */
    val DOCUMENT_MIME_ARRAY: Array<String> = DOCUMENT_MIMES.toTypedArray()

    /** Image upload policy — downscale ≤1280px, JPEG q0.82 (spec §1.1). */
    const val MAX_IMAGE_DIMENSION_PX: Int = 1280
    const val IMAGE_JPEG_QUALITY: Int = 82

    /**
     * Mime by extension — the resolver fallback when contentResolver.getType
     * returns null for a picked document. Only wire-allowed types map; unknown
     * extensions degrade to octet-stream (server will refuse — honest error).
     */
    fun mimeForFileName(fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "text", "log", "md" -> "text/plain"
            "csv", "tsv" -> "text/csv"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    /** "850 KB" / "3.2 MB" — bubble + staged-card meta line (web humanFileSize). */
    fun humanFileSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            val whole = kb < 10.0
            return (if (whole) "%.1f KB" else "%.0f KB").format(java.util.Locale.US, kb)
        }
        val mb = kb / 1024.0
        val wholeMb = mb >= 10.0 || mb % 1.0 == 0.0
        return (if (wholeMb) "%.0f MB" else "%.1f MB").format(java.util.Locale.US, mb)
    }

    /** "data:<mime>;base64,… → <mime>" — null when the data-URL is malformed. */
    fun mimeOfDataUrl(dataUrl: String): String? {
        if (!dataUrl.startsWith("data:")) return null
        if (!dataUrl.contains(',')) return null
        val head = dataUrl.substringBefore(',').removePrefix("data:")
        val mime = head.substringBefore(';')
        return mime.takeIf { it.isNotBlank() }
    }

    /** True when the bytes fit the client document cap. */
    fun documentFits(bytes: Long): Boolean = bytes in 1..MAX_DOCUMENT_BYTES

    // ── Wave 2 depth helpers (PURE — JVM-pinned by PulseWave2LogicTest) ──

    /** Recording floor — shorter takes are discarded (web MIN_VOICE_MS). */
    const val MIN_VOICE_MS: Long = 600

    /**
     * Voice-note duration on the wire: quantize to 100 ms, never send 0 —
     * `max(1, round(ms/100)*100)`, the exact web rounding (spec §1 row 11).
     */
    fun voiceDurationMs(elapsedMs: Long): Long = maxOf(1L, Math.round(elapsedMs / 100.0) * 100)

    /**
     * Recording ceiling — the server refuses durationMs > 600000 (messages
     * route: "durationMs must be a number between 0 and 600000"), and the
     * MediaRecorder carries the same setMaxDuration. A hold that reaches the
     * cap auto-sends the take instead of clipping mid-air (D31).
     */
    const val MAX_VOICE_MS: Long = 600_000

    /** Live hold-to-record waveform — number of amplitude bars rendered (D31). */
    const val RECORD_WAVEFORM_BARS: Int = 40

    /**
     * MediaRecorder.maxAmplitude (0..32767) → normalized 0f..1f for the live
     * hold-to-record waveform. Raw ≤ 0 (silence/not-yet-sampled) → 0f.
     */
    fun normalizeRecordAmplitude(rawAmplitude: Int): Float =
        if (rawAmplitude <= 0) 0f else (rawAmplitude / 32767f).coerceIn(0f, 1f)

    /**
     * D31 — the exact web `voiceBars` bubble waveform (chat-room.tsx:6232),
     * ported bit-for-bit so all three surfaces render IDENTICAL decorative
     * bars for the same message id (the payload carries NO waveform — web
     * derives it client-side from the id, natives mirror that):
     *   1. `hashString` (pulse-utils.ts:59) — Int32-wrap h*31+code, then abs.
     *      Kotlin `Int` overflow wraps like JS `|0`; the abs is taken in
     *      Double space so Int.MIN_VALUE still maps to 2147483648.
     *   2. LCG loop `h = (h*1103515245 + 12345) % 2147483648` — JS `%` is
     *      fmod on DOUBLES (h*1.1e9 exceeds 2^53, so this must be Double
     *      math, not Long — Int would silently diverge from the web).
     *   3. bar = round(28 + v*72) with v in 0..1 → heights 28..100 (%).
     */
    fun voiceBubbleBars(seed: String, count: Int = 26): List<Int> {
        var hash = 0
        for (char in seed) hash = hash * 31 + char.code
        var h = if (hash == Int.MIN_VALUE) 2147483648.0 else Math.abs(hash).toDouble()
        val bars = ArrayList<Int>(count.coerceAtLeast(0))
        repeat(count) {
            h = (h * 1103515245.0 + 12345.0) % 2147483648.0
            val v = Math.abs(h) / 2147483648.0
            bars.add(Math.round(28.0 + v * 72.0).toInt())
        }
        return bars
    }

    /**
     * Link-unfurl trigger (spec §1 row 8): `https?://` or a bare `www.`
     * anywhere in the body — the sender's client then calls /unfurl once.
     */
    fun isUnfurlCandidate(content: String): Boolean =
        Regex("(https?://|(^|\\s)www\\.)", RegexOption.IGNORE_CASE).containsMatchIn(content)

    /**
     * View-once row state (spec §1 rows 5/6): GATED = photo behind the tap
     * overlay; BURNED = tombstone, NO render path of the image at all.
     * `myOptionId`-style wire fields never leak in — state derives purely
     * from (viewOnce flag, viewedAt stamp, viewer relationship).
     */
    enum class ViewOnceState { NONE, GATED, BURNED }

    fun viewOnceState(
        viewOnce: Boolean,
        viewedAtMs: Long?,
        mine: Boolean,
        hasImage: Boolean,
    ): ViewOnceState = when {
        !viewOnce || !hasImage -> ViewOnceState.NONE
        mine -> ViewOnceState.NONE          // sender always sees their own photo
        viewedAtMs != null -> ViewOnceState.BURNED
        else -> ViewOnceState.GATED
    }
}
