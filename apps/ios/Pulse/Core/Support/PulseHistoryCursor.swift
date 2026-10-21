import Foundation

/// Paging follows raw server rows, including replies hidden from the main river.
struct PulseHistoryCursor: Equatable {
    let createdAt: String
    let id: String

    static func oldest(_ rows: [WireChatMessage]) -> PulseHistoryCursor? {
        rows.filter { !$0.id.hasPrefix("local_") }.map {
            PulseHistoryCursor(createdAt: $0.createdAt, id: $0.id)
        }.min { $0.precedes($1) }
    }

    func precedes(_ other: PulseHistoryCursor) -> Bool {
        let left = PulseFormat.date(createdAt) ?? .distantPast
        let right = PulseFormat.date(other.createdAt) ?? .distantPast
        return left == right ? id < other.id : left < right
    }
}

/// A late latest-page response must not rewind a page consumed by the reader.
/// Cursor and exhaustion are one state; even an empty page advances the revision.
struct PulseHistoryWindow {
    private(set) var cursor: PulseHistoryCursor?
    private(set) var hasMore = false
    private(set) var revision: UInt64 = 0

    // No cursor means no proven history boundary: an explicit jump may fetch
    // the latest page, including after an empty/offline initial load.
    var canExpandForJump: Bool { cursor == nil || hasMore }

    mutating func invalidateSeed() { revision &+= 1 }

    mutating func record(cursor: PulseHistoryCursor?, hasMore: Bool) {
        self.cursor = cursor
        self.hasMore = hasMore
        invalidateSeed()
    }

    @discardableResult
    mutating func seed(cursor: PulseHistoryCursor?, hasMore: Bool, ifUnchanged ticket: UInt64) -> Bool {
        guard revision == ticket else { return false }
        record(cursor: cursor, hasMore: hasMore)
        return true
    }
}
