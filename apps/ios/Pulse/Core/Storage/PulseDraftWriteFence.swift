import Foundation

/// Process-local ordering only. The actual draft/operation handoff is a SQLite
/// transaction; old tasks do not survive a process restart. Store handles for the
/// same database share this fence so a reopened view cannot revive older text.
final class PulseDraftWriteFence: @unchecked Sendable {
    private final class Registry: @unchecked Sendable {
        let lock = NSLock()
        var stores: [String: PulseDraftWriteFence] = [:]
    }
    private static let registry = Registry()
    static func shared(for identity: String) -> PulseDraftWriteFence {
        registry.lock.lock(); defer { registry.lock.unlock() }
        if let existing = registry.stores[identity] { return existing }
        let fence = PulseDraftWriteFence()
        registry.stores[identity] = fence
        return fence
    }

    private let lock = NSLock()
    private var revisions: [String: UInt64] = [:]

    func reserve(_ room: String) -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        revisions[room, default: 0] &+= 1
        return revisions[room, default: 0]
    }

    func current(_ room: String) -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        return revisions[room, default: 0]
    }

    @discardableResult
    func write(_ room: String, expected: UInt64?, action: () throws -> Void) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        if let expected, expected != revisions[room, default: 0] { return false }
        try action()
        if expected == nil { revisions[room, default: 0] &+= 1 }
        return true
    }

    /// Always persist the operation, but only consume the draft revision it owns.
    func enqueue(_ room: String, expected: UInt64, action: (Bool) throws -> Void) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        let consume = expected == revisions[room, default: 0]
        try action(consume)
        if consume { revisions[room, default: 0] &+= 1 }
        return consume
    }
}

struct PulseDraftConsumption {
    let storageIdentity: String
    let conversationId: String
    let revision: UInt64
    var parentId: String? = nil
    var storageKey: String { PulseThreadDraft.key(conversationId, parentId: parentId) }
}


/// Separate local namespace in the existing draft table. Lengths avoid delimiter collisions.
enum PulseThreadDraft {
    static func key(_ room: String, parentId: String?) -> String {
        guard let parentId else { return room }
        return "thread-draft:v1|\(room.utf16.count):\(room)|\(parentId.utf16.count):\(parentId)"
    }
    static func isThread(_ key: String) -> Bool { key.hasPrefix("thread-draft:v1|") }
}
