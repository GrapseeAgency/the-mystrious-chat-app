package app.pulse.domain.sync

/** Lazy layout includes prefetched/clipped rows. Only measured overlap can anchor Seen. */
fun hasReadViewportOverlap(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int, minimumVisible: Int): Boolean {
    if (size <= 0 || viewportEnd <= viewportStart || minimumVisible <= 0) return false
    val start = maxOf(offset.toLong(), viewportStart.toLong())
    val end = minOf(offset.toLong() + size, viewportEnd.toLong())
    return end - start >= minOf(size, minimumVisible)
}
