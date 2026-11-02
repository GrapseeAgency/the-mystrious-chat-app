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
        /// Section 13 item 7 (provenance) - where this row's value came from.
        /// Optional so every pre-provenance journal blob keeps decoding.
        let origin: String?
        static let intentOrigin = "intent"
        static let migrationCacheOrigin = "migration-cache"
        static let serverOrigin = "server"

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
        // Section 13 item 7 - migrated rows are stamped "migration-cache":
        // a v1 cache cannot prove the explicit user intent that produced it,
        // so it must never be presented as a first-hand local choice.
        let migrated = legacy.mapValues { Entry(origin: Entry.migrationCacheOrigin, on: $0, revision: UUID(), pending: nil) }
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
        // Section 13 - an explicit local choice is an "intent" row.
        values[room] = Entry(origin: Entry.intentOrigin, on: on, revision: id, pending: id)
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
        // Section 13 - the confirmation retires the pending token; the row's
        // provenance (already "intent" for local writes) is preserved.
        let previous = values[ticket.room]
        values[ticket.room] = Entry(origin: previous?.origin ?? Entry.intentOrigin, on: on, revision: UUID(), pending: nil)
        try save(values, owner: ticket.owner)
    }
    func mergeSnapshot(_ read: PulseScreenPrivacyRead, on: Bool) throws {
        var values = try entries(owner: read.owner)
        let entry = values[read.room]
        guard entry?.revision == read.revision, entry?.pending == nil else { return }
        // Section 13 - a value adopted from a server snapshot is provenanced
        // "server", not "intent" (no local explicit choice produced it here).
        values[read.room] = Entry(origin: Entry.serverOrigin, on: on, revision: UUID(), pending: nil)
        try save(values, owner: read.owner)
    }
}
