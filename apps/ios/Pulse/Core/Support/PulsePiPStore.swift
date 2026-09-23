import Foundation
import Combine

// ─────────────────────────────────────────────────────────────
// Pulse — PiP pane store (R1-W2I, spec F-PI-01..03).
// Mirror of the web src/components/chat/pip-store.ts (zustand + persist):
//
// A floating pane = one conversation rendered as a draggable glass window.
// Model (web R28-a pane-management rework):
//   · at most ONE expanded (focused) window at a time
//   · every other live pane is collapsed into the compact pill stack
//     on the right edge ("minimized")
//   · maxPanes live panes; opening a new one demotes the focused window
//     to the stack and evicts the OLDEST stacked pane beyond the cap
//   · per-pane normalized position (0..1 of the draggable range)
//     survives relaunches (web localStorage `pulse.pip.v2` → native
//     UserDefaults key-value, same JSON shape)
//
// Web legacy contract kept for the room header toggle (chat-room.tsx):
//   open(id)    → expand (or create) the pane for `id`, demote the rest
//   close()     → close the focused window; stacked panes keep living
//   isOpen      → true while ≥ 1 pane exists
//   conversationId → the focused (expanded) pane's conversation,
//                    null when only the stack is showing
// Owned by PulseSession (Core/Support session layer — same home as the
// call engine / stories / voice-rooms owners) and rendered by the
// RootView-level PipPaneHostView.
// ─────────────────────────────────────────────────────────────

/// Frame geometry reserves — web pip-store.ts constants (web px ≈ iOS pt).
enum PipGeometry {
    /// side margin the pane keeps from the frame edges
    static let marginX: CGFloat = 10
    /// below the room header (~56px + safe-area + breathing room)
    static let topReserve: CGFloat = 64
    /// above the composer (~64px) / the ~76px nav capsule + safe-area
    static let bottomReserve: CGFloat = 92
    /// pill size + gap used by the stack band
    static let stackPill: CGFloat = 48
    static let stackGap: CGFloat = 8
    /// pane size clamps (web mobile-first: fits a 390px frame with margins)
    static let paneMinW: CGFloat = 220
    static let paneMaxW: CGFloat = 276
    static let paneMinH: CGFloat = 300
    static let paneMaxH: CGFloat = 400
}

@MainActor
public final class PulsePiPStore: ObservableObject {
    /// One live pane. Web PipPane parity: pane key == conversation id; nx/ny
    /// are the normalized top-left across the draggable range; lastSeenAt
    /// drives unread badges; openedAt (creation/last-focus) is the eviction
    /// order. Web's `meta` display blob is not stored — native cards read
    /// the shared GRDB conversation cache (the same "shared caches, no extra
    /// fetching" rule the web pills follow).
    public struct PipPane: Codable, Equatable, Sendable {
        public let conversationId: String
        public var minimized: Bool
        public var nx: Double
        public var ny: Double
        public var lastSeenAt: Double
        public var openedAt: Double
    }

    private struct PersistedState: Codable {
        var panes: [PipPane]
        var isOpen: Bool
        var conversationId: String?
        var minimized: Bool
    }

    /// Max simultaneously live panes (1 expanded + rest stacked) — web PIP_MAX_PANES.
    public static let maxPanes = 3
    private static let storageKey = "pulse.pip.v2"

    @Published public private(set) var panes: [PipPane] = []
    @Published public private(set) var conversationId: String?
    @Published public private(set) var minimized = false

    /// ≥ 1 pane exists (window and/or stack) — web isOpen.
    public var isOpen: Bool { !panes.isEmpty }

    public init() {
        // Relaunch restore — web persist.merge() parity: sanitize panes, the
        // focused pane must exist AND be expanded, otherwise stack-only.
        guard
            let raw = UserDefaults.standard.string(forKey: Self.storageKey),
            let data = raw.data(using: .utf8),
            let saved = try? JSONDecoder().decode(PersistedState.self, from: data)
        else { return }
        let panes = Self.sanitize(saved.panes)
        let focused = saved.conversationId.flatMap { id in
            panes.first { $0.conversationId == id && !$0.minimized }
        }
        self.panes = panes
        self.conversationId = focused?.conversationId
        self.minimized = !panes.isEmpty && focused == nil
    }

    // ── legacy contract ops (web usePipChat) ─────────────────────

    /// open (or focus) the pane for a conversation — demotes the rest.
    public func open(_ conversationId: String) {
        let now = Self.nowMs()
        var next = panes.map { p in
            p.conversationId == conversationId
                ? p.expanded(openedAt: now)
                : p.minimizedCopy()
        }
        if !next.contains(where: { $0.conversationId == conversationId }) {
            // new pane: staggered default down the right edge (web parity)
            next.append(
                PipPane(
                    conversationId: conversationId,
                    minimized: false,
                    nx: 1,
                    ny: Self.clamp01(0.06 + 0.07 * Double(next.count)),
                    lastSeenAt: now,
                    openedAt: now,
                )
            )
        }
        // cap live panes — evict the OLDEST pane that is not the focused one
        while next.count > Self.maxPanes {
            guard
                let oldest = next.filter({ $0.conversationId != conversationId })
                    .min(by: { $0.openedAt <= $1.openedAt })
            else { break }
            next.removeAll { $0.conversationId == oldest.conversationId }
        }
        panes = next
        self.conversationId = conversationId
        minimized = false
        persist()
    }

