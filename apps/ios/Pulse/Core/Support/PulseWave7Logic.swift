import Foundation

// ─────────────────────────────────────────────────────────────
// Wave 7 — pure, UI-free logic kernels (Collaboration & Hub).
// Ported 1:1 from the web so native behaviour matches exactly:
//   parseRelativeReminder ← src/components/chat/reminders-sheet.tsx:132
//   durationToMs/endOfDay ← reminders-sheet.tsx:118-130
//   tic-tac-toe win scan  ← src/app/api/games/[id]/move/route.ts:29-38
// ─────────────────────────────────────────────────────────────

public enum PulseWave7Logic {

    private static let durationToken = "(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|week|weeks)"

    /// Minutes→ms etc. — unit is matched on its first char exactly like the web.
    static func durationToMs(_ value: Int, _ unit: String) -> Int64? {
        switch unit.first {
        case "m": return Int64(value) * 60_000
        case "h": return Int64(value) * 3_600_000
        case "d": return Int64(value) * 86_400_000
        case "w": return Int64(value) * 7 * 86_400_000
        default: return nil
        }
    }

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
        if input.isEmpty { return nil }

        func endOfDayShift(_ hour: Int) -> Int64 {
            let comps = calendar.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(nowEpochMs) / 1000))
            var target = calendar.date(from: DateComponents(year: comps.year, month: comps.month, day: comps.day, hour: hour, minute: 0))!
            if Int64(target.timeIntervalSince1970) * 1000 <= nowEpochMs {
                target = calendar.date(byAdding: .day, value: 1, to: target)!
            }
            return Int64(target.timeIntervalSince1970) * 1000
        }

        func firstMatch(_ pattern: String) -> NSTextCheckingResult? {
            guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .anchorsMatchLines]) else { return nil }
            let full = NSRange(location: 0, length: (input as NSString).length)
            return re.firstMatch(in: input, options: [], range: full)
        }

        // Pattern 1: "… in 30m"
        if let m = firstMatch("\\s+in\\s+(\\d+)\\s*" + durationToken + "$") {
            let value = Int((input as NSString).substring(with: m.range(at: 1))) ?? 0
            let unit = (input as NSString).substring(with: m.range(at: 2))
            if let ms = durationToMs(value, unit.lowercased()) {
                let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
                if !note.isEmpty && note.lowercased() != "in" {
                    return ParsedReminder(note: note, remindAtEpochMs: nowEpochMs + ms)
                }
                return nil
            }
        }
        // Pattern 2: bare trailing "30m" — a leftover "in" is stripped from the note.
        if let m = firstMatch("\\s+(\\d+)\\s*" + durationToken + "$") {
            let value = Int((input as NSString).substring(with: m.range(at: 1))) ?? 0
            let unit = (input as NSString).substring(with: m.range(at: 2))
            if let ms = durationToMs(value, unit.lowercased()) {
                var note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
                if let strip = try? NSRegularExpression(pattern: "\\s+in$", options: [.caseInsensitive]) {
                    let full = NSRange(location: 0, length: (note as NSString).length)
                    note = strip.stringByReplacingMatches(in: note, options: [], range: full, withTemplate: "")
                        .trimmingCharacters(in: .whitespaces)
                }
                if !note.isEmpty && note.lowercased() != "in" {
                    return ParsedReminder(note: note, remindAtEpochMs: nowEpochMs + ms)
                }
                return nil
            }
        }
        // Pattern 3: "next week"
        if let m = firstMatch("\\s+next\\s+week$") {
            let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            if !note.isEmpty {
                return ParsedReminder(note: note, remindAtEpochMs: nowEpochMs + 7 * 86_400_000)
            }
            return nil
        }
        // Pattern 4: "tomorrow" (09:00 local)
        if let m = firstMatch("\\s+tomorrow$") {
            let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            if !note.isEmpty {
                return ParsedReminder(note: note, remindAtEpochMs: endOfDayShift(9))
            }
            return nil
        }
        // Pattern 5: "tonight" (20:00 local)
        if let m = firstMatch("\\s+tonight$") {
            let note = (input as NSString).substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            if !note.isEmpty {
                return ParsedReminder(note: note, remindAtEpochMs: endOfDayShift(20))
            }
            return nil
        }
        return nil
    }

    // MARK: payload decoders (tolerant)

    public static func redPacketPayload(_ payload: String?) -> WireRedPacketPayload? {
        guard let payload, !payload.isEmpty else { return nil }
        return try? JSONDecoder().decode(WireRedPacketPayload.self, from: Data(payload.utf8))
    }

    public static func gamePayload(_ payload: String?) -> WireGamePayload? {
        guard let payload, !payload.isEmpty else { return nil }
        return try? JSONDecoder().decode(WireGamePayload.self, from: Data(payload.utf8))
    }

    public static func tournamentPayload(_ payload: String?) -> WireTournamentPayload? {
        guard let payload, !payload.isEmpty else { return nil }
        return try? JSONDecoder().decode(WireTournamentPayload.self, from: Data(payload.utf8))
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

    /// Mirror of the server win scan — used to strike the winning line while polling.
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
        guard match.status == "active" else { return false }
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
