import Foundation

// ─────────────────────────────────────────────────────────────
// R3-A — bubble body runs (pure logic, no transport, no UI):
//   • mentionRuns            — verbatim port of chat-room.tsx
//                              buildMentionRuns (longest-first roster
//                              names, case-insensitive @token match)
//   • bubbleRuns             — flat styled-run list for one bubble body:
//                              the ported MessageTextParser (FORMAT_RE)
//                              feeds the styles, mention runs split first
//                              exactly like the web BubbleText order.
//   • cachedRuns             — NSCache front (keyed by message id + roster
//                              signature) so LazyVStack re-renders never
//                              re-scan long bodies.
// R4-A — URL auto-linking (web splitUrlSegments parity):
//   • splitUrlSegments       — verbatim port of pulse-utils.ts:163-180
//                              (the EXACT regex, no trailing-punctuation
//                              trimming — web parity over cleverness).
//   • bubbleRuns composes    — plain stretches split into text/url runs
//                              exactly where the web's renderPlain applies
//                              (chat-room.tsx:6873-6896): URLs linkify in
//                              PLAIN stretches only — never inside bold/
//                              italic/code/pre/spoiler runs (pre/spoiler
//                              render as detached blocks anyway, and code
//                              is its own web node, so both are excluded
//                              by construction — same as the web).
// References: src/components/chat/chat-room.tsx buildMentionRuns /
// BubbleText (:6836-6985), src/lib/pulse-utils.ts splitUrlSegments
// (:163-180) (jumbo stays in PulseRemediationLogic.isJumboEmoji — the
// tested port).
// ─────────────────────────────────────────────────────────────
public enum PulseBubbleTextLogic {

    public enum RunStyle: Equatable {
        case plain
        case bold
        case italic
        case underline
        case strike
        case code
        case pre
        case spoiler
        case mention
        /// R4-A — a URL detected by the verbatim web splitUrlSegments
        /// regex; the renderer carries it as a tappable link run.
        case url
    }

    /// One output element of the verbatim web segmenter — plain text or a
    /// detected URL token (kind/value parity with pulse-utils.ts).
    public enum UrlSegment: Equatable {
        case text(String)
        case url(String)
    }

    /// The web regex, byte-for-byte: /(https?:\/\/[^\s<]+|www\.[^\s<]+)/gi
    /// (pulse-utils.ts:164). Deliberately NOT extended with trailing-
    /// punctuation exclusion — web parity over cleverness (R4-A brief).
    private static let urlPattern = "(https?://[^\\s<]+|www\\.[^\\s<]+)"

    /// Verbatim port of pulse-utils.ts splitUrlSegments (:163-175): splits
    /// text into plain/url segments so bubbles can auto-link URLs. The `gi`
    /// flags map to NSRegularExpression .caseInsensitive (global by nature).
    public static func splitUrlSegments(in text: String) -> [UrlSegment] {
        if text.isEmpty { return [] }
        // Fixed literal pattern — the try? fallback degrades to one plain
        // segment, mirroring the web loop matching nothing.
        guard let regex = try? NSRegularExpression(pattern: urlPattern, options: [.caseInsensitive]) else {
            return [.text(text)]
        }
        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        if matches.isEmpty { return [.text(text)] }
        var segments: [UrlSegment] = []
        var last = 0
        for match in matches {
            if match.range.location > last {
                let gap = ns.substring(with: NSRange(location: last, length: match.range.location - last))
                segments.append(.text(gap))
            }
            segments.append(.url(ns.substring(with: match.range)))
            last = match.range.location + match.range.length
        }
        if last < ns.length {
            segments.append(.text(ns.substring(from: last)))
        }
        return segments
    }

    public struct Run: Equatable {
        public let text: String
        public let style: RunStyle

        init(_ text: String, _ style: RunStyle) {
            self.text = text
            self.style = style
        }
    }

    /// Longest-first roster names so "Alice Chen" wins over "Alice"
    /// (web buildMentionRuns sort parity).
    static func sortedNames(_ memberNames: [String]) -> [String] {
        var names: [String] = []
        for name in memberNames {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { names.append(trimmed) }
        }
        return names.sorted { $0.count > $1.count }
    }

