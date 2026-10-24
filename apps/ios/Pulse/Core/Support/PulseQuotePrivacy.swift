import Foundation

/// Works on visible rows as well as complete cache context. A mutation outside
/// the visible page can still revoke its quotes through the cache projection.
enum PulseQuotePrivacy {
    static func project(_ rows: [WireChatMessage], known: [WireChatMessage], now: Date = Date()) -> [WireChatMessage] {
        var context: [String: WireChatMessage] = [:]
        for row in rows + known { context[row.id] = PulseMessageMerge.merge(context[row.id], incoming: row) }
        return rows.map { incoming in
            var row = PulseMessageMerge.merge(context[incoming.id], incoming: incoming)
            if let quote = row.replyTo, let source = context[quote.id] {
                row.replyTo = quote.restricting(source: source, root: source.parentId.flatMap { context[$0] },
                                               roomId: row.conversationId, ownerId: row.id, now: now)
            }
            return row
        }
    }
}
