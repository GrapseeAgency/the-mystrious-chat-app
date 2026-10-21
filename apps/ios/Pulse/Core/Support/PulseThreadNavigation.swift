import Foundation

/// Navigation selects a live reply in this exact thread, not an index in partial history.
enum PulseThreadNavigation {
    static func target(in rows: [WireChatMessage], roomId: String, rootId: String, targetId: String, now: Date = Date()) -> WireChatMessage? {
        rows.first {
            guard $0.id == targetId, $0.conversationId == roomId, $0.parentId == rootId, $0.deletedAt == nil else { return false }
            if let expires = $0.expiresAt { return PulseFormat.date(expires).map { $0 > now } ?? false }
            return true
        }
    }

    static func shouldFollowTail(nearTail: Bool, pendingTarget: Bool) -> Bool {
        nearTail && !pendingTarget
    }
}
