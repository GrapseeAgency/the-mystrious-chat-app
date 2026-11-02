import Foundation

/// Section 13 item 8 - durable UNSAVED profile-edit intent, mirroring
/// PulseSettingsJournal's ownership discipline: an @MainActor journal over
/// UserDefaults, keyed PER ACCOUNT OWNER (gateway+id base64, the same owner
/// shape PulsePrefs uses), one owner at a time, and a draft never leaks
/// across accounts because every read/write goes through the owner key and
/// the editor re-binds on ownership change (editorOwner gate).
///
/// The draft holds what the user typed but has NOT confirmed yet: name,
/// bio (about), handle (username) and the staged photo (the local upload
/// identifier or the picked-image marker BEFORE the confirmed save). A
/// confirmed save (or explicit discard) clears exactly its fields.
@MainActor
final class PulseProfileDraftJournal {
    enum Field: String, CaseIterable, Sendable {
        case name, about, username, photo
    }

    /// Tolerant decode house style: every member optional so a draft blob
    /// written by an older build (or with fewer fields) keeps decoding.
    struct Draft: Codable, Equatable, Sendable {
        var name: String?
        var about: String?
        var username: String?
        var photo: String?
    }

    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }
    private func key(_ owner: String) -> String { "pulse.profile.draft.v1." + owner }

    private func read(_ owner: String) -> Draft {
        guard let data = defaults.data(forKey: key(owner)),
              let draft = try? JSONDecoder().decode(Draft.self, from: data) else { return Draft() }
        return draft
    }
    private func write(_ draft: Draft, owner: String) {
        let allEmpty = [draft.name, draft.about, draft.username, draft.photo].allSatisfy { $0?.isEmpty ?? true }
        if allEmpty { defaults.removeObject(forKey: key(owner)) }
        else if let data = try? JSONEncoder().encode(draft) { defaults.set(data, forKey: key(owner)) }
    }

    /// Stage one field of UNSAVED intent. An empty value clears the field
    /// from the draft (the user edited back to nothing to save).
    func stage(owner: String, field: Field, value: String) {
        var draft = read(owner)
        switch field {
        case .name: draft.name = value.isEmpty ? nil : value
        case .about: draft.about = value.isEmpty ? nil : value
        case .username: draft.username = value.isEmpty ? nil : value
        case .photo: draft.photo = value.isEmpty ? nil : value
        }
        write(draft, owner: owner)
    }

    func draft(owner: String) -> Draft { read(owner) }

    /// Confirmed save / explicit discard - only the named fields retire; a
    /// concurrent unrelated draft field survives.
    func clear(owner: String, fields: Set<Field>) {
        guard !fields.isEmpty else { return }
        var draft = read(owner)
        for field in fields {
            switch field {
            case .name: draft.name = nil
            case .about: draft.about = nil
            case .username: draft.username = nil
            case .photo: draft.photo = nil
            }
        }
        write(draft, owner: owner)
    }
}
