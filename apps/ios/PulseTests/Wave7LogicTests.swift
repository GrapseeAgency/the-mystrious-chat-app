import XCTest
@testable import Pulse

/// Wave 7 - pure-logic parity tests (Collaboration & Hub):
/// relative-reminder parse (web reminders-sheet.tsx:132), tic-tac-toe
/// board scan, check-in window/reward rules, payload decode.
@MainActor
final class Wave7LogicTests: XCTestCase {

    private let zone = Calendar.current

    private func date(_ comps: DateComponents) -> Int64 {
        Int64(zone.date(from: comps)!.timeIntervalSince1970) * 1000
    }

    // parseRelativeReminder

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
        let nowComps = DateComponents(year: 2026, month: 5, day: 14, hour: 10)
        let now = cal.date(from: nowComps)!
        let nowMs = Int64(now.timeIntervalSince1970) * 1000
        let parsed = PulseWave7Logic.parseRelativeReminder("Call mom tomorrow", nowEpochMs: nowMs)
        XCTAssertEqual(parsed?.note, "Call mom")
        let expectedDate = cal.date(from: DateComponents(year: 2026, month: 5, day: 15, hour: 9))!
        XCTAssertEqual(parsed?.remindAtEpochMs, Int64(expectedDate.timeIntervalSince1970) * 1000)
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

