import Foundation

// ─────────────────────────────────────────────────────────────
// REM-B — pure logic parity ports (no transport, no UI):
//   • anonAliasFor       — verbatim port of chat-room.tsx anonStableHash /
//                          anonAliasPreview AND the server's messages route
//                          FNV-1a (identical word lists, "Adjective the Animal")
//   • isJumboEmoji       — port of src/lib/pulse-utils.ts isJumboEmoji
//   • MessageTextParser  — port of the chat-room.tsx FORMAT_RE bubble formatter
//   • TtlFilter          — client-side hiding of expired rows (F-MS-19)
//   • PulseSlash         — the 25 web slash commands + applySlash outcome parser
//   • PulseStickers      — the web sticker-picker packs (kind:"sticker" payload)
// ─────────────────────────────────────────────────────────────
public enum PulseRemediationLogic {

    // ── F-MS-17 — deterministic incognito alias ──────────────

    /// Server word lists (messages/route.ts:38-39) — order is load-bearing.
    static let anonAdjectives = ["Swift", "Quiet", "Neon", "Ember", "Frost", "Lucky", "Cosmic", "Silent"]
    static let anonAnimals = ["Falcon", "Otter", "Panda", "Wolf", "Comet", "Tiger", "Raven", "Fox"]

    /// Stable 32-bit FNV-1a string hash. JS parity: `charCodeAt` walks UTF-16
    /// code units, `Math.imul` is 32-bit truncating multiply, `>>> 0` widens
    /// to unsigned — so iterate `utf16` and mask to UInt32.
    public static func fnv1a(_ value: String) -> UInt32 {
        var hash: UInt32 = 0x811c_9dc5
        for unit in value.utf16 {
            hash ^= UInt32(unit)
            hash = hash &* 0x0100_0193
        }
        return hash
    }

    /// "Adjective the Animal" — client mirror of the server's deterministic
    /// incognito alias so the OPTIMISTIC bubble already shows the exact alias
    /// the server will store (web anonAliasPreview parity). Key is
    /// "userId:conversationId".
    public static func anonAlias(viewerId: String, conversationId: String) -> String {
        let hash = fnv1a("\(viewerId):\(conversationId)")
        let adjective = anonAdjectives[Int(hash % UInt32(anonAdjectives.count))]
        let animal = anonAnimals[Int(hash / UInt32(anonAdjectives.count)) % UInt32(anonAnimals.count)]
        return "\(adjective) the \(animal)"
    }

    // ── F-MS-03 — jumbo emoji (pulse-utils.ts) ───────────────

    /// Port of the web JUMBO_EMOJI_RE check: ≤24 UTF-16 units (JS .length),
    /// every code point is pictographic / emoji-component / whitespace /
    /// ZWJ / VS16, and at least one real pictograph. ICU supports both
    /// Extended_Pictographic and Emoji_Component binary properties; the
    /// conservative scalar fallback covers exotic runtimes honestly.
    public static func isJumboEmoji(_ content: String) -> Bool {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.utf16.count > 24 { return false }
        if let match = jumboRegex.firstMatch(
            in: trimmed,
            range: NSRange(trimmed.startIndex..., in: trimmed),
        ) {
            // The web's final /\p{Extended_Pictographic}/u check — folded in
            // by requiring the whole-string scan to contain a pictograph.
            return match.range.length > 0 && jumboRegexPictograph.firstMatch(
                in: trimmed,
                range: NSRange(trimmed.startIndex..., in: trimmed),
            ) != nil
        }
        return false
    }

    /// Whole-string jumbo gate (web JUMBO_EMOJI_RE verbatim).
    private static let jumboRegex = try! NSRegularExpression(
        pattern: #"^(?:\p{Extended_Pictographic}|\p{Emoji_Component}|\s|\u200d|\ufe0f){1,9}$"#,
    )
    private static let jumboRegexPictograph = try! NSRegularExpression(
        pattern: #"\p{Extended_Pictographic}"#,
    )

    // ── F-MS-19 — expired-row filter ─────────────────────────

