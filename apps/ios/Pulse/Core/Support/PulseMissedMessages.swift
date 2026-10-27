import Foundation

/// Local jump-pill arrivals, not the server's unread watermark. Snapshots and
/// history can discover IDs without inventing new arrivals; replay is idempotent.
struct PulseMissedMessages {
    private var known: Set<String> = []
    private var pending: Set<String> = []
    var count: Int { pending.count }

    mutating func reset() {
        known.removeAll()
        pending.removeAll()
    }

    mutating func receive(_ rows: [WireChatMessage], roomId: String, viewerId: String?, topicId: String?,
                          after boundary: PulseHistoryCursor? = nil, now: Date = Date()) {
        for row in rows where Self.eligible(row, roomId: roomId, viewerId: viewerId, topicId: topicId, now: now) {
            if let boundary, !boundary.precedes(PulseHistoryCursor(createdAt: row.createdAt, id: row.id)) { continue }
            if known.insert(row.id).inserted { pending.insert(row.id) }
        }
    }

    /// Prune only invalidated arrivals. Removing an unrelated row cannot clear the badge.
    mutating func reconcile(_ rows: [WireChatMessage], roomId: String, viewerId: String?, topicId: String?,
                            readingTail: Bool, now: Date = Date()) {
        known.formUnion(rows.map(\.id))
        let live = Set(rows.filter { Self.eligible($0, roomId: roomId, viewerId: viewerId, topicId: topicId, now: now) }.map(\.id))
        pending.formIntersection(live)
        if readingTail { pending.removeAll() }
    }

    static func latestBoundary(_ rows: [WireChatMessage]) -> PulseHistoryCursor? {
        rows.filter { !$0.id.hasPrefix("local_") && $0.parentId == nil }
            .map { PulseHistoryCursor(createdAt: $0.createdAt, id: $0.id) }
            .max { $0.precedes($1) }
    }

    private static func eligible(_ row: WireChatMessage, roomId: String, viewerId: String?, topicId: String?, now: Date) -> Bool {
        guard let viewerId, row.conversationId == roomId, !row.isOutgoingFor(viewerId),
              !row.id.hasPrefix("local_"), row.parentId == nil, row.deletedAt == nil,
              topicId == nil || row.topicId == topicId else { return false }
        if let expiry = row.expiresAt { return PulseFormat.date(expiry).map { $0 > now } ?? false }
        return true
    }
}
