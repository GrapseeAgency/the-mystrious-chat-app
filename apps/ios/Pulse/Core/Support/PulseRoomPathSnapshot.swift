import Foundation

/// Section 14 item 1 - the chats back-stack persisted as durable IDs, never
/// model objects. A process-restored composition rebuilds the path by
/// resolving every stored room ID against the CURRENT summaries (fresh
/// models, current membership), drops IDs that no longer resolve, and never
/// resurrects a departed conversation.
struct RoomPathEntry: Codable, Equatable {
    let roomId: String
    var jumpMessageId: String?
}

enum RoomPathSnapshot {
    /// Owner-keyed: a snapshot belongs to the account that wrote it and is
    /// never restored under a different one.
    static func key(owner: String) -> String { "pulse.roompath.v1.<\(owner)>" }

    static func encode(_ entries: [RoomPathEntry]) -> Data? {
        guard let data = try? JSONEncoder().encode(entries) else { return nil }
        return data
    }

    /// Tolerant read: an unreadable snapshot reads as empty and is RETAINED
    /// (never deleted by a read), so recovery stays possible.
    static func decode(_ data: Data) -> [RoomPathEntry] {
        (try? JSONDecoder().decode([RoomPathEntry].self, from: data)) ?? []
    }

    static func write(_ entries: [RoomPathEntry], owner: String, defaults: UserDefaults) {
        let key = key(owner: owner)
        guard let data = encode(entries) else { return }
        defaults.set(data, forKey: key)
    }

    static func read(owner: String, defaults: UserDefaults) -> [RoomPathEntry] {
        let key = key(owner: owner)
        guard let raw = defaults.data(forKey: key) else { return [] }
        return decode(raw)
    }

    /// The restore contract (pure): only the SAME owner's snapshot may
    /// rebuild, and only for room IDs that resolve in the CURRENT summary
    /// set. Unresolved IDs are dropped (no stale models, no dead rooms).
    static func restore(saved: [RoomPathEntry], snapshotOwner: String?, currentOwner: String?, loadedRoomIds: Set<String>) -> [RoomPathEntry] {
        guard let snapshotOwner, snapshotOwner == currentOwner else { return [] }
        return saved.filter { loadedRoomIds.contains($0.roomId) }
    }
}
