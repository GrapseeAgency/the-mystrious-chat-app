import SwiftUI

/// Pulse design tokens - native mirror of the web palette (emerald on warm neutrals).
enum PulseTheme {
    static let emerald = Color(red: 0.06, green: 0.72, blue: 0.51)
    static let emeraldDeep = Color(red: 0.02, green: 0.47, blue: 0.34)
    static let ink = Color(red: 0.035, green: 0.035, blue: 0.043)
    static let mist = Color(red: 0.98, green: 0.98, blue: 0.976)
}

/// Dock tab set - index order drives the direction-aware slide (web §1).
enum PulseTab: Int, CaseIterable {
    // Reference dock order: Chats / Call / Updates / Profile. Contacts stays
    // a reachable case (chats header menu) but holds no dock slot.
    case chats, calls, hub, contacts, profile
}

/// Root shell - session + prefs live here (single ownership), the ambient
/// field renders behind the tab chrome, the particle overlay above it, and
/// the onboarding (name → live @handle picker, web design parity) IS the app
/// until a viewer exists. Color scheme follows prefs (system/light/dark).
/// Tab chrome is the R4-A 13-style navigation registry (web nav-registry.ts
/// parity; default = the floating capsule dock, web §12) - the stock TabView
/// is gone; exactly one panel is mounted at a time (web AnimatePresence
/// parity). Every style renders over the same shared dock context; the
/// floating-top bar and the side rail ride their own safe-area channels.
/// @MainActor - the R10-b app-lock singleton feeds an eager @ObservedObject
/// (same shape as SettingsView's pushCenter).
@MainActor
struct RootView: View {
    @StateObject private var session = PulseSession()
    @StateObject private var prefs = PulsePrefs()
    // R10-b - the biometric app lock (singleton owns needsLock; the
    // fullScreenCover gate below presents it when the scene is active).
    @ObservedObject private var appLock = PulseAppLock.shared
    @State private var didBootstrap = false
    @State private var tab: PulseTab = .chats
    @State private var navDirection = 0
    // R4-A item 3 - the mirrored navigation style. prefs owns the persisted
    // value; RootView mirrors it on attach + onChange (the uiTheme pattern)
    // and the dock switch renders the matching layout renderer.
    @State private var navStyle: PulseNavStyle = .capsule
    // Dock nav surfaces - every dock button now opens something real.
    @State private var newChatOpen = false
    @State private var settingsOpen = false
    @State private var storiesOpen = false
    // R3-A item 9 - the dock More menu carries a Calls row that opens the
    // real call-history page (same surface the chats header hosts).
    @State private var callsOpen = false
    // Wave 2 - dock More → Saved opens the real saved library (spec §1 row 14);
    // the old create-self-chat detour is gone.
    @State private var savedLibraryOpen = false
    // Wave 6 - pulse:// deep links (F-DL): invite previews join, user opens
    // the full user page (Contacts tab), room opens the conversation.
    @State private var pendingInvite: InviteLinkTarget?
    // R14 5-b - the ?login= handoff (web /?login=Name parity): the deep
    // link's display-name prefill + auto-lookup target for OnboardingView.
    @State private var pendingLoginName: String?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.scenePhase) private var scenePhase

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

    // R12 - the monolithic body exceeded Swift's type-check budget in
    // release batches; the view tree is unchanged, but each layer now
    // type-checks as its own small expression (body → shell → panels).

    var body: some View {
        ZStack {
            AmbientFieldView(mode: prefs.ambientMode, dark: isDark)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            authenticatedShell

            pipOverlay

            callSurfaces

            ParticleOverlayView(bus: session.particles)
        }
        .tint(PulseTheme.accent)
        // R17 Neo - rounded system type everywhere the theme does not set an
        // explicit font (SF Rounded floor; iOS 17 target covers the 16.1 API).
        .fontDesign(.rounded)
        .preferredColorScheme(colorScheme)
        // R14 5-b - deep links now mount at the ROOT (the login route must
        // reach the app pre-identity; every other route re-checks the viewer
        // inside handleDeepLink exactly like before).
        .onOpenURL { url in
            handleDeepLink(url)
        }
        // R10-b - the app-lock gate. Armed on scenePhase → .background (when
        // enabled); presenting the moment the scene is active again. Layered
        // at the very root like the call/rooms full-screen surfaces so it
        // covers everything (onboarding included).
        .fullScreenCover(isPresented: $appLock.needsLock) {
            PulseAppLockView()
        }
        .onAppear {
            // Wave 8 - prefs handoff: incoming attention gate, settings PATCH
            // funnel, quiet-gate refresh (idempotent, closures attach once).
            session.attach(prefs: prefs)
            // 3-d - push handoff: the registration center reads the live API
            // client through this closure (rebuilt per gateway override +
            // identity). attach re-arms any pending token → server POST.
            PulsePushRegistrationCenter.shared.attach { [weak session] in session?.api }
            // R10-b - quick-reply handoff: the coordinator reads the live
            // session through the SAME seam the push registration center
            // uses, and opens rooms through the standard linked-room bridge
            // (pendingLinkedRoomId → openLinkedRoom below).
            PulseQuickReplyCoordinator.shared.attach { [weak session] in session }
            PulseQuickReplyCoordinator.shared.onOpenRoom = { [weak session] conversationId in
                session?.pendingLinkedRoomId = conversationId
            }
            // R4-A item 3 - mirror the nav style on attach (onChange below
            // keeps the mirror live after Appearance picks).
            navStyle = prefs.navStyle
            // System Reduce Motion AND the in-app reducedMotion pref both calm
            // the ambient particles (the prefs toggle PATCHes to the server).
            session.particles.reduceMotionDisabled = reduceMotion || prefs.reducedMotion
            // R2-D - mirror the design language into the token set (the
            // settings picker flips prefs.uiTheme; every PulseTheme-fed view
            // swaps with it).
            PulseTheme.activeUiTheme = prefs.uiTheme
            // Bootstrap the live layer when identity already exists.
            if !didBootstrap, let viewer = prefs.viewer {
                didBootstrap = true
                session.start(as: viewer)
                // R14 5-b - boot identity probe (web app-root.tsx BootGate):
                // a stored identity the server no longer knows (404) wipes
                // the session and lands on onboarding. Network flakes keep
                // it - the identical optimistic contract.
                session.validateStoredIdentity { [weak session] name in
                    session?.prefs?.setViewer(nil)
                    session?.toasts.show("“\(name)” no longer exists on this Pulse - sign in or create an identity.")
                }
            }
            // R2-D - reminder-notification taps route like pulse://room: the
            // delegate (registered in PulseApp.init) parses the userInfo into
            // PulseDeepLink.room and hands it over through the session bridge.
            // Cold-start taps that landed before this closure existed are
            // replayed from the delegate's handoff slot.
            PulseReminderNotificationDelegate.shared.onDeepLink = { link in
                if case .room(let conversationId) = link {
                    session.pendingLinkedRoomId = conversationId
                }
            }
            if let tapped = PulseReminderNotificationDelegate.shared.consumeLastTappedRoomId() {
                session.pendingLinkedRoomId = tapped
            }
        }
        .onChange(of: prefs.uiTheme) { _, theme in
            PulseTheme.activeUiTheme = theme
        }
        // R4-A item 3 - RootView mirrors prefs.navStyle on change so every
        // dock channel (bottom/top/leading) re-renders with the pick.
        .onChange(of: prefs.navStyle) { _, style in
            navStyle = style
        }
        .onChange(of: reduceMotion) { _, newValue in
            session.particles.reduceMotionDisabled = newValue || prefs.reducedMotion
        }
        .onChange(of: prefs.reducedMotion) { _, newValue in
            session.particles.reduceMotionDisabled = reduceMotion || newValue
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                // Outbox flush trigger: app foreground (web visibilitychange).
                session.flushOutbox()
            case .background:
                PulseApp.scheduleOutboxRefresh()
                // R10-b - arm the app lock while leaving the foreground
                // (when the toggle is on). The fullScreenCover gate presents
                // when the scene is active again.
                appLock.appDidEnterBackground(appLockEnabled: prefs.appLockEnabled)
            default:
                break
            }
        }
        // R2-D - the shared linked-room bridge: reminder-notification taps
        // (delegate) AND calls-history row taps land here and follow the
        // exact pulse://room path (fetch detail → Chats tab → open room).
        .onReceive(session.$pendingLinkedRoomId) { pending in
            guard let conversationId = pending else { return }
            session.pendingLinkedRoomId = nil
            openLinkedRoom(conversationId)
        }
        // R10-b - home-screen quick actions: cold launch replays the
        // pending route through this subscription (@Published replays the
        // current value on attach); warm launches publish on tap.
        .onReceive(PulseQuickActions.shared.$pendingRoute) { pending in
            guard let route = pending else { return }
            PulseQuickActions.shared.consume()
            switch route {
            case .openChats:
                switchTab(.chats)
            case .newMessage:
                // Never compose under the lock cover (invisible UI) - the
                // guard keeps the tap honest while the gate is up.
                guard prefs.viewer != nil, !appLock.needsLock else { break }
                PulseHaptics.tap()
                newChatOpen = true
            }
        }
    }

    // ── R12 body decomposition ──────────────────────────────

    /// Tab panels - exactly one mounted at a time (web §1): 220ms crossfade
    /// with a ±24pt horizontal slide whose sign follows the travel direction;
    /// reduced motion → fade only.
    private var tabPanels: some View {
        ZStack {
            switch tab {
            case .chats:
                ChatsView(
                    session: session,
                    prefs: prefs,
                    onGoContacts: { switchTab(.contacts) },
                    onGoProfile: { switchTab(.profile) },
                    // R25 - the dock More menu lives in the chats header now.
                    onGoSaved: { savedLibraryOpen = true },
                    onGoStories: { storiesOpen = true },
                    onGoSettings: { settingsOpen = true },
                    isActive: tab == .chats,
                )
                .transition(panelTransition)
            case .calls:
                // Reference dock: Call is a real tab now (was a More sheet).
                CallsHistoryView(session: session, onOpenConversation: { conversationId in
                    switchTab(.chats)
                    session.pendingLinkedRoomId = conversationId
                })
                .transition(panelTransition)
            case .hub:
                HubView(session: session, onOpenRoom: { conversation in
                    switchTab(.chats)
                    session.requestOpenRoom(conversation)
                })
                .transition(panelTransition)
            case .contacts:
                ContactsView(session: session)
                    .transition(panelTransition)
            case .profile:
                ProfileView(
                    session: session,
                    prefs: prefs,
                    // R39 - the profile corner menu hands off to the real
                    // Hub tab and the Settings sheet (web ProfileMoreMenu).
                    onOpenHub: { switchTab(.hub) },
                    onOpenSettings: { settingsOpen = true },
                )
                    .transition(panelTransition)
            }
        }
        // R14 5-b - edge-swipe tab switching (web main-shell.tsx :337-377):
        // horizontal drags move one tab in the drag direction. The
        // simultaneous attachment (never highPriority) keeps scrollables,
        // carousels and the room's swipe-reply in control of their own
        // gestures; rooms opt out entirely inside the gesture.
        .simultaneousGesture(edgeSwipeGesture)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: tab)
    }

    /// R14 5-b - the swipe verdict: vertical-cancel (±45pt) + a real
    /// horizontal travel (>60pt - a notch above the web's 56 so scroll-
    /// flicks stay list-owned) → prev/next, clamped at the registry edges
    /// (web main-shell.tsx :337-372 parity; the 24pt edge anchor becomes a
    /// whole-shell gesture here - simultaneous, never hijacking). Rooms and
    /// the app-lock cover never participate.
    private var edgeSwipeGesture: some Gesture {
        DragGesture(minimumDistance: 50)
            .onEnded { value in
                guard !reduceMotion, !session.roomVisible, !appLock.needsLock else { return }
                guard abs(value.translation.height) < 45,
                      abs(value.translation.width) > 60 else { return }
                let order = PulseTab.allCases
                guard let index = order.firstIndex(of: tab) else { return }
                let next = value.translation.width < 0 ? index + 1 : index - 1
                guard order.indices.contains(next) else { return }
                PulseHaptics.tap()
                switchTab(order[next])
            }
    }

    @ViewBuilder
    private var authenticatedShell: some View {
        if prefs.viewer != nil, UserDefaults.standard.bool(forKey: "ui.mirrorAudit") {
            // R59 - the native mirror audit: the artboard redrawn natively
            // (SwiftUI) on the REAL session data; the shipping shell stays
            // untouched behind the flag.
            MirrorRootView(session: session)
        } else if prefs.viewer != nil {
            ZStack {
                tabPanels
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                // Reserve the dock's footprint so lists always clear it
                // (zero while a chat room owns the screen - dock hidden;
                // zero for top/side styles - they reserve their own
                // R4-A channels below).
                Color.clear.frame(height: dockBottomReserve)
            }
            // R4-A item 3 - the floating-top channel: the glass bar lives
            // IN the top inset so every screen (lists, room chrome) clears
            // it through the same mechanism the bottom dock reserve uses.
            // Zero while a room owns the screen.
            .safeAreaInset(edge: .top, spacing: 0) {
                if !session.roomVisible, navStyle.zone == .top {
                    topDock
                        .transition(.move(edge: .top).combined(with: .opacity))
                } else {
                    Color.clear.frame(height: 0)
                }
            }
            // R4-A item 3 - the rail channel: a persistent left rail; all
            // content insets right of it. Zero while a room owns the screen
            // (dock auto-hide in room, unchanged).
            .safeAreaInset(edge: .leading, spacing: 0) {
                if !session.roomVisible, navStyle.zone == .side {
                    RailDock(context: dockContext, active: tab)
                        .transition(.move(edge: .leading).combined(with: .opacity))
                } else {
                    Color.clear.frame(width: 0, height: 0)
                }
            }
            .overlay(alignment: .bottom) {
                if !session.roomVisible {
                    bottomDock
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: session.roomVisible)
            .animation(reduceMotion ? nil : .pulse(.pulseSoft, reduceMotion: reduceMotion), value: navStyle)
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
                SettingsView(session: session, prefs: prefs, onOpenHub: {
                    // R14 5-b - the Hub link row: dismiss, then select the hub
                    // tab (web onOpenHub parity).
                    settingsOpen = false
                    switchTab(.hub)
                })
            }
            .sheet(isPresented: $storiesOpen) {
                StoriesView(session: session)
            }
            // R3-A item 9 - the dock More → Calls page. Row taps ride the
            // EXISTING linked-room bridge (pendingLinkedRoomId → fetch →
            // Chats tab → open room), identical to the chats-header entry.
            .sheet(isPresented: $callsOpen) {
                CallsHistoryView(session: session, onOpenConversation: { conversationId in
                    callsOpen = false
                    session.pendingLinkedRoomId = conversationId
                })
            }
            .sheet(isPresented: $savedLibraryOpen) {
                SavedLibraryView(session: session) { conversation, messageId in
                    savedLibraryOpen = false
                    switchTab(.chats)
                    session.requestOpenRoom(conversation, jumpMessageId: messageId)
                }
            }
            .sheet(item: $pendingInvite) { target in
                JoinInviteSheet(session: session, code: target.code) { conversation in
                    pendingInvite = nil
                    switchTab(.chats)
                    session.requestOpenRoom(conversation)
                }
            }
        } else {
            // Gate on identity exactly like the web onboarding - the
            // two-step screen replaces the shell (not a modal sheet).
            // R14 5-b - a pending ?login= deep link prefills the name and
            // runs the live lookup (the "That's me" affordance) on appear.
            OnboardingView(
                session: session,
                prefs: prefs,
                deepLinkLoginName: pendingLoginName,
                onPicked: { didBootstrap = true },
            )
        }
    }

    /// R1-W2I - the PiP pane overlay (F-PI-01..03): floats above the tab
    /// chrome and pushed rooms, below the call/rooms overlays. The host
    /// self-gates on the store being open and observes it directly.
    @ViewBuilder
    private var pipOverlay: some View {
        if prefs.viewer != nil {
            PipPaneHostView(
                session: session,
                pip: session.pip,
                onOpenRoom: { conversationId in
                    openLinkedRoom(conversationId)
                },
            )
            // R4-A item 3 - the PiP host rides the SAME nav channels: panes
            // keep their internal top/bottom reserves but must also clear
            // the floating-top bar and the rail.
            .safeAreaInset(edge: .top, spacing: 0) {
                Color.clear.frame(height: pipTopReserve)
            }
            .safeAreaInset(edge: .leading, spacing: 0) {
                Color.clear.frame(width: pipLeadingReserve)
            }
        }
    }

    /// Wave 3 - the call surfaces own the WHOLE screen whenever their engine
    /// is not idle; mounted beside each other at shell level (web
    /// main-shell.tsx parity).
    @ViewBuilder
    private var callSurfaces: some View {
        if let engine = session.callEngine {
            CallOverlayHostView(engine: engine)
                .ignoresSafeArea()
        }
        // 3-d - the GROUP (mesh) call surface + the shell-level ring banner
        // and the 'Ongoing group call · N in call - Join' discovery banner
        // (web GroupCallRingBanner parity). Suppressed while CallKit owns
        // the incoming presentation (no double-ring).
        if let groupEngine = session.groupCallEngine {
            GroupCallOverlayHostView(engine: groupEngine, viewer: prefs.viewer)
                .ignoresSafeArea()
            GroupCallBannerHostView(engine: groupEngine)
        }
        // W5-f - the rooms surfaces (voice/stage/space) present through ONE
        // fullScreenCover driven by the session model's surface state;
        // hosted at the root so room membership survives chat navigation
        // (VR-1).
        if let rooms = session.voiceRooms {
            VoiceRoomsSurfaceHost(model: rooms)
        }
    }

    private func switchTab(_ target: PulseTab) {
        guard target != tab else { return }
        navDirection = target.rawValue > tab.rawValue ? 1 : -1
        tab = target
    }

    // ── R4-A item 3 - nav-style geometry + dock dispatch ────

    /// The shared dock state/actions - every style renderer consumes THIS
    /// (web TabProps + onContextAction parity); RootView keeps owning the
    /// sheets and bridges behind every closure.
    private var dockContext: PulseDockContext {
        PulseDockContext(
            unread: session.dockUnreadCount,
            dark: isDark,
            reduceMotion: reduceMotion,
            onTab: { switchTab($0) },
            onCompose: { newChatOpen = true },
            onSettings: { settingsOpen = true },
            onSearch: { session.requestChatsSearch() },
            onCalls: { callsOpen = true },
            onSaved: { savedLibraryOpen = true },
            onStories: { storiesOpen = true },
            // R50-c - Hub joins the dock menu (tab jump, same as the dock tab).
            onHub: { switchTab(.hub) },
        )
    }

    /// Bottom-zone styles reserve the dock's footprint; floating-top and rail
    /// reserve nothing at the bottom - they occupy their own top/leading
    /// channels instead. Radial is the overlay FAB: it floats above the
    /// bottom edge, so the SAME footprint applies. EMB-I - the reserve is
    /// the list contentPadding: content slides behind the floating pill and
    /// rests ~96pt clear of the screen bottom (dock 64 + float 14 + air).
    private var dockBottomReserve: CGFloat {
        if session.roomVisible { return 0 }
        switch navStyle.zone {
        case .bottom, .overlay: return 96
        default: return 0
        }
    }

    /// PiP clearances - mirror the visible nav channels so panes never
    /// slide under the floating-top bar or the rail.
    private var pipTopReserve: CGFloat {
        (!session.roomVisible && navStyle.zone == .top) ? PulseDockMetrics.topBarHeight : 0
    }

    private var pipLeadingReserve: CGFloat {
        (!session.roomVisible && navStyle.zone == .side) ? PulseDockMetrics.railWidth : 0
    }

    /// R14 5-b - the top-zone channel dispatch: the floating-top bar stays
    /// byte-as-is; the command-bar strip joins it (both live in the top
    /// inset so every screen clears them through the same mechanism).
    @ViewBuilder
    private var topDock: some View {
        switch navStyle {
        case .commandBar:
            CommandBarDock(context: dockContext, active: tab)
        default:
            FloatingTopDock(context: dockContext, active: tab)
        }
    }

    /// The bottom-zone dock host - capsule stays byte-as-is (brief), the
    /// other bottom styles are the R4-A renderers over the shared context.
    /// R14 5-b - floating-dock / radial / gesture / contextual-dock join as
    /// the honest mobile adaptations of the remaining web idioms.
    @ViewBuilder
    private var bottomDock: some View {
        switch navStyle {
        case .capsule:
            CapsuleDock(
                session: session,
                dark: isDark,
                reduceMotion: reduceMotion,
                tab: tab,
                onTab: { switchTab($0) },
                onCompose: { newChatOpen = true },
                onSettings: { settingsOpen = true },
                onSaved: { savedLibraryOpen = true },
                onStories: { storiesOpen = true },
                onCalls: { callsOpen = true },
                onHub: { switchTab(.hub) },
            )
        case .floatingTop, .commandBar, .rail:
            // Top/side styles render in their own inset channels.
            Color.clear.frame(height: 0)
        case .floatingDock:
            FloatingDockDock(context: dockContext, active: tab)
        case .pill:
            PillNavDock(context: dockContext, active: tab)
        case .bottomBar:
            BottomBarDock(context: dockContext, active: tab)
        case .tabBar:
            TabBarDock(context: dockContext, active: tab)
        case .floatingTabBar:
            FloatingTabBarDock(context: dockContext, active: tab)
        case .island:
            IslandDock(context: dockContext, active: tab)
        case .radial:
            RadialDock(context: dockContext, active: tab)
        case .gesture:
            GestureDock(context: dockContext, active: tab)
        case .contextualDock:
            ContextualDock(context: dockContext, active: tab)
        }
    }

    // ── Wave 6 deep links (F-DL) ─────────────────────────────

    /// pulse://invite/{code} → JoinGroupSheet parity · pulse://user/{id} →
    /// UserPage via the Contacts tab · pulse://room/{id} → open conversation
    /// · R14 5-b - pulse://login/{name} → the web /?login= onboarding
    /// prefill (identity-less apps ONLY). Links arriving while signed in
    /// are IGNORED (the web requires login too - no half-onboarded limbo).
    private func handleDeepLink(_ url: URL) {
        guard let link = PulseDeepLink.parse(url) else { return }
        switch link {
        case .login(let name):
            guard prefs.viewer == nil else { return }
            PulseHaptics.tap()
            pendingLoginName = name
        case .invite(let code):
            guard prefs.viewer != nil else { return }
            PulseHaptics.tap()
            pendingInvite = InviteLinkTarget(code: code)
        case .user(let userId):
            guard prefs.viewer != nil else { return }
            PulseHaptics.tap()
            switchTab(.contacts)
            session.requestOpenUser(userId, name: nil)
        case .room(let conversationId):
            guard prefs.viewer != nil else { return }
            openLinkedRoom(conversationId)
        case .compose:
            // R10-b - pulse://new (the quick-action destination parity).
            guard prefs.viewer != nil else { return }
            PulseHaptics.tap()
            newChatOpen = true
        }
    }

    private func openLinkedRoom(_ conversationId: String) {
        Task {
            if let conversation = try? await session.api.conversationDetail(id: conversationId, userId: session.api.userId) {
                switchTab(.chats)
                session.requestOpenRoom(conversation)
            } else {
                session.toasts.show("That chat isn't available right now")
            }
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
// The floating capsule dock - PULSE EMBER chrome (EMB-I): a 64pt dark
// warm pill (white 8% ring) with the four registry tabs + More, and the
// 56pt plus FAB floating to its right (compose). Active tab = white icon
// + label with a 4pt red dot under the icon; inactive = white 45%.
// Routing, badges, the More menu, haptics and the wobble are unchanged.
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
    // R3-A item 9 - More → Calls (the real history page).
    var onCalls: () -> Void = {}
    // R50-c - More → Hub (tab jump parity with the shared dock context).
    var onHub: () -> Void = {}

    @State private var moreOpen = false
    @State private var wobbling: PulseTab?
    @State private var wobbleAngle = 0.0

    var body: some View {
        HStack(spacing: 10) {
            // The floating pill: the four reference tabs (Chats / Call /
            // Updates / Profile); compose lives on the FAB, More moved to the
            // chats header ellipsis.
            HStack(spacing: 4) {
                dockTab(.chats, icon: "bubble.left.and.bubble.right", filled: "bubble.left.and.bubble.right.fill", label: "Chats", badge: session.dockUnreadCount)
                dockTab(.calls, icon: "phone", filled: "phone.fill", label: "Call", badge: 0)
                dockTab(.hub, icon: "arrow.triangle.2.circlepath", filled: "arrow.triangle.2.circlepath", label: "Updates", badge: 0)
                dockTab(.profile, icon: "person.crop.circle", filled: "person.crop.circle.fill", label: "Profile", badge: 0)
            }
            .padding(.horizontal, 10)
            .frame(height: 64)
            .background(dockPanel)

            composeButton
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 14)
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
                        .offset(y: -96)
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
                    .font(.system(size: 22, weight: isActive ? .semibold : .medium))
                    .foregroundStyle(isActive ? Color.white : Color.white.opacity(0.45))
                Text(label)
                    .font(.system(size: 10, weight: isActive ? .semibold : .medium))
                    .foregroundStyle(isActive ? Color.white : Color.white.opacity(0.45))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .overlay(alignment: .top) {
                if isActive {
                    // 4pt red signal dot, centered 3pt under the icon.
                    Circle()
                        .fill(PulseTheme.emberRed)
                        .frame(width: 4, height: 4)
                        .offset(y: 27)
                        .accessibilityHidden(true)
                }
            }
            .overlay(alignment: .topTrailing) {
                if badge > 0 {
                    Text(badge > 99 ? "99+" : "\(badge)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 20, minHeight: 20)
                        .background(Capsule().fill(PulseTheme.emberRed))
                        .overlay(Capsule().strokeBorder(PulseTheme.emberDotRing, lineWidth: 2))
                        .offset(x: 10, y: -2)
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

    // ── compose - the 56pt ember FAB right of the pill ──────
    private var composeButton: some View {
        Button {
            PulseHaptics.tap()
            onCompose()
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 24, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(Circle().fill(Color.white.opacity(0.10)))
                .overlay(Circle().strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(DockPressStyle())
        .accessibilityLabel("New chat")
    }

    // ── more ──────────────────────────────────────────────────
    private var moreButton: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) { moreOpen.toggle() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.45))
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .buttonStyle(DockPressStyle())
        .accessibilityLabel("More")
    }

    private var moreMenu: some View {
        VStack(alignment: .leading, spacing: 2) {
            moreItem("Hub", icon: "globe.americas") {
                onHub()
            }
            moreItem("Settings", icon: "gearshape.fill") {
                onSettings()
            }
            moreItem("Search", icon: "magnifyingglass") {
                session.requestChatsSearch()
            }
            moreItem("Calls", icon: "phone") {
                onCalls()
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
                .foregroundStyle(.white)
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

    // ── ember chrome (EMB-I): dark warm pill + menu ─────────
    private var dockPanel: some View {
        RoundedRectangle(cornerRadius: 32, style: .continuous)
            .fill(PulseTheme.emberChrome.opacity(0.92))
            .overlay(
                RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.35), radius: 18, y: 10)
    }

    private var menuPanel: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(PulseTheme.emberChrome.opacity(0.96))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.10), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.35), radius: 16, y: 8)
    }
}

/// Identifiable wrapper so the invite join sheet can ride .sheet(item:).
private struct InviteLinkTarget: Identifiable {
    let code: String
    var id: String { code }
}

/// Dock press feedback - scale .88 spring on every dock button.
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
