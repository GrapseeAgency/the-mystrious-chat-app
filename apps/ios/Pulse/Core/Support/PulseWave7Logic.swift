import Foundation

// Wave 7 - pure, UI-free logic kernels (Collaboration & Hub).
// Ported 1:1 from the web so native behaviour matches exactly:
//   parseRelativeReminder ← src/components/chat/reminders-sheet.tsx:132
//   durationToMs/endOfDay ← reminders-sheet.tsx:118-130
//   tic-tac-toe win scan  ← src/app/api/games/[id]/move/route.ts:29-38

public enum PulseWave7Logic {

    private static let durationToken = "(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|week|weeks)"

    /// Minutes→ms etc. - unit is matched on its first char exactly like the web.
    static func durationToMs(_ value: Int, _ unit: String) -> Int64? {
        let multiplier: Int64
        switch unit.lowercased().first {
        case "s": multiplier = 1_000
        case "m": multiplier = 60_000
        case "h": multiplier = 3_600_000
        case "d": multiplier = 86_400_000
        case "w": multiplier = 604_800_000
        default: return nil
        }
        guard value > 0 else { return nil }
        let (duration, overflow) = Int64(value).multipliedReportingOverflow(by: multiplier)
        return overflow ? nil : duration
    }

    private static let minReminderEpoch: Int64 = -62_135_596_800_000
    private static let maxReminderEpoch: Int64 = 253_402_300_799_999

    public struct ParsedReminder: Equatable {
        public let note: String
        public let remindAtEpochMs: Int64
    }

