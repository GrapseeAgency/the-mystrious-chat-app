import XCTest
@testable import Pulse

/// R7 — message clustering kernel tests (web ground truth:
/// chat-room.tsx:1367-1424 buildItems — head on first row / day change /
/// sender change / anon-mask change / >5min gap; tail = last row OR next is
/// head). All inputs are plain ms epochs + strings, so the suite is
/// deterministic under test (no Date()/now inside the kernel).
final class PulseClusterTests: XCTestCase {

    // ── fixtures ─────────────────────────────────────────────

    /// 2026-03-10T04:00:00Z — a mid-afternoon-in-any-real-timezone instant,
    /// so ±5min probes can never straddle a calendar midnight (the day-change
    /// case is probed with an exact +24h step instead).
    private let baseMs: Double = 1_773_115_200_000

    private let minute: Double = 60_000

    private func flags(
        _ stamps: [Double],
        senders: [String],
        anon: [Bool] = [],
        aliases: [String?] = [],
    ) -> [PulseCluster.Flags] {
        let anonFilled: [Bool] = anon.isEmpty ? Array(repeating: false, count: stamps.count) : anon
        let aliasFilled: [String?] = aliases.isEmpty ? Array(repeating: nil, count: stamps.count) : aliases
        return PulseCluster.clusterFlags(
            createdAtMs: stamps,
            senderIds: senders,
            anon: anonFilled,
            anonAliases: aliasFilled,
        )
    }

    // ── the web rules ────────────────────────────────────────

    func testSameSenderWithinWindowIsOneCluster() {
        let out = flags(
            [baseMs, baseMs + minute, baseMs + 4 * minute],
            senders: ["a", "a", "a"],
        )
        XCTAssertEqual(out.map(\.head), [true, false, false], "same sender ≤5min → single head")
        XCTAssertEqual(out.map(\.tail), [false, false, true], "only the last row closes the cluster")
    }

    func testSenderChangeBreaksCluster() {
        let out = flags(
            [baseMs, baseMs + minute, baseMs + 2 * minute],
            senders: ["a", "a", "b"],
        )
        XCTAssertEqual(out.map(\.head), [true, false, true])
        // tail = last OR next-is-head (web :1420): row 1 closes because row 2 opens.
        XCTAssertEqual(out.map(\.tail), [false, true, true])
    }

    func testAnonToggleBreaksCluster() {
        let out = flags(
            [baseMs, baseMs + minute],
            senders: ["a", "a"],
            anon: [false, true],
        )
        XCTAssertEqual(out.map(\.head), [true, true], "an incognito send always opens a fresh cluster (web R24-b)")
    }

    func testAliasChangeBreaksCluster() {
        let same = flags(
            [baseMs, baseMs + minute],
            senders: ["a", "a"],
            anon: [true, true],
            aliases: ["Falcon", "Falcon"],
        )
        XCTAssertEqual(same.map(\.head), [true, false], "same mask alias keeps the cluster")

        let changed = flags(
            [baseMs, baseMs + minute],
            senders: ["a", "a"],
            anon: [true, true],
            aliases: ["Falcon", "Kestrel"],
        )
        XCTAssertEqual(changed.map(\.head), [true, true], "a different mask alias opens a fresh cluster")
    }

    func testDayChangeBreaksCluster() {
        // Exactly +24h — a different calendar day in every timezone.
        let out = flags(
            [baseMs, baseMs + 24 * 60 * minute],
            senders: ["a", "a"],
        )
        XCTAssertEqual(out.map(\.head), [true, true])
    }

    func testWindowBoundaryIsStrictlyGreaterThan() {
        // Exactly 5*60*1000 → still one cluster (web `> CLUSTER_WINDOW_MS`).
        let atLimit = flags([baseMs, baseMs + PulseCluster.clusterWindowMs], senders: ["a", "a"])
        XCTAssertEqual(atLimit.map(\.head), [true, false])

        // 1ms past the window → break.
        let pastLimit = flags([baseMs, baseMs + PulseCluster.clusterWindowMs + 1], senders: ["a", "a"])
        XCTAssertEqual(pastLimit.map(\.head), [true, true])
    }

    func testTailOnLastRowAndBeforeHeads() {
        let out = flags(
            [baseMs, baseMs + minute, baseMs + 6 * minute, baseMs + 7 * minute],
            senders: ["a", "a", "a", "a"],
        )
        XCTAssertEqual(out.map(\.head), [true, false, true, false])
        XCTAssertEqual(out.map(\.tail), [false, true, false, true])
    }

    func testEmptyAndSingleRowInputs() {
        XCTAssertEqual(PulseCluster.clusterFlags(createdAtMs: [], senderIds: [], anon: [], anonAliases: []).count, 0)
        let single = flags([baseMs], senders: ["a"])
        XCTAssertEqual(single, [PulseCluster.Flags(head: true, tail: true)])
    }
}
