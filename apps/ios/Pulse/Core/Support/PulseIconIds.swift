import Foundation

// R18-b - the icon-id contract (mirror of web src/lib/icon-ids.ts).
// User-pickable folder, topic and profile-status values are STABLE STRING
// IDS on the wire (Folder.emoji, Topic.emoji, AppUser.statusEmoji - the
// wire field names are unchanged). Stored values are never rendered raw:
// every surface maps the id to its own SF Symbol, and unknown or stale
// values (rows saved before the server backfill) normalize to the
// registry default exactly like the web pickers.

/// Pickable folder icons - POST/PATCH /api/folders `emoji` ids
/// (server default `folder`; web FOLDER_ICON_IDS).
enum PulseFolderIconId: String, CaseIterable, Equatable {
    case folder, briefcase, game, heart, flame, target, music, brain

    /// Registry default - the server's folder default id.
    static let fallback: PulseFolderIconId = .folder

    /// Unknown/stale stored values resolve to the default (web folderIconId).
    static func normalize(_ raw: String?) -> PulseFolderIconId {
        PulseFolderIconId(rawValue: raw ?? "") ?? .fallback
    }

    /// The SF Symbol this surface renders for the id (iOS 17.0 floor).
    var symbolName: String {
        switch self {
        case .folder: return "folder"
        case .briefcase: return "briefcase"
        case .game: return "gamecontroller"
        case .heart: return "heart.fill"
        case .flame: return "flame.fill"
        case .target: return "target"
        case .music: return "music.note"
        case .brain: return "brain.head.profile"
        }
    }
}

/// Pickable topic icons - POST /api/conversations/[id]/topics `emoji` ids
/// (server default `chat`; web TOPIC_ICON_IDS).
enum PulseTopicIconId: String, CaseIterable, Equatable {
    case chat, palette, rocket, brain, confetti, wrench, pin, coffee

    /// Registry default - the server's topic default id.
    static let fallback: PulseTopicIconId = .chat

    /// Unknown/stale stored values resolve to the default (web topicIconId).
    static func normalize(_ raw: String?) -> PulseTopicIconId {
        PulseTopicIconId(rawValue: raw ?? "") ?? .fallback
    }

    /// The SF Symbol this surface renders for the id (iOS 17.0 floor).
    var symbolName: String {
        switch self {
        case .chat: return "message.fill"
        case .palette: return "paintpalette.fill"
        case .rocket: return "rocket"
        case .brain: return "brain.head.profile"
        case .confetti: return "party.popper.fill"
        case .wrench: return "wrench.fill"
        case .pin: return "pin.fill"
        case .coffee: return "cup.and.saucer.fill"
        }
    }
}

/// Pickable profile-status icons - PATCH /api/users/[id] `statusEmoji` ids
/// (web STATUS_ICON_IDS). Absence is nil (no status rendered); a non-empty
/// unknown value renders as the DEFAULT (sparkles), mirroring statusIconId.
enum PulseStatusIconId: String, CaseIterable, Equatable {
    case flame, sparkles, target, coffee, headphones, moon, bulb, rocket, sleep, food, vacation

    /// Registry default - what an unknown stored value renders as.
    static let fallback: PulseStatusIconId = .sparkles

    /// nil/empty -> nil (no status); unknown -> the default.
    static func normalize(_ raw: String?) -> PulseStatusIconId? {
        guard let raw, !raw.isEmpty else { return nil }
        return PulseStatusIconId(rawValue: raw) ?? .fallback
    }

    /// The SF Symbol this surface renders for the id (iOS 17.0 floor).
    var symbolName: String {
        switch self {
        case .flame: return "flame.fill"
        case .sparkles: return "sparkles"
        case .target: return "target"
        case .coffee: return "cup.and.saucer.fill"
        case .headphones: return "headphones"
        case .moon: return "moon.fill"
        case .bulb: return "lightbulb"
        case .rocket: return "rocket"
        case .sleep: return "bed.double.fill"
        case .food: return "fork.knife"
        case .vacation: return "airplane"
        }
    }

    /// Human label (web status-glyph.tsx LABEL_BY_ID) for a11y + text rows.
    var label: String {
        switch self {
        case .flame: return "On fire"
        case .sparkles: return "Sparkles"
        case .target: return "Focused"
        case .coffee: return "Coffee break"
        case .headphones: return "Listening"
        case .moon: return "Night owl"
        case .bulb: return "Ideas"
        case .rocket: return "Shipping"
        case .sleep: return "Sleeping"
        case .food: return "Eating"
        case .vacation: return "On vacation"
        }
    }
}
