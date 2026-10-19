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
