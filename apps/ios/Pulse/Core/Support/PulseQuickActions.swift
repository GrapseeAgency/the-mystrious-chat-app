import Foundation
import UIKit

// R10-b - home-screen quick actions (UIApplicationShortcutItem).
//
// The two STATIC shortcuts are declared in project.yml
// (infoProperties → UIApplicationShortcutItems); the type strings below
// must stay byte-identical with that list (the XCTest pins the mapping,
// not the plist - CI/device QA confirms the plist round-trip).
//
// Delivery chain:
//   cold launch → PulseAppDelegate.application(
//      _:configurationForConnecting:options:) installs PulseSceneDelegate
//      → PulseSceneDelegate.scene(_:willConnectTo:options:) reads
//      connectionOptions.shortcutItem;
//   warm launch → PulseSceneDelegate.windowScene(_:performActionFor:…).
//   Both funnel into ONE @Published pendingRoute; RootView consumes it
//   (.onReceive) into real destinations - the Chats tab, or the dock's
//   own New-chat sheet (the exact sheet the compose button opens).
//
// Lifecycle honesty: PulseAppDelegate implements configurationForConnecting
// ONLY to install PulseSceneDelegate (the established SwiftUI-lifecycle
// pattern for quick actions). The delegate owns NO window and NO UI -
// SwiftUI's WindowGroup keeps providing the window - and both delivery
// hooks converge on one idempotent route value, so a cold-launch replay
// through both hooks is harmless. CI compiles this; on-device behavior of
// the scene-delegate override needs real-device QA (noted in the worklog).

/// Pure shortcut-type → destination mapping (unit-tested; the type string
/// constants live here so the test can pin them without touching the
/// @MainActor singleton).
public enum PulseQuickActionRoute: Equatable {
    /// "Open Pulse" - lands on the Chats tab (the app's home).
    case openChats
    /// "New message" - opens the dock compose sheet (NewChatSheet).
    case newMessage

    /// Must match project.yml → UIApplicationShortcutItems item 1 type.
    public static let openType = "app.pulse.shortcut.open"
    /// Must match project.yml → UIApplicationShortcutItems item 2 type.
    public static let composeType = "app.pulse.shortcut.compose"

    /// Unknown types are ignored - the app never invents a destination it
    /// wasn't asked for.
    public static func parse(shortcutType: String?) -> PulseQuickActionRoute? {
        switch shortcutType {
        case openType: return .openChats
        case composeType: return .newMessage
        default: return nil
        }
    }
}

@MainActor
public final class PulseQuickActions: ObservableObject {

    public static let shared = PulseQuickActions()

    @Published public private(set) var pendingRoute: PulseQuickActionRoute?

    private init() {}

    /// Scene-delegate handoff (cold + warm paths converge here; the route
    /// is idempotent so a double delivery is harmless).
    public func handle(shortcut: UIApplicationShortcutItem) {
        guard let route = PulseQuickActionRoute.parse(shortcutType: shortcut.type) else {
            NSLog("Pulse quick actions: unknown shortcut type %@", shortcut.type)
            return
        }
        pendingRoute = route
    }

    /// RootView consumption - returns and clears the pending route.
    @discardableResult
    public func consume() -> PulseQuickActionRoute? {
        let pending = pendingRoute
        pendingRoute = nil
        return pending
    }
}

/// The scene delegate installed by PulseAppDelegate via
/// application(_:configurationForConnecting:options:). Deliberately
/// window-less: SwiftUI owns the window; this object only observes
/// quick-action delivery (cold + warm).
public final class PulseSceneDelegate: NSObject, UIWindowSceneDelegate {

    public func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        // Cold launch via a quick action - the shortcut rides the options.
        if let shortcut = connectionOptions.shortcutItem {
            Task { @MainActor in
                PulseQuickActions.shared.handle(shortcut: shortcut)
            }
        }
    }

    public func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        // Warm launch (app alive in the background).
        Task { @MainActor in
            PulseQuickActions.shared.handle(shortcut: shortcutItem)
        }
        completionHandler(true)
    }
}
