import Foundation

// R18-b - the icon-id contract (mirror of web src/lib/icon-ids.ts).
// User-pickable folder, topic and profile-status values are STABLE STRING
// IDS on the wire (Folder.emoji, Topic.emoji, AppUser.statusEmoji - the
// wire field names are unchanged). R19-b extends the contract to message
// reactions and sticker stamps: same rules - the wire keys keep their
// historical names (Reaction.emoji, sticker payload `emoji`), the VALUE
// domain is ids. Stored values are never rendered raw: every surface maps
// the id to its own SF Symbol, and unknown or stale values (rows saved
// before the server backfill) normalize through the registry exactly like
// the web pickers.

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

// MARK: - Reactions (R19-b)

/// Message reactions carry one of these stable ids on the wire (the
/// Reaction.emoji column and the react POST body keep their historical
/// names - web REACTION_IDS, server default `heart`).
enum PulseReactionId: String, CaseIterable, Equatable, Hashable {
    case heart, fire, laugh, wow, sad, celebrate, thumbsup

    /// Registry default - what an unknown wire value renders as.
    static let fallback: PulseReactionId = .heart

    /// Ids pass through, legacy emoji values map to their id, anything
    /// else falls back to the default (web reactionId).
    static func normalize(_ raw: String?) -> PulseReactionId {
        guard let raw, !raw.isEmpty else { return .fallback }
        return PulseReactionId(rawValue: raw) ?? legacy[raw] ?? .fallback
    }

    /// Legacy emoji values (pre-id era) resolve to ids - web
    /// REACTION_LEGACY 1:1. Keys are escaped code points so this file
    /// stays emoji-free; the escaped values are byte-identical.
    static let legacy: [String: PulseReactionId] = [
        "\u{2764}": .heart, "\u{2665}": .heart, "\u{1FA77}": .heart,
        "\u{1FA75}": .heart, "\u{1F90D}": .heart, "\u{1F90E}": .heart,
        "\u{1F496}": .heart, "\u{1F49D}": .heart, "\u{1F498}": .heart,
        "\u{1F497}": .heart, "\u{1F493}": .heart, "\u{1F49E}": .heart,
        "\u{1F495}": .heart, "\u{1F49F}": .heart, "\u{1F9E1}": .heart,
        "\u{1F49B}": .heart, "\u{1F49A}": .heart, "\u{1F499}": .heart,
        "\u{1F5A4}": .heart,
        "\u{1F525}": .fire,
        "\u{1F602}": .laugh, "\u{1F923}": .laugh, "\u{1F606}": .laugh,
        "\u{1F604}": .laugh, "\u{1F603}": .laugh, "\u{1F600}": .laugh,
        "\u{1F642}": .laugh,
        "\u{1F62E}": .wow, "\u{1F632}": .wow, "\u{1F92F}": .wow,
        "\u{1F622}": .sad, "\u{1F62D}": .sad, "\u{2639}": .sad,
        "\u{1F641}": .sad, "\u{1F61E}": .sad, "\u{1F614}": .sad,
        "\u{1F389}": .celebrate, "\u{1F38A}": .celebrate, "\u{1F973}": .celebrate,
        "\u{1F44D}": .thumbsup, "\u{1F44F}": .thumbsup, "\u{1FAF6}": .thumbsup,
        "\u{1F91D}": .thumbsup, "\u{1F64F}": .thumbsup,
    ]

    /// Human label (web REACTION_LABELS) for a11y + menu rows.
    var label: String {
        switch self {
        case .heart: return "Heart"
        case .fire: return "Fire"
        case .laugh: return "Laugh"
        case .wow: return "Wow"
        case .sad: return "Sad"
        case .celebrate: return "Celebrate"
        case .thumbsup: return "Thumbs up"
        }
    }

