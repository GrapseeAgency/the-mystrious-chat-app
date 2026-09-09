import SwiftUI

/// Pulse design tokens — native mirror of the web palette (emerald on warm neutrals).
enum PulseTheme {
    static let emerald = Color(red: 0.06, green: 0.72, blue: 0.51)
    static let emeraldDeep = Color(red: 0.02, green: 0.47, blue: 0.34)
    static let ink = Color(red: 0.035, green: 0.035, blue: 0.043)
    static let mist = Color(red: 0.98, green: 0.98, blue: 0.976)
}

/// Root shell — session + prefs live here (single ownership), the ambient
/// field renders behind the tab chrome, the particle overlay above it, and
/// the onboarding (name → live @handle picker, web design parity) IS the app
/// until a viewer exists. Color scheme follows prefs (system/light/dark).
struct RootView: View {
    @StateObject private var session = PulseSession()
    @StateObject private var prefs = PulsePrefs()
    @State private var didBootstrap = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var systemScheme

    private var colorScheme: ColorScheme? {
        switch prefs.appearance {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    private var isDark: Bool {
        prefs.appearance == "dark" || (prefs.appearance == "system" && systemScheme == .dark)
    }

    var body: some View {
        ZStack {
            AmbientFieldView(mode: prefs.ambientMode, dark: isDark)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            if prefs.viewer != nil {
                TabView {
                    ChatsView(session: session, prefs: prefs)
                        .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right.fill") }
                    HubView(session: session)
                        .tabItem { Label("Hub", systemImage: "flame.fill") }
                    ContactsView(session: session)
                        .tabItem { Label("Contacts", systemImage: "person.2.fill") }
                    ProfileView(session: session, prefs: prefs)
                        .tabItem { Label("Profile", systemImage: "person.crop.circle.fill") }
                }
                .scrollContentBackground(.hidden)
                .toolbarBackground(.ultraThinMaterial, for: .tabBar)
            } else {
                // Gate on identity exactly like the web onboarding — the
                // two-step screen replaces the shell (not a modal sheet).
                OnboardingView(session: session, prefs: prefs, onPicked: { didBootstrap = true })
            }

            ParticleOverlayView(bus: session.particles)
        }
        .tint(PulseTheme.emerald)
        .preferredColorScheme(colorScheme)
        .onAppear {
            session.particles.reduceMotionDisabled = reduceMotion
            // Bootstrap the live layer when identity already exists.
            if !didBootstrap, let viewer = prefs.viewer {
                didBootstrap = true
                session.start(as: viewer)
            }
        }
        .onChange(of: reduceMotion) { _, newValue in
            session.particles.reduceMotionDisabled = newValue
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
