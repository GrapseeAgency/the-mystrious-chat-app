import Foundation

public struct PulseScreenPrivacyTicket: Sendable {
    let owner: String
    let room: String
    let id: UUID
    let on: Bool
}
struct PulseScreenPrivacyRead: Sendable {
    let owner: String
    let room: String
    let revision: UUID?
    let activation: UUID
}

/// Personal room protection is a separate endpoint, not part of /settings.
/// Persist its value and pending identity together; a GET is never a write ACK.
@MainActor
final class PulseScreenPrivacyJournal {
    struct Entry: Codable {
        let on: Bool
        let revision: UUID
        let pending: UUID?
    }
    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }
    private func key(_ owner: String) -> String { "pulse.settings.veil.v2." + owner }
    func entries(owner: String) throws -> [String: Entry] {
        if let raw = defaults.object(forKey: key(owner)) {
            guard let data = raw as? Data else { throw unreadable() }
            return try JSONDecoder().decode([String: Entry].self, from: data)
        }
        // Old scoped values were only a cache: don't invent a remote write.
        guard let raw = defaults.object(forKey: "pulse.settings.veil.v1." + owner) else { return [:] }
        guard let data = raw as? Data else { throw unreadable() }
        let legacy = try JSONDecoder().decode([String: Bool].self, from: data)
        let migrated = legacy.mapValues { Entry(on: $0, revision: UUID(), pending: nil) }
        try save(migrated, owner: owner)
        return migrated
    }
    private func unreadable() -> PulseAPIClient.Failure {
        .init(kind: .validation, message: "Saved screen-security choices could not be read. Nothing was discarded.")
    }
    private func save(_ entries: [String: Entry], owner: String) throws {
        defaults.set(try JSONEncoder().encode(entries), forKey: key(owner))
    }
    func stage(owner: String, room: String, on: Bool) throws -> PulseScreenPrivacyTicket {
        var values = try entries(owner: owner)
        let id = UUID()
        values[room] = Entry(on: on, revision: id, pending: id)
        try save(values, owner: owner)
        return PulseScreenPrivacyTicket(owner: owner, room: room, id: id, on: on)
    }
    func pending(owner: String, room: String) throws -> PulseScreenPrivacyTicket? {
        guard let entry = try entries(owner: owner)[room], let id = entry.pending else { return nil }
        return PulseScreenPrivacyTicket(owner: owner, room: room, id: id, on: entry.on)
    }
    func current(_ ticket: PulseScreenPrivacyTicket) throws -> Bool {
        try entries(owner: ticket.owner)[ticket.room]?.pending == ticket.id
    }
    func acknowledge(_ ticket: PulseScreenPrivacyTicket, on: Bool) throws {
        guard on == ticket.on else { throw PulseAPIClient.Failure(kind: .server, message: "Screen security was not confirmed.") }
        var values = try entries(owner: ticket.owner)
        guard values[ticket.room]?.pending == ticket.id else { return }
        values[ticket.room] = Entry(on: on, revision: UUID(), pending: nil)
        try save(values, owner: ticket.owner)
    }
    func mergeSnapshot(_ read: PulseScreenPrivacyRead, on: Bool) throws {
        var values = try entries(owner: read.owner)
        let entry = values[read.room]
        guard entry?.revision == read.revision, entry?.pending == nil else { return }
        values[read.room] = Entry(on: on, revision: UUID(), pending: nil)
        try save(values, owner: read.owner)
    }
}