    /// Rows with a non-nil expiresAt in the past never render (web parity:
    /// the TTL sweep deletes server-side, the client hides until then).
    public static func isAlive(_ expiresAtIso: String?, now: Date = Date()) -> Bool {
        guard let iso = expiresAtIso, !iso.isEmpty else { return true }
        guard let expires = PulseFormat.date(iso) else { return true }
        return expires > now
    }

    // ── F-MS-02 — bubble text formatting ─────────────────────

    public enum TextSegment: Equatable {
        case plain(String)
        case pre(String)
        case code(String)
        case bold(String)
        case underline(String)
        case strike(String)
        case spoiler(String)
        case italic(String)
    }

    /// Port of the web FORMAT_RE (chat-room.tsx:6586) — multi-char tokens
    /// first so ** wins over *, pre/code span newlines, the rest don't.
    /// Group order: 1 pre · 2 code · 3 **bold** · 4 __underline__ ·
    /// 5 ~~strike~~ · 6 ||spoiler|| · 7 *bold* · 8 _italic_ · 9 ~strike~.
    static let formatRegex = try! NSRegularExpression(
        pattern: #"```([\s\S]+?)```|`([^`\n]+)`|\*\*([^*\n]+?)\*\*|__([^_\n]+?)__|~~([^~\n]+?)~~|\|\|([^|\n]+?)\|\||\*([^*\n]+?)\*|_([^_\n]+?)_|~([^~\n]+?)~"#,
    )

