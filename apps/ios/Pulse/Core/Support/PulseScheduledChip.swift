import Foundation

// ─────────────────────────────────────────────────────────────
// R5-A Item 6 — scheduled-chip next-dispatch pick (web scheduledChip
// memo, chat-room.tsx:2401-2405). The web takes items[0] of the list
// the API returns (pending rows soonest-first, refused rows last);
// computing the min over the NON-CANCELLED rows gives the same
// verdict and stays honest when the locally-cached list order drifts
// (e.g. after an in-place append from scheduleDraft).
// ─────────────────────────────────────────────────────────────
enum PulseScheduledChip {
    /// The scheduledAt ISO of the soonest non-cancelled row, or nil when
    /// every row is refused/cancelled (or nothing is schedulable).
    static func nextIso(in items: [WireScheduledItem]) -> String? {
        var soonestIso: String?
        var soonestDate: Date?
        for item in items {
            guard item.cancelledAt == nil else { continue }
            guard let date = PulseFormat.date(item.scheduledAt) else { continue }
            if soonestDate == nil || date < soonestDate! {
                soonestDate = date
                soonestIso = item.scheduledAt
            }
        }
        return soonestIso
    }
}
