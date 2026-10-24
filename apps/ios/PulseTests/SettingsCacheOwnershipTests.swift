import XCTest
import Combine
@testable import Pulse

@MainActor
final class SettingsCacheOwnershipTests: XCTestCase {
    func testAccountAndGatewaySnapshotsDoNotAdoptLegacyGlobalOptIns() throws {
        let suite = "SettingsCacheOwnership.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let gateway = PulseEndpoints.configuredBase
        defer { defaults.removePersistentDomain(forName: suite); PulseEndpoints.configuredBase = gateway }
        PulseEndpoints.configuredBase = "https://settings-cache.test"
        defaults.set(true, forKey: PulsePrefs.readReceiptsKey)
        defaults.set(true, forKey: PulsePrefs.notifPreviewsKey)
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "first", name: "First"))
        XCTAssertFalse(prefs.readReceipts); XCTAssertFalse(prefs.notifPreviews)
        prefs.applyServer(WirePulsePrefs(density: "compact", notifPreviews: true, readReceipts: true,
            convThemes: ["room": WireConvTheme(wallpaper: "dusk", tint: "amber")]))
        prefs.setScreenPrivacy(conversationId: "room", on: true)
        prefs.setReadReceipts(false) // pending must overlay its owner's cache on return
        let observer = prefs.$viewer.dropFirst().sink { viewer in
            if viewer?.id == "second" {
                XCTAssertFalse(prefs.notifPreviews, "Replacement viewer published before privacy reset")
                XCTAssertTrue(prefs.convThemes.isEmpty)
            }
        }
        defer { observer.cancel() }
        prefs.setViewer(PulseViewer(id: "second", name: "Second"))
        XCTAssertFalse(prefs.readReceipts); XCTAssertFalse(prefs.notifPreviews)
        XCTAssertEqual(prefs.density, .cozy); XCTAssertTrue(prefs.convThemes.isEmpty)
        XCTAssertTrue(prefs.screenPrivacy.isEmpty); XCTAssertNil(prefs.lastSyncNote)
        prefs.applyServer(WirePulsePrefs(readReceipts: true))
        prefs.setViewer(PulseViewer(id: "first", name: "First"))
        XCTAssertFalse(prefs.readReceipts); XCTAssertTrue(prefs.notifPreviews)
        XCTAssertEqual(prefs.density, .compact); XCTAssertNotNil(prefs.lastSyncNote)
        XCTAssertEqual(prefs.screenPrivacy["room"], true); XCTAssertEqual(prefs.convThemes["room"]?.wallpaper, "dusk")
        let reopened = PulsePrefs(defaults: try XCTUnwrap(UserDefaults(suiteName: suite)))
        XCTAssertFalse(reopened.readReceipts); XCTAssertEqual(reopened.density, .compact)
        XCTAssertNotNil(reopened.lastSyncNote)
        PulseEndpoints.configuredBase = "https://different-settings-cache.test"
        reopened.refreshSettingsOwner()
        XCTAssertFalse(reopened.notifPreviews); XCTAssertEqual(reopened.density, .cozy)
        XCTAssertTrue(reopened.convThemes.isEmpty); XCTAssertTrue(reopened.screenPrivacy.isEmpty)
        XCTAssertNil(reopened.lastSyncNote)
        reopened.setViewer(nil)
        XCTAssertFalse(reopened.readReceipts); XCTAssertFalse(reopened.typingVisible)
    }

    func testCorruptPendingJournalCannotExposeCachedOptInOrDisappear() throws {
        let suite = "SettingsCorruptJournal.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        prefs.applyServer(WirePulsePrefs(readReceipts: true))
        var ticket: PulseSettingsTicket?
        prefs.patchRemote = { ticket = $0 }
        prefs.setDensity(.compact)
        let owner = try XCTUnwrap(ticket).owner
        defaults.set("unreadable", forKey: "pulse.settings.pending.v1." + owner)
        let reopened = PulsePrefs(defaults: defaults)
        XCTAssertFalse(reopened.readReceipts); XCTAssertNotNil(reopened.lastSyncNote)
        reopened.retryPendingSettings()
        XCTAssertNotNil(reopened.lastSyncNote)
        XCTAssertEqual(defaults.string(forKey: "pulse.settings.pending.v1." + owner), "unreadable")
    }
}
