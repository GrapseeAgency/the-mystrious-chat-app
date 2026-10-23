import Foundation
import CoreFoundation

/// Origin display is not account verification. Legacy payload-only flags were
/// client-writable; never promote those rows to a trusted integration label.
enum PulseMessageOrigin {
    private static let reserved: Set<String> = ["bot", "webhook", "webhookName", "webhookColor"]
    private static func object(_ raw: String?) -> [String: Any]? {
        guard let raw, raw.utf16.count <= 2048, let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
    private static func flag(_ value: Any?) -> Bool {
        guard let value = value as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { return false }
        return value.boolValue
    }
    static func label(payload: String?, automated: Bool, deleted: Bool = false) -> String? {
        guard !deleted else { return nil }
        let row = object(payload)
        let webhook = flag(row?["webhook"])
        let bot = flag(row?["bot"])
        guard automated else { return webhook || bot ? "Unverified integration" : nil }
        if webhook {
            let name = row?["webhookName"] as? String ?? ""
            let safe = name.unicodeScalars.filter { $0.properties.generalCategory != .format && $0.properties.generalCategory != .control }
                .prefix(40).map { String($0) }.joined().trimmingCharacters(in: .whitespacesAndNewlines)
            return safe.isEmpty ? "Webhook" : "Webhook · \(safe)"
        }
        return bot ? "Bot" : "Automation"
    }
    static func forwardPayload(_ raw: String?) throws -> String? {
        guard let raw else { return nil }
        guard var row = object(raw) else { throw CocoaError(.coderReadCorrupt) }
        guard row.keys.contains(where: { reserved.contains($0) }) else { return raw }
        for key in reserved { row.removeValue(forKey: key) }
        return row.isEmpty ? nil : String(data: try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]), encoding: .utf8)
    }
}
extension WireChatMessage {
    var displayAuthorName: String {
        if anon == true {
            let alias = anonAlias?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return alias.isEmpty ? "Anonymous" : alias
        }
        return integrationLabel ?? sender?.name ?? "Unknown"
    }
    var integrationLabel: String? { PulseMessageOrigin.label(payload: payload, automated: viaAutomation == true, deleted: deletedAt != nil) }
}