    /// Parse the tail of a "/remind <note> <time>" draft into (note, remindAt).
    /// Accepted trailing times: "in 30m|2h|1d|2w", bare "30m|2h", "tomorrow"
    /// (09:00 local), "tonight" (20:00 local), "next week" (+7d).
    /// Case-insensitive; nil when no sane time token or empty note.
    public static func parseRelativeReminder(_ arg: String, nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970) * 1000, calendar: Calendar = .current) -> ParsedReminder? {
        let input = arg.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        guard !input.isEmpty, (minReminderEpoch...maxReminderEpoch).contains(nowEpochMs) else { return nil }

        func future(_ delta: Int64) -> Int64? {
            guard delta > 0, delta <= maxReminderEpoch - nowEpochMs else { return nil }
            return nowEpochMs + delta
        }
        func endOfDayShift(_ hour: Int, tomorrow: Bool = false) -> Int64? {
            let base = Date(timeIntervalSince1970: TimeInterval(nowEpochMs) / 1000)
            var comps = calendar.dateComponents([.era, .year, .month, .day], from: base)
            comps.hour = hour; comps.minute = 0; comps.second = 0; comps.nanosecond = 0
            guard var target = calendar.date(from: comps) else { return nil }
            if tomorrow || target <= base {
                guard let next = calendar.date(byAdding: .day, value: 1, to: target) else { return nil }
                target = next
            }
            let ms = target.timeIntervalSince1970 * 1000
            guard ms.isFinite, ms > Double(nowEpochMs), ms <= Double(maxReminderEpoch) else { return nil }
            return Int64(ms)
        }

        func firstMatch(_ pattern: String) -> NSTextCheckingResult? {
            guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .anchorsMatchLines]) else { return nil }
            let full = NSRange(location: 0, length: (input as NSString).length)
            return re.firstMatch(in: input, options: [], range: full)
        }

        // Pattern 1: "… in 30m"
        if let m = firstMatch("\\s+in\\s+(\\d+)\\s*" + durationToken + "$") {
            guard let value = Int((input as NSString).substring(with: m.range(at: 1))) else { return nil }
            let unit = (input as NSString).substring(with: m.range(at: 2))
            if let ms = durationToMs(value, unit), let at = future(ms) {
                let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
                if !note.isEmpty && note.lowercased() != "in" {
                    return ParsedReminder(note: note, remindAtEpochMs: at)
                }
                return nil
            }
        }
        // Pattern 2: bare trailing "30m" - a leftover "in" is stripped from the note.
        if let m = firstMatch("\\s+(\\d+)\\s*" + durationToken + "$") {
            guard let value = Int((input as NSString).substring(with: m.range(at: 1))) else { return nil }
            let unit = (input as NSString).substring(with: m.range(at: 2))
            if let ms = durationToMs(value, unit), let at = future(ms) {
                var note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
                if let strip = try? NSRegularExpression(pattern: "\\s+in$", options: [.caseInsensitive]) {
                    let full = NSRange(location: 0, length: (note as NSString).length)
                    note = strip.stringByReplacingMatches(in: note, options: [], range: full, withTemplate: "")
                        .trimmingCharacters(in: .whitespaces)
                }
                if !note.isEmpty && note.lowercased() != "in" {
                    return ParsedReminder(note: note, remindAtEpochMs: at)
                }
                return nil
            }
        }
        // Pattern 3: "next week"
        if let m = firstMatch("\\s+next\\s+week$") {
            let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            if !note.isEmpty, let at = future(604_800_000) {
                return ParsedReminder(note: note, remindAtEpochMs: at)
            }
            return nil
        }
        // Pattern 4: "tomorrow" (09:00 local)
        if let m = firstMatch("\\s+tomorrow$") {
            let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            if !note.isEmpty, let at = endOfDayShift(9, tomorrow: true) {
                return ParsedReminder(note: note, remindAtEpochMs: at)
            }
            return nil
        }
        // Pattern 5: "tonight" (20:00 local)
        if let m = firstMatch("\\s+tonight$") {
            let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            if !note.isEmpty, let at = endOfDayShift(20) {
                return ParsedReminder(note: note, remindAtEpochMs: at)
            }
            return nil
        }
        return nil
    }

    /// Separate Note/When fields must not interpret a time-field prefix as a replacement note.
    public static func parseReminderTime(_ text: String, nowEpochMs: Int64, calendar: Calendar = .current) -> Int64? {
        guard let parsed = parseRelativeReminder("reminder-time " + text.trimmingCharacters(in: .whitespacesAndNewlines),
            nowEpochMs: nowEpochMs, calendar: calendar), parsed.note == "reminder-time" else { return nil }
        return parsed.remindAtEpochMs
    }

    /// User-entered local time must survive an exact Gregorian round trip.
    /// Reject DST gaps/overlaps rather than silently picking a different time;
    /// an explicit ISO offset makes an ambiguous wall clock intentional.
    public static func parseEventStart(_ text: String, timeZone: TimeZone = .current) -> String? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let output = ISO8601DateFormatter()
        let wall = DateFormatter()
        wall.locale = Locale(identifier: "en_US_POSIX")
        wall.calendar = Calendar(identifier: .gregorian)
        wall.timeZone = TimeZone(secondsFromGMT: 0)
        wall.isLenient = false
        if value.range(of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$"#, options: .regularExpression) != nil {
            let clock = String(value.prefix(19))
            wall.dateFormat = "yyyy-MM-dd'T'HH:mm"
            // Validate the calendar day and minute separately from ISO's parser,
            // which may normalize an impossible date on some Foundation versions.
            let minute = String(clock.prefix(16))
            guard let nominal = wall.date(from: minute), wall.string(from: nominal) == minute,
                  let second = Int(clock.suffix(2)), (0...59).contains(second) else { return nil }
            let fractional = ISO8601DateFormatter()
            fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            guard let exact = fractional.date(from: value) ?? output.date(from: value) else { return nil }
            return value.contains(".") ? fractional.string(from: exact) : output.string(from: exact)
        }
        let local = value.count == 10 ? value + " 19:00" : value.replacingOccurrences(of: "T", with: " ")
        guard local.range(of: #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$"#, options: .regularExpression) != nil else { return nil }
        wall.dateFormat = "yyyy-MM-dd HH:mm"
        guard let nominal = wall.date(from: local), wall.string(from: nominal) == local else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: nominal)
        components.second = 0
        calendar.timeZone = timeZone
        guard let approximate = calendar.date(from: components) else { return nil }
        let start = calendar.startOfDay(for: approximate).addingTimeInterval(-1)
        guard let first = calendar.nextDate(after: start, matching: components, matchingPolicy: .strict, repeatedTimePolicy: .first),
              let last = calendar.nextDate(after: start, matching: components, matchingPolicy: .strict, repeatedTimePolicy: .last),
              first == last else { return nil }
        wall.timeZone = timeZone
        guard wall.string(from: first) == local else { return nil }
        return output.string(from: first)
    }

    // MARK: payload decoders (tolerant)

    public static func redPacketPayload(_ payload: String?) -> WireRedPacketPayload? {
        guard let payload, !payload.isEmpty else { return nil }
        return try? JSONDecoder().decode(WireRedPacketPayload.self, from: Data(payload.utf8))
    }

    public static func gamePayload(_ payload: String?) -> WireGamePayload? {
        guard let payload, !payload.isEmpty else { return nil }
        guard let result = try? JSONDecoder().decode(WireGamePayload.self, from: Data(payload.utf8)),
              !result.matchId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return result
    }

    public static func tournamentPayload(_ payload: String?) -> WireTournamentPayload? {
        guard let payload, !payload.isEmpty else { return nil }
        guard let result = try? JSONDecoder().decode(WireTournamentPayload.self, from: Data(payload.utf8)),
              !result.tournamentId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return result
    }

    // MARK: tic-tac-toe board

    public static let winLines: [[Int]] = [
        [0, 1, 2], [3, 4, 5], [6, 7, 8],
        [0, 3, 6], [1, 4, 7], [2, 5, 8],
        [0, 4, 8], [2, 4, 6],
    ]

    public static func cellAt(_ board: String?, _ index: Int) -> Character {
        guard let board, board.count == 9, index >= 0, index < 9 else { return " " }
        let chars = Array(board)
        return chars[index]
    }

    /// Mirror of the server win scan - used to strike the winning line while polling.
    public static func detectWinLine(_ board: String?) -> (Character, [Int])? {
        guard let board, board.count == 9 else { return nil }
        let chars = Array(board)
        for line in winLines {
            let v = chars[line[0]]
            if v != " " && v == chars[line[1]] && v == chars[line[2]] {
                return (v, line)
            }
        }
        return nil
    }

    public static func sideOf(_ match: WireGameMatch, _ viewerId: String) -> Character? {
        if match.playerXId == viewerId { return "X" }
        if let o = match.playerOId, o == viewerId { return "O" }
        return nil
    }

    public static func isMyTurn(_ match: WireGameMatch, _ viewerId: String) -> Bool {
        guard match.status == "active", let opponent = match.playerOId, !opponent.isEmpty, opponent != match.playerXId, !viewerId.isEmpty else { return false }
        guard let side = sideOf(match, viewerId) else { return false }
        return match.turn == String(side)
    }

    // MARK: check-in window + wallet rules

    /// Check-in window: startsAt − 15 min … + 2 h (server checkin route:36-37).
    public static func checkinWindowOpen(startsAtEpochMs: Int64, nowEpochMs: Int64) -> Bool {
        nowEpochMs >= startsAtEpochMs - 15 * 60_000 && nowEpochMs <= startsAtEpochMs + 2 * 3_600_000
    }

    /// Streak bonus display rule: +2/day capped +20 on the 25 PC base.
    public static func checkinReward(_ streakAfter: Int) -> Int {
        25 + min(max(streakAfter - 1, 0) * 2, 20)
    }

    public static let kanbanColumns = ["todo", "doing", "done"]
    public static let taskStatuses = ["todo", "doing", "done"]
    public static let rsvpStatuses = ["going", "maybe", "no"]
}
