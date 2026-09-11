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
}
