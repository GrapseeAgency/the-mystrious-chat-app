import SwiftUI

// ─────────────────────────────────────────────────────────────
// R4-A item 3 - the navigation architectures (R14 5-b: all 13 web idioms).
// Web ground truth: src/lib/nav-registry.ts (:50-64 meta - labels/hints
// byte-verbatim, ported into PulseNavStyle in PulsePrefs.swift) +
// src/components/chat/nav-router.tsx renderers:
//   FloatingTopNav :466 · PillNav :563 · BottomBar :617 · TabBarNav :664 ·
//   FloatingTabBar :719 · RailNav :873 · IslandNav :925.
// The current default capsule dock STAYS in RootView.swift untouched
// (brief: keep as-is) - the styles below are layout renderers over
// the SAME shared state/actions (PulseDockContext = web TabProps +
// onContextAction parity): one tab registry (PulseNavDestinations ≙
// NAV_ITEMS :68-73), one unread badge (≙ UnreadBadge :105-122, 99+ cap),
// one More menu (≙ NavOverflowButton rows).
// R14 5-b - the five previously-excluded web idioms now ship as honest
// mobile adaptations (floating-dock = magnify-emphasis dock :floating-dock,
// command-bar = top text strip, radial = FAB arc overlay, gesture = drag
// pill quick switcher, contextual-dock = per-tab trailing chip).
// R96 - the per-style ACTIVE TRUTH sweep: every renderer now carries its
// own web dark-mode language from nav-router.tsx (amber-400 active tints,
// per-style pill/indicator shapes) instead of the uniform white wash.
// iOS deviations, both deliberate and documented: compose rides only the
// capsule (all other styles keep the Chats-header compose entry), and every
// style carries a More affordance because the dock menu is the ONLY
// Settings entry on iOS (web surfaces don't all expose it).
// ─────────────────────────────────────────────────────────────

/// Shared geometry constants - the PiP host (RootView) reserves the same
/// channels so panes never slide under the top bar or the rail.
enum PulseDockMetrics {
    /// Floating-top bar: 52pt tabs + 12pt padding + 2pt top pad.
    static let topBarHeight: CGFloat = 66
    /// Web rail w-[68px].
    static let railWidth: CGFloat = 68
}

/// One destination in the shared dock model (web NAV_ITEMS :68-73 -
/// id/label/badge registry; icons are the SF Symbols mirrors already used
/// by the shipped capsule dock).
struct PulseNavDestination: Identifiable, Equatable {
    let tab: PulseTab
    let label: String
    let icon: String
    let filled: String
    var id: PulseTab { tab }
}

enum PulseNavDestinations {
    static let all: [PulseNavDestination] = [
        PulseNavDestination(tab: .chats, label: "Chats", icon: "bubble.left.and.bubble.right", filled: "bubble.left.and.bubble.right.fill"),
        PulseNavDestination(tab: .calls, label: "Call", icon: "phone", filled: "phone.fill"),
        PulseNavDestination(tab: .hub, label: "Updates", icon: "arrow.triangle.2.circlepath", filled: "arrow.triangle.2.circlepath"),
        PulseNavDestination(tab: .profile, label: "Profile", icon: "person.crop.circle", filled: "person.crop.circle.fill"),
    ]

    /// Web NAV_ITEMS[0] - the badge-carrying destination.
    static let chats = all[0]
}

/// Shared dock state + actions - every style is a layout renderer over the
/// SAME context (web TabProps + onContextAction parity). RootView owns the
/// sheets behind every closure; the renderers never touch storage.
struct PulseDockContext {
    let unread: Int
    let dark: Bool
    let reduceMotion: Bool
    let onTab: (PulseTab) -> Void
    let onCompose: () -> Void
    let onSettings: () -> Void
    let onSearch: () -> Void
    let onCalls: () -> Void
    let onSaved: () -> Void
    let onStories: () -> Void
    // R50-c - the profile More-menu parity: Hub joins the dock menu on every
    // nav style (the user audit: the three dots only had Settings).
    var onHub: () -> Void = {}
}

// ── shared unread badge (web UnreadBadge :105-122) ──────────

/// Emerald gradient disc, 99+ cap, ring - pops (scale 0.4 → 1) on every
/// count CHANGE like the web key-remount spring. Hidden at count ≤ 0.
struct PulseNavBadge: View {
    let count: Int
    let dark: Bool
    let reduceMotion: Bool
    @State private var popScale: CGFloat = 0.4