    /// close the focused window (stacked panes stay).
    public func close() {
        guard let id = conversationId else { return }
        panes.removeAll { $0.conversationId == id }
        self.conversationId = nil
        minimized = !panes.isEmpty
        persist()
    }

    /// collapse the focused window into the stack.
    public func minimize() {
        guard let id = conversationId else { return }
        panes = panes.map { $0.conversationId == id ? $0.minimizedCopy() : $0 }
        self.conversationId = nil
        minimized = !panes.isEmpty
        persist()
    }

    /// expand the most recently demoted stacked pane.
    public func restore() {
        guard conversationId == nil else { return }
        guard let next = panes.filter(\.minimized).max(by: { $0.openedAt < $1.openedAt }) else { return }
        let now = Self.nowMs()
        panes = panes.map { p in
            p.conversationId == next.conversationId ? p.expanded(openedAt: now) : p.minimizedCopy()
        }
        conversationId = next.conversationId
        minimized = false
        persist()
    }

    // ── R28-a pane ops (web parity) ──────────────────────────────

    /// expand a specific stacked pane, demote the rest.
    public func focusPane(_ conversationId: String) {
        guard panes.contains(where: { $0.conversationId == conversationId }),
              self.conversationId != conversationId else { return }
        let now = Self.nowMs()
        panes = panes.map { p in
            p.conversationId == conversationId ? p.expanded(openedAt: now) : p.minimizedCopy()
        }
        self.conversationId = conversationId
        minimized = false
        persist()
    }

    /// close a specific pane (focused or stacked).
    public func closePane(_ conversationId: String) {
        panes.removeAll { $0.conversationId == conversationId }
        let wasFocused = self.conversationId == conversationId
        if wasFocused { self.conversationId = nil }
        minimized = !panes.isEmpty && (wasFocused ? true : minimized)
        persist()
    }

    /// persist a pane's normalized position (0..1) — web setPanePosition.
    public func setPanePosition(_ conversationId: String, nx: Double, ny: Double) {
        guard let idx = panes.firstIndex(where: { $0.conversationId == conversationId }) else { return }
        let sx = Self.clamp01(nx)
        let sy = Self.clamp01(ny)
        guard abs(panes[idx].nx - sx) >= 0.0005 || abs(panes[idx].ny - sy) >= 0.0005 else { return }
        panes[idx].nx = sx
        panes[idx].ny = sy
        persist()
    }

    /// mark the focused reading surface seen (≥1s guard, web markSeen).
    public func markSeen(_ conversationId: String, at: Double) {
        guard let idx = panes.firstIndex(where: { $0.conversationId == conversationId }) else { return }
        guard at - panes[idx].lastSeenAt >= 1000 else { return }
        panes[idx].lastSeenAt = at
        persist()
    }

    // ── internals ────────────────────────────────────────────────

    private func persist() {
        let state = PersistedState(panes: panes, isOpen: isOpen, conversationId: conversationId, minimized: minimized)
        guard let data = try? JSONEncoder().encode(state),
              let raw = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(raw, forKey: Self.storageKey)
    }

    /// Web sanitizePanes: dedupe by id, clamp positions, keep the newest
    /// maxPanes ordered oldest → newest.
    static func sanitize(_ raw: [PipPane]) -> [PipPane] {
        var seen = Set<String>()
        var out: [PipPane] = []
        for var p in raw where seen.insert(p.conversationId).inserted {
            p.nx = clamp01(p.nx)
            p.ny = clamp01(p.ny)
            out.append(p)
        }
        return Array(out.sorted { $0.openedAt < $1.openedAt }.suffix(maxPanes))
    }

    static func clamp01(_ v: Double) -> Double { v.isFinite ? min(1, max(0, v)) : 0 }

    private static func nowMs() -> Double { Date().timeIntervalSince1970 * 1000 }
}

private extension PulsePiPStore.PipPane {
    func expanded(openedAt: Double) -> PulsePiPStore.PipPane {
        PulsePiPStore.PipPane(
            conversationId: conversationId,
            minimized: false,
            nx: nx,
            ny: ny,
            lastSeenAt: lastSeenAt,
            openedAt: openedAt
        )
    }

    func minimizedCopy() -> PulsePiPStore.PipPane {
        PulsePiPStore.PipPane(
            conversationId: conversationId,
            minimized: true,
            nx: nx,
            ny: ny,
            lastSeenAt: lastSeenAt,
            openedAt: openedAt
        )
    }
}
