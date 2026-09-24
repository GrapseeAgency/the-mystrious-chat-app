package app.pulse.protocol

import java.time.Instant
import java.time.ZoneId

/**
 * R7 item 1 — message clustering kernel (web chat-room.tsx:1376-1393 +
 * CLUSTER_WINDOW_MS :277). Pure, UI-free: given the asc room timeline,
 * decide per message whether it OPENS a visual cluster (head) and whether it
 * CLOSES one (tail). Web truth, verbatim:
 *
 *   clusterBreak =
 *     prev === null ||
 *     !isSameDayIso(prev.createdAt, message.createdAt) ||
 *     prev.senderId !== message.senderId ||
 *     prev.anon !== message.anon ||            // R24-b: incognito opens fresh
 *     prev.anonAlias !== message.anonAlias ||
 *     Date.parse(message.createdAt) - Date.parse(prev.createdAt) > CLUSTER_WINDOW_MS
 *
 *   head  = clusterBreak
 *   tail  = i === built.length - 1 || built[i + 1].head
 *
 * with CLUSTER_WINDOW_MS = 5 * 60 * 1000. The Android room river already
 * excludes thread replies (ChatRoomViewModel messages flow filters
 * `threadRootId == null`, web `parentId !== null` filter parity) and deleted
 * rows stay in the timeline on BOTH platforms, so the kernel never needs to
 * filter — it only labels.
 */
object PulseClusterKernel {

    /** Web CLUSTER_WINDOW_MS (chat-room.tsx:277) — 5 minutes. */
    const val CLUSTER_WINDOW_MS = 5 * 60 * 1000L

    /** One asc timeline message, reduced to the fields clustering reads. */
    data class Entry(
        val id: String,
        val senderId: String,
        val createdAtMs: Long,
        val anon: Boolean = false,
        val anonAlias: String? = null,
    )

    /** head = opens a cluster (sender label row); tail = closes one. */
    data class Flags(val head: Boolean, val tail: Boolean)

    /**
     * Compute head/tail per entry id. Same-local-day comparison uses [zone]
     * (web isSameDayIso runs on the viewer's clock); entries with
     * unparseable timestamps (createdAtMs <= 0) carry a neutral "" day key so
     * they cluster by sender/gap alone, exactly like the Android timeline
     * treats undated rows today.
     */
    fun flags(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Map<String, Flags> {
        if (entries.isEmpty()) return emptyMap()
        val out = HashMap<String, Flags>(entries.size)
        var prev: Entry? = null
        var prevHead = false
        for (entry in entries) {
            val head = isBreak(prev, entry, zone)
            // tail of the PREVIOUS entry: the web closes it when this row opens one.
            prev?.let { p -> out[p.id] = Flags(head = prevHead, tail = head) }
            out[entry.id] = Flags(head = head, tail = false) // fixed up on the next pass
            prev = entry
            prevHead = head
        }
        // The last entry always closes its cluster (web: i === built.length - 1).
        prev?.let { last -> out[last.id] = Flags(head = prevHead, tail = true) }
        return out
    }

    private fun isBreak(prev: Entry?, cur: Entry, zone: ZoneId): Boolean {
        if (prev == null) return true
        if (dayKey(prev, zone) != dayKey(cur, zone)) return true
        if (prev.senderId != cur.senderId) return true
        if (prev.anon != cur.anon) return true
        if (prev.anonAlias != cur.anonAlias) return true
        return cur.createdAtMs - prev.createdAtMs > CLUSTER_WINDOW_MS
    }

    private fun dayKey(entry: Entry, zone: ZoneId): String {
        if (entry.createdAtMs <= 0L) return ""
        return Instant.ofEpochMilli(entry.createdAtMs).atZone(zone).toLocalDate().toString()
    }
}
