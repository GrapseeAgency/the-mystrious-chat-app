import Foundation

/// Wave 6 deep links (F-DL) — the `pulse://` scheme the app registers in
/// project.yml. Mirrors the web hash routes:
///   pulse://invite/{code}   → JoinGroupSheet parity (#/join handled via ?join=)
///   pulse://user/{id}       → the full user page (#/user/:id)
///   pulse://room/{id}       → open the conversation
///   pulse://new             → the dock compose sheet (R10-b: the
///                             "New message" home-screen quick action
///                             shares this destination)
/// Percent-encoded path segments decode here so hand-built NFC/QR links
/// behave exactly like in-app taps. Pure + total: anything unparseable is
/// `.none` (the caller ignores it — no crash, no half-state).
public enum PulseDeepLink: Equatable {
    case invite(code: String)
    case user(userId: String)
    case room(conversationId: String)
    /// R10-b — the argument-less compose route (pulse://new).
    case compose

    public static let scheme = "pulse"

    /// Parse a URL handed over by the system (onOpenURL) — scheme must be
    /// `pulse`, host picks the family, the first path component is the key.
    public static func parse(_ url: URL) -> PulseDeepLink? {
        guard url.scheme?.lowercased() == scheme else { return nil }
        // pulse://invite/abc → host "invite", path "/abc".
        // pulse:invite/abc   → host empty, first path segment "invite"
        // (both forms arrive depending on how the link was built).
        var parts: [String] = []
        if let host = url.host, !host.isEmpty { parts.append(host.lowercased()) }
        let pathSegments = url.pathComponents.filter { $0 != "/" }
        parts.append(contentsOf: pathSegments.compactMap { segment in
            segment.removingPercentEncoding
        })
        guard !parts.isEmpty else { return nil }
        let kind = parts[0].lowercased()
        // R10-b — the argument-less compose route: pulse://new (and the
        // pulse://compose spelling) carry no key segment. Every other
        // family needs the key below.
        if kind == "new" || kind == "compose" { return .compose }
        guard parts.count >= 2 else { return nil }
        let key = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return nil }
        switch kind {
        case "invite", "join", "group":
            return .invite(code: key)
        case "user", "u":
            return .user(userId: key)
        case "room", "chat", "conversation":
            return .room(conversationId: key)
        default:
            return nil
        }
    }
}
