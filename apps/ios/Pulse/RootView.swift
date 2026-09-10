import SwiftUI

/// Pulse design tokens — native mirror of the web palette (emerald on warm neutrals).
enum PulseTheme {
    static let emerald = Color(red: 0.06, green: 0.72, blue: 0.51)
    static let emeraldDeep = Color(red: 0.02, green: 0.47, blue: 0.34)
    static let ink = Color(red: 0.035, green: 0.035, blue: 0.043)
    static let mist = Color(red: 0.98, green: 0.98, blue: 0.976)
}

/// Dock tab set — index order drives the direction-aware slide (web §1).
enum PulseTab: Int, CaseIterable {
    case chats, hub, contacts, profile
}

/// Root shell — session + prefs live here (single ownership), the ambient
/// field renders behind the tab chrome, the particle overlay above it, and
/// the onboarding (name → live @handle picker, web design parity) IS the app
/// until a viewer exists. Color scheme follows prefs (system/light/dark).
/// Tab chrome is the floating capsule dock (web §12) — the stock TabView is
/// gone; exactly one panel is mounted at a time (web AnimatePresence parity).
struct RootView: View {
    @StateObject private var session = PulseSession()
    @StateObject private var prefs = PulsePrefs()
    @State private var didBootstrap = false
    @State private var tab: PulseTab = .chats
    @State private var navDirection = 0
    // Dock nav surfaces — every dock button now opens something real.
    @State private var newChatOpen = false
    @State private var settingsOpen = false
    @State private var storiesOpen = false
    @State private var savedInFlight = false

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
                // Tab panels — exactly one mounted at a time (web §1):
                // 220ms crossfade with a ±24pt horizontal slide whose sign
                // follows the travel direction; reduced motion → fade only.
                ZStack {
                    switch tab {
                    case .chats:
                        ChatsView(
                            session: session,
                            prefs: prefs,
                            onGoContacts: { switchTab(.contacts) },
                            onGoProfile: { switchTab(.profile) },
                        )
                        .transition(panelTransition)
                    case .hub:
                        HubView(session: session)
                            .transition(panelTransition)
                    case .contacts:
                        ContactsView(session: session)
                            .transition(panelTransition)
                    case .profile:
                        ProfileView(session: session, prefs: prefs)
                            .transition(panelTransition)
                    }
                }
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: tab)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    // Reserve the dock's footprint so lists always clear it
                    // (zero while a chat room owns the screen — dock hidden).
                    Color.clear.frame(height: session.roomVisible ? 0 : 74)
                }
                .overlay(alignment: .bottom) {
                    if !session.roomVisible {
                        CapsuleDock(
                            session: session,
                            dark: isDark,
                            reduceMotion: reduceMotion,
                            tab: tab,
                            onTab: { switchTab($0) },
                            onCompose: { newChatOpen = true },
                            onSettings: { settingsOpen = true },
                            onSaved: { Task { await openSaved() } },
                            onStories: { storiesOpen = true },
                        )
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: session.roomVisible)
                .overlay {
                    ToastHostView(center: session.toasts)
                }
                .sheet(isPresented: $newChatOpen) {
                    NewChatSheet(session: session) { conv in
                        newChatOpen = false
                        switchTab(.chats)
                        session.requestOpenRoom(conv)
                    }
                }
                .sheet(isPresented: $settingsOpen) {
                    SettingsView(session: session, prefs: prefs)
                }
                .sheet(isPresented: $storiesOpen) {
                    StoriesView(session: session)
                }
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

    private func switchTab(_ target: PulseTab) {
        guard target != tab else { return }
        navDirection = target.rawValue > tab.rawValue ? 1 : -1
        tab = target
    }

    /// More → Saved — the idempotent self-chat (server get-or-creates the
    /// isSelf conversation), then the Chats tab pushes it. Land on Chats
    /// first so the NavigationStack exists to receive the room handoff.
    private func openSaved() async {
        guard !savedInFlight else { return }
        savedInFlight = true
        defer { savedInFlight = false }
        switchTab(.chats)
        do {
            let conv = try await session.api.createSelfChat()
            session.requestOpenRoom(conv)
        } catch {
            session.toasts.show("Could not open Note to Self")
        }
    }

    private var panelTransition: AnyTransition {
        guard !reduceMotion else { return .opacity }
        let slide = 24.0 * CGFloat(navDirection)
        return .asymmetric(
            insertion: .opacity.combined(with: .offset(x: slide)),
            removal: .opacity.combined(with: .offset(x: -slide)),
        )
    }
}

// ─────────────────────────────────────────────────────────────
// The floating capsule dock — home chrome shared by every tab
// (web §12): [Chats, Hub] · compose · [Contacts, Profile] · More,
// glass panel, emerald active pill, unread badge on Chats, wobble
// on press, compact More menu with honest actions (no fakes).
// ─────────────────────────────────────────────────────────────
private struct CapsuleDock: View {
    @ObservedObject var session: PulseSession
    let dark: Bool
    let reduceMotion: Bool
    let tab: PulseTab
    let onTab: (PulseTab) -> Void
    // Dock actions owned by RootView (sheets + Saved handoff live there).
    var onCompose: () -> Void = {}
    var onSettings: () -> Void = {}
    var onSaved: () -> Void = {}
    var onStories: () -> Void = {}

    @State private var moreOpen = false
    @State private var wobbling: PulseTab?
    @State private var wobbleAngle = 0.0