    /// Non-overlapping, left-to-right scan with plain gaps between matches
    /// (JS matchAll(/…/g) parity).
    public static func parseText(_ content: String) -> [TextSegment] {
        var segments: [TextSegment] = []
        let ns = content as NSString
        let matches = formatRegex.matches(in: content, range: NSRange(location: 0, length: ns.length))
        var cursor = 0
        for match in matches {
            if match.range.location > cursor {
                segments.append(.plain(ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))))
            }
            func group(_ index: Int) -> String? {
                guard index < match.numberOfRanges, let range = Range(match.range(at: index), in: content) else { return nil }
                return String(content[range])
            }
            if let v = group(1) { segments.append(.pre(v)) }
            else if let v = group(2) { segments.append(.code(v)) }
            else if let v = group(3) { segments.append(.bold(v)) }
            else if let v = group(4) { segments.append(.underline(v)) }
            else if let v = group(5) { segments.append(.strike(v)) }
            else if let v = group(6) { segments.append(.spoiler(v)) }
            else if let v = group(7) { segments.append(.bold(v)) }
            else if let v = group(8) { segments.append(.italic(v)) }
            else if let v = group(9) { segments.append(.strike(v)) }
            cursor = match.range.location + match.range.length
        }
        if cursor < ns.length {
            segments.append(.plain(ns.substring(from: cursor)))
        }
        return segments
    }

    // ── F-MS-22 — slash commands (web PULSE_SLASH_COMMANDS verbatim) ──

    public struct SlashCommand: Equatable, Identifiable {
        public var id: String { cmd }
        public let cmd: String
        public let args: String
        public let help: String
    }

    public static let slashCommands: [SlashCommand] = [
        .init(cmd: "/me", args: "<action>", help: "Send an italic action line"),
        .init(cmd: "/shrug", args: "[text]", help: "Append ¯\\_(ツ)_/¯"),
        .init(cmd: "/tableflip", args: "[text]", help: "Append (╯°□°）╯︵ ┻━┻"),
        .init(cmd: "/unflip", args: "[text]", help: "Prefix ┬─┬ ノ( ゜-゜ノ"),
        .init(cmd: "/roll", args: "[AdM]", help: "Roll dice, e.g. /roll 2d6"),
        .init(cmd: "/poll", args: "", help: "Open the live-poll builder"),
        .init(cmd: "/schedule", args: "", help: "Schedule this message for later"),
        .init(cmd: "/remind", args: "<message> in <time>", help: "Set a reminder on your next message"),
        .init(cmd: "/recap", args: "", help: "AI summary of the recent chat"),
        .init(cmd: "/sticker", args: "", help: "Open the sticker packs"),
        .init(cmd: "/location", args: "", help: "Share a live map pin"),
        .init(cmd: "/whiteboard", args: "", help: "Open the shared whiteboard"),
        .init(cmd: "/redpacket", args: "", help: "Send a red packet (coins)"),
        .init(cmd: "/game", args: "", help: "Start tic-tac-toe in this chat"),
        .init(cmd: "/kanban", args: "", help: "Open the group board"),
        .init(cmd: "/events", args: "", help: "Group events with RSVP"),
        .init(cmd: "/topic", args: "<name>", help: "Create a topic and file here"),
        .init(cmd: "/stage", args: "", help: "Open the live stage room"),
        .init(cmd: "/space", args: "", help: "Open the spatial space"),
        .init(cmd: "/tournament", args: "", help: "Start a group tournament"),
        .init(cmd: "/effects confetti", args: "[text]", help: "Send with a confetti blast"),
        .init(cmd: "/effects lasers", args: "[text]", help: "Send with sweeping laser beams"),
        .init(cmd: "/effects echo", args: "[text]", help: "Send with expanding echo rings"),
        .init(cmd: "/effects sparkles", args: "[text]", help: "Send with twinkling sparkles"),
        .init(cmd: "/help", args: "", help: "Show every command"),
    ]

    /// applySlash outcome — the send-shaped results the room handles;
    /// sheet/tool commands surface as explicit intents.
    public enum SlashOutcome: Equatable {
        case send(String)
        case effect(name: String, content: String)
        case error(String)
        case sheet(String) // poll | schedule | sticker | location | whiteboard | redpacket | kanban | events | game | stage | space | tournament
        case topic(String)
        case remind(String)
        case help
    }

    static let effectNames: Set<String> = ["confetti", "lasers", "echo", "sparkles"]
    /// R21-a bot commands pass through as plain messages (server answers).
    static let botCommands: Set<String> = ["math", "flip", "8ball", "rps", "dice", "time", "wallet"]

    /// Port of the web applySlash — leading-slash parsing into real content
    /// or UI actions. Non-slash input is returned as `.send` verbatim; a
    /// leading token that is not a \w+ word (like the web's `^\/(\w+)…`
    /// regex) also falls through to `.send`.
    public static func applySlash(_ rawInput: String) -> SlashOutcome {
        let input = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.hasPrefix("/") else { return .send(input) }
        let body = String(input.dropFirst())
        let parts = body.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
        let word = parts.first.map(String.init)?.lowercased() ?? ""
        guard !word.isEmpty, word.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }) else {
            return .send(input)
        }
        let rest = parts.count > 1 ? String(parts[1]) : ""
        let arg = rest.trimmingCharacters(in: .whitespacesAndNewlines)
        switch word {
        case "me":
            if arg.isEmpty { return .error("Usage: /me waves hello") }
            return .send("_\(String(arg.prefix(1998)))_")
        case "shrug":
            return .send(arg.isEmpty ? "¯\\_(ツ)_/¯" : "\(arg) ¯\\_(ツ)_/¯")
        case "tableflip":
            return .send(arg.isEmpty ? "(╯°□°）╯︵ ┻━┻" : "\(arg) (╯°□°）╯︵ ┻━┻")
        case "unflip":
            return .send(arg.isEmpty ? "┬─┬ ノ( ゜-゜ノ" : "┬─┬ ノ( ゜-゜ノ \(arg)")
        case "roll":
            if arg.isEmpty {
                let total = Int.random(in: 1...6)
                return .send("Rolled **1d6**: *\(total)*")
            }
            guard let roll = rollDice(arg) else { return .error("Usage: /roll AdM — e.g. /roll 2d6") }
            return .send("Rolled **\(arg.lowercased())**: \(roll.rolls.joined(separator: " + ")) = *\(roll.total)*")
        case "poll": return .sheet("poll")
        case "schedule": return .sheet("schedule")
        case "remind": return .remind(rest)
        case "sticker": return .sheet("sticker")
        case "location": return .sheet("location")
        case "whiteboard", "redpacket", "kanban", "events", "game", "stage", "space", "tournament":
            return .sheet(word)
        case "topic":
            if arg.isEmpty { return .error("Usage: /topic Design") }
            return .topic(arg)
        case "effects":
            let wordParts = arg.split(separator: " ", maxSplits: 1)
            let effectWord = wordParts.first.map { $0.lowercased() } ?? ""
            guard effectNames.contains(effectWord) else {
                return .error("Usage: /effects confetti|lasers|echo|sparkles [text]")
            }
            let text = wordParts.count > 1 ? String(wordParts[1]).trimmingCharacters(in: .whitespaces) : ""
            return .effect(name: effectWord, content: text)
        case "help": return .help
        default:
            if botCommands.contains(word) { return .send(input) }
            return .error("Unknown command \"/\(word)\" — try /help")
        }
    }

    /// "AdM" dice spec — web rollDice parity (1..100 dice, 2..1000 sides).
    public static func rollDice(_ spec: String) -> (rolls: [String], total: Int)? {
        let trimmed = spec.trimmingCharacters(in: .whitespaces).lowercased()
        guard let m = diceRegex.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)),
              m.numberOfRanges >= 3,
              let countRange = Range(m.range(at: 1), in: trimmed),
              let sidesRange = Range(m.range(at: 2), in: trimmed) else { return nil }
        let count = Int(trimmed[countRange]) ?? 1
        let sides = Int(trimmed[sidesRange]) ?? 0
        guard (1...100).contains(count), (2...1000).contains(sides) else { return nil }
        var rolls: [String] = []
        var total = 0
        for _ in 0..<count {
            let value = Int.random(in: 1...sides)
            rolls.append(String(value))
            total += value
        }
        return (rolls, total)
    }

    private static let diceRegex = try! NSRegularExpression(pattern: #"^(\d{1,3})d(\d{1,4})$"#)

    // ── F-MS-23 — incoming effect → ParticleBus mapping ──────

    /// Web EFFECT_PARTICLES: confetti→confetti · sparkles→stars ·
    /// lasers/echo→burst. Unknown/absent effects → nil (no burst).
    public static func particleKind(forEffect effect: String?) -> String? {
        switch effect {
        case "confetti": return "confetti"
        case "sparkles": return "stars"
        case "lasers", "echo": return "burst"
        default: return nil
        }
    }

    /// payload JSON ({ effect: "…" } on kind "text" rows) → effect name.
    public static func effectOfPayload(_ payload: String?) -> String? {
        guard let payload, let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let effect = object["effect"] as? String else { return nil }
        return effectNames.contains(effect) ? effect : nil
    }

    // ── F-MS-24 — sticker packs (web sticker-picker.tsx verbatim) ──

    public struct StickerPack: Equatable, Identifiable {
        public var id: String { name }
        public let name: String
        public let badge: String
        public let items: [String]
    }

    public static let stickerPacks: [StickerPack] = [
        .init(name: "Pulse", badge: "⚡️", items: ["⚡️", "🔥", "💥", "🎉", "✨", "🌟", "💫", "🚀", "🎯", "🏆"]),
        .init(name: "Faces", badge: "😄", items: ["😂", "😍", "😎", "🤯", "😭", "😡", "🥳", "😴", "🤔", "🫠"]),
        .init(name: "Reactions", badge: "👍", items: ["👍", "👎", "🙏", "👏", "💪", "🤝", "😅", "🫡", "🤌", "🤗"]),
        .init(name: "Love", badge: "❤️", items: ["❤️", "🧡", "💛", "💚", "💜", "🖤", "💖", "💘", "💞", "🫶"]),
        .init(name: "Critters", badge: "🐾", items: ["🐶", "🐱", "🐼", "🦊", "🐸", "🐵", "🦄", "🐙", "🦋", "🐢"]),
    ]
}
