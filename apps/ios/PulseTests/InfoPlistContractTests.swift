import XCTest
@testable import Pulse

/// Inspect the actual host app product, not a dictionary copied from project.yml.
/// Missing declarations cannot be excused by a successful compiler/archive.
final class InfoPlistContractTests: XCTestCase {
    func testBuiltAppDeclaresBackgroundReadReplayAndNotificationWakeModes() {
        let modes = Set(Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? [])
        XCTAssertTrue(modes.contains("fetch"))
        XCTAssertTrue(modes.contains("remote-notification"))
        XCTAssertTrue(modes.contains("voip"))
        let identifiers = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
        XCTAssertTrue(identifiers.contains("app.pulse.chat.outbox"))
    }

    func testBuiltAppIncludesModernLaunchMetadataAndExistingPermissionDescriptions() {
        XCTAssertNotNil(Bundle.main.object(forInfoDictionaryKey: "UILaunchScreen") as? [String: Any])
        for key in ["NSFaceIDUsageDescription", "NSMicrophoneUsageDescription",
                    "NSCameraUsageDescription", "NSLocationWhenInUseUsageDescription"] {
            let description = Bundle.main.object(forInfoDictionaryKey: key) as? String
            XCTAssertFalse(description?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true, key)
        }
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String, "Pulse")
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        XCTAssertEqual(version, "0.10.0", "The product must use the explicit marketing build setting")
        XCTAssertNotNil(version.range(of: #"^\d+(\.\d+){0,2}$"#, options: .regularExpression), "Built marketing version: \(version)")
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String, "1")
        let orientations = Bundle.main.object(forInfoDictionaryKey: "UISupportedInterfaceOrientations") as? [String] ?? []
        XCTAssertTrue(orientations.contains("UIInterfaceOrientationPortrait"))
    }

    func testBuiltAppRetainsDeepLinksAndHomeScreenShortcuts() {
        let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        XCTAssertTrue(types.contains { ($0["CFBundleURLSchemes"] as? [String] ?? []).contains("pulse") })
        let shortcuts = Bundle.main.object(forInfoDictionaryKey: "UIApplicationShortcutItems") as? [[String: Any]] ?? []
        let names = Set(shortcuts.compactMap { $0["UIApplicationShortcutItemType"] as? String })
        XCTAssertEqual(names, Set(["app.pulse.shortcut.open", "app.pulse.shortcut.compose"]))
    }
}