    var body: some View {
        Group {
            if count > 0 {
                Text(count > 99 ? "99+" : "\(count)")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .frame(minWidth: 20, minHeight: 20)
                    .background(Capsule().fill(PulseTheme.emberRed))
                    .overlay(Capsule().strokeBorder(PulseTheme.emberDotRing, lineWidth: 2))
                    .scaleEffect(popScale)
                    .accessibilityLabel("\(count) unread chats")
            }
        }
        .onAppear {
            guard !reduceMotion else {
                popScale = 1
                return
            }
            withAnimation(.spring(response: 0.3, dampingFraction: 0.55)) { popScale = 1 }
        }
        .onChange(of: count) { _, _ in
            guard !reduceMotion else {
                popScale = 1
                return
            }
            popScale = 0.4
            withAnimation(.spring(response: 0.3, dampingFraction: 0.55)) { popScale = 1 }
        }
    }
}

// ── shared More menu (web NavOverflowButton rows) ───────────

/// The five compact glass-menu rows - identical content to the shipped
/// CapsuleDock More menu (R3-A), factored so every non-capsule style hosts
/// the same surface. Each row haptic-closes then fires.
struct PulseNavMoreMenu: View {
    let context: PulseDockContext
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            PulseNavMoreMenu.row("Settings", icon: "gearshape.fill", onDismiss: onDismiss) {
                context.onSettings()
            }
            PulseNavMoreMenu.row("Search", icon: "magnifyingglass", onDismiss: onDismiss) {
                context.onSearch()
            }
            PulseNavMoreMenu.row("Calls", icon: "phone", onDismiss: onDismiss) {
                context.onCalls()
            }
            PulseNavMoreMenu.row("Saved", icon: "bookmark", onDismiss: onDismiss) {
                context.onSaved()
            }
            PulseNavMoreMenu.row("Stories", icon: "sparkles", onDismiss: onDismiss) {
                context.onStories()
            }
        }
        .padding(6)
        .frame(width: 232, alignment: .leading)
        .background(PulseNavMenuPanel(dark: context.dark))
        .transition(.scale(scale: 0.92, anchor: .bottom).combined(with: .opacity))
    }

    private static func row(_ label: String, icon: String, onDismiss: @escaping () -> Void, action: @escaping () -> Void) -> some View {
        Button {
            PulseHaptics.tap()
            onDismiss()
            action()
        } label: {
            Label(label, systemImage: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The glass recipe behind the menu (same layers as the shipped dock
/// panel - CapsuleDock keeps its private copy byte-as-is per the brief).
/// The scheme comes from the dock context (RootView's resolved isDark),
/// matching the panel it floats above instead of the global trait.
struct PulseNavMenuPanel: View {
    let dark: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(PulseTheme.emberChrome.opacity(0.96)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(0.16), radius: 14, y: 6)
    }
}

/// Veil + anchored glass menu - the shipped CapsuleDock dismissal pattern
/// (veil covers the screen via ignoresSafeArea, menu floats above the
/// trigger). Shared by every non-capsule style.
private struct PulseNavMenuHost: View {
    let anchor: Alignment
    let context: PulseDockContext
    let onDismiss: () -> Void
    /// vertical nudge between the trigger and the menu (negative = above).
    var menuOffsetY: CGFloat = -80
    /// horizontal nudge for side rails (menu clears the rail to the right).
    var menuOffsetX: CGFloat = 0

    var body: some View {
        ZStack(alignment: anchor) {
            Color.clear
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { onDismiss() }
                }
                .accessibilityLabel("Close menu")
            PulseNavMoreMenu(context: context, onDismiss: onDismiss)
                .offset(x: menuOffsetX, y: menuOffsetY)
        }
        .transition(.opacity)
    }
}

// ── shared tab press feedback ───────────────────────────────
// (the wobble lives per-renderer as @State - the proven CapsuleDock
// runWobble pattern; floating-top reuses the capsule tab semantics.)

// ── 2 · floating-top - glass capsule bar beneath the top edge ──
// (web FloatingTopNav :518-553 via CapsuleTab :128-202: active pill is the
// amber-400 dark wash 16% -> 5% + amber-400/25 inset ring (:169); active
// icon/label dark:amber-400, inactive dark:zinc-400 (:182/:195).)

struct FloatingTopDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false
    // The web wobble state (rotate [0, -8, 6, 0]° over ~350ms,
    // nav-router.tsx:152) - reduced motion → no-op.
    @State private var wobbling: PulseTab?
    @State private var wobbleAngle = 0.0

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 1) {
                ForEach(PulseNavDestinations.all) { item in
                    floatingTopTab(item)
                }
                moreButton
            }
            .padding(6)
            .background(floatingTopPanel)
            .padding(.top, 2)
        }
        .padding(.horizontal, 12)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
            }
        }
        .frame(height: PulseDockMetrics.topBarHeight, alignment: .top)
    }

    /// Web CapsuleTab :128-202 verbatim semantics - active pill, icon
    /// scale 1.08, 10pt label, wobble on press.
    private func floatingTopTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            runWobble(item.tab)
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 22, weight: isActive ? .semibold : .regular))
                    .scaleEffect(isActive ? 1.08 : 1)
                    .offset(y: isActive ? -1 : 0)
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 10, weight: isActive ? .semibold : .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, minHeight: 52)
            .background {
                if isActive {
                    // web CapsuleTab :169 dark truth - amber-400 wash
                    // 16% -> 5% + amber-400/25 inset ring.
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(LinearGradient(
                            colors: [PulseTheme.amber400.opacity(0.16), PulseTheme.amber400.opacity(0.05)],
                            startPoint: .top, endPoint: .bottom,
                        ))
                        .overlay(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .strokeBorder(PulseTheme.amber400.opacity(0.25), lineWidth: 1),
                        )
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .rotationEffect(.degrees(wobbling == item.tab ? wobbleAngle : 0))
        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: isActive)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    /// Web CapsuleTab wobble - rotate [0, -8, 6, 0]° over ~350ms.
    private func runWobble(_ target: PulseTab) {
        guard !context.reduceMotion else { return }
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

    private var moreButton: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen.toggle() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("More options")
    }

    private var floatingTopPanel: some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(PulseTheme.emberChrome.opacity(0.92)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 4 · pill - single segmented pill with sliding fill ──────
// (web PillNav :615-665: rounded-full GLASS_PANEL, one amber-600 ->
// orange-600 gradient fill that slides to the active segment (:650),
// white label when active, dark:zinc-400 inactive (:643).)

struct PillNavDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false
    @Namespace private var fillNamespace

    var body: some View {
        HStack(spacing: 0) {
            ForEach(PulseNavDestinations.all) { item in
                pillSegment(item)
            }
            pillMoreButton
        }
        .padding(4)
        .background(pillPanel)
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -66)
            }
        }
        .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: active)
    }

    private func pillSegment(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 17, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 8, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 11, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? Color.white : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, minHeight: 44)
            .background {
                if isActive {
                    // The sliding fill - matchedGeometryEffect ≙ the web's
                    // layoutId "nav-pill-fill" (:650) - amber-600 -> orange-600.
                    Capsule()
                        .fill(LinearGradient(
                            colors: [PulseTheme.amber600, PulseTheme.orange600],
                            startPoint: .leading, endPoint: .trailing,
                        ))
                        .matchedGeometryEffect(id: "pulse-nav-pill-fill", in: fillNamespace)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    /// iOS deviation: the dock More menu is the only Settings entry.
    private var pillMoreButton: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen.toggle() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .frame(width: 40, height: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("More options")
    }

    private var pillPanel: some View {
        Capsule()
            .fill(.ultraThinMaterial)
            .overlay(
                Capsule()
                    .fill(PulseTheme.emberChrome.opacity(0.92)),
            )
            .overlay(
                Capsule()
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 5 · bottom-bar - classic edge-to-edge bar with labels ───
// (web BottomBar :669-712: full-width bar, border-t, active dark:amber-400
// (:691) with a top-center amber-500 tick (:703), icon-over-label rows.)

struct BottomBarDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false

    var body: some View {
        HStack(spacing: 0) {
            ForEach(PulseNavDestinations.all) { item in
                bottomBarTab(item)
            }
            bottomBarMore
        }
        .padding(.top, 6)
        .frame(maxWidth: .infinity)
        .frame(height: 56)
        .background {
            // Edge-to-edge - the material bleeds through the home-indicator
            // strip (web pb-[env(safe-area-inset-bottom)] parity).
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(PulseTheme.emberChrome.opacity(0.96)))
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(Color.white.opacity(0.10))
                        .frame(height: 0.5)
                }
                .ignoresSafeArea(edges: .bottom)
        }
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -70)
            }
        }
    }

    private func bottomBarTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 22, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .top) {
                if isActive {
                    // web "nav-bottombar-dot" (:703) - top-center amber-500 tick.
                    Capsule()
                        .fill(PulseTheme.amber500)
                        .frame(width: 32, height: 3)
                        .offset(y: -3)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private var bottomBarMore: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen.toggle() }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 22, weight: .regular))
                Text("More")
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("More options")
    }
}

