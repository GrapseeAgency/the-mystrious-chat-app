import Foundation

struct PulseMentionBadgeSnapshot {
    let count: Int
    let expiresAt: Date?

    /// A capped badge needs at most 100 live identities, not a full-history array.
    static func load(fetch: (String?) async throws -> WireMentionEntriesPage) async throws -> Self {
        var retained: [String: WireMentionEntry] = [:]
        var cursor: String?
        while true {
            try Task.checkCancellation()
            let page = try await fetch(cursor)
            try Task.checkCancellation()
            guard let rows = page.items, rows.count <= 100, let more = page.hasMore,
                  page.feedPageVersion == 1, more == (page.nextCursor != nil),
                  page.nextCursor == nil || page.nextCursor != cursor else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Mention feed did not advance.")
            }
            retained = retained.filter { $0.value.isVisible() }
            for row in rows where row.isVisible() && retained.count < 100 { retained[row.id] = row }
            if retained.count >= 100 || !more {
                return Self(count: retained.count, expiresAt: retained.values.compactMap { PulseFormat.date($0.expiresAt) }.min())
            }
            cursor = page.nextCursor
        }
    }
}
