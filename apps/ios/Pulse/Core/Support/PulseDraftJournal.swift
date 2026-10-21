import Foundation

/// The main-actor front buffer makes a rapid reopen see the last keystroke even
/// before SQLite commits. The writer does finite storage work off the UI actor.
@MainActor
final class PulseDraftJournal {
    private struct Snapshot {
        let owner: UInt64
        let revision: UInt64
        let text: String?
        let storageRevision: UInt64
    }
    private static var snapshots: [String: Snapshot] = [:]

    private actor Writer {
        var newest: [String: (owner: UInt64, revision: UInt64)] = [:]

        func save(_ text: String, revision: UInt64, owner: UInt64, store: PulseStore, room: String, writeRevision: UInt64) throws {
            let key = store.storageIdentity + "\u{0}" + room
            if let previous = newest[key] {
                guard owner > previous.owner || (owner == previous.owner && revision >= previous.revision) else { return }
            }
            newest[key] = (owner, revision)
            try store.saveDraftSnapshot(conversationId: room, text: text, expectedRevision: writeRevision)
        }
    }
    private static let writer = Writer()
    private let store: PulseStore
    private let room: String
    private let conversationId: String
    private let parentId: String?
    private let key: String
    private let owner = DispatchTime.now().uptimeNanoseconds

    init(store: PulseStore, conversationId: String, parentId: String? = nil) {
        self.store = store
        self.conversationId = conversationId
        self.parentId = parentId
        let storageRoom = PulseThreadDraft.key(conversationId, parentId: parentId)
        self.room = storageRoom
        self.key = store.storageIdentity + "\u{0}" + storageRoom
        // Claim ownership synchronously; even a not-yet-started old disposal
        // task cannot overwrite a new room's composer after this point.
        let current = store.captureDraftConsumption(conversationId: conversationId, parentId: parentId).revision
        let pending = Self.snapshots[key].flatMap { $0.storageRevision == current ? $0.text : nil }
        Self.snapshots[key] = Snapshot(owner: owner, revision: 0, text: pending, storageRevision: current)
    }

    var isCurrentOwner: Bool { Self.snapshots[key]?.owner == owner }

    var snapshot: String? {
        let current = store.captureDraftConsumption(conversationId: conversationId, parentId: parentId).revision
        if let snapshot = Self.snapshots[key], snapshot.storageRevision == current, let text = snapshot.text { return text }
        return store.draftSnapshot(conversationId: room)
    }

    /// Call synchronously on each UI change, before creating any observer task.
    func submit(_ text: String, revision: UInt64) -> Task<Void, Error> {
        if let previous = Self.snapshots[key],
           previous.owner > owner || (previous.owner == owner && previous.revision > revision) {
            return Task<Void, Error> { }
        }
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : text
        let store = store, room = room, owner = owner, key = key
        let writeRevision = store.reserveDraftWrite(conversationId: room)
        Self.snapshots[key] = Snapshot(owner: owner, revision: revision, text: value, storageRevision: writeRevision)
        return Task {
            try await Self.writer.save(value, revision: revision, owner: owner, store: store, room: room, writeRevision: writeRevision)
            if let current = Self.snapshots[key], current.owner == owner, current.revision == revision {
                // The committed value can now be read from SQLite. Keep the
                // ordering fence, but do not retain every draft's body in RAM.
                Self.snapshots[key] = Snapshot(owner: owner, revision: revision, text: nil, storageRevision: writeRevision)
            }
        }
    }

    func prepareSend() throws -> PulseDraftConsumption {
        guard isCurrentOwner else { throw CocoaError(.userCancelled) }
        return store.captureDraftConsumption(conversationId: conversationId, parentId: parentId)
    }

    /// Called synchronously after the same transaction committed queue + clear.
    func didConsumeDraft() {
        guard let current = Self.snapshots[key], current.owner == owner else { return }
        Self.snapshots[key] = Snapshot(owner: owner, revision: current.revision &+ 1, text: "",
            storageRevision: store.captureDraftConsumption(conversationId: conversationId, parentId: parentId).revision)
    }

    func save(_ text: String, revision: UInt64) async throws {
        try await submit(text, revision: revision).value
    }
}