    /// The SF Symbol this surface renders for the id (iOS 17.0 floor,
    /// conservative names only).
    var symbolName: String {
        switch self {
        case .heart: return "heart.fill"
        case .fire: return "flame.fill"
        case .laugh: return "face.smiling.fill"
        case .wow: return "eye.fill"
        case .sad: return "face.dashed"
        case .celebrate: return "party.popper.fill"
        case .thumbsup: return "hand.thumbsup.fill"
        }
    }
}

// MARK: - Stamps (R19-b)

/// Sticker messages carry one of these stable stamp ids in their payload
/// (the payload key keeps its historical name; web STAMP_IDS, server
/// default `sparkles`). Every surface maps the id to its own large glyph.
enum PulseStampId: String, CaseIterable, Equatable, Hashable {
    case bolt, flame, sparkles, rocket, target, star
    case trophy, crown, gift, cake, music, heart
    case palette, camera, mic, gamepad, brain, drama
    case smile, pin, sun, shield, key, thumbsup, thumbsdown
    case leaf, moon, drop, planet, coffee, paw

    /// Registry default - what an unknown payload value renders as.
    static let fallback: PulseStampId = .sparkles

    /// Ids pass through, legacy emoji values map to their stamp, anything
    /// else falls back to the default (web stampId).
    static func normalize(_ raw: String?) -> PulseStampId {
        guard let raw, !raw.isEmpty else { return .fallback }
        return PulseStampId(rawValue: raw) ?? legacy[raw] ?? .fallback
    }

    /// Legacy emoji sticker values (pre-id era) resolve to stamp ids -
    /// web STAMP_LEGACY 1:1 (escaped code points, emoji-free source).
    static let legacy: [String: PulseStampId] = [
        "\u{26A1}": .bolt, "\u{26A1}\u{FE0F}": .bolt, "\u{1F4A5}": .bolt,
        "\u{1F4AA}": .bolt,
        "\u{1F525}": .flame,
        "\u{2728}": .sparkles, "\u{1F31F}": .sparkles, "\u{1F4AB}": .sparkles,
        "\u{2B50}": .sparkles,
        "\u{1F680}": .rocket,
        "\u{1F3AF}": .target, "\u{26BD}": .target,
        "\u{1F3C6}": .trophy,
        "\u{1F451}": .crown,
        "\u{1F381}": .gift,
        "\u{1F382}": .cake, "\u{1F370}": .cake, "\u{1F355}": .cake,
        "\u{1F3B5}": .music, "\u{1F3B6}": .music,
        "\u{2764}": .heart, "\u{2764}\u{FE0F}": .heart, "\u{1F9E1}": .heart,
        "\u{1F49B}": .heart, "\u{1F49A}": .heart, "\u{1F499}": .heart,
        "\u{1F49C}": .heart, "\u{1F5A4}": .heart, "\u{1F496}": .heart,
        "\u{1F498}": .heart, "\u{1F49E}": .heart, "\u{1FAF6}": .heart,
        "\u{1F3A8}": .palette,
        "\u{1F4F7}": .camera, "\u{1F4F8}": .camera,
        "\u{1F3A4}": .mic, "\u{1F399}": .mic,
        "\u{1F3AE}": .gamepad,
        "\u{1F9E0}": .brain,
        "\u{1F3AD}": .drama,
        "\u{1F600}": .smile, "\u{1F602}": .smile, "\u{1F979}": .smile,
        "\u{1F60D}": .smile, "\u{1F60E}": .smile, "\u{1F914}": .smile,
        "\u{1F973}": .smile, "\u{1F64C}": .smile, "\u{1F44B}": .smile,
        "\u{1F64F}": .smile, "\u{1F44F}": .smile, "\u{1F91D}": .smile,
        "\u{1F605}": .smile, "\u{1FAE1}": .smile, "\u{1F90C}": .smile,
        "\u{1F917}": .smile,
        "\u{1F634}": .moon, "\u{1F319}": .moon,
        "\u{2600}": .sun, "\u{2600}\u{FE0F}": .sun,
        "\u{1F308}": .leaf,
        "\u{2615}": .coffee,
        "\u{1F43E}": .paw, "\u{1F436}": .paw, "\u{1F431}": .paw,
        "\u{1F43C}": .paw, "\u{1F98A}": .paw, "\u{1F438}": .paw,
        "\u{1F435}": .paw, "\u{1F984}": .paw, "\u{1F419}": .paw,
        "\u{1F98B}": .paw, "\u{1F422}": .paw,
        "\u{1F30D}": .planet, "\u{1F30E}": .planet,
        "\u{1F44D}": .thumbsup,
        "\u{1F44E}": .thumbsdown,
    ]