// ── 6 · tab-bar - iOS-style tinted squircles ────────────────
// (web TabBarNav :716-767: full-width bar, active tab wears a rounded
// amber-400/10 dark squircle with an amber-500/25 inset ring (:744).)

struct TabBarDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(PulseNavDestinations.all) { item in
                tabBarTab(item)
            }
            tabBarMore
        }
        .padding(.horizontal, 8)
        .padding(.top, 6)
        .frame(maxWidth: .infinity)
        .frame(height: 58)
        .background {
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(PulseTheme.emberChrome.opacity(0.96)))
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(Color.white.opacity(0.10))
                        .frame(height: 0.5)
                }
                .ignoresSafeArea(edges: .bottom)
        }
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -72)
            }
        }
    }

    private func tabBarTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 21, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 10, weight: isActive ? .semibold : .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if isActive {
                    // web "nav-tabbar-squircle" (:744 dark) - rounded-2xl
                    // amber-400/10 fill + amber-500/25 inset ring.
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(PulseTheme.amber400.opacity(0.10))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(PulseTheme.amber500.opacity(0.25), lineWidth: 1),
                        )
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private var tabBarMore: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen.toggle() }
        } label: {
            VStack(spacing: 3) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 21, weight: .regular))
                Text("More")
                    .font(.system(size: 10, weight: .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("More options")
    }
}

