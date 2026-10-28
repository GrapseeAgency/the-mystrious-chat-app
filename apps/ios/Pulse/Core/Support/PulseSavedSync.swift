import Foundation

struct PulseSavedIntent: Codable, Sendable {
    let owner: String
    let messageId: String
    let conversationId: String
    let saved: Bool
    let clientId: String
    let expectedRevision: String?
    let predecessorClientId: String?
    var prefix: String { "saved-intent:v1|\(owner)|\(messageId)|" }
    var key: String { self.prefix + clientId }
}
struct PulseSavedStates: Codable {
    let savedStateVersion: Int
    let userId: String
    let states: [WireSavedStateReceipt]
}
struct PulseSavedOfflineCursor: Codable {
    let owner: String
    let at: String
    let id: String
}
struct PulseSavedCachedPage {
    let items: [WireSavedItem]
    let nextCursor: String?
}
extension WireSavedStateReceipt {
    static func validRevision(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard (1...40).contains(bytes.count) else { return false }
        return bytes == [48] || ((49...57).contains(bytes[0]) && bytes.allSatisfy { (48...57).contains($0) })
    }
    static func compareRevisions(_ left: String, _ right: String) -> Int {
        if left.count != right.count { return left.count < right.count ? -1 : 1 }
        return left == right ? 0 : left.lexicographicallyPrecedes(right) ? -1 : 1
    }
    func validate(owner: String, id: String, intent: PulseSavedIntent? = nil) throws {
        guard savedReceiptVersion == 2, userId == owner, messageId == id,
              let revision, Self.validRevision(revision),
              let visible, !visible || saved,
              saved ? savedAt.flatMap(PulseFormat.date) != nil : savedAt == nil else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Saved state was not confirmed.")
        }
        if let intent {
            guard clientId == intent.clientId, let outcome,
                  ["applied", "superseded", "conflict", "rejected"].contains(outcome),
                  outcome != "applied" || saved == intent.saved else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Saved action was not confirmed.")
            }
        }
    }
}

@MainActor
enum PulseSavedReplay {
    /// One bounded pass. Unavailable rows do not starve unrelated identities;
    /// descendants of an unresolved row stay ordered behind it.
    static func flush(store: PulseStore, owner: String, isCurrent: () -> Bool,
                      send: (PulseSavedIntent) async throws -> WireSavedStateReceipt) async -> Bool {
        do {
            var blocked = Set<String>()
            for intent in try store.pendingSaved(owner: owner) {
                guard !Task.isCancelled, isCurrent(), intent.owner == owner else { return false }
                if blocked.contains(intent.messageId) { continue }
                let snapshot = store.beginSavedSnapshot()
                do {
                    let receipt = try await send(intent)
                    try Task.checkCancellation()
                    guard isCurrent() else { return false }
                    try store.acceptSavedState(receipt, intent: intent, snapshot: snapshot)
                } catch {
                    guard !Task.isCancelled, isCurrent() else { return false }
                    blocked.insert(intent.messageId)
                    try store.deferSavedIntent(intent)
                    if !PulseReadJournalReplay.canContinue(after: error) { return false }
                }
            }
            return try isCurrent() && store.pendingSaved(owner: owner).isEmpty
        } catch { return false }
    }
}
