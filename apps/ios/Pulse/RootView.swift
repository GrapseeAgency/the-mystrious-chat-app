import SwiftUI

/// Pulse design tokens — native mirror of the web palette (emerald on warm neutrals).
enum PulseTheme {
    static let emerald = Color(red: 0.06, green: 0.72, blue: 0.51)
    static let emeraldDeep = Color(red: 0.02, green: 0.47, blue: 0.34)
    static let ink = Color(red: 0.035, green: 0.035, blue: 0.043)
    static let mist = Color(red: 0.98, green: 0.98, blue: 0.976)
}

/// Four destinations — parity with web NAV_ITEMS + Android bottom bar.
struct RootView: View {
    @State private var tab: PulseTab = .chats

    enum PulseTab: Hashable { case chats, hub, contacts, profile }

    var body: some View {
        TabView(selection: $tab) {
            ChatsScreen()
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right.fill") }
                .tag(PulseTab.chats)
            HubScreen()
                .tabItem { Label("Hub", systemImage: "flame.fill") }
                .tag(PulseTab.hub)
            ContactsScreen()
                .tabItem { Label("Contacts", systemImage: "person.2.fill") }
                .tag(PulseTab.contacts)
            ProfileScreen()
                .tabItem { Label("Profile", systemImage: "person.crop.circle.fill") }
                .tag(PulseTab.profile)
        }
    }
}

struct ChatsScreen: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableCompat(title: "Chats", systemImage: "bubble.left.and.bubble.right.fill", note: "Data layer lands in wave N2.")
                .navigationTitle("Chats")
        }
    }
}

struct HubScreen: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableCompat(title: "Hub", systemImage: "flame.fill", note: "Wallet, market, tasks and tournaments land later.")
                .navigationTitle("Hub")
        }
    }
}

struct ContactsScreen: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableCompat(title: "Contacts", systemImage: "person.2.fill", note: "Synced contacts land with the data layer.")
                .navigationTitle("Contacts")
        }
    }
}

struct ProfileScreen: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableCompat(title: "Profile", systemImage: "person.crop.circle.fill", note: "Account, appearance, privacy — 9 sections like the web.")
                .navigationTitle("Profile")
        }
    }
}

/// iOS 17-safe stand-in for ContentUnavailableView with a custom note.
struct ContentUnavailableCompat: View {
    let title: String
    let systemImage: String
    let note: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44, weight: .medium))
                .foregroundStyle(.secondary)
            Text(title).font(.title3.weight(.semibold))
            Text(note)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    RootView()
}
