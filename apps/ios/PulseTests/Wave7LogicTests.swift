import XCTest
@testable import Pulse

/// Wave 7 — pure-logic parity tests (Collaboration & Hub):
/// relative-reminder parse (web reminders-sheet.tsx:132), tic-tac-toe
/// board scan, check-in window/reward rules, payload decode.
@MainActor
final class Wave7LogicTests: XCTestCase {

    private let zone = Calendar.current

    private func date(_ comps: DateComponents) -> Int64 {
        Int64(zone.date(from: comps)!.timeIntervalSince1970) * 1000
    }

    // ── parseRelativeReminder ────────────────────────────────────

    func testInRelativeMinutes() throws {
        let now: Int64 = 1_768_450_800_000
        let parsed = PulseWave7Logic.parseRelativeReminder("Stand up in 30m", nowEpochMs: now)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.note, "Stand up")
        XCTAssertEqual(parsed?.remindAtEpochMs, now + 30 * 60_000)
    }

    func testBareTokenStripsLeftoverIn() throws {
        let now: Int64 = 1_768_450_800_000
        let parsed = PulseWave7Logic.parseRelativeReminder("log in in 30m", nowEpochMs: now)
        XCTAssertEqual(parsed?.note, "log in")
        XCTAssertEqual(parsed?.remindAtEpochMs, now + 30 * 60_000)
    }

    func testHoursDaysWeeks() {
        let now: Int64 = 1_768_450_800_000
        XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("x 2h", nowEpochMs: now)?.remindAtEpochMs, now + 2 * 3_600_000)
        XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("x 1day", nowEpochMs: now)?.remindAtEpochMs, now + 86_400_000)
        XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("x 2w", nowEpochMs: now)?.remindAtEpochMs, now + 14 * 86_400_000)
    }

    func testTomorrowShiftsTo9am() throws {
        let cal = Calendar.current
        let now = cal.date(from: DateComponents(year: 2026, month: 1, hour: 10))!
        let nowMs = Int64(now.timeIntervalSince1970) * 1000
        let parsed = PulseWave7Logic.parseRelativeReminder("Call mom tomorrow", nowEpochMs: nowMs)
        XCTAssertEqual(parsed?.note, "Call mom")
        let expected = cal.date(from: DateComponents(year: 2026, month: 1, day: 3, hour: 9))! // day = now.day+1
        let expectedMs = Int64(cal.date(byAdding: .day, value: 1, to: cal.date(from: DateComponents(year: 2026, month: 1, day: now.day, hour: 9)))!.timeIntervalSince1970) * 1000
        XCTAssertEqual(parsed?.remindAtEpochMs, expectedMs)
        _ = expected // silence unused when timezone shifts the fixture day
    }

    func testTonightShiftsTo8pmOrTomorrow() throws {
        let cal = Calendar.current
        let morning = cal.date(from: DateComponents(year: 2026, month: 3, day: 10, hour: 10))!
        let morningMs = Int64(morning.timeIntervalSince1970) * 1000
        let parsed = PulseWave7Logic.parseRelativeReminder("Party tonight", nowEpochMs: morningMs)
        XCTAssertEqual(parsed?.remindAtEpochMs, Int64(cal.date(from: DateComponents(year: 2026, month: 3, day: 10, hour: 20))!.timeIntervalSince1970) * 1000)

        let late = cal.date(from: DateComponents(year: 2026, month: 3, day: 10, hour: 23))!
        let lateMs = Int64(late.timeIntervalSince1970) * 1000
        let parsed2 = PulseWave7Logic.parseRelativeReminder("Party tonight", nowEpochMs: lateMs)
        XCTAssertEqual(parsed2?.remindAtEpochMs, Int64(cal.date(from: DateComponents(year: 2026, month: 3, day: 11, hour: 20))!.timeIntervalSince1970) * 1000)
    }

    func testNextWeekAdds7Days() {
        let now: Int64 = 1_768_450_800_000
        XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("x next week", nowEpochMs: now)?.remindAtEpochMs, now + 7 * 86_400_000)
    }

    func testEmptyNoteOrNoTokenIsNull() {
        let now: Int64 = 1_768_450_800_000
        XCTAssertNil(PulseWave7Logic.parseRelativeReminder("", nowEpochMs: now))
        XCTAssertNil(PulseWave7Logic.parseRelativeReminder("no time here", nowEpochMs: now))
        XCTAssertNil(PulseWave7Logic.parseRelativeReminder("in 30m", nowEpochMs: now))
        XCTAssertNil(PulseWave7Logic.parseRelativeReminder("buy milk 5parsecs", nowEpochMs: now))
    }

    func testCaseInsensitiveTokens() {
        let now: Int64 = 1_768_450_800_000
        let parsed = PulseWave7Logic.parseRelativeReminder("Ping TOMORROW", nowEpochMs: now)
        XCTAssertEqual(parsed?.note, "Ping")
    }

    // ── payload decoders ─────────────────────────────────────────

    func testRedPacketPayloadDecodes() throws {
        let p = PulseWave7Logic.redPacketPayload(#"{"packetId":"p1","total":100,"count":5,"note":"新年快乐"}"#)
        XCTAssertEqual(p?.packetId, "p1")
        XCTAssertEqual(p?.total, 100)
        XCTAssertEqual(p?.count, 5)
        XCTAssertEqual(p?.note, "新年快乐")
        XCTAssertNil(PulseWave7Logic.redPacketPayload(nil))
        XCTAssertNil(PulseWave7Logic.redPacketPayload("not json"))
    }

    func testGameAndTournamentPayloadDecode() {
        XCTAssertEqual(PulseWave7Logic.gamePayload(#"{"matchId":"m1","game":"tictactoe"}"#)?.matchId, "m1")
        XCTAssertNil(PulseWave7Logic.gamePayload(nil))
        XCTAssertEqual(PulseWave7Logic.tournamentPayload(#"{"tournamentId":"t1","name":"Friday","game":"tictactoe"}"#)?.tournamentId, "t1")
    }

    // ── tic-tac-toe board ────────────────────────────────────────

    func testDetectWinLine() {
        XCTAssertEqual(PulseWave7Logic.detectWinLine("XXX      ")?.1, [0, 1, 2])
        XCTAssertEqual(PulseWave7Logic.detectWinLine("O   O   O")?.1, [0, 4, 8])
        XCTAssertEqual(PulseWave7Logic.detectWinLine("O  O  O  ")?.1, [0, 3, 6])
        XCTAssertEqual(PulseWave7Logic.detectWinLine("  O O O  ")?.1, [2, 4, 6])
        XCTAssertNil(PulseWave7Logic.detectWinLine("XOX      "))
        XCTAssertNil(PulseWave7Logic.detectWinLine("bad"))
    }

    func testCellAtPaddedBoard() {
        let board = "X O      "
        XCTAssertEqual(PulseWave7Logic.cellAt(board, 0), "X")
        XCTAssertEqual(PulseWave7Logic.cellAt(board, 2), "O")
        XCTAssertEqual(PulseWave7Logic.cellAt(board, 8), " ")
        XCTAssertEqual(PulseWave7Logic.cellAt(board, 9), " ")
        XCTAssertEqual(PulseWave7Logic.cellAt("short", 0), " ")
    }

    func testSideOfAndTurnGate() {
        let match = WireGameMatch(id: "m", conversationId: nil, game: nil, playerXId: "u1", playerOId: nil, board: "         ", turn: "X", status: "active", winnerId: nil, winLine: nil, moveCount: 0, createdAt: nil, updatedAt: nil)
        XCTAssertEqual(PulseWave7Logic.sideOf(match, "u1"), "X")
        XCTAssertNil(PulseWave7Logic.sideOf(match, "u2"))
        XCTAssertTrue(PulseWave7Logic.isMyTurn(match, "u1"))
        XCTAssertFalse(PulseWave7Logic.isMyTurn(match, "u2"))
        var done = match
        done.status = "x_won"
        XCTAssertFalse(PulseWave7Logic.isMyTurn(done, "u1"))
        var theirs = match
        theirs.turn = "O"
        theirs.playerOId = "u2"
        XCTAssertTrue(PulseWave7Logic.isMyTurn(theirs, "u2"))
    }

    // ── check-in window + wallet rules ───────────────────────────

    func testCheckinWindow() {
        let start: Int64 = 1_768_500_000_000
        XCTAssertTrue(PulseWave7Logic.checkinWindowOpen(startsAtEpochMs: start, nowEpochMs: start - 15 * 60_000))
        XCTAssertFalse(PulseWave7Logic.checkinWindowOpen(startsAtEpochMs: start, nowEpochMs: start - 16 * 60_000))
        XCTAssertTrue(PulseWave7Logic.checkinWindowOpen(startsAtEpochMs: start, nowEpochMs: start + 2 * 3_600_000))
        XCTAssertFalse(PulseWave7Logic.checkinWindowOpen(startsAtEpochMs: start, nowEpochMs: start + 2 * 3_600_000 + 1))
    }

    func testCheckinRewardStreakRule() {
        XCTAssertEqual(PulseWave7Logic.checkinReward(1), 25)
        XCTAssertEqual(PulseWave7Logic.checkinReward(2), 27)
        XCTAssertEqual(PulseWave7Logic.checkinReward(11), 45)
        XCTAssertEqual(PulseWave7Logic.checkinReward(30), 45)
    }
}
