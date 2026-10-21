import Foundation

/// Server rows replace matching IDs, never matching text. Pending operations survive refresh.
enum PulseTimelineMerge {
    static func merge(_ existing: [WireChatMessage], incoming: [WireChatMessage], topicId: String?) -> [WireChatMessage] {
        var rows = Dictionary(existing.map { ($0.id, $0) }, uniquingKeysWith: { _, newest in newest })
        for row in incoming { rows[row.id] = PulseMessageMerge.merge(rows[row.id], incoming: row) }
        return rows.values.filter { row in
            row.parentId == nil && PulseRemediationLogic.isAlive(row.expiresAt) &&
                (topicId == nil || row.topicId == topicId)
        }.sorted {
            let left = PulseFormat.date($0.createdAt) ?? .distantPast
            let right = PulseFormat.date($1.createdAt) ?? .distantPast
            return left == right ? $0.id < $1.id : left < right
        }
    }
}
