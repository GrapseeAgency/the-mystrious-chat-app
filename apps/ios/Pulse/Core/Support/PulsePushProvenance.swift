import Foundation

/// Section 6 - cross-gateway push provenance + retraction identity (pure kernel).
///
/// Mirrors Android's `PushOriginGate` and the server transport's tag format:
///  · a push payload stamped with a FOREIGN gateway's origin must not render
///    or act on this device, even when conversation/user ids collide across
///    deployments; an unstamped (legacy) payload is accepted;
///  · the per-message APNs collapse id (`pulse-msg-v1/<rec>/<conv>/<msg>`,
///    percent-encoded exactly like JavaScript's encodeURIComponent) is the
///    identity a retraction removes from the delivered tray.
public enum PulsePushProvenance {

    static func normalize(_ origin: String?) -> String? {
        guard let raw = origin?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        var value = Substring(raw)
        while value.hasSuffix("/") { value = value.dropLast() }
        let cleaned = String(value).lowercased()
        return cleaned.isEmpty ? nil : cleaned
    }

    /// True when the stamped origin is present and does not match the
    /// configured gateway (including the unconfigured case).
    public static func isForeign(stamped: String?, configured: String?) -> Bool {
        guard let stamped = normalize(stamped) else { return false }
        guard let configured = normalize(configured) else { return true }
        return stamped != configured
    }

    /// The exact character set JavaScript's encodeURIComponent leaves
    /// untouched - the server transport builds collapse ids with it, so the
    /// removal identity must match byte-for-byte.
    private static let uriComponentAllowed = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")

    static func encodeURIComponent(_ raw: String) -> String {
        raw.addingPercentEncoding(withAllowedCharacters: uriComponentAllowed) ?? raw
    }

    /// `pulse-msg-v1/<recipient>/<conversation>/<message>` - the same string
    /// the server stamps as the APNs collapse id and Android's notification
    /// tag. Nil when any component is blank.
    public static func messageAlertIdentifier(recipient: String, conversationId: String, messageId: String) -> String? {
        let parts = [recipient, conversationId, messageId]
        guard parts.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { return nil }
        return "pulse-msg-v1/" + parts.map(encodeURIComponent).joined(separator: "/")
    }
}