// ── 7 · floating-tab-bar - detached elevated card, active lifted ──
// (web FloatingTabBar :771-834: detached GLASS_PANEL card, the active tab
// translates -4 and wears the dark zinc-800 -> zinc-900 card + amber-500/30
// ring (:805), dark:amber-400 tints (:813/:823).)

struct FloatingTabBarDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false

    var body: some View {
        HStack(spacing: 6) {
            ForEach(PulseNavDestinations.all) { item in
                floatingTab(item)
            }
            floatingTabMore
        }
        .padding(8)
        .background(floatingTabPanel)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -84)
            }
        }
    }

    private func floatingTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 21, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, minHeight: 54)
            .background {
                if isActive {
                    // web "nav-ftab-card" (:805 dark) - elevated zinc-800 ->
                    // zinc-900 card + amber-500/30 ring.
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(LinearGradient(
                            colors: [PulseTheme.zinc(800), PulseTheme.zinc(900)],
                            startPoint: .top, endPoint: .bottom,
                        ))
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .strokeBorder(PulseTheme.amber500.opacity(0.30), lineWidth: 1),
                        )
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .offset(y: isActive ? -4 : 0)
        .scaleEffect(isActive ? 1.02 : 1)
        .animation(.pulse(.pulseBouncy, reduceMotion: context.reduceMotion), value: isActive)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private var floatingTabMore: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen.toggle() }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 21, weight: .regular))
                Text("More")
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("More options")
    }

    private var floatingTabPanel: some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(PulseTheme.emberChrome.opacity(0.92)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 9 · rail - persistent vertical side rail (left edge) ────
// (web RailNav :925-973: 68pt rail, "P" logo tile, dark:amber-400 active
// tint (:952) with the left amber-500 indicator bar (:959) - NO pill fill.)

struct RailDock: View {
    let context: PulseDockContext
    let active: PulseTab

    var body: some View {
        VStack(spacing: 4) {
            // web :880 - the brand tile.
            Text("P")
                .font(.system(size: 14, weight: .black))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white.opacity(0.12)))
                
                .padding(.bottom, 10)
                .accessibilityHidden(true)

            ForEach(PulseNavDestinations.all) { item in
                railTab(item)
            }

            Spacer(minLength: 0)

            // iOS deviation: system Menu - the rail is 68pt wide, too
            // narrow to anchor the glass popover; the dock menu is the only
            // Settings entry on iOS so the affordance must exist.
            Menu {
                Button { context.onHub() } label: { Label("Hub", systemImage: "globe.americas") }
                Button { context.onSettings() } label: { Label("Settings", systemImage: "gearshape") }
                Button { context.onSearch() } label: { Label("Search", systemImage: "magnifyingglass") }
                Button { context.onCalls() } label: { Label("Calls", systemImage: "phone") }
                Button { context.onSaved() } label: { Label("Saved", systemImage: "bookmark") }
                Button { context.onStories() } label: { Label("Stories", systemImage: "sparkles") }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("More options")
        }
        .frame(width: PulseDockMetrics.railWidth)
        .frame(maxHeight: .infinity)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .background {
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(PulseTheme.emberChrome.opacity(0.94)))
                .overlay(alignment: .trailing) {
                    Rectangle()
                        .fill(Color.white.opacity(0.10))
                        .frame(width: 0.5)
                }
                .ignoresSafeArea()
        }
    }

    private func railTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 20, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 8, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 9, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(width: 56)
            .padding(.vertical, 10)
            .overlay(alignment: .leading) {
                if isActive {
                    // web "nav-rail-bar" (:959) - left amber-500 edge
                    // indicator; RailNav wears NO pill fill, tint + bar only.
                    Capsule()
                        .fill(PulseTheme.amber500)
                        .frame(width: 4, height: 28)
                        .offset(x: -10)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: isActive)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }
}

