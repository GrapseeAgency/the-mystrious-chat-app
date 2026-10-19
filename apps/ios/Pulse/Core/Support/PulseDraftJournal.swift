import Foundation

/// Captures one store, never a mutable session. All journals for the same file
/// share revision ordering, including an old room disappearing after a new room opens.
final class PulseDraftJournal: Sendable {
    private actor Writer {
        var newest: [String: (owner: UInt64, revision: UInt64)] = [:]

        func save(_ text: String, revision: UInt64, owner: UInt64, store: PulseStore, room: String) throws {
            let key = store.storageIdentity + "\u{0}" + room
            if let previous = newest[key] {
                guard owner > previous.owner || (owner == previous.owner && revision >= previous.revision) else { return }
            }
            newest[key] = (owner, revision)
            try store.saveDraftSnapshot(conversationId: room,
                text: text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : text)
        }
    }
    private static let writer = Writer()
    private let store: PulseStore
    private let room: String
    private let owner = DispatchTime.now().uptimeNanoseconds

    init(store: PulseStore, conversationId: String) {
        self.store = store
        self.room = conversationId
    }

    func save(_ text: String, revision: UInt64) async throws {
        try await Self.writer.save(text, revision: revision, owner: owner, store: store, room: room)
    }
}