    /// Splits text into [plain, @mention, plain, …] runs against real
    /// roster names — web buildMentionRuns parity (case-insensitive,
    /// longest-first, regex-metacharacter-safe).
    public static func mentionRuns(in content: String, memberNames: [String]) -> [Run] {
        let names = sortedNames(memberNames)
        if names.isEmpty || content.isEmpty { return [Run(content, .plain)] }
        // "@" + (name1|name2|…) — anchored on the literal @ like the web.
        var pattern = "@("
        for (index, name) in names.enumerated() {
            if index > 0 { pattern += "|" }
            pattern += NSRegularExpression.escapedPattern(for: name)
        }
        pattern += ")"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return [Run(content, .plain)]
        }
        let ns = content as NSString
        let matches = regex.matches(in: content, range: NSRange(location: 0, length: ns.length))
        if matches.isEmpty { return [Run(content, .plain)] }
        var runs: [Run] = []
        var cursor = 0
        for match in matches {
            if match.range.location > cursor {
                let gap = ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
                runs.append(Run(gap, .plain))
            }
            runs.append(Run(ns.substring(with: match.range), .mention))
            cursor = match.range.location + match.range.length
        }
        if cursor < ns.length {
            runs.append(Run(ns.substring(from: cursor), .plain))
        }
        return runs.isEmpty ? [Run(content, .plain)] : runs
    }

    /// Flat body runs: mention splits FIRST (web BubbleText order), then the
    /// ported FORMAT_RE parser styles each plain stretch. Spoiler and pre
    /// keep their dedicated styles so the renderer can detach tap/blur.
    public static func bubbleRuns(in content: String, memberNames: [String]) -> [Run] {
        if content.isEmpty { return [] }
        var runs: [Run] = []
        for mentionRun in mentionRuns(in: content, memberNames: memberNames) {
            if mentionRun.style == .mention {
                runs.append(mentionRun)
                continue
            }
            for segment in PulseRemediationLogic.parseText(mentionRun.text) {
                switch segment {
                case .plain(let text):
                    // R4-A — web renderPlain order: URL segmentation applies
                    // to PLAIN stretches only (:6873-6896), after formatting
                    // split the runs. Everything else stays unsegmented.
                    for urlSegment in splitUrlSegments(in: text) {
                        switch urlSegment {
                        case .text(let piece): runs.append(Run(piece, .plain))
                        case .url(let piece): runs.append(Run(piece, .url))
                        }
                    }
                case .bold(let text): runs.append(Run(text, .bold))
                case .italic(let text): runs.append(Run(text, .italic))
                case .underline(let text): runs.append(Run(text, .underline))
                case .strike(let text): runs.append(Run(text, .strike))
                case .code(let text): runs.append(Run(text, .code))
                case .pre(let text): runs.append(Run(text, .pre))
                case .spoiler(let text): runs.append(Run(text, .spoiler))
                }
            }
        }
        return runs
    }

    // ── parse cache ──────────────────────────────────────────

    private final class RunBox {
        let runs: [Run]
        init(_ runs: [Run]) { self.runs = runs }
    }

    /// Bounded in-memory cache — the key carries the roster signature so a
    /// member-list change can never serve stale mention chips. NSCache is
    /// thread-safe and evicts under pressure (no retention leak).
    private static let cache = NSCache<NSString, RunBox>()

    static func rosterSignature(_ memberNames: [String]) -> String {
        "\(memberNames.count)#\(PulseTheme.hashString(memberNames.joined(separator: "\u{1}")))"
    }

    /// Cached bubbleRuns — `key` should be the message id (stable per row);
    /// a signature mismatch (roster edited) forces a fresh parse.
    public static func cachedRuns(key: String, content: String, memberNames: [String]) -> [Run] {
        if content.isEmpty { return [] }
        let cacheKey = "\(key)#\(rosterSignature(memberNames))" as NSString
        if let box = cache.object(forKey: cacheKey) {
            return box.runs
        }
        let runs = bubbleRuns(in: content, memberNames: memberNames)
        cache.setObject(RunBox(runs), forKey: cacheKey)
        return runs
    }
}
