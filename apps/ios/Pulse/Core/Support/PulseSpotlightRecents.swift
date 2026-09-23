import Foundation

// ─────────────────────────────────────────────────────────────
// R2-D ITEM 8 — spotlight recent searches (web spotlight.tsx:46-73
// parity, iOS ChatsView search field). Persisted under the web's exact
// localStorage key `pulse.spotlight.recents.v1` as a JSON array of strings:
//   • newest-first push, case-insensitive dedupe (web pushRecent);
//   • capped at the newest 5 (RECENTS_MAX);
//   • empty/whitespace queries never stored;
//   • clear action removes the key (web clearRecents).
// Storage failures degrade honestly — recents are best-effort, the search
// itself never depends on them.
// ─────────────────────────────────────────────────────────────

public struct PulseSpotlightRecents: Sendable {
    /// Byte-same key as the web store (spotlight.tsx RECENTS_KEY).
    public static let storageKey = "pulse.spotlight.recents.v1"
    /// Web RECENTS_MAX.
    public static let maxItems = 5

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The persisted recents, newest first. Junk payloads (non-array, non-
    /// string rows, wrong JSON) degrade to an empty list — web readRecents.
    public func read() -> [String] {
        guard let raw = defaults.string(forKey: Self.storageKey),
              let data = raw.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String] else {
            return []
        }
        return Self.sanitize(parsed)
    }

    /// Web pushRecent parity: trim, drop the previous occurrence
    /// (case-insensitive), prepend, cap at 5. Empty queries are ignored.
    public func push(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let lowered = trimmed.lowercased()
        let next = [trimmed] + read().filter { $0.lowercased() != lowered }
        write(Self.sanitize(next))
    }

    /// Web clearRecents parity — the key goes away entirely.
    public func clear() {
        defaults.removeObject(forKey: Self.storageKey)
    }

    private func write(_ items: [String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: items),
              let raw = String(data: data, encoding: .utf8) else { return }
        defaults.set(raw, forKey: Self.storageKey)
    }

    /// Pure normalization — shared by read/push (and tests): drops empty and
    /// whitespace-only rows, case-insensitive dedupe keeping the FIRST
    /// (newest) occurrence, capped at RECENTS_MAX.
    public static func sanitize(_ items: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for item in items {
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            if seen.insert(trimmed.lowercased()).inserted {
                out.append(trimmed)
            }
            if out.count >= maxItems { break }
        }
        return out
    }
}
