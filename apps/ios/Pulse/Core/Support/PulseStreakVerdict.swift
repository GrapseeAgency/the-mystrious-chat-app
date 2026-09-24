import Foundation

// ─────────────────────────────────────────────────────────────
// R5-A Item 5 — streak nudge verdict (web chat-room.tsx:1736-1751
// verbatim). The toast fires ONLY when THIS send GREW the streak —
// i.e. the second-or-later consecutive day: `streak.continued` must
// be true AND `streak.count` must be ≥ 2. Restarts (continued=false)
// and same-day re-sends (no streak sibling at all) stay silent —
// one send = one bump per UTC day, server-decided (route.ts:678-706).
// Web copy, character-for-character:
//   count === 2 → "2-day streak — keep it alive"
//   count  > 2  → "{count}-day streak"
// (`best` rides the wire but the web toast does not surface it.)
// ─────────────────────────────────────────────────────────────
enum PulseStreakVerdict {
    /// nil = stay silent (restart / same-day resend / first day / junk).
    static func toastLine(count: Int?, continued: Bool?) -> String? {
        guard let count, count >= 2 else { return nil }
        guard continued == true else { return nil }
        if count == 2 { return "2-day streak — keep it alive" }
        return "\(count)-day streak"
    }
}
