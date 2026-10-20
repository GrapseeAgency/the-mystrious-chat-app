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
        if let raw = lastMessage?.expiresAt, let expiry = PulseFormat.date(raw), expiry <= now {
            copy.lastMessage = nil
        }
        if let unreadExpirations {
            var remaining = max(0, unreadCount ?? 0)
            copy.unreadExpirations = unreadExpirations.filter { bucket in
                guard let expiry = PulseFormat.date(bucket.expiresAt), expiry <= now else { return true }
                remaining -= min(remaining, max(0, bucket.count))
                return false
            }
            copy.unreadCount = remaining
        }
        return copy
    }
}
