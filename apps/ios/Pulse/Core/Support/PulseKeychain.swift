import Foundation
import Security

/// Keychain wrapper (kSecClassGenericPassword) — the iOS mirror of Android's
/// SecureSessionStore. Two Pulse accounts live here:
///   • "viewer.identity"    — JSON envelope { viewer, token (future slot) }
///   • "endpoints.override" — JSON { gateway, socket } from update-manifest.json
/// Items use kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly (no iCloud sync,
/// readable after first unlock so BGAppRefreshTask flushes keep working).
/// All calls are funneled through a serial queue; errSecItemNotFound is a
/// graceful nil, never a crash.
public final class PulseKeychain: @unchecked Sendable {
    public static let shared = PulseKeychain()

    public static let service = "app.pulse.chat"
    public static let identityAccount = "viewer.identity"
    public static let endpointsAccount = "endpoints.override"

    private let queue = DispatchQueue(label: "app.pulse.chat.keychain")

    private init() {}

    // ── raw item API ─────────────────────────────────────────

    /// Saves (or updates) a generic-password item. Returns false when the
    /// Keychain refuses (simulator quirk, entitlement loss) — callers decide
    /// whether that is fatal (tests skip) or best-effort (app paths).
    @discardableResult
    public func save(_ data: Data, account: String) -> Bool {
        queue.sync { Self.saveItem(data, account: account) }
    }

    /// Loads an item; nil when absent or unreadable.
    public func load(account: String) -> Data? {
        queue.sync { Self.loadItem(account: account) }
    }

    /// Deletes an item; missing items are already "deleted".
    public func delete(account: String) {
        queue.sync { Self.deleteItem(account: account) }
    }

    // ── typed helpers ────────────────────────────────────────

    /// Mirrors the viewer identity (and a future session-token slot) into the
    /// Keychain. Best effort — UserDefaults stays the source of truth.
    public func saveViewer(_ viewer: PulseViewer?) {
        guard let viewer else {
            delete(account: Self.identityAccount)
            return
        }
        struct IdentityEnvelope: Codable {
            let viewer: PulseViewer
            let token: String?
        }
        let envelope = IdentityEnvelope(viewer: viewer, token: nil)
        guard let data = try? JSONEncoder().encode(envelope) else { return }
        _ = save(data, account: Self.identityAccount)
    }

    /// Reads back the mirrored viewer (nil when absent/corrupt).
    public func loadViewer() -> PulseViewer? {
        guard let data = load(account: Self.identityAccount) else { return nil }
        struct IdentityEnvelope: Codable {
            let viewer: PulseViewer?
            let token: String?
        }
        guard let envelope = try? JSONDecoder().decode(IdentityEnvelope.self, from: data) else { return nil }
        return envelope.viewer
    }

    // ── SecItem plumbing (queue-confined, static) ────────────

    private static func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func saveItem(_ data: Data, account: String) -> Bool {
        let query = baseQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        // Prefer an update when the item exists (idempotent saves).
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecSuccess {
            return true
        }

        var add = query
        add.merge(attributes) { _, new in new }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        if addStatus == errSecSuccess { return true }
        // Race with a parallel add — one retry via update.
        if addStatus == errSecDuplicateItem {
            return SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecSuccess
        }
        return false
    }

    private static func loadItem(account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func deleteItem(account: String) {
        let query = baseQuery(account: account)
        SecItemDelete(query as CFDictionary)
    }
}