    func testOversizedNonfutureAndUnrepresentableDurationsAreRejectedWithoutTrapping() {
        let now: Int64 = 1_768_450_800_000
        for token in ["0m", "999999999999999999999999h", "9223372036854775807w", "999999999999d"] {
            XCTAssertNil(PulseWave7Logic.parseRelativeReminder("keep note in \(token)", nowEpochMs: now), token)
            XCTAssertNil(PulseWave7Logic.parseRelativeReminder("keep note \(token)", nowEpochMs: now), token)
        }
        for clock in [Int64.max, Int64.min, 253_402_300_799_999] {
            for token in ["1s", "next week", "tomorrow", "tonight"] {
                XCTAssertNil(PulseWave7Logic.parseRelativeReminder("note \(token)", nowEpochMs: clock))
            }
        }
        XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("note 1H", nowEpochMs: now)?.remindAtEpochMs, now + 3_600_000)
        XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("note 1SECOND", nowEpochMs: now)?.remindAtEpochMs, now + 1_000)
    }

    func testTomorrowIsNextCalendarDayBeforeNineAndAcrossDST() throws {
        for (zone, month, day) in [("Asia/Dhaka", 1, 15), ("America/New_York", 3, 7)] {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = try XCTUnwrap(TimeZone(identifier: zone))
            let before = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: 8)))
            let after = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: month, day: day + 1, hour: 9)))
            XCTAssertEqual(PulseWave7Logic.parseRelativeReminder("note tomorrow", nowEpochMs: Int64(before.timeIntervalSince1970 * 1000), calendar: calendar)?.remindAtEpochMs,
                Int64(after.timeIntervalSince1970 * 1000))
        }
    }

    func testSlashTransformsKeepEveryUnicodeUnitOrRejectTheWholeOperation() {
        let atLimit = String(repeating: "🌿", count: 999)
        XCTAssertEqual(PulseRemediationLogic.applySlash("/me " + atLimit), .send("_" + atLimit + "_"))
        for text in ["/me " + atLimit + "a", "/me " + String(repeating: "a", count: 2000), "/shrug " + String(repeating: "a", count: 1995)] {
            guard case .error = PulseRemediationLogic.applySlash(text) else { XCTFail("Silently shortened or oversize result"); continue }
        }
    }

    func testSeparateWhenFieldAcceptsItsAdvertisedExamplesWithoutReplacingTheNote() {
        let now: Int64 = 1_768_450_800_000
        XCTAssertEqual(PulseWave7Logic.parseReminderTime("in 30m", nowEpochMs: now), now + 1_800_000)
        XCTAssertEqual(PulseWave7Logic.parseReminderTime("30m", nowEpochMs: now), now + 1_800_000)
        XCTAssertNotNil(PulseWave7Logic.parseReminderTime("tomorrow", nowEpochMs: now))
        XCTAssertNotNil(PulseWave7Logic.parseReminderTime("tonight", nowEpochMs: now))
        XCTAssertNil(PulseWave7Logic.parseReminderTime("replace the note in 30m", nowEpochMs: now))
        XCTAssertNil(PulseWave7Logic.parseReminderTime("in 9223372036854775807w", nowEpochMs: now))
    }

    func testAbsoluteEventAndReminderTimesRejectCalendarAndDSTSurprises() throws {
        let dhaka = try XCTUnwrap(TimeZone(identifier: "Asia/Dhaka"))
        XCTAssertEqual(PulseWave7Logic.parseEventStart("2026-10-22 18:00", timeZone: dhaka), "2026-10-22T12:00:00Z")
        XCTAssertEqual(PulseWave7Logic.parseEventStart("2026-10-22T18:00", timeZone: dhaka), "2026-10-22T12:00:00Z")
        XCTAssertEqual(PulseWave7Logic.parseEventStart("2026-10-22", timeZone: dhaka), "2026-10-22T13:00:00Z")
        XCTAssertEqual(PulseWave7Logic.parseEventStart("2026-10-22T18:00:00.123+06:00", timeZone: dhaka), "2026-10-22T12:00:00.123Z")
        for value in ["2026-02-30 12:00", "2026-02-30T12:00:00Z", "2026-10-22 24:30", "2026-10-22 18:00 junk"] {
            XCTAssertNil(PulseWave7Logic.parseEventStart(value, timeZone: dhaka), value)
            XCTAssertNil(Wave7EventsSheet.parseDate(value), value)
            XCTAssertNil(Wave7RemindersSheet.parseIsoLoose(value), value)
        }
        let ny = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        XCTAssertNil(PulseWave7Logic.parseEventStart("2026-03-08 02:30", timeZone: ny))
        XCTAssertNil(PulseWave7Logic.parseEventStart("2026-11-01 01:30", timeZone: ny))
        XCTAssertEqual(PulseWave7Logic.parseEventStart("2026-11-01T01:30:00-04:00", timeZone: ny), "2026-11-01T05:30:00Z")
        XCTAssertEqual(PulseWave7Logic.parseEventStart("2026-11-01T01:30:00-05:00", timeZone: ny), "2026-11-01T06:30:00Z")
    }

    // payload decoders

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
        XCTAssertNil(PulseWave7Logic.gamePayload(#"{"matchId":" "}"#))
        XCTAssertNil(PulseWave7Logic.tournamentPayload(#"{"tournamentId":""}"#))
        XCTAssertEqual(PulseWave7Logic.tournamentPayload(#"{"tournamentId":"t1","name":"Friday","game":"tictactoe"}"#)?.tournamentId, "t1")
    }

    // tic-tac-toe board

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

    private func makeMatch(
        turn: String = "X",
        status: String = "active",
        playerOId: String? = nil,
    ) -> WireGameMatch {
        WireGameMatch(
            id: "m", conversationId: nil, game: nil, playerXId: "u1", playerOId: playerOId,
            board: "         ", turn: turn, status: status, winnerId: nil, winLine: nil,
            moveCount: 0, createdAt: nil, updatedAt: nil,
        )
    }

    func testSideOfAndTurnGate() {
        let match = makeMatch()
        XCTAssertEqual(PulseWave7Logic.sideOf(match, "u1"), "X")
        XCTAssertNil(PulseWave7Logic.sideOf(match, "u2"))
        XCTAssertFalse(PulseWave7Logic.isMyTurn(match, "u1"))
        XCTAssertTrue(PulseWave7Logic.isMyTurn(makeMatch(playerOId: "u2"), "u1"))
        XCTAssertFalse(PulseWave7Logic.isMyTurn(match, "u2"))
        XCTAssertFalse(PulseWave7Logic.isMyTurn(makeMatch(status: "x_won"), "u1"))
        XCTAssertTrue(PulseWave7Logic.isMyTurn(makeMatch(turn: "O", playerOId: "u2"), "u2"))
    }

    // check-in window + wallet rules

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
