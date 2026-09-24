import SwiftUI

// ─────────────────────────────────────────────────────────────
// R4-A item 3 — the 8 phone-feasible navigation architectures.
// Web ground truth: src/lib/nav-registry.ts (:50-64 meta — labels/hints
// byte-verbatim, ported into PulseNavStyle in PulsePrefs.swift) +
// src/components/chat/nav-router.tsx renderers:
//   FloatingTopNav :466 · PillNav :563 · BottomBar :617 · TabBarNav :664 ·
//   FloatingTabBar :719 · RailNav :873 · IslandNav :925.
// The current default capsule dock STAYS in RootView.swift untouched
// (brief: keep as-is) — the seven styles below are layout renderers over
// the SAME shared state/actions (PulseDockContext = web TabProps +
// onContextAction parity): one tab registry (PulseNavDestinations ≙
// NAV_ITEMS :68-73), one unread badge (≙ UnreadBadge :105-122, 99+ cap),
// one More menu (≙ NavOverflowButton rows).
// EXCLUDED on purpose (desktop / keyboard / exotic idioms a phone pane
// cannot carry honestly): floating-dock (hover magnification), command-bar
// (⌘-strip), radial (arc overlay), gesture (edge-swipe quick switcher),
// contextual-dock (per-tab morphing). Their ids still PARSE → capsule
// fallback (PulseNavStyle.parse) so a future widening is drop-in.
// iOS deviations, both deliberate and documented: compose rides only the
// capsule (all other styles keep the Chats-header compose entry), and every
// style carries a More affordance because the dock menu is the ONLY
// Settings entry on iOS (web surfaces don't all expose it).
// ─────────────────────────────────────────────────────────────

/// Shared geometry constants — the PiP host (RootView) reserves the same
/// channels so panes never slide under the top bar or the rail.
enum PulseDockMetrics {
    /// Floating-top bar: 52pt tabs + 12pt padding + 2pt top pad.
    static let topBarHeight: CGFloat = 66
    /// Web rail w-[68px].
    static let railWidth: CGFloat = 68
}

/// One destination in the shared dock model (web NAV_ITEMS :68-73 —
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
        PulseNavDestination(tab: .hub, label: "Hub", icon: "flame", filled: "flame.fill"),
        PulseNavDestination(tab: .contacts, label: "Contacts", icon: "person.2", filled: "person.2.fill"),
        PulseNavDestination(tab: .profile, label: "Profile", icon: "person.crop.circle", filled: "person.crop.circle.fill"),
    ]

    /// Web NAV_ITEMS[0] — the badge-carrying destination.
    static let chats = all[0]
}

/// Shared dock state + actions — every style is a layout renderer over the
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
}

// ── shared unread badge (web UnreadBadge :105-122) ──────────

/// Emerald gradient disc, 99+ cap, ring — pops (scale 0.4 → 1) on every
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
                    .background(Capsule().fill(PulseTheme.brandGradient))
                    .overlay(Capsule().strokeBorder(dark ? PulseTheme.zinc(900) : .white, lineWidth: 2))
                    .shadow(color: PulseTheme.emerald500.opacity(0.65), radius: 6, y: 2)
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

/// The five compact glass-menu rows — identical content to the shipped
/// CapsuleDock More menu (R3-A), factored so every non-capsule style hosts
/// the same surface. Each row haptic-closes then fires.
struct PulseNavMoreMenu: View {
    let context: PulseDockContext
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            PulseNavMoreMenu.row("Settings", icon: "gearshape", onDismiss: onDismiss) {
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
                .foregroundStyle(PulseTheme.titleOnPanel)
                .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The glass recipe behind the menu (same layers as the shipped dock
/// panel — CapsuleDock keeps its private copy byte-as-is per the brief).
/// The scheme comes from the dock context (RootView's resolved isDark),
/// matching the panel it floats above instead of the global trait.
struct PulseNavMenuPanel: View {
    let dark: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(dark ? PulseTheme.zinc(900).opacity(0.72) : Color.white.opacity(0.78)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1),
            )
            .shadow(color: .black.opacity(0.16), radius: 14, y: 6)
    }
}

/// Veil + anchored glass menu — the shipped CapsuleDock dismissal pattern
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
// (the wobble lives per-renderer as @State — the proven CapsuleDock
// runWobble pattern; floating-top reuses the capsule tab semantics.)

// ── 2 · floating-top — glass capsule bar beneath the top edge ──
// (web FloatingTopNav :466-501: ALL four tabs + overflow, GLASS_PANEL,
// rounded-[26px], the SAME CapsuleTab wobble/pill as the default dock.)

struct FloatingTopDock: View {
    let context: PulseDockContext
    let active: PulseTab

