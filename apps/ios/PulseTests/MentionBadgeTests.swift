import XCTest
@testable import Pulse

final class MentionBadgeTests: XCTestCase {
    func testTraversesEmptyScanWindowsAndCapsLiveIdentities() async throws {
        var calls = 0
        let snapshot = try await PulseMentionBadgeSnapshot.load { cursor in
            let page = Int(cursor ?? "0")!
            calls += 1
            let rows: [[String: Any]] = page < 120 ? [] : (0..<50).map { ["messageId": "\(page)-\($0)", "conversationId": "r"] }
            return try JSONDecoder().decode(WireMentionEntriesPage.self, from: JSONSerialization.data(withJSONObject:
                ["items": rows, "feedPageVersion": 1, "hasMore": true, "nextCursor": "\(page + 1)"]))
        }
        XCTAssertEqual(snapshot.count, 100); XCTAssertEqual(calls, 122); XCTAssertNil(snapshot.expiresAt)
    }
    func testRejectsNonAdvancingCursorAndMissingMetadata() async throws {
        for body in [#"{"items":[],"hasMore":false}"#,
                     #"{"items":[],"feedPageVersion":1,"hasMore":true,"nextCursor":"repeat"}"#] {
            var calls = 0
            do {
                _ = try await PulseMentionBadgeSnapshot.load { _ in
                    calls += 1
                    return try JSONDecoder().decode(WireMentionEntriesPage.self, from: Data(body.utf8))
                }
                XCTFail("Invalid feed accepted")
            } catch { XCTAssertLessThanOrEqual(calls, 2) }
        }
    }
    func testExpiredAndDuplicateRowsDoNotInflateCount() async throws {
        let snapshot = try await PulseMentionBadgeSnapshot.load { _ in
            try JSONDecoder().decode(WireMentionEntriesPage.self, from: Data(#"{"items":[{"messageId":"a","conversationId":"r"},{"messageId":"a","conversationId":"r"},{"messageId":"b","conversationId":"r","expiresAt":"1970-01-01T00:00:01Z"}],"feedPageVersion":1,"hasMore":false}"#.utf8))
        }
        XCTAssertEqual(snapshot.count, 1)
    }
}