// ── 10 · island - dynamic-island pill that expands on tap ───
// (web IslandNav :977-1093: closed 148pt pill showing the amber-400 active
// icon (:1082), tap expands; active tab wears the amber-500/18 pill +
// amber-500/30 ring (:1041), dark:amber-400 icon (:1048).)

struct IslandDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var expanded = false
    @State private var moreOpen = false

    private var activeItem: PulseNavDestination {
        let match = PulseNavDestinations.all.first { $0.tab == active }
        return match ?? PulseNavDestinations.all[0]
    }

    var body: some View {
        GeometryReader { geo in
            let expandedWidth = min(geo.size.width - 24, 380)
            islandBody(expandedWidth: expandedWidth)
                .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
        }
        .frame(height: PulseDockMetrics.topBarHeight)
        .padding(.bottom, 12)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -96)
            }
        }
        // Auto-collapse ≙ web :935-941 - 4.2s timer, paused while the
        // overflow menu is open (the menu lives inside the expanded island).
        .task(id: "\(expanded)#\(moreOpen)") {
            guard expanded, !moreOpen else { return }
            try? await Task.sleep(nanoseconds: 4_200_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { expanded = false }
        }
    }

    private func islandBody(expandedWidth: CGFloat) -> some View {
        // The nav itself toggles expansion on tap (web :948-951).
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { expanded.toggle() }
        } label: {
            Group {
                if expanded {
                    expandedRow
                        .transition(.scale(scale: 0.9).combined(with: .opacity))
                } else {
                    closedPill
                        .transition(.scale(scale: 0.85).combined(with: .opacity))
                }
            }
            .frame(width: expanded ? expandedWidth : 148, height: expanded ? 62 : 54)
            .background(islandPanel)
            .clipShape(Capsule())
            .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: expanded)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(expanded ? "Collapse navigation" : "Navigation - \(activeItem.label), tap to expand")
    }

    /// Closed state ≙ web :1021-1035 - active icon + label + grip glyph.
    private var closedPill: some View {
        HStack(spacing: 8) {
            Image(systemName: activeItem.filled)
                .font(.system(size: 22, weight: .semibold))
                // web :1082 dark truth - the closed-pill icon is amber-400.
                .foregroundStyle(PulseTheme.amber400)
                .overlay(alignment: .topTrailing) {
                    if activeItem.tab == PulseNavDestinations.chats.tab {
                        PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                            .offset(x: 8, y: -6)
                    }
                }
            Text(activeItem.label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.zinc(200))
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(PulseTheme.textTertiary)
        }
        .padding(.horizontal, 14)
    }

    /// Expanded state ≙ web :961-1019 - 4 destinations + labeled More.
    private var expandedRow: some View {
        HStack(spacing: 0) {
            ForEach(PulseNavDestinations.all) { item in
                islandTab(item)
            }
            islandMore
        }
        .padding(6)
    }

    private func islandTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { expanded = false }
        } label: {
            VStack(spacing: 2) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 20, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 8, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(PulseTheme.zinc(300))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, minHeight: 46)
            .background {
                if isActive {
                    // web "nav-island-pill" (:1041) - amber-500/18 fill
                    // + amber-500/30 inset ring.
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(PulseTheme.amber500.opacity(0.18))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .strokeBorder(PulseTheme.amber500.opacity(0.30), lineWidth: 1),
                        )
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private var islandMore: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen = true }
        } label: {
            VStack(spacing: 2) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(PulseTheme.textSecondary)
                Text("More")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("More options")
    }

    private var islandPanel: some View {
        Capsule()
            .fill(.ultraThinMaterial)
            .overlay(
                Capsule()
                    .fill(PulseTheme.emberChrome.opacity(0.92)),
            )
            .overlay(
                Capsule()
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 3 · floating-dock - desktop dock, mobile magnify emphasis ──
// (web FloatingDock :557-611 is a hover-magnifying desktop dock; phones
// have no hover, so the honest adaptation keeps the roomy tile dock and
// applies the magnification to the ACTIVE destination instead. R14 5-b.
// Active truth (:591/:598 dark): amber-500/25 -> 8% wash pill + amber-500/40
// ring, dark:amber-400 icon, dark:zinc-400 inactive.)

struct FloatingDockDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false

    var body: some View {
        HStack(spacing: 14) {
            ForEach(PulseNavDestinations.all) { item in
                floatingDockTab(item)
            }
            floatingDockMore
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(floatingDockPanel)
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -84)
            }
        }
        .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: active)
    }

    private func floatingDockTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 24, weight: .medium))
                    // The magnify emphasis - the active tile grows while the
                    // rest stay dock-sized (web hover-magnify parity).
                    .scaleEffect(isActive ? 1.3 : 1.0)
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 9.5, weight: isActive ? .bold : .medium))
                    .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(minWidth: 48)
            .background {
                if isActive {
                    // web "nav-dock-pill" (:591 dark) - amber-500/25 -> 8%
                    // vertical wash + amber-500/40 inset ring.
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(LinearGradient(
                            colors: [PulseTheme.amber500.opacity(0.25), PulseTheme.amber500.opacity(0.08)],
                            startPoint: .top, endPoint: .bottom,
                        ))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(PulseTheme.amber500.opacity(0.40), lineWidth: 1),
                        )
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private var floatingDockMore: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { moreOpen.toggle() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .frame(width: 36, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("More options")
    }

    private var floatingDockPanel: some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(PulseTheme.emberChrome.opacity(0.92)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 8 · command-bar - compact text command strip with search ──
// (web CommandBarNav :838-921: a TOP text strip - brand, text tab commands,
// then the search + settings glyphs inline. Active truth: dark:amber-400
// text (:887) over an amber-500 bottom bar (:894) - NO pill. R14 5-b.)

struct CommandBarDock: View {
    let context: PulseDockContext
    let active: PulseTab

    var body: some View {
        HStack(spacing: 2) {
            // web :825 - the brand tile leads the strip.
            Text("P")
                .font(.system(size: 12, weight: .black))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color.white.opacity(0.12)))
                .padding(.trailing, 6)
                .accessibilityHidden(true)

            ForEach(PulseNavDestinations.all) { item in
                commandTab(item)
            }

            Spacer(minLength: 0)

            commandButton("magnifyingglass", "Search") {
                context.onSearch()
            }
            commandButton("gearshape.fill", "Settings") {
                context.onSettings()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background {
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(PulseTheme.emberChrome.opacity(0.96)))
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(Color.white.opacity(0.10))
                        .frame(height: 0.5)
                }
                .ignoresSafeArea(edges: .top)
        }
        .frame(height: PulseDockMetrics.topBarHeight, alignment: .bottom)
    }

    private func commandTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            Text(item.label)
                .font(.system(size: 12, weight: isActive ? .bold : .medium))
                .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
                .padding(.horizontal, 10)
                .frame(minHeight: 36)
                .overlay(alignment: .bottom) {
                    if isActive {
                        // web "nav-cmd-underline" (:894) - amber-500 bar.
                        Capsule()
                            .fill(PulseTheme.amber500)
                            .frame(height: 2.5)
                            .padding(.horizontal, 8)
                            .offset(y: 2)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if item.tab == PulseNavDestinations.chats.tab {
                        PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                            .offset(x: 8, y: 2)
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private func commandButton(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button {
            PulseHaptics.tap()
            action()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
                .frame(width: 36, height: 36)
                .contentShape(Circle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(label)
    }
}

// ── 11 · radial - center FAB fanning destinations in an arc ──
// (web RadialNav :1097-1165: a FAB overlay; tap fans the destinations in
// an arc above it. Active truth: dark:amber-600 icon (:1136), white label;
// FAB amber-400 -> orange-600 gradient (:1157). Reduced motion -> instant
// placement. R14 5-b.)

struct RadialDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var expanded = false

    private var activeItem: PulseNavDestination {
        let match = PulseNavDestinations.all.first { $0.tab == active }
        return match ?? PulseNavDestinations.all[0]
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if expanded {
                // The veil: a tap anywhere collapses the fan.
                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { expanded = false }
                    }
                    .accessibilityLabel("Close navigation")
                radialFan
                    .transition(.opacity)
            }
            radialFab
        }
        .padding(.bottom, 14)
        .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: expanded)
    }

    /// The arc - destinations fan out above the FAB, left to right, with
    /// labels; the active one arrives highlighted.
    private var radialFan: some View {
        HStack(spacing: 14) {
            ForEach(PulseNavDestinations.all) { item in
                radialLeaf(item)
            }
            radialLeafButton("gearshape.fill", label: "Settings") {
                context.onSettings()
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(PulseNavMenuPanel(dark: context.dark))
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .offset(y: -68)
    }

    private func radialLeaf(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { expanded = false }
        } label: {
            VStack(spacing: 3) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 20, weight: .semibold))
                    // web :1136 - the active leaf icon is amber-600.
                    .foregroundStyle(isActive ? PulseTheme.amber600 : Color.white)
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 8, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 9, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(Color.white)
            .frame(width: 58, height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    private func radialLeafButton(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button {
            PulseHaptics.tap()
            action()
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: .semibold))
                Text(label)
                    .font(.system(size: 9, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(PulseTheme.textSecondary)
            .frame(width: 58, height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var radialFab: some View {
        Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { expanded.toggle() }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: expanded ? "xmark" : activeItem.filled)
                    .font(.system(size: 18, weight: .bold))
                if !expanded {
                    Text(activeItem.label)
                        .font(.system(size: 12, weight: .bold))
                        .lineLimit(1)
                }
            }
            .foregroundStyle(.white)
            .frame(height: 54)
            .padding(.horizontal, 20)
            // web RadialNav FAB (:1157) - amber-400 -> orange-600 gradient.
            .background(Capsule().fill(LinearGradient(
                colors: [PulseTheme.amber400, PulseTheme.orange600],
                startPoint: .topLeading, endPoint: .bottomTrailing,
            )))
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(expanded ? "Close navigation" : "Navigation - \(activeItem.label), tap to fan out")
    }
}

// ── 12 · gesture - minimal bar + draggable quick-switcher pill ──
// (web GestureNav :1169-1241: a minimal bar whose pill drags between tabs
// and opens the quick switcher. Active truth: dark:amber-400 icon (:1210),
// amber-500/18 pill (:1203); handle open amber-500 w-16, closed
// dark:zinc-600 w-24 (:1235). R14 5-b - the shell already carries the
// edge-swipe route change; the pill adds the in-dock drag + switcher.)

struct GestureDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var switcherOpen = false

    private var activeItem: PulseNavDestination {
        let match = PulseNavDestinations.all.first { $0.tab == active }
        return match ?? PulseNavDestinations.all[0]
    }

    var body: some View {
        VStack(spacing: 8) {
            if switcherOpen {
                switcherPanel
                    .transition(.scale(scale: 0.94, anchor: .bottom).combined(with: .opacity))
            }
            gestureBar
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
        .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: switcherOpen)
    }

    /// Minimal bar: the active label + the draggable pill under it.
    private var gestureBar: some View {
        VStack(spacing: 6) {
            Text(activeItem.label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(PulseTheme.textSecondary)
            Capsule()
                .fill(switcherOpen ? PulseTheme.amber500 : PulseTheme.zinc(600))
                .frame(width: switcherOpen ? 64 : 96, height: 6)
                
                .frame(width: 220, height: 34, alignment: .center)
                .contentShape(Rectangle())
                // Drag the pill left/right → prev/next tab (web :1116-1147);
                // tap → the quick switcher.
                .gesture(
                    DragGesture(minimumDistance: 12)
                        .onEnded { value in
                            let dx = value.translation.width
                            guard abs(dx) > 40 else { return }
                            let order = PulseNavDestinations.all
                            guard let index = order.firstIndex(where: { $0.tab == active }) else { return }
                            let next = dx < 0 ? index + 1 : index - 1
                            guard order.indices.contains(next) else { return }
                            PulseHaptics.tap()
                            context.onTab(order[next].tab)
                        },
                )
                .onTapGesture {
                    PulseHaptics.tap()
                    withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { switcherOpen.toggle() }
                }
                .accessibilityLabel("Tab switcher - drag or tap")
                .accessibilityAddTraits(.isButton)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 20)
        .background {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(PulseTheme.emberChrome.opacity(0.92)),
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
                )
                .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 12, y: 6)
        }
        .overlay(alignment: .topTrailing) {
            // iOS deviation: the dock menu is the only Settings entry.
            Menu {
                Button { context.onHub() } label: { Label("Hub", systemImage: "globe.americas") }
                Button { context.onSettings() } label: { Label("Settings", systemImage: "gearshape") }
                Button { context.onSearch() } label: { Label("Search", systemImage: "magnifyingglass") }
                Button { context.onCalls() } label: { Label("Calls", systemImage: "phone") }
                Button { context.onSaved() } label: { Label("Saved", systemImage: "bookmark") }
                Button { context.onStories() } label: { Label("Stories", systemImage: "sparkles") }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(PulseTheme.textTertiary)
                    .frame(width: 32, height: 32)
                    .contentShape(Circle())
            }
            .accessibilityLabel("More options")
        }
    }

    /// The quick switcher - every destination + the dock actions.
    private var switcherPanel: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(PulseNavDestinations.all) { item in
                switcherRow(item)
            }
        }
        .padding(6)
        .frame(width: 232, alignment: .leading)
        .background(PulseNavMenuPanel(dark: context.dark))
    }

    private func switcherRow(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            withAnimation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion)) { switcherOpen = false }
            context.onTab(item.tab)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
                    .frame(width: 22)
                Text(item.label)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(PulseTheme.zinc(300))
                Spacer()
                if item.tab == PulseNavDestinations.chats.tab {
                    PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }
}

