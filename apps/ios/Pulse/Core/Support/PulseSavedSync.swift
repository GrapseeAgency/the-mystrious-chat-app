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
    func validate(owner: String, id: String, intent: PulseSavedIntent? = nil) throws {
        guard savedReceiptVersion == 2, userId == owner, messageId == id,
              let revision, !revision.isEmpty, revision.count <= 128,
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
