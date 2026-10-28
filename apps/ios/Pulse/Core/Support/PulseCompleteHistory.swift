import Foundation

/// Require a complete server-bounded snapshot; failures/cancellation must never
/// turn a successfully fetched prefix into an export or destructive clear sweep.
enum PulseCompleteHistory {
    static let start = "0001-01-01T00:00:00.000Z"
    /// Constant-memory traversal; a successful prefix is never a completed export.
    static func stream(room: String, fetch: (String?) async throws -> WireMessagesPage,
                       consume: ([WireChatMessage]) throws -> Void) async throws {
        var cursor: String?
        var scanned: PulseHistoryCursor?
        var previous: PulseHistoryCursor?
        func invalid(_ message: String) -> PulseAPIClient.Failure { .init(kind: .validation, message: message) }
        while true {
            try Task.checkCancellation()
            let page = try await fetch(cursor)
            try Task.checkCancellation()
            guard page.exportVersion == 1, page.messages.count <= 200 else { throw invalid("Update the gateway for streaming export.") }
            let next = page.exportPosition.map { PulseHistoryCursor(createdAt: $0.createdAt, id: $0.id) }
            guard !page.hasMore || (page.nextSyncCursor?.isEmpty == false && page.nextSyncCursor != cursor && next != nil),
                  page.hasMore || page.nextSyncCursor == nil else { throw invalid("Export did not advance or finish.") }
            if let next {
                guard !next.id.isEmpty, PulseFormat.date(next.createdAt) != nil,
                      scanned == nil || scanned!.precedes(next) else { throw invalid("Export scan repeated a position.") }
            }
            for row in page.messages {
                guard row.conversationId == room, !row.id.isEmpty, !row.id.hasPrefix("local_"), PulseFormat.date(row.createdAt) != nil else {
                    throw invalid("Export returned an unrelated row.")
                }
                let position = PulseHistoryCursor(createdAt: row.createdAt, id: row.id)
                guard previous == nil || previous!.precedes(position), let next, !next.precedes(position) else {
                    throw invalid("Export repeated rows or exceeded its scan position.")
                }
                previous = position
            }
            try consume(page.messages)
            if !page.hasMore { return }
            scanned = next
            cursor = page.nextSyncCursor
        }
    }

    static func load(room: String, maximumRows: Int = 50_000, maximumBytes: Int = 32 * 1024 * 1024,
                     fetch: (String?) async throws -> WireMessagesPage) async throws -> [WireChatMessage] {
        var result: [WireChatMessage] = []
        var cursor: String?
        var previous: PulseHistoryCursor?
        var bytes = 0
        while true {
            try Task.checkCancellation()
            let page = try await fetch(cursor)
            try Task.checkCancellation()
            guard page.syncVersion == 1 else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Update the gateway before exporting or clearing complete history.")
            }
            guard !page.hasMore || (!page.messages.isEmpty && page.nextSyncCursor?.isEmpty == false && page.nextSyncCursor != cursor) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "History did not advance. No partial history was accepted.")
            }
            for message in page.messages {
                guard message.conversationId == room, !message.id.isEmpty, !message.id.hasPrefix("local_"),
                      PulseFormat.date(message.createdAt) != nil else {
                    throw PulseAPIClient.Failure(kind: .validation, message: "History returned an unrelated or invalid message.")
                }
                let position = PulseHistoryCursor(createdAt: message.createdAt, id: message.id)
                guard previous == nil || previous!.precedes(position) else {
                    throw PulseAPIClient.Failure(kind: .validation, message: "History repeated or reordered a page. No partial history was accepted.")
                }
                previous = position
            }
            bytes += try JSONEncoder().encode(page).count
            guard result.count + page.messages.count <= maximumRows, bytes <= maximumBytes else {
                throw PulseAPIClient.Failure(kind: .validation, message: "This chat exceeds the safe in-memory history limit. No partial export or clear was started.")
            }
            result.append(contentsOf: page.messages)
            if !page.hasMore { return result }
            cursor = page.nextSyncCursor
        }
    }
}
