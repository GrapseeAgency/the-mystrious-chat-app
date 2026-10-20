package app.pulse.domain.sync

/** Main-thread confined. A poll begun before a write must never put old rows
 * back on screen. Overlapping writes defer reads until the last writer ends. */
class RoomRefreshGate {
    private var revision = 0L
    private var writers = 0
    fun beginRead(): Long? = if (writers == 0) ++revision else null
    fun accepts(ticket: Long): Boolean = writers == 0 && ticket == revision
    fun beginWrite() { writers++; revision++ }
    fun endWrite() { check(writers > 0); writers--; revision++ }
}
