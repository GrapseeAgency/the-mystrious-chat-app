import Foundation

// R5-A Item 6 - scheduled-chip next-dispatch pick (web scheduledChip
// memo, chat-room.tsx:2401-2405). The web takes items[0] of the list
// the API returns (pending rows soonest-first, refused rows last);
// computing the min over the NON-CANCELLED rows gives the same
// verdict and stays honest when the locally-cached list order drifts
// (e.g. after an in-place append from scheduleDraft).
enum PulseScheduledChip {
    /// "Tomorrow" means the next local calendar date, including before 09:00
    /// and across daylight-saving boundaries; it is not "the next 09:00".
    static func tomorrowMorning(now: Date = Date(), calendar: Calendar = .current) -> Date? {
        guard let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) else { return nil }
        return calendar.date(bySettingHour: 9, minute: 0, second: 0, of: tomorrow)
    }

    /// Snapshot once per presentation, not once per render or retry tap.
    static func presetDates(now: Date = Date(), calendar: Calendar = .current) -> [Date?] {
        [now.addingTimeInterval(3600), tomorrowMorning(now: now, calendar: calendar), now.addingTimeInterval(7 * 86400)]
    }

    struct Attempt: Equatable {
        let text: String
        let date: Date
    }

    static func canSubmit(text: String, date: Date, now: Date = Date(), previous: Attempt?) -> Bool {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, body.utf16.count <= 2000 else { return false }
        return previous == Attempt(text: body, date: date) ||
            (date.timeIntervalSince(now) >= 30 && date.timeIntervalSince(now) <= 30 * 86400)
    }

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
