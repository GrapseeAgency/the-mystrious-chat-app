import Foundation

/// Section 8 items 6/7/8/10 - durable offline intent journal (iOS mirror of
/// the Android Section8IntentJournal). An intent is persisted BEFORE the
/// network call and removed ONLY after a confirmed server receipt, so a
/// process loss replays the SAME clientId + original client clock and the
/// server's total order converges instead of duplicating.
@MainActor
final class PulseSection8Journal {
    static let shared = PulseSection8Journal()
    struct Intent: Codable, Sendable {
        let kind: String      // "block" | "unblock" | "report" | "folder-create" | "phrase-create" | "phrase-delete" | "profile-patch" (Section 13)
        let clientId: String
        let intentAtMs: Int64
        let targetId: String
        let payload: [String: String]
    }

    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private func key(_ owner: String) -> String { "pulse.section8.pending.v1." + owner }

    private func read(_ owner: String) -> [Intent] {
        guard let data = defaults.data(forKey: key(owner)) else { return [] }
        return (try? JSONDecoder().decode([Intent].self, from: data)) ?? []
    }

    private func write(_ owner: String, _ intents: [Intent]) {
        guard let data = try? JSONEncoder().encode(intents) else { return }
        defaults.set(data, forKey: key(owner))
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

    /// The exact intent body the API calls replay with.
    func blockBody(_ intent: Intent) -> [String: Any] {
        var body: [String: Any] = [
            "clientId": intent.clientId,
            "intentAt": ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(intent.intentAtMs) / 1000)),
        ]
        if !intent.targetId.isEmpty { body["userId"] = intent.targetId }
        return body
    }

    // MARK: Section 13 item 9 - profile patches

    /// PATCH /api/users/{id} replay body: the ORIGINAL profile field
    /// instructions + the SAME durable clientId (a replayed write returns the
    /// stored result with `replayed: true`; a different instruction set under
    /// the same identity is refused 409 profile_operation_mismatch).
    func profilePatchBody(_ intent: Intent) -> [String: Any] {
        var body: [String: Any] = [:]
        for (key, value) in intent.payload where PulseAPIClient.profilePatchFields.contains(key) {
            body[key] = value
        }
        body["clientId"] = intent.clientId
        return body
    }
}
