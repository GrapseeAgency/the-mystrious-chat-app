import XCTest
@testable import Pulse

@MainActor
final class RoomPrivacyIntentTests: XCTestCase {
    func testLateReadsPendingReopenAndABAReceipts() throws {
        let suite = "RoomPrivacyIntent.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        var tickets: [PulseScreenPrivacyTicket] = []
        prefs.patchScreenPrivacy = { tickets.append($0) }
        let before = try XCTUnwrap(prefs.screenPrivacyRead(conversationId: "room"))
        prefs.setScreenPrivacy(conversationId: "room", on: true)
        prefs.adoptServerScreenPrivacy(before, on: false)
        XCTAssertEqual(prefs.screenPrivacy["room"], true)
        let during = try XCTUnwrap(prefs.screenPrivacyRead(conversationId: "room"))
        try prefs.acknowledgeScreenPrivacy(tickets[0], on: true)
        prefs.adoptServerScreenPrivacy(during, on: false)
        XCTAssertEqual(prefs.screenPrivacy["room"], true)
        XCTAssertFalse(prefs.screenPrivacyPending.contains("room"))
        prefs.setScreenPrivacy(conversationId: "room", on: true)
        prefs.setScreenPrivacy(conversationId: "room", on: false)
        prefs.setScreenPrivacy(conversationId: "room", on: true)
        try prefs.acknowledgeScreenPrivacy(tickets[1], on: true)
        XCTAssertTrue(prefs.screenPrivacyPending.contains("room"))
        let reopened = PulsePrefs(defaults: defaults)
        XCTAssertEqual(reopened.screenPrivacy["room"], true)
        XCTAssertTrue(reopened.screenPrivacyPending.contains("room"))
        var retry: PulseScreenPrivacyTicket?
        reopened.patchScreenPrivacy = { retry = $0 }
        reopened.retryScreenPrivacy(conversationId: "room")
        XCTAssertEqual(retry?.id, tickets.last?.id)
        try reopened.acknowledgeScreenPrivacy(try XCTUnwrap(retry), on: true)
        XCTAssertFalse(reopened.screenPrivacyPending.contains("room"))
        reopened.adoptServerScreenPrivacy(try XCTUnwrap(reopened.screenPrivacyRead(conversationId: "room")), on: false)
        XCTAssertEqual(reopened.screenPrivacy["room"], false)
    }
    func testOtherRoomSuccessCannotClearPendingAndRetiredOwnerReadCannotApply() throws {
        let suite = "RoomPrivacyOwners.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        var tickets: [PulseScreenPrivacyTicket] = []
        prefs.patchScreenPrivacy = { tickets.append($0) }
        prefs.setScreenPrivacy(conversationId: "room", on: true)
        prefs.setScreenPrivacy(conversationId: "other", on: false)
        try prefs.acknowledgeScreenPrivacy(tickets[1], on: false)
        XCTAssertEqual(prefs.screenPrivacyPending, ["room"])
        let oldRead = try XCTUnwrap(prefs.screenPrivacyRead(conversationId: "other"))
        prefs.setViewer(PulseViewer(id: "different", name: "Different"))
        XCTAssertTrue(prefs.screenPrivacy.isEmpty); XCTAssertTrue(prefs.screenPrivacyPending.isEmpty)
        XCTAssertThrowsError(try prefs.acknowledgeScreenPrivacy(tickets[0], on: true))
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        prefs.adoptServerScreenPrivacy(oldRead, on: true)
        XCTAssertEqual(prefs.screenPrivacy["other"], false)
        XCTAssertTrue(prefs.screenPrivacyPending.contains("room"))
    }
    func testOldRoomWideReadCannotUndoConfirmedControlOrSurviveDismissal() throws {
        func room(_ on: Bool) throws -> WireConversationSummary {
            try JSONDecoder().decode(WireConversationSummary.self, from: JSONSerialization.data(withJSONObject: [
                "id": "room", "isGroup": true, "members": [], "screenPrivacy": on,
            ]))
        }
        let model = RoomViewModel(conversation: try room(false), session: PulseSession(), beginAutomaticWork: false)
        let before = try XCTUnwrap(model.beginPrivacyRead())
        model.applyFreshDetail(try room(true))
        XCTAssertFalse(model.applyPrivacyRead(before, detail: try room(false)))
        XCTAssertEqual(model.roomScreenPrivacy, true)
        let old = try XCTUnwrap(model.beginPrivacyRead())
        let latest = try XCTUnwrap(model.beginPrivacyRead())
        XCTAssertFalse(model.applyPrivacyRead(old, detail: try room(false)))
        XCTAssertTrue(model.applyPrivacyRead(latest, detail: try room(false)))
        let closing = try XCTUnwrap(model.beginPrivacyRead())
        model.handleRoomDisappeared()
        XCTAssertFalse(model.applyPrivacyRead(closing, detail: try room(true)))
        XCTAssertEqual(model.roomScreenPrivacy, false)
    }
    func testWrongGatewayCannotDispatchAndCorruptIntentDoesNotBecomeProtectionOff() throws {
        let suite = "RoomPrivacyCorrupt.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        var saved: PulseScreenPrivacyTicket?
        prefs.patchScreenPrivacy = { saved = $0 }
        prefs.setScreenPrivacy(conversationId: "room", on: true)
        let ticket = try XCTUnwrap(saved)
        let foreign = PulseAPIClient(baseURL: URL(string: "https://different-room-privacy.test")!, userId: "owner")
        XCTAssertThrowsError(try prefs.currentScreenPrivacy(ticket, api: foreign))
        defaults.set("corrupt", forKey: "pulse.settings.veil.v2." + ticket.owner)
        let reopened = PulsePrefs(defaults: defaults)
        XCTAssertNotNil(reopened.screenPrivacyReadError)
        XCTAssertNil(reopened.screenPrivacyRead(conversationId: "room"))
        reopened.setScreenPrivacy(conversationId: "room", on: false)
        XCTAssertEqual(reopened.screenPrivacy["room"], true)
        XCTAssertNotNil(reopened.screenPrivacyNotes["room"])
        XCTAssertEqual(defaults.string(forKey: "pulse.settings.veil.v2." + ticket.owner), "corrupt")
    }
    func testLiveCorruptionRetiresCachedOffAndReloadDoesNotInventAnotherWrite() throws {
        let suite = "RoomPrivacyRecovery.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        var tickets: [PulseScreenPrivacyTicket] = []
        prefs.patchScreenPrivacy = { tickets.append($0) }
        prefs.setScreenPrivacy(conversationId: "room", on: false)
        let key = "pulse.settings.veil.v2." + tickets[0].owner
        let saved = try XCTUnwrap(defaults.data(forKey: key))
        XCTAssertEqual(prefs.screenPrivacy["room"], false)
        defaults.set("unreadable", forKey: key)
        XCTAssertNil(prefs.screenPrivacyRead(conversationId: "room"))
        XCTAssertNil(prefs.screenPrivacy["room"]) // unknown is protected, not Off
        XCTAssertNotNil(prefs.screenPrivacyReadError)
        XCTAssertEqual(defaults.string(forKey: key), "unreadable")
        defaults.set(saved, forKey: key)
        prefs.reloadScreenPrivacy()
        XCTAssertNil(prefs.screenPrivacyReadError)
        XCTAssertEqual(prefs.screenPrivacy["room"], false)
        XCTAssertTrue(prefs.screenPrivacyPending.contains("room"))
        XCTAssertEqual(tickets.count, 1)
        prefs.retryScreenPrivacy(conversationId: "room")
        XCTAssertEqual(tickets.last?.id, tickets.first?.id)
    }
    func testLegacyCacheMigrationNeverInventsPendingIntent() throws {
        let suite = "RoomPrivacyMigration.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data(#"{"room":false}"#.utf8), forKey: "pulse.settings.veil.v1.scope")
        let journal = PulseScreenPrivacyJournal(defaults: defaults)
        XCTAssertEqual(try journal.entries(owner: "scope")["room"]?.on, false)
        XCTAssertNil(try journal.pending(owner: "scope", room: "room"))
        let revision = try journal.entries(owner: "scope")["room"]?.revision
        XCTAssertEqual(try PulseScreenPrivacyJournal(defaults: defaults).entries(owner: "scope")["room"]?.revision, revision)
        XCTAssertTrue(try journal.entries(owner: "other").isEmpty)
    }
}
