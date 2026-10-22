import Foundation

/// Require a complete server-bounded snapshot; failures/cancellation must never
/// turn a successfully fetched prefix into an export or destructive clear sweep.
enum PulseCompleteHistory {
    static let start = "0001-01-01T00:00:00.000Z"
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
