import XCTest
@testable import Pulse

/// R2-D ITEM 8 — spotlight recent-searches store (web spotlight.tsx:46-73).
/// Pure-logic asserts run allSatisfy-style per the R1-CLOSE test lessons;
/// storage tests ride an isolated UserDefaults suite.
final class PulseSpotlightRecentsTests: XCTestCase {

    private func makeStore() -> (PulseSpotlightRecents, UserDefaults, String) {
        let suiteName = "pulse-spotlight-recents-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        return (PulseSpotlightRecents(defaults: defaults), defaults, suiteName)
    }

    // ── the pure sanitizer ──────────────────────────────────

    func testSanitizeDedupesCaseInsensitivelyKeepingNewest() {
        let out = PulseSpotlightRecents.sanitize([
            "contracts", "Contracts", "CONTRACTS", "party plans", "party Plans",
        ])
        // First (newest) occurrence wins; order preserved.
        XCTAssertEqual(out, ["contracts", "party plans"])
    }

    func testSanitizeCapsAtFiveAndDropsEmptyRows() {
        let input = ["", "   ", "a", "b", "c", "d", "e", "f"]
        let out = PulseSpotlightRecents.sanitize(input)
        XCTAssertEqual(out.count, PulseSpotlightRecents.maxItems)
        XCTAssertEqual(PulseSpotlightRecents.maxItems, 5)
        XCTAssertTrue(out.allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
        XCTAssertEqual(out, ["a", "b", "c", "d", "e"])
    }

    func testSanitizeTrimsStoredQueries() {
        let out = PulseSpotlightRecents.sanitize(["  beach trip  ", "\tbudget\t"])
        XCTAssertEqual(out, ["beach trip", "budget"])
    }

    // ── storage round trip ──────────────────────────────────

    func testPushPrependsAndDedupesAcrossWrites() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.push("contracts")
        store.push("party plans")
        XCTAssertEqual(store.read(), ["party plans", "contracts"])

        // Re-pushing an existing query (any case) moves it to the front.
        store.push("CONTRACTS")
        XCTAssertEqual(store.read(), ["contracts", "party plans"])
    }

    func testPushIgnoresBlankQueries() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.push("   ")
        store.push("")
        XCTAssertTrue(store.read().isEmpty)
        XCTAssertNil(defaults.string(forKey: PulseSpotlightRecents.storageKey))
    }

    func testStoreNeverGrowsPastFive() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        for query in ["one", "two", "three", "four", "five", "six"] {
            store.push(query)
        }
        let read = store.read()
        XCTAssertEqual(read.count, 5)
        XCTAssertEqual(read.first, "six")
        XCTAssertFalse(read.contains("one"))
    }

    func testClearRemovesEverything() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.push("contracts")
        store.push("invoices")
        XCTAssertFalse(store.read().isEmpty)
        store.clear()
        XCTAssertTrue(store.read().isEmpty)
        XCTAssertNil(defaults.string(forKey: PulseSpotlightRecents.storageKey))
    }

    func testKeyMatchesTheWebStorageKey() {
        XCTAssertEqual(PulseSpotlightRecents.storageKey, "pulse.spotlight.recents.v1")
    }

    func testJunkStoredPayloadsDegradeToEmpty() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        // Not JSON.
        defaults.set("not json at all", forKey: PulseSpotlightRecents.storageKey)
        XCTAssertTrue(store.read().isEmpty)
        // JSON but not an array of strings.
        defaults.set("{\"nope\":true}", forKey: PulseSpotlightRecents.storageKey)
        XCTAssertTrue(store.read().isEmpty)
    }
}
