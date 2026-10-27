import Foundation

/// Buffer bounded pages before accepting any of them as complete thread history.
enum PulseCompleteThread {
    static func load(rootId: String, maximumRows: Int = 10_000, maximumBytes: Int = 16 * 1024 * 1024,
                     maximumPages: Int = 512, fetch: (String?) async throws -> WireThreadSlice) async throws -> WireThreadPage {
        var replies: [WireChatMessage] = []
        var cursors: Set<String> = []
        var cursor: String?
        var room: String?
        var previous: PulseHistoryCursor?
        var bytes = 0, pages = 0
        func invalid(_ message: String) -> PulseAPIClient.Failure { .init(kind: .validation, message: message) }
        while true {
            try Task.checkCancellation()
            pages += 1
            guard pages <= maximumPages else { throw invalid("This thread exceeded the safe paging limit. No partial refresh was accepted.") }
            let page = try await fetch(cursor)
            try Task.checkCancellation()
            let parent = page.parent
            guard page.threadPageVersion == 1 else { throw invalid("Update the gateway to load complete threads.") }
            guard parent.id == rootId, parent.parentId == nil, parent.deletedAt == nil, !parent.conversationId.isEmpty,
                  room == nil || parent.conversationId == room, PulseRemediationLogic.isAlive(parent.expiresAt) else {
                throw invalid("Thread returned an unrelated or unavailable parent.")
            }
            room = parent.conversationId
            if page.hasMore {
                guard let next = page.nextCursor, !next.isEmpty, cursors.insert(next).inserted else {
                    throw invalid("Thread paging did not advance. No partial refresh was accepted.")
                }
            } else if page.nextCursor != nil { throw invalid("Thread completion was not confirmed.") }
            for reply in page.replies {
                guard !reply.id.isEmpty, reply.id != rootId, !reply.id.hasPrefix("local_"), reply.parentId == rootId,
                      reply.conversationId == room, PulseFormat.date(reply.createdAt) != nil else { throw invalid("Thread returned an unrelated reply.") }
                let position = PulseHistoryCursor(createdAt: reply.createdAt, id: reply.id)
                guard previous == nil || previous!.precedes(position) else { throw invalid("Thread repeated or reordered replies. No partial refresh was accepted.") }
                previous = position
            }
            bytes += try JSONEncoder().encode(page).count
            guard replies.count + page.replies.count <= maximumRows, bytes <= maximumBytes else {
                throw invalid("This thread exceeds the safe in-memory limit. No partial refresh was accepted.")
            }
            replies.append(contentsOf: page.replies)
            if !page.hasMore { return WireThreadPage(parent: parent, replies: replies) }
            cursor = page.nextCursor
        }
    }
}