    /// Human label (web STAMP_LABELS) for a11y rows.
    var label: String {
        switch self {
        case .bolt: return "Bolt"
        case .flame: return "Flame"
        case .sparkles: return "Sparkles"
        case .rocket: return "Rocket"
        case .target: return "Target"
        case .star: return "Star"
        case .trophy: return "Trophy"
        case .crown: return "Crown"
        case .gift: return "Gift"
        case .cake: return "Cake"
        case .music: return "Music"
        case .heart: return "Heart"
        case .palette: return "Palette"
        case .camera: return "Camera"
        case .mic: return "Microphone"
        case .gamepad: return "Gamepad"
        case .brain: return "Brain"
        case .drama: return "Drama"
        case .smile: return "Smile"
        case .pin: return "Pin"
        case .sun: return "Sun"
        case .shield: return "Shield"
        case .key: return "Key"
        case .thumbsup: return "Thumbs up"
        case .thumbsdown: return "Thumbs down"
        case .leaf: return "Leaf"
        case .moon: return "Moon"
        case .drop: return "Drop"
        case .planet: return "Planet"
        case .coffee: return "Coffee"
        case .paw: return "Paw"
        }
    }

    /// The SF Symbol this surface renders for the id (iOS 17.0 floor,
    /// conservative names only).
    var symbolName: String {
        switch self {
        case .bolt: return "bolt.fill"
        case .flame: return "flame.fill"
        case .sparkles: return "sparkles"
        case .rocket: return "rocket.fill"
        case .target: return "target"
        case .star: return "star.fill"
        case .trophy: return "trophy.fill"
        case .crown: return "crown.fill"
        case .gift: return "gift.fill"
        case .cake: return "cake.fill"
        case .music: return "music.note"
        case .heart: return "heart.fill"
        case .palette: return "paintpalette.fill"
        case .camera: return "camera.fill"
        case .mic: return "mic.fill"
        case .gamepad: return "gamecontroller.fill"
        case .brain: return "brain.head.profile"
        case .drama: return "theatermasks.fill"
        case .smile: return "face.smiling.fill"
        case .pin: return "pin.fill"
        case .sun: return "sun.max.fill"
        case .shield: return "shield.fill"
        case .key: return "key.fill"
        case .thumbsup: return "hand.thumbsup.fill"
        case .thumbsdown: return "hand.thumbsdown.fill"
        case .leaf: return "leaf.fill"
        case .moon: return "moon.fill"
        case .drop: return "drop.fill"
        case .planet: return "globe.americas.fill"
        case .coffee: return "cup.and.saucer.fill"
        case .paw: return "pawprint.fill"
        }
    }

    /// The pack this stamp ships in (web STAMP_PACKS membership); unknown
    /// ids resolve into the first pack like the web neutral gradient.
    var packName: String {
        switch self {
        case .bolt, .flame, .sparkles, .rocket, .target, .star: return "Signal"
        case .trophy, .crown, .gift, .cake, .music, .heart: return "Celebrate"
        case .palette, .camera, .mic, .gamepad, .brain, .drama: return "Create"
        case .leaf, .moon, .drop, .planet, .coffee, .paw: return "Nature"
        case .smile, .pin, .sun, .shield, .key, .thumbsup, .thumbsdown: return "Marks"
        }
    }
}
