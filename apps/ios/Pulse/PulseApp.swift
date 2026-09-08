// Pulse iOS — app entry (SwiftUI lifecycle, iOS 17+).
// Native per the blueprint: SwiftUI UI, Clean Architecture folders,
// GRDB (SQLite) local store, URLSession networking — no cross-platform shell.

import SwiftUI

@main
struct PulseApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(PulseTheme.emerald)
        }
    }
}
