import Foundation

// ─────────────────────────────────────────────────────────────
// R1-W2G D44 — the whiteboard PENDING-stroke draft store. A stroke is
// persisted the moment it is drawn (BEFORE any sync attempt), namespaced
// per conversation under "whiteboard.draft:<conversationId>" in
// UserDefaults and JSON-encoded as [WireWhiteboardStrokePost] — the stroke
// model encoding itself, not a bespoke shape. A crash/kill mid-draw
// therefore loses nothing: the sheet restores the draft on open, re-syncs
// it, and a stroke leaves the store only when the server confirms the POST
// (or honestly on failure — the web truth at whiteboard-sheet.tsx:527-529
// pending semantics and :497-500 honest removal, with a durable native
// store the web does not have).
// ─────────────────────────────────────────────────────────────
public enum PulseWhiteboardDraft {
    /// Namespaced per-conversation key (house "<feature>.<kind>:<id>" style,
    /// cf. "hub:tasks:<viewerId>" / "voice.captions").
    public static func key(_ conversationId: String) -> String {
        "whiteboard.draft:\(conversationId)"
    }

    /// The pending strokes for a conversation, oldest first. Anything
    /// unreadable degrades to an empty draft (never a crash).
    public static func strokes(conversationId: String, defaults: UserDefaults = .standard) -> [WireWhiteboardStrokePost] {
        guard let data = defaults.data(forKey: key(conversationId)) else { return [] }
        return (try? JSONDecoder().decode([WireWhiteboardStrokePost].self, from: data)) ?? []
    }

    /// Draw-time write-through — persists the stroke immediately, before
    /// the sheet even tries to sync it.
    public static func append(_ stroke: WireWhiteboardStrokePost, conversationId: String, defaults: UserDefaults = .standard) {
        var pending = strokes(conversationId: conversationId, defaults: defaults)
        pending.append(stroke)
        store(pending, conversationId: conversationId, defaults: defaults)
    }

    /// Sync-verdict bookkeeping — drops the first `count` entries (the
    /// batch that was just POSTed) from the front of the queue.
    public static func removeFirst(conversationId: String, count: Int, defaults: UserDefaults = .standard) {
        guard count > 0 else { return }
        var pending = strokes(conversationId: conversationId, defaults: defaults)
        guard !pending.isEmpty else { return }
        pending.removeFirst(min(count, pending.count))
        store(pending, conversationId: conversationId, defaults: defaults)
    }

    /// Restore-time swap — rewrites the whole draft (the dedupe pass).
    public static func replaceAll(conversationId: String, strokes: [WireWhiteboardStrokePost], defaults: UserDefaults = .standard) {
        store(strokes, conversationId: conversationId, defaults: defaults)
    }

    /// Server-side board reset — the pending queue is gone with the board.
    public static func clear(conversationId: String, defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key(conversationId))
    }

    /// Pure restore-time dedupe — a draft stroke that the full snapshot
    /// ALREADY carries (identical color, width and points) reached the
    /// server before the crash; the draft copy is redundant, not lost.
    /// Feed order preserved.
    public static func droppingSynced(_ draft: [WireWhiteboardStrokePost], snapshot: [WireWhiteboardStroke]) -> [WireWhiteboardStrokePost] {
        guard !draft.isEmpty else { return [] }
        return draft.filter { stroke in
            !snapshot.contains { remote in
                remote.color == stroke.color
                    && remote.width == stroke.width
                    && remote.points == stroke.points
            }
        }
    }

    private static func store(_ pending: [WireWhiteboardStrokePost], conversationId: String, defaults: UserDefaults) {
        guard let data = try? JSONEncoder().encode(pending) else { return }
        defaults.set(data, forKey: key(conversationId))
    }
}
