import Foundation

public struct WireUnreadExpiration: Codable, Hashable, Sendable {
    public let expiresAt: String
    public let count: Int
}

extension WireConversationSummary {
    /// Idempotent projection of an authoritative count, never a count inferred
    /// from the locally cached history window. Manual-unread remains independent.
    public func removingExpiredSummary(at now: Date = Date()) -> WireConversationSummary {
        var copy = self
        if let raw = lastMessage?.expiresAt, !PulseRemediationLogic.isAlive(raw, now: now) {
            copy.lastMessage = nil
        }
        if let unreadExpirations {
            var remaining = max(0, unreadCount ?? 0)
            copy.unreadExpirations = unreadExpirations.filter { bucket in
                if PulseRemediationLogic.isAlive(bucket.expiresAt, now: now) { return true }
                remaining -= min(remaining, max(0, bucket.count))
                return false
            }
            copy.unreadCount = remaining
        }
        return copy
    }
}

public struct WireReadScopeState: Decodable, Sendable {
    public let readStateVersion: Int
    public let conversationId: String
    public let userId: String
    public let threadId: String?
    public let topicId: String?
    public let unreadCount: Int
    public let firstUnreadMessageId: String?
    public let unreadExpirations: [WireUnreadExpiration]?

    public func unread(at now: Date = Date()) -> Int {
        (unreadExpirations ?? []).reduce(max(0, unreadCount)) { count, bucket in
            PulseRemediationLogic.isAlive(bucket.expiresAt, now: now) ? count : count - min(count, max(0, bucket.count))
        }
    }
}

extension WireTopic {
    public func removingExpiredUnread(at now: Date = Date()) -> WireTopic {
        guard var remaining = unreadCount else { return self }
        var copy = self
        copy.unreadExpirations = unreadExpirations?.filter { bucket in
            if PulseRemediationLogic.isAlive(bucket.expiresAt, now: now) { return true }
            remaining -= min(max(0, remaining), max(0, bucket.count))
            return false
        }
        copy.unreadCount = max(0, remaining)
        return copy
    }
}
