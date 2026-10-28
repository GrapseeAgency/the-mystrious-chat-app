package app.pulse.domain.model

/** One bounded feed window. Empty items do not imply the scan has finished. */
data class FeedPage<T>(val items: List<T>, val nextCursor: String?, val hasMore: Boolean, val cached: Boolean = false)
