import Foundation

// ─────────────────────────────────────────────────────────────
// Wave 6 — pure logic kernels for the discovery surfaces. Pulled out of
// the views so the CI test target can pin the web behaviours (mentions
// regex mirror, 99+ badge cap, channel/folder field validation, two-tap
// delete window) without any SwiftUI/transport involved.
// ─────────────────────────────────────────────────────────────

public enum PulseBadgeCap {
    /// The universal feed-badge cap — 100+ renders as "99+" (web mentions
    /// header + dock badge + entry pills all share this rule).
    public static func cap(_ value: Int) -> String {
        value > 99 ? "99+" : "\(value)"
    }
}

/// @mention token machinery — TWO mirrors kept in lockstep with the wire:
///   · `activeToken(in:)`  — composer trigger, web chat-room.tsx
///     `/(?:^|\s)@([^@\s]*)$/` on the text up to the caret (the native
///     composer evaluates the draft tail, which is the same set while
///     typing; mid-text edits still complete from the tail token).
///   · `firstMatch(of:in:)` — the feed-row chip highlight, the exact
///     /api/mentions rule `@{name}(?=\s|$|[^A-Za-z0-9])` case-insensitive
///     (mentions-page.tsx mentionPatternFor — keep the two in sync).
public enum PulseMentions {
    /// The mention the composer should complete right now, if any:
    /// `(token, startIndexOfTheAtSign)`.
    public static func activeToken(in text: String) -> (token: String, atStartIndex: Int)? {
        guard let pattern = try? NSRegularExpression(pattern: "(?:^|\\s)@([^@\\s]*)$") else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern.firstMatch(in: text, range: range),
              match.numberOfRanges >= 2,
              let tokenRange = Range(match.range(at: 1), in: text),
              let atRange = Range(match.range(at: 0), in: text) else { return nil }
        // The at-sign sits at the last "@" inside the whole match.
        let matchText = String(text[atRange])
        guard let atOffset = matchText.lastIndex(of: "@") else { return nil }
        let atStart = matchText.distance(from: matchText.startIndex, to: atOffset)
        let start = text.distance(from: text.startIndex, to: atRange.lowerBound) + atStart
        return (String(text[tokenRange]), start)
    }

    /// Roster filter — member display names that START with the token
    /// (case-insensitive), top 5, viewer excluded by the caller.
    public static func matches(for token: String, in names: [String], limit: Int = 5) -> [String] {
        let needle = token.lowercased()
        guard !needle.isEmpty || token.isEmpty else { return [] }
        var out: [String] = []
        for name in names where name.lowercased().hasPrefix(needle) {
            out.append(name)
            if out.count == limit { break }
        }
        return out
    }

    /// Insertion for a picked member — "@Full Name " (trailing space, web
    /// pickMention parity), replacing the active token.
    public static func insertion(for name: String) -> String { "@\(name) " }

    /// The first "@Full Name" occurrence in a snippet — the emerald chip
    /// range the feed rows render. Case-insensitive, boundary-guarded.
    public static func firstMatch(of name: String, in snippet: String) -> Range<String.Index>? {
        guard !name.isEmpty else { return nil }
        let escaped = NSRegularExpression.escapedPattern(for: name)
        // (?=\s|$|[^A-Za-z0-9]) — the API boundary rule, verbatim.
        guard let pattern = try? NSRegularExpression(pattern: "@\(escaped)(?=\\s|$|[^A-Za-z0-9])", options: [.caseInsensitive]) else { return nil }
        let range = NSRange(snippet.startIndex..., in: snippet)
        guard let match = pattern.firstMatch(in: snippet, range: range),
              let swiftRange = Range(match.range(at: 0), in: snippet) else { return nil }
        return swiftRange
    }
}

/// Channel create-form validation — the exact server contract
/// (POST /api/channels: name 2-40 after trim, description ≤200) with the
/// verbatim web copy strings (new-chat-sheet.tsx CHANNEL_NAME_MIN/MAX).
public enum PulseChannelDraft {
    public static let nameMin = 2
    public static let nameMax = 40
    public static let descriptionMax = 200

    /// nil = valid; otherwise the exact sheet error copy.
    public static func nameError(_ rawName: String) -> String? {
        let length = rawName.trimmingCharacters(in: .whitespaces).count
        if length < nameMin { return "Name needs at least \(nameMin) characters." }
        if length > nameMax { return "Keep the name under \(nameMax + 1) characters." }
        return nil
    }

    public static func trimmedDescription(_ raw: String) -> String {
        String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(descriptionMax))
    }
}

/// Folder create/rename validation — POST/PATCH /api/folders name 1-24
/// (folders-sheet.tsx FOLDER_NAME_MAX).
public enum PulseFolderDraft {
    public static let nameMax = 24

    public static func isValidName(_ rawName: String) -> Bool {
        let trimmed = rawName.trimmingCharacters(in: .whitespaces)
        return !trimmed.isEmpty && trimmed.count <= nameMax
    }
}

/// Two-tap destructive confirm — the second tap inside the window executes;
/// the window expiring (or tapping elsewhere) re-arms. 2600 ms everywhere
/// (folders-sheet confirm window parity). Pure math — sheets hold the state.
public enum PulseTwoTap {
    public static let windowMs: Int = 2600

    /// Should a tap at `nowMs` execute the destructive action, given the
    /// previous arming stamp (nil = never armed)?
    public static func shouldExecute(nowMs: Int, armedAtMs: Int?, windowMs: Int = PulseTwoTap.windowMs) -> Bool {
        guard let armedAtMs else { return false }
        return nowMs - armedAtMs <= windowMs && nowMs - armedAtMs >= 0
    }
}
