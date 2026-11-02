import Foundation

public struct PulseSettingsTicket: Sendable {
    let owner: String
    let patch: WirePulsePrefs
    let fields: [String: UUID]
    /// Section 13 items 1-3 - the durable replay identity (128-bit UUID
    /// without dashes) minted at stage time. Retries and post-relaunch
    /// replays carry the SAME clientId so the server returns the STORED
    /// result (`replayed: true`) instead of writing a second operation.
    let clientId: String?
    /// Section 13 - when the USER made the change (patch-level ISO stamp
    /// the server orders last-write-wins comparisons with).
    let intentAt: String?
    /// Section 13 - per-field stage moments (field → ISO stamp) so the
    /// server can order FIELD-level intent across devices.
    let fieldTimes: [String: String]

    init(owner: String, patch: WirePulsePrefs, fields: [String: UUID],
         clientId: String? = nil, intentAt: String? = nil, fieldTimes: [String: String] = [:]) {
        self.owner = owner
        self.patch = patch
        self.fields = fields
        self.clientId = clientId
        self.intentAt = intentAt
        self.fieldTimes = fieldTimes
    }
}

extension WirePulsePrefs {
    var settingsKeys: Set<String> { Set(asPatchBody().keys) }
    func selectingSettings(_ keys: Set<String>) -> WirePulsePrefs {
        WirePulsePrefs(
            bubbleRadius: keys.contains("bubbleRadius") ? bubbleRadius : nil,
            density: keys.contains("density") ? density : nil,
            wallpaper: keys.contains("wallpaper") ? wallpaper : nil,
            notifPreviews: keys.contains("notifPreviews") ? notifPreviews : nil,
            notifSound: keys.contains("notifSound") ? notifSound : nil,
            notifVibrate: keys.contains("notifVibrate") ? notifVibrate : nil,
            lastSeenVisible: keys.contains("lastSeenVisible") ? lastSeenVisible : nil,
            readReceipts: keys.contains("readReceipts") ? readReceipts : nil,
            typingVisible: keys.contains("typingVisible") ? typingVisible : nil,
            reducedMotion: keys.contains("reducedMotion") ? reducedMotion : nil,
            convThemes: keys.contains("chat.convThemes") ? convThemes : nil)
    }
}