    @State private var moreOpen = false
    // The web wobble state (rotate [0, -8, 6, 0]° over ~350ms,
    // nav-router.tsx:152) — reduced motion → no-op.
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

    /// Web CapsuleTab :128-202 verbatim semantics — active pill, icon
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
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background {
                if isActive {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(PulseTheme.dockPillGradient)
                        .overlay(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .strokeBorder(PulseTheme.emerald500.opacity(0.30), lineWidth: 1),
                        )
                        .shadow(color: PulseTheme.emerald500.opacity(0.55), radius: 10, y: 6)
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

    /// Web CapsuleTab wobble — rotate [0, -8, 6, 0]° over ~350ms.
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
                    .fill(context.dark ? PulseTheme.zinc(900).opacity(0.65) : Color.white.opacity(0.70)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 4 · pill — single segmented pill with sliding fill ──────
// (web PillNav :563-613: rounded-full GLASS_PANEL, one emerald gradient
// fill that slides to the active segment, white label when active.)

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
            .foregroundStyle(isActive ? Color.white : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background {
                if isActive {
                    // The sliding fill — matchedGeometryEffect ≙ the web's
                    // layoutId "nav-pill-fill" (:596).
                    Capsule()
                        .fill(PulseTheme.brandGradient)
                        .shadow(color: PulseTheme.emerald500.opacity(0.7), radius: 9, y: 6)
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
                .frame(width: 40, minHeight: 44)
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
                    .fill(context.dark ? PulseTheme.zinc(900).opacity(0.65) : Color.white.opacity(0.70)),
            )
            .overlay(
                Capsule()
                    .strokeBorder(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 5 · bottom-bar — classic edge-to-edge bar with labels ───
// (web BottomBar :617-660: full-width bar, border-t, active emerald with
// a top-center dot, icon-over-label rows.)

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
            // Edge-to-edge — the material bleeds through the home-indicator
            // strip (web pb-[env(safe-area-inset-bottom)] parity).
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(Rectangle().fill(context.dark ? PulseTheme.zinc(950).opacity(0.85) : Color.white.opacity(0.85)))
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.80))
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
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .top) {
                if isActive {
                    // web "nav-bottombar-dot" (:649-653) — top-center tick.
                    Capsule()
                        .fill(PulseTheme.accent)
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

// ── 6 · tab-bar — iOS-style tinted squircles ────────────────
// (web TabBarNav :664-715: full-width bar, active tab wears a rounded
// emerald-15% squircle with an inset ring.)

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
                .overlay(Rectangle().fill(context.dark ? PulseTheme.zinc(950).opacity(0.80) : Color.white.opacity(0.80)))
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70))
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
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if isActive {
                    // web "nav-tabbar-squircle" (:690-694) — inset-x-3
                    // inset-y-1 rounded-2xl emerald-500/15 + inset ring.
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(PulseTheme.emerald500.opacity(context.dark ? 0.10 : 0.15))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(PulseTheme.emerald500.opacity(0.25), lineWidth: 1),
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

// ── 7 · floating-tab-bar — detached elevated card, active lifted ──
// (web FloatingTabBar :719-782: detached GLASS_PANEL card, the active tab
// translates -4 and wears a white/zinc card + emerald ring.)

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
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background {
                if isActive {
                    // web "nav-ftab-card" (:750-755) — elevated card.
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(context.dark ? PulseTheme.zinc(800) : Color.white)
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .strokeBorder(PulseTheme.emerald500.opacity(0.30), lineWidth: 1),
                        )
                        .shadow(color: PulseTheme.emerald500.opacity(0.45), radius: 12, y: 8)
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
                    .fill(context.dark ? PulseTheme.zinc(900).opacity(0.65) : Color.white.opacity(0.70)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}

// ── 9 · rail — persistent vertical side rail (left edge) ────
// (web RailNav :873-921: 68pt rail, "P" logo tile, left indicator bar on
// the active destination, labels under icons.)

struct RailDock: View {
    let context: PulseDockContext
    let active: PulseTab

    var body: some View {
        VStack(spacing: 4) {
            // web :880 — the brand tile.
            Text("P")
                .font(.system(size: 14, weight: .black))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(PulseTheme.brandGradient))
                .shadow(color: PulseTheme.emerald500.opacity(0.7), radius: 8, y: 4)
                .padding(.bottom, 10)
                .accessibilityHidden(true)

            ForEach(PulseNavDestinations.all) { item in
                railTab(item)
            }

            Spacer(minLength: 0)

            // iOS deviation: system Menu — the rail is 68pt wide, too
            // narrow to anchor the glass popover; the dock menu is the only
            // Settings entry on iOS so the affordance must exist.
            Menu {
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
                .overlay(Rectangle().fill(context.dark ? PulseTheme.zinc(950).opacity(0.60) : Color.white.opacity(0.60)))
                .overlay(alignment: .trailing) {
                    Rectangle()
                        .fill(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70))
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
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(width: 56)
            .padding(.vertical, 10)
            .background {
                if isActive {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(PulseTheme.emerald500.opacity(0.10))
                }
            }
            .overlay(alignment: .leading) {
                if isActive {
                    // web "nav-rail-bar" (:905-909) — left edge indicator.
                    Capsule()
                        .fill(PulseTheme.accent)
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

// ── 10 · island — dynamic-island pill that expands on tap ───
// (web IslandNav :925-1041: closed 148pt pill showing the active tab,
// tap expands to the full tab row + labeled More, auto-collapse 4.2s
// paused while the overflow menu is open.)

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
        // Auto-collapse ≙ web :935-941 — 4.2s timer, paused while the
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
        .accessibilityLabel(expanded ? "Collapse navigation" : "Navigation — \(activeItem.label), tap to expand")
    }

    /// Closed state ≙ web :1021-1035 — active icon + label + grip glyph.
    private var closedPill: some View {
        HStack(spacing: 8) {
            Image(systemName: activeItem.filled)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(PulseTheme.accent)
                .overlay(alignment: .topTrailing) {
                    if activeItem.tab == PulseNavDestinations.chats.tab {
                        PulseNavBadge(count: context.unread, dark: context.dark, reduceMotion: context.reduceMotion)
                            .offset(x: 8, y: -6)
                    }
                }
            Text(activeItem.label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(PulseTheme.textTertiary)
        }
        .padding(.horizontal, 14)
    }

    /// Expanded state ≙ web :961-1019 — 4 destinations + labeled More.
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
                    .foregroundStyle(PulseTheme.titleOnPanel)
                    .lineLimit(1)
            }
            .foregroundStyle(isActive ? PulseTheme.accent : PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 46)
            .background {
                if isActive {
                    // web "nav-island-pill" (:986-990).
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(PulseTheme.emerald500.opacity(0.18))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .strokeBorder(PulseTheme.emerald500.opacity(0.30), lineWidth: 1),
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
                    .fill(context.dark ? PulseTheme.zinc(900).opacity(0.65) : Color.white.opacity(0.70)),
            )
            .overlay(
                Capsule()
                    .strokeBorder(context.dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.70), lineWidth: 1),
            )
            .shadow(color: .black.opacity(PulseTheme.panelShadowOpacity), radius: 16, y: 8)
    }
}