    var body: some View {
        HStack(spacing: 4) {
            dockTab(.chats, icon: "bubble.left.and.bubble.right", filled: "bubble.left.and.bubble.right.fill", label: "Chats", badge: session.dockUnreadCount)
            dockTab(.hub, icon: "flame", filled: "flame.fill", label: "Hub", badge: 0)
            composeButton
            dockTab(.contacts, icon: "person.2", filled: "person.2.fill", label: "Contacts", badge: 0)
            dockTab(.profile, icon: "person.crop.circle", filled: "person.crop.circle.fill", label: "Profile", badge: 0)
            moreButton
        }
        .padding(6)
        .background(dockPanel)
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                // Veil dismiss layer + the compact glass menu above the More button.
                ZStack(alignment: .bottomTrailing) {
                    Color.clear
                        .contentShape(Rectangle())
                        .ignoresSafeArea()
                        .onTapGesture {
                            withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) { moreOpen = false }
                        }
                    moreMenu
                        .offset(y: -80)
                }
                .transition(.opacity)
            }
        }
    }

    // ── tabs ──────────────────────────────────────────────────
    private func dockTab(_ target: PulseTab, icon: String, filled: String, label: String, badge: Int) -> some View {
        let isActive = tab == target
        return Button {
            PulseHaptics.tap()
            runWobble(target)
            onTab(target)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: isActive ? filled : icon)
                    .font(.system(size: 22, weight: isActive ? .semibold : .regular))
                    .scaleEffect(isActive ? 1.08 : 1)
                    .offset(y: isActive ? -1 : 0)
                Text(label)
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background {
                if isActive {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(PulseTheme.dockPillGradient)
                        .overlay(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .strokeBorder(PulseTheme.emerald500.opacity(0.30), lineWidth: 1)
                        )
                        .shadow(color: PulseTheme.emerald500.opacity(0.55), radius: 10, y: 6)
                }
            }
            .overlay(alignment: .topTrailing) {
                if badge > 0 {
                    Text(badge > 99 ? "99+" : "\(badge)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 20, minHeight: 20)
                        .background(Capsule().fill(PulseTheme.brandGradient))
                        .overlay(Capsule().strokeBorder(dark ? PulseTheme.zinc(900) : .white, lineWidth: 2))
                        .shadow(color: PulseTheme.emerald500.opacity(0.45), radius: 6, y: 2)
                        .offset(x: 10, y: -6)
                        .transition(.scale(scale: 0.4).combined(with: .opacity))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .rotationEffect(.degrees(wobbling == target ? wobbleAngle : 0))
        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: isActive)
        .animation(.spring(response: 0.25, dampingFraction: 0.6), value: badge)
    }

    // ── compose ───────────────────────────────────────────────
    private var composeButton: some View {
        Button {
            PulseHaptics.tap()
            onCompose()
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 46, height: 46)
                .background(Circle().fill(PulseTheme.brandGradient))
                .shadow(color: PulseTheme.emerald500.opacity(0.55), radius: 10, y: 4)
                .contentShape(Circle())
        }
        .buttonStyle(DockPressStyle())
    }

    // ── more ──────────────────────────────────────────────────
    private var moreButton: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) { moreOpen.toggle() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .buttonStyle(DockPressStyle())
    }

    private var moreMenu: some View {
        VStack(alignment: .leading, spacing: 2) {
            moreItem("Settings", icon: "gearshape") {
                onSettings()
            }
            moreItem("Search", icon: "magnifyingglass") {
                session.requestChatsSearch()
            }
            moreItem("Saved", icon: "bookmark") {
                onSaved()
            }
            moreItem("Stories", icon: "sparkles") {
                onStories()
            }
        }
        .padding(6)
        .frame(width: 232, alignment: .leading)
        .background(menuPanel)
        .transition(.scale(scale: 0.92, anchor: .bottom).combined(with: .opacity))
    }

    private func moreItem(_ label: String, icon: String, action: @escaping () -> Void) -> some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) { moreOpen = false }
            action()
        } label: {
            Label(label, systemImage: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(PulseTheme.titleOnPanel)
                .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // ── wobble (web: rotate [0, -8, 6, 0]° over ~350ms) ───────
    private func runWobble(_ target: PulseTab) {
        guard !reduceMotion else { return }
        wobbling = target
        wobbleAngle = 0
        withAnimation(.easeIn(duration: 0.09)) { wobbleAngle = -8 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.09) {
            withAnimation(.easeIn(duration: 0.09)) { wobbleAngle = 6 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.10) {
                withAnimation(.spring(response: 0.17, dampingFraction: 0.55)) { wobbleAngle = 0 }
            }
        }
    }

    // ── glass recipes (web §12 panel + menu) ──────────────────
    private var dockPanel: some View {
        RoundedRectangle(cornerRadius: 28, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .fill(dark ? PulseTheme.zinc(900).opacity(0.65) : Color.white.opacity(0.70))
            )
            .overlay(
                // hairline ring — zinc-200/70 light, white/10 dark
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .strokeBorder(dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1)
            )
            .overlay(
                // specular top edge
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .strokeBorder(
                        LinearGradient(colors: [Color.white.opacity(0.45), Color.white.opacity(0.04)], startPoint: .top, endPoint: .bottom),
                        lineWidth: 1
                    )
                    .opacity(dark ? 0.5 : 1)
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }

    private var menuPanel: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(dark ? PulseTheme.zinc(900).opacity(0.72) : Color.white.opacity(0.78))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.16), radius: 14, y: 6)
    }
}

/// Dock press feedback — scale .88 spring on every dock button.
private struct DockPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.88 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
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
