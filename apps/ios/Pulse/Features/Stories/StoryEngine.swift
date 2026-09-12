import SwiftUI

// MARK: - Story palette (web AVATAR_GRADIENTS parity)

/// The 8 story gradient stages — Tailwind palette values matching the web
/// (from-*-400 to-*-600). Unknown keys degrade to emerald (server default).
enum StoryPalette {
    static let keys = ["emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan"]
    static let defaultKey = "emerald"

    /// from → to color pair per key (hex values are the Tailwind 400/600 stops).
    private static let stages: [String: (Color, Color)] = [
        "emerald": (Color(red: 0x34 / 255, green: 0xD3 / 255, blue: 0x99 / 255), Color(red: 0x05 / 255, green: 0x96 / 255, blue: 0x69 / 255)),
        "rose": (Color(red: 0xFB / 255, green: 0x71 / 255, blue: 0x85 / 255), Color(red: 0xE1 / 255, green: 0x1D / 255, blue: 0x48 / 255)),
        "amber": (Color(red: 0xFB / 255, green: 0xBF / 255, blue: 0x24 / 255), Color(red: 0xD9 / 255, green: 0x77 / 255, blue: 0x06 / 255)),
        "violet": (Color(red: 0xA7 / 255, green: 0x8B / 255, blue: 0xFA / 255), Color(red: 0x7C / 255, green: 0x3A / 255, blue: 0xED / 255)),
        "teal": (Color(red: 0x2D / 255, green: 0xD4 / 255, blue: 0xBF / 255), Color(red: 0x0D / 255, green: 0x94 / 255, blue: 0x88 / 255)),
        "orange": (Color(red: 0xFB / 255, green: 0x92 / 255, blue: 0x3C / 255), Color(red: 0xEA / 255, green: 0x58 / 255, blue: 0x0C / 255)),
        "pink": (Color(red: 0xF4 / 255, green: 0x72 / 255, blue: 0xB6 / 255), Color(red: 0xDB / 255, green: 0x27 / 255, blue: 0x77 / 255)),
        "cyan": (Color(red: 0x22 / 255, green: 0xD3 / 255, blue: 0xEE / 255), Color(red: 0x08 / 255, green: 0x91 / 255, blue: 0xB2 / 255)),
    ]

    static func pair(for key: String?) -> (Color, Color) {
        stages[key ?? ""] ?? stages[defaultKey]!
    }

