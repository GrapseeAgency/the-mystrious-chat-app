import Foundation

public struct PulseSettingsTicket: Sendable {
    let owner: String
    let patch: WirePulsePrefs
    let fields: [String: UUID]
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
@MainActor
final class PulseSettingsJournal {
    private struct Entry: Codable { let id: UUID; let patch: WirePulsePrefs }
    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }
    private func key(_ owner: String) -> String { "pulse.settings.pending.v1." + owner }
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
        for field in patch.settingsKeys {
            let id = UUID(); fields[field] = id
            pending[field] = Entry(id: id, patch: patch.selectingSettings([field]))
        }
        try write(pending, owner: owner)
        return PulseSettingsTicket(owner: owner, patch: patch, fields: fields)
    }
    func current(_ ticket: PulseSettingsTicket) throws -> WirePulsePrefs {
        let pending = try read(ticket.owner)
        return ticket.patch.selectingSettings(Set(ticket.fields.compactMap { key, id in pending[key]?.id == id ? key : nil }))
    }
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
        return PulseSettingsTicket(owner: owner, patch: patch, fields: pending.mapValues(\.id))
    }
    func acknowledge(_ ticket: PulseSettingsTicket, sent: WirePulsePrefs, server: WirePulsePrefs, apply: (WirePulsePrefs) -> Void) throws {
        guard sent.settingsKeys.isSubset(of: server.settingsKeys) else {
            throw PulseAPIClient.Failure(kind: .server, message: "The server did not confirm the requested settings fields.")
        }
        var pending = try read(ticket.owner)
        let accepted = Set(sent.settingsKeys.filter { pending[$0]?.id == ticket.fields[$0] })
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
