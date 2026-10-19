import Foundation
import CryptoKit

/// Persist before sending; retain on every uncertain failure, including cancellation.
/// Scope includes gateway, owner and canonical instruction. No timed key eviction.
actor PulseWalletOperations {
    static let shared = PulseWalletOperations(directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WalletOperations", isDirectory: true))
    private let directory: URL
    init(directory: URL) { self.directory = directory }
    nonisolated static func gatewayKey(_ url: URL) -> String {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url.absoluteString }
        parts.scheme = parts.scheme?.lowercased()
        parts.host = parts.host?.lowercased()
        if (parts.scheme == "https" && parts.port == 443) || (parts.scheme == "http" && parts.port == 80) { parts.port = nil }
        while parts.path.hasSuffix("/") { parts.path.removeLast() }
        return parts.string ?? url.absoluteString
    }
    private func file(_ intent: [String]) throws -> URL {
        let bytes = Data(intent.map { "\($0.utf8.count):\($0)" }.joined().utf8)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest + ".operation")
    }
    func begin(_ intent: [String]) throws -> String {
        try Task.checkCancellation()
        let path = try file(intent)
        if FileManager.default.fileExists(atPath: path.path) {
            let id = try String(contentsOf: path, encoding: .utf8)
            guard UUID(uuidString: id) != nil else { throw CocoaError(.fileReadCorruptFile) }
            return id
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let id = UUID().uuidString
        try Data(id.utf8).write(to: path, options: .atomic)
        return id
    }
    func finish(_ intent: [String], id: String) throws {
        try Task.checkCancellation()
        let path = try file(intent)
        guard FileManager.default.fileExists(atPath: path.path) else { return }
        guard try String(contentsOf: path, encoding: .utf8) == id else { return }
        try FileManager.default.removeItem(at: path)
    }
}
