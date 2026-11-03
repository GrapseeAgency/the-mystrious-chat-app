import Foundation

/// Section 12 items 1/9 - durable offline intent journal for the integration
/// rails (automations + webhooks), mirroring PulseSection8Journal. An intent
/// is persisted BEFORE the network call and removed ONLY after a confirmed
/// server receipt, so a process loss replays the SAME clientId and the
/// server's stored operation converges instead of duplicating: a replayed
/// create returns the SAME rule/token, a replayed delete/rotate the SAME
/// verdict even though the row is gone.
@MainActor
final class PulseSection12Journal {
    static let shared = PulseSection12Journal()
    struct Intent: Codable, Sendable {
        let kind: String      // "automation-create" | "automation-delete" | "webhook-create" | "webhook-delete" | "webhook-rotate"
        let clientId: String
        let intentAtMs: Int64
        let targetId: String
        let payload: [String: String]
    }

    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    /// Section 14 item 9 - a failed journal write keeps every PREVIOUS row
    /// (nothing is discarded); the flag makes the fault observable.
    private(set) var lastWriteFailed = false

    private func key(_ owner: String) -> String { "section12-intent:v1|" + owner }

    /// Tolerant read: an unreadable payload reads as empty AND IS RETAINED.
    private func read(_ owner: String) -> [Intent] {
        guard let data = defaults.data(forKey: key(owner)) else { return [] }
        return (try? JSONDecoder().decode([Intent].self, from: data)) ?? []
    }

    @discardableResult
    private func write(_ owner: String, _ intents: [Intent]) -> Bool {
        guard let data = try? JSONEncoder().encode(intents) else {
            lastWriteFailed = true
            return false
        }
        defaults.set(data, forKey: key(owner))
        lastWriteFailed = false
        return true
    }

    func enqueue(owner: String, kind: String, targetId: String, payload: [String: String] = [:]) -> Intent {
        let intent = Intent(
            kind: kind,
            clientId: UUID().uuidString.replacingOccurrences(of: "-", with: ""),
            intentAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            targetId: targetId,
            payload: payload)
        var intents = read(owner)
        intents.append(intent)
        write(owner, intents)
        return intent
    }

    /// Confirmed receipts are the ONLY way a row leaves the journal.
    func confirm(owner: String, clientId: String) {
        write(owner, read(owner).filter { $0.clientId != clientId })
    }

    func pending(owner: String) -> [Intent] { read(owner) }

    // MARK: body builders - every replay carries the SAME clientId

    /// POST /api/conversations/{id}/automations - the create replays the
    /// ORIGINAL trigger/reply instructions with the SAME identity.
    func automationCreateBody(_ intent: Intent, userId: String) -> [String: Any] {
        [
            "userId": userId,
            "conversationId": intent.targetId,
            "trigger": intent.payload["trigger"] ?? "",
            "reply": intent.payload["reply"] ?? "",
            "clientId": intent.clientId,
        ]
    }

    /// DELETE /api/automations/{id} - the rule id rides the path; the verdict
    /// replay identity rides the JSON body next to the owner (the route
    /// pairs them).
    func automationDeleteBody(_ intent: Intent, userId: String) -> [String: Any] {
        ["userId": userId, "clientId": intent.clientId]
    }

    /// POST /api/webhooks - the create replays the SAME conversation + name,
    /// never minting a second capability for the same instructions.
    func webhookCreateBody(_ intent: Intent, requesterId: String) -> [String: Any] {
        [
            "conversationId": intent.targetId,
            "name": intent.payload["name"] ?? "",
            "requesterId": requesterId,
            "clientId": intent.clientId,
        ]
    }

    /// DELETE /api/webhooks/{token} - the requester rides the query, so the
    /// replay identity rides the query too (the route reads the body only
    /// when the query is bare).
    func webhookDeleteBody(_ intent: Intent, requesterId: String) -> [String: Any] {
        ["requesterId": requesterId, "clientId": intent.clientId]
    }

    /// PATCH /api/webhooks/{token} - the rotate replay carries the OLD token
    /// on the path (the ACK was lost before the fresh one could be learned);
    /// the body keeps the same identity.
    func webhookRotateBody(_ intent: Intent, requesterId: String) -> [String: Any] {
        ["requesterId": requesterId, "clientId": intent.clientId]
    }
}