/// Main-actor journal of unconfirmed field intent, scoped by account AND gateway.
/// A receipt removes only its own field token, including across an ABA toggle.
///
/// Section 13 items 1-3 - every staged field also persists its durable replay
/// identity (patch-level clientId) and its stage moment. The stored entries
/// keep decoding WITHOUT those keys (tolerant optionals) so journal blobs
/// written by older builds stay readable.
@MainActor
final class PulseSettingsJournal {
    /// Section 13 - `clientId` + `stagedAt` are optional: old journal blobs
    /// (pre-durability) decode with them nil and keep their legacy semantics.
    private struct Entry: Codable {
        let id: UUID
        let patch: WirePulsePrefs
        let clientId: String?
        let stagedAt: Date?
    }
    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }
    private func key(_ owner: String) -> String { "pulse.settings.pending.v1." + owner }

    /// Section 13 - ISO8601 stamps the server compares intents with (the
    /// same wire shape the Section 8 block watermark uses).
    private static let iso: ISO8601DateFormatter = ISO8601DateFormatter()
    static func timestamp(_ date: Date) -> String { iso.string(from: date) }

    private func read(_ owner: String) throws -> [String: Entry] {
        guard let raw = defaults.object(forKey: key(owner)) else { return [:] }
        guard let data = raw as? Data else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Unreadable pending settings; nothing was discarded.")
        }
        return try JSONDecoder().decode([String: Entry].self, from: data)
    }
    private func write(_ pending: [String: Entry], owner: String) throws {
        if pending.isEmpty { defaults.removeObject(forKey: key(owner)) }
        else { defaults.set(try JSONEncoder().encode(pending), forKey: key(owner)) }
    }
    func hasPending(owner: String) throws -> Bool { try !read(owner).isEmpty }
    func stage(_ patch: WirePulsePrefs, owner: String) throws -> PulseSettingsTicket {
        var pending = try read(owner)
        var fields: [String: UUID] = [:]
        var fieldTimes: [String: String] = [:]
        // Section 13 - ONE durable identity per staged patch; every field
        // entry carries it so a post-crash replay rebuilds the same ticket.
        let clientId = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let now = Date()
        for field in patch.settingsKeys {
            let id = UUID(); fields[field] = id
            fieldTimes[field] = Self.timestamp(now)
            pending[field] = Entry(id: id, patch: patch.selectingSettings([field]), clientId: clientId, stagedAt: now)
        }
        try write(pending, owner: owner)
        return PulseSettingsTicket(owner: owner, patch: patch, fields: fields,
            clientId: clientId, intentAt: Self.timestamp(now), fieldTimes: fieldTimes)
    }
    func current(_ ticket: PulseSettingsTicket) throws -> WirePulsePrefs {
        let pending = try read(ticket.owner)
        return ticket.patch.selectingSettings(Set(ticket.fields.compactMap { key, id in pending[key]?.id == id ? key : nil }))
    }
    /// Section 13 - the reconstructed retry ticket. The durable identity
    /// rides the stored entries: when every pending entry agrees on ONE
    /// clientId (the normal single-burst case) the replay reuses it so the
    /// server converges on the stored operation. Heterogeneous pending
    /// entries (mixed stages) must NOT reuse either identity - the merged
    /// instruction set is not the operation either clientId stored, and
    /// reusing one would answer 409 settings_operation_mismatch forever -
    /// so the ticket goes out identity-less like the legacy funnel.
    func pendingTicket(owner: String) throws -> PulseSettingsTicket {
        let pending = try read(owner)
        var object: [String: Any] = [:]
        for (field, entry) in pending {
            guard let value = entry.patch.asPatchBody()[field] else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Unreadable pending settings; nothing was discarded.")
            }
            object[field] = value
        }
        let patch = try JSONDecoder().decode(WirePulsePrefs.self, from: JSONSerialization.data(withJSONObject: object))
        let identities = Set(pending.values.compactMap(\.clientId))
        var fieldTimes: [String: String] = [:]
        for (field, entry) in pending { if let stagedAt = entry.stagedAt { fieldTimes[field] = Self.timestamp(stagedAt) } }
        let intentAt = pending.values.compactMap(\.stagedAt).min().map(Self.timestamp)
        return PulseSettingsTicket(owner: owner, patch: patch, fields: pending.mapValues(\.id),
            clientId: identities.count == 1 ? identities.first : nil,
            intentAt: intentAt, fieldTimes: fieldTimes)
    }
    func acknowledge(_ ticket: PulseSettingsTicket, sent: WirePulsePrefs, server: WirePulsePrefs,
                     superseded: Set<String> = [], apply: (WirePulsePrefs) -> Void) throws {
        var pending = try read(ticket.owner)
        // Section 13 item 1 - a field named in `superseded` LOST the
        // last-write-wins race against another device's newer intent: the
        // server did not apply it and `server` (the response preferences)
        // carries the winning value. ADOPT server truth for exactly those
        // still-owned fields and retire the local pending token - the lost
        // intent must never resurface on the next retry or restore.
        let lost = Set(sent.settingsKeys).intersection(superseded)
            .filter { pending[$0]?.id == ticket.fields[$0] }
        apply(server.selectingSettings(lost))
        for field in lost { pending.removeValue(forKey: field) }
        guard sent.settingsKeys.subtracting(superseded).isSubset(of: server.settingsKeys) else {
            throw PulseAPIClient.Failure(kind: .server, message: "The server did not confirm the requested settings fields.")
        }
        let accepted = Set(sent.settingsKeys.filter { !superseded.contains($0) && pending[$0]?.id == ticket.fields[$0] })
        // Apply confirmed fields before retiring intent. An unrelated full reply
        // cannot rewrite privacy/appearance fields this request never touched.
        apply(server.selectingSettings(accepted))
        for field in accepted { pending.removeValue(forKey: field) }
        try write(pending, owner: ticket.owner)
    }
    func applySnapshot(_ server: WirePulsePrefs, owner: String, apply: (WirePulsePrefs) -> Void) throws {
        let pending = try pendingTicket(owner: owner)
        apply(server.selectingSettings(server.settingsKeys.subtracting(pending.fields.keys)))
        apply(pending.patch)
    }
}

/// FIFO HTTP operations. Local preference staging happens synchronously BEFORE
/// enqueue, so a slow request never prevents the next privacy choice applying.
@MainActor
final class PulseSettingsWriteQueue {
    private var tail: Task<Void, Never>?
    private var tasks: [UUID: Task<Void, Never>] = [:]
    @discardableResult
    func enqueue(_ operation: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let previous = tail, id = UUID()
        let task = Task { [weak self] in
            defer { self?.tasks.removeValue(forKey: id) }
            await previous?.value
            guard !Task.isCancelled else { return }
            await operation()
        }
        tasks[id] = task; tail = task
        return task
    }
    func cancelAll() {
        for task in tasks.values { task.cancel() }
        tasks.removeAll(); tail = nil
    }
}