// ── 13 · contextual-dock - dock that adapts to the active tab ──
// (web ContextualDock :1252-1306: the four destinations as CapsuleTabs
// (amber-400 dark truth :169/:182) plus a trailing amber-500 -> orange-600
// gradient chip (:1297) whose action follows the active tab. R14 5-b.)

struct ContextualDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false

    /// web CONTEXT_ACTION - label + glyph per active tab.
    private var contextAction: (label: String, icon: String) {
        switch active {
        case .chats: return ("New chat", "plus")
        case .calls: return ("Search", "magnifyingglass")
        case .hub: return ("Search", "magnifyingglass")
        case .contacts: return ("New group", "person.2")
        case .profile: return ("Settings", "gearshape")
        }
    }

    var body: some View {
        HStack(spacing: 1) {
            ForEach(PulseNavDestinations.all) { item in
                contextualTab(item)
            }
            contextChip
        }
        .padding(6)
        .background(contextualPanel)
        .padding(.horizontal, 12)
        .padding(.bottom, 12)
        .overlay(alignment: .bottomTrailing) {
            if moreOpen {
                PulseNavMenuHost(anchor: .bottomTrailing, context: context) {
                    moreOpen = false
                }
                .offset(y: -84)
            }
        }
        .animation(.pulse(.pulseSnappy, reduceMotion: context.reduceMotion), value: active)
    }

    private func contextualTab(_ item: PulseNavDestination) -> some View {
        let isActive = active == item.tab
        return Button {
            PulseHaptics.tap()
            context.onTab(item.tab)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: isActive ? item.filled : item.icon)
                    .font(.system(size: 20, weight: isActive ? .semibold : .regular))
                    .overlay(alignment: .topTrailing) {
                        if item.tab == PulseNavDestinations.chats.tab {
                            PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                                .offset(x: 8, y: -6)
                        }
                    }
                Text(item.label)
                    .font(.system(size: 9.5, weight: isActive ? .semibold : .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .foregroundStyle(isActive ? PulseTheme.amber400 : PulseTheme.zinc(400))
            .frame(maxWidth: .infinity, minHeight: 50)
            .background {
                if isActive {
                    // web CapsuleTab :169 dark truth - amber-400 wash + ring.
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(LinearGradient(
                            colors: [PulseTheme.amber400.opacity(0.16), PulseTheme.amber400.opacity(0.05)],
                            startPoint: .top, endPoint: .bottom,
                        ))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .strokeBorder(PulseTheme.amber400.opacity(0.25), lineWidth: 1),
                        )
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    /// The trailing chip - its glyph/label/action follow the active tab
    /// (web :1225-1253 popLayout swap parity).
    private var contextChip: some View {
        Button {
            PulseHaptics.tap()
            switch active {
            case .chats: context.onCompose()
            case .calls: context.onSearch()
            case .hub: context.onSearch()
            case .contacts: context.onCompose()
            case .profile: context.onSettings()
            }
        } label: {
            VStack(spacing: 3) {
                Image(systemName: contextAction.icon)
                    .font(.system(size: 18, weight: .bold))
                Text(contextAction.label)
                    .font(.system(size: 9, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(.white)
            .frame(width: 64, height: 50)
            // web :1297 - amber-500 -> orange-600 gradient chip.
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(
                        colors: [PulseTheme.amber500, PulseTheme.orange600],
                        startPoint: .topLeading, endPoint: .bottomTrailing,
                    )),
            )
        }
        .buttonStyle(PulseButtonStyle())
        .padding(.leading, 2)
        .accessibilityLabel(contextAction.label)
    }

    private var contextualPanel: some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(PulseTheme.emberChrome.opacity(0.92)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.08), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}
