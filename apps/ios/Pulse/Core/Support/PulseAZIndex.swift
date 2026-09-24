import Foundation

// ─────────────────────────────────────────────────────────────
// R5-A Item 2 — A–Z directory grouping (web contacts-tab.tsx
// indexLetterOf :53-57 + sections memo :125-137). Pure kernel so the
// Contacts list and its index rail stay testable:
//   • bucket by the first character of the display name,
//     case-insensitive;
//   • anything that is not A–Z (digits, emoji, non-Latin letters)
//     lands in the "#" bucket;
//   • letters sort A→Z (localized), "#" sorts LAST (web parity);
//   • original input order is preserved inside a bucket (the users
//     API already orders by name).
// ─────────────────────────────────────────────────────────────
enum PulseAZIndex {
    struct Section<Item> {
        let letter: String
        let items: [Item]
    }

    /// "ada" → "A" · "  Bob" → "B" · "3milio" → "#" · "émile" → "#" · "" → "#".
    /// Unicode-scalar range check — no Character == closure predicates
    /// (house rule after the R1-CLOSE r8-r12 CI runner findings).
    static func letter(for name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.uppercased().unicodeScalars.first else { return "#" }
        // U+0041 ("A") … U+005A ("Z") — the plain Latin uppercase range.
        if first.value >= 65 && first.value <= 90 { return String(first) }
        return "#"
    }

    /// Group items into letter sections, "#" last, letters A→Z between.
    static func sections<Item>(_ items: [Item], nameOf: (Item) -> String) -> [Section<Item>] {
        var order: [String] = []
        var buckets: [String: [Item]] = [:]
        for item in items {
            let bucket = letter(for: nameOf(item))
            if buckets[bucket] == nil { order.append(bucket) }
            buckets[bucket, default: []].append(item)
        }
        let sortedBuckets = order.sorted { lhs, rhs in
            if lhs == "#" { return false }
            if rhs == "#" { return true }
            return lhs.localizedStandardCompare(rhs) == .orderedAscending
        }
        return sortedBuckets.map { bucket in
            Section(letter: bucket, items: buckets[bucket] ?? [])
        }
    }
}