    static func gradient(for key: String?) -> LinearGradient {
        let (from, to) = pair(for: key)
        return LinearGradient(colors: [from, to], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    static func isValid(_ key: String) -> Bool { keys.contains(key) }
}

// MARK: - Relative time (web storyRelativeTime parity)

/// "now" / "Xm" / "Xh" / "Xd" — the day branch is unreachable for live
/// stories (24h TTL) but kept for the owner viewers list.
func storyRelativeTime(_ epochMs: Int64, now: Int64) -> String {
    guard epochMs > 0 else { return "" }
    let mins = max(0, (now - epochMs) / 60_000)
    if mins < 1 { return "now" }
    if mins < 60 { return "\(mins)m" }
    if mins < 60 * 24 { return "\(mins / 60)h" }
    return "\(mins / (60 * 24))d"
}

/// ISO-8601 UTC wire stamp → epoch ms (0 when unparseable).
func storyEpochMs(_ iso: String?) -> Int64 {
    guard let iso else { return 0 }
    let formats = ["yyyy-MM-dd'T'HH:mm:ss.SSSXXXX", "yyyy-MM-dd'T'HH:mm:ssXXXX"]
    for f in formats {
        let df = DateFormatter()
        df.dateFormat = f
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone(identifier: "UTC")
        if let date = df.date(from: iso) {
            return Int64(date.timeIntervalSince1970 * 1000)
        }
    }
    return 0
}

// MARK: - Composer state (web composer parity, pure)

/// Pure composer state — Text/Photo pill toggle, hard 280-char caption cap,
/// the 8 gradient keys, upload/post flags and the canPost gate that mirrors
/// POST /api/stories server validation exactly.
struct StoryComposerState: Equatable {
    enum Mode: Equatable { case text, photo }

    static let captionMax = 280

    var mode: Mode = .text
    var caption: String = ""
    var background: String = StoryPalette.defaultKey
    /// Uploaded gateway filename (from POST /api/uploads) — non-nil ⇒ photo content.
    var imagePath: String?
    var uploading = false
    var posting = false
    /// User-visible failure copy.
    var error: String?

    /// The exact client-side mirror of the server validation.
    var canPost: Bool {
        guard !uploading, !posting else { return false }
        switch mode {
        case .text: return !caption.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .photo: return !(imagePath ?? "").isEmpty
        }
    }

    func withCaption(_ raw: String) -> StoryComposerState {
        var next = self
        next.caption = String(raw.prefix(Self.captionMax))
        next.error = nil
        return next
    }

    func withMode(_ mode: Mode) -> StoryComposerState {
        var next = self
        next.mode = mode
        next.error = nil
        return next
    }

    /// Swatch select — TEXT mode only (ignored in photo mode, wire rule).
    func withBackground(_ key: String) -> StoryComposerState {
        guard mode == .text, StoryPalette.isValid(key) else { return self }
        var next = self
        next.background = key
        next.error = nil
        return next
    }

    /// An uploaded photo flips the composer into photo mode; nil clears it.
    func withImage(_ path: String?) -> StoryComposerState {
        var next = self
        next.imagePath = path
        next.mode = path == nil ? .text : .photo
        next.error = nil
        return next
    }

    func withUploading(_ flag: Bool) -> StoryComposerState { mutating { $0.uploading = flag } }
    func withPosting(_ flag: Bool) -> StoryComposerState { mutating { $0.posting = flag } }
    func withError(_ message: String) -> StoryComposerState { mutating { $0.error = message } }

    private func mutating(_ body: (inout StoryComposerState) -> Void) -> StoryComposerState {
        var next = self
        body(&next)
        return next
    }
}

// MARK: - Viewer state machine (web stories-sheet.tsx parity, pure)

/// Pure, testable state machine behind the full-screen story viewer.
/// Web-truth semantics preserved as behavior:
///  - 5000ms per story; pause RESUMES from elapsed (never restarts).
///  - advance crosses groups; closes after the last story; prev no-ops at first.
///  - D2 (web defect fixed): expired stories are dropped BEFORE flattening.
///  - D3 (web defect fixed): a vanished displayed story auto-advances to the
///    nearest survivor, or closes when nothing survives.
///  - D6 (web defect fixed): optimistic seen-marking with exactly one retry.
final class StoryViewerMachine: ObservableObject {
    struct FlatStory: Identifiable, Equatable {
        let userId: String
        let userName: String
        let userColor: String?
        let mine: Bool
        let groupIndex: Int
        let storyIndexInGroup: Int
        let story: WireStoryItem

        var id: String { story.id ?? "" }
        var isImage: Bool { story.imagePath != nil }
    }

    struct PendingMark: Equatable {
        let storyId: String
        var attempt: Int = 0
    }

    struct State: Equatable {
        var flat: [FlatStory] = []
        var index = 0
        var elapsedMs: Int64 = 0
        var paused = false
        /// drag-down dismiss OR finished the last story — the view closes on this.
        var dismissed = false
        var pendingMark: PendingMark?
        var revision = 0

        var current: FlatStory? { flat.indices.contains(index) ? flat[index] : nil }
        /// 0...1 of the CURRENT story's progress bar.
        var progress: Double { min(1, max(0, Double(elapsedMs) / Double(StoryViewerMachine.durationMs))) }
    }

    static let durationMs: Int64 = 5_000
    /// press-and-hold ≥240ms = pause (web hold timer).
    static let holdThresholdMs: Int64 = 240
    /// vertical drag-down dismiss thresholds (web touch values).
    static let dismissDistancePx: CGFloat = 110
    static let dismissVelocityPxPerS: CGFloat = 550

    @Published private(set) var state = State()

    private let startUserId: String?
    private var started = false
    /// Timestamp of the last tick while unpaused — nil = clock not armed.
    private var lastTickAt: Int64?

    init(startUserId: String? = nil) {
        self.startUserId = startUserId
    }

    // ── inputs ──────────────────────────────────────────────

    func tick(nowMs: Int64) {
        var next = bump()
        if next.paused || next.dismissed || next.current == nil { return }
        guard let last = lastTickAt else {
            lastTickAt = nowMs // arm the clock; the first frame counts 0
            return
        }
        lastTickAt = nowMs
        let elapsed = next.elapsedMs + max(0, nowMs - last)
        if elapsed >= Self.durationMs {
            next.elapsedMs = elapsed
            apply(go(to: next.index + 1, from: next))
        } else {
            next.elapsedMs = elapsed
            state = next
        }
    }

    func tapNext() {
        let next = bump()
        apply(go(to: next.index + 1, from: next))
    }

    func tapPrev() {
        let next = bump()
        guard next.index > 0 else { return } // no-op at the very first story
        apply(go(to: next.index - 1, from: next))
    }

    func holdStart(nowMs: Int64) { var next = bump(); next.paused = true; lastTickAt = nowMs; state = next }
    func holdEnd(nowMs: Int64) { var next = bump(); next.paused = false; lastTickAt = nowMs; state = next }

    func dragDismiss() { var next = bump(); next.dismissed = true; state = next }

    /// Feed update — start-position pick, D2 expiry re-filter and D3 vanish
    /// reconciliation all flow through this one door.
    func groupsUpdated(_ groups: [WireStoryGroup], nowMs: Int64) {
        var next = bump()
        // D2: drop expired BEFORE flattening (offline arithmetic, no network).
        var flat: [FlatStory] = []
        for (gi, g) in groups.enumerated() {
            guard let user = g.user else { continue }
            let stories = (g.stories ?? []).filter { item in
                let exp = storyEpochMs(item.expiresAt)
                return exp > nowMs
            }
            for (si, s) in stories.enumerated() {
                flat.append(FlatStory(
                    userId: user.id ?? "",
                    userName: user.name ?? "",
                    userColor: user.color,
                    mine: g.mine ?? false,
                    groupIndex: gi,
                    storyIndexInGroup: si,
                    story: s,
                ))
            }
        }

        if !started {
            if flat.isEmpty { next.dismissed = true; state = next; return }
            started = true
            let target = startUserId.flatMap { id in flat.firstIndex { $0.userId == id } } ?? -1
            lastTickAt = nil
            next.flat = flat
            next.index = target >= 0 ? target : 0
            next.elapsedMs = 0
            next.pendingMark = pendingMark(for: next)
            state = next
            return
        }

        let currentId = next.current?.id
        let found = currentId.flatMap { id in flat.firstIndex { $0.id == id } } ?? -1
        if found < 0 {
            // D3: nearest survivor (position clamped), or close when empty.
            if flat.isEmpty {
                next.flat = flat
                next.dismissed = true
            } else {
                lastTickAt = nil
                next.flat = flat
                next.index = min(next.index, flat.count - 1)
                next.elapsedMs = 0
                next.pendingMark = pendingMark(for: next)
            }
        } else {
            // Same story still live — keep position AND elapsed (no restart).
            let hadMark = next.pendingMark?.storyId == currentId
            next.flat = flat
            next.index = found
            if hadMark, next.current?.story.viewedByMe == true {
                next.pendingMark = nil
            }
        }
        state = next
    }

    /// D6: a successful view POST settles the optimistic mark.
    func viewMarkOk(storyId: String, viewCount: Int) {
        var next = bump()
        next.flat = next.flat.map { item in
            guard item.id == storyId else { return item }
            var s = item.story
            s = WireStoryItemCopy(s, viewedByMe: true, viewCount: viewCount)
            return FlatStory(userId: item.userId, userName: item.userName, userColor: item.userColor,
                             mine: item.mine, groupIndex: item.groupIndex,
                             storyIndexInGroup: item.storyIndexInGroup, story: s)
        }
        next.pendingMark = next.pendingMark?.storyId == storyId ? nil : next.pendingMark
        state = next
    }

    /// D6: ONE retry — the first failure re-queues, the second gives up.
    func viewMarkFailed(storyId: String) {
        var next = bump()
        if let mark = next.pendingMark, mark.storyId == storyId {
            next.pendingMark = mark.attempt == 0 ? PendingMark(storyId: storyId, attempt: 1) : nil
        }
        state = next
    }

    // ── internals ───────────────────────────────────────────

    private func bump() -> State {
        var next = state
        next.revision += 1
        return next
    }

    private func apply(_ next: State) { state = next }

    private func go(to target: Int, from next: State) -> State {
        var out = next
        if out.dismissed { return out }
        if target >= out.flat.count { out.dismissed = true; return out }
        guard target >= 0 else { return out }
        lastTickAt = nil
        out.index = target
        out.elapsedMs = 0
        out.pendingMark = pendingMark(for: out)
        return out
    }

    private func pendingMark(for state: State) -> PendingMark? {
        guard let cur = state.current, !cur.mine, cur.story.viewedByMe != true, let id = cur.story.id, !id.isEmpty else {
            return nil
        }
        return PendingMark(storyId: id, attempt: 0)
    }
}

/// Wire groups are structs with let fields — build the mutated copy.
func WireStoryGroupCopy(
    _ group: WireStoryGroup,
    allSeen: Bool?? = nil,
    stories: [WireStoryItem]? = nil,
) -> WireStoryGroup {
    WireStoryGroup(
        user: group.user,
        mine: group.mine,
        allSeen: allSeen ?? group.allSeen,
        stories: stories ?? group.stories,
    )
}

/// Wire items are structs with let fields — build the seen/count-updated copy.
func WireStoryItemCopy(_ item: WireStoryItem, viewedByMe: Bool, viewCount: Int) -> WireStoryItem {
    WireStoryItem(
        id: item.id,
        kind: item.kind,
        imagePath: item.imagePath,
        caption: item.caption,
        background: item.background,
        createdAt: item.createdAt,
        expiresAt: item.expiresAt,
        viewCount: viewCount,
        viewedByMe: viewedByMe,
    )
}
