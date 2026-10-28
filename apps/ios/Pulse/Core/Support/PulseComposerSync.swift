import Foundation

struct PulseComposerFlight: Codable, Equatable, Sendable {
    let clientId: String
    let text: String
    let expectedRevision: String
    var localEditRevision: String? = nil
}
struct PulseComposerIntent: Codable, Equatable, Sendable {
    let owner: String
    let kind: String
    let targetId: String
    let conversationId: String
    let rootId: String?
    var desiredId: String
    var text: String
    var baseText: String
    var baseEditedAt: String?
    var baseRevision: String? = nil
    var localEditRevision: String? = nil
    var flight: PulseComposerFlight?
    var blocked: String?
}
struct PulseComposerLocalEdit: Codable, Sendable {
    var revision: String? = UUID().uuidString
    let text: String
    let baseText: String
    let baseEditedAt: String?
}
struct PulseComposerReceipt: Codable, Sendable {
    let composerVersion: Int
    let userId: String
    let kind: String
    let targetId: String
    let rootId: String?
    let conversationId: String
    let text: String
    let revision: String
    let editedAt: String?
    let clientId: String?
    let outcome: String?
    func hasSafeDraftBaseline(_ intent: PulseComposerIntent) -> Bool {
        intent.kind != "draft" || intent.baseRevision != nil || text.utf16.elementsEqual(intent.text.utf16) || revision.hasSuffix(":0")
    }
    func validate(_ intent: PulseComposerIntent, flight: PulseComposerFlight? = nil) throws {
        guard composerVersion == 1, userId == intent.owner, kind == intent.kind, targetId == intent.targetId,
              rootId == intent.rootId, conversationId == intent.conversationId, text.utf16.count <= 2000,
              !revision.isEmpty, revision.utf8.count <= 256,
              revision.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [45,46,58,95].contains($0) }),
              editedAt == nil || PulseFormat.date(editedAt!) != nil else { throw CocoaError(.coderInvalidValue) }
        if let flight {
            guard clientId == flight.clientId, ["applied","conflict","superseded"].contains(outcome ?? ""),
                  outcome != "applied" || text.utf16.elementsEqual(flight.text.utf16),
                  kind != "edit" || outcome != "applied" || editedAt != nil else { throw CocoaError(.coderInvalidValue) }
        }
    }
}

@MainActor
enum PulseComposerReplay {
    private static var busy = Set<String>()
    static func flush(store: PulseStore, client: PulseAPIClient, current: () -> Bool,
                      accepted: (PulseComposerIntent, PulseComposerReceipt) throws -> Void = { _,_ in }) async throws -> Int {
        guard busy.insert(store.storageIdentity).inserted else { return try store.pendingComposerCount(owner: client.userId) }
        defer { busy.remove(store.storageIdentity) }
        for row in try store.composerPage(owner: client.userId).prefix(25) {
            try Task.checkCancellation(); guard current() else { throw CancellationError() }
            var intent = row.intent
            do {
                if intent.flight == nil {
                    guard intent.text.utf16.count <= 2000, intent.kind != "edit" || !intent.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw CocoaError(.coderInvalidValue) }
                    let state = try await client.composerState(intent)
                    try state.validate(intent)
                    guard current() else { throw CancellationError() }
                    let sameTime = state.editedAt == intent.baseEditedAt || (PulseFormat.date(state.editedAt) != nil && PulseFormat.date(state.editedAt) == PulseFormat.date(intent.baseEditedAt))
                    guard state.hasSafeDraftBaseline(intent), (intent.baseRevision == nil || intent.baseRevision == state.revision),
                        state.text.utf16.elementsEqual(intent.text.utf16) ||
                        (state.text.utf16.elementsEqual(intent.baseText.utf16) && (intent.kind != "edit" || sameTime)) else {
                        try store.blockComposer(intent, notice: "Changed on another device. Your text is retained; review before replacing it.")
                        continue
                    }
                    guard let frozen = try store.freezeComposer(intent, revision: state.revision) else { continue }
                    intent = frozen
                }
                guard let flight = intent.flight else { continue }
                let receipt = try await client.writeComposer(intent, flight: flight)
                try receipt.validate(intent, flight: flight)
                try Task.checkCancellation(); guard current() else { throw CancellationError() }
                try store.acceptComposer(intent, receipt: receipt)
                if receipt.outcome == "applied" { try accepted(intent,receipt) }
            } catch is CancellationError { throw CancellationError() }
            catch {
                guard current() else { throw CancellationError() }
                if let failure = error as? PulseAPIClient.Failure {
                    if [400,403,404,410,422].contains(failure.status ?? 0) || failure.kind == .validation {
                        try store.blockComposer(intent, notice: failure.message ?? "This operation needs review. Your text is retained.")
                    } else { try store.deferComposer(intent) }
                    if failure.kind == .auth || failure.kind == .rateLimited { break }
                } else if error is CocoaError {
                    try store.blockComposer(intent, notice: "Composer state could not be verified. Your original text is retained.")
                } else { try store.deferComposer(intent) }
            }
        }
        return try store.pendingComposerCount(owner: client.userId)
    }
}

struct PulseComposerRecovery: Identifiable, Sendable {
    let id: String
    let version: String
    let kind: String
    let targetId: String
    let conversationId: String
    let text: String
    let notice: String
    var canRetry = false
    var canReplace = false
}
struct PulseComposerRecoveryPage {
    let items: [PulseComposerRecovery]
    let nextCursor: String?
}

struct PulseDetachedDraft: Codable {
    let conversationId: String
    let rootId: String?
    let text: String
}
