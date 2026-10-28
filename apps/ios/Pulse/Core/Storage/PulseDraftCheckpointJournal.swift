import Foundation
import CryptoKit

/// Independent, owner-database-scoped recovery files. A SQLite receipt names
/// the UUID consumed by a committed save/send/clear; stale files cannot revive it.
final class PulseDraftCheckpointJournal: @unchecked Sendable {
    struct Snapshot: Codable {
        let scope: String
        let text: String
        let id: UUID
    }
    private final class Locks: @unchecked Sendable {
        let lock = NSLock()
        var values: [String: NSLock] = [:]
        func forPath(_ path: String) -> NSLock {
            lock.lock(); defer { lock.unlock() }
            if let existing = values[path] { return existing }
            let value = NSLock(); values[path] = value; return value
        }
    }
    private static let locks = Locks()
    private let directory: URL?
    private let lock: NSLock
    init(path: String?) {
        directory = path.map { URL(fileURLWithPath: $0 + ".draft-recovery", isDirectory: true) }
        lock = Self.locks.forPath(directory?.path ?? UUID().uuidString)
    }
    private func hash(_ scope: String) -> String {
        SHA256.hash(data: Data(scope.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    func receiptKey(_ scope: String) -> String { "draft-checkpoint-ack:v1|" + hash(scope) }
    private func url(_ scope: String) -> URL? { directory?.appendingPathComponent(hash(scope) + ".json") }
    private func readUnlocked(_ scope: String) throws -> Snapshot? {
        guard let file = url(scope), FileManager.default.fileExists(atPath: file.path) else { return nil }
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: file))
        guard snapshot.scope == scope else { throw CocoaError(.coderReadCorrupt) }
        return snapshot
    }
    func scopes() throws -> [String] {
        lock.lock(); defer { lock.unlock() }
        guard let directory, FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }.map { file in
                let record = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: file))
                guard file.lastPathComponent == hash(record.scope) + ".json" else { throw CocoaError(.coderReadCorrupt) }
                return record.scope
            }
    }
    func read(_ scope: String) throws -> Snapshot? {
        lock.lock(); defer { lock.unlock() }
        return try readUnlocked(scope)
    }
    func write(_ scope: String, text: String) throws -> Snapshot? {
        lock.lock(); defer { lock.unlock() }
        guard var directory, let file = url(scope) else { return nil }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try directory.setResourceValues(resources)
        let snapshot = Snapshot(scope: scope, text: text, id: UUID())
        try JSONEncoder().encode(snapshot).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        let handle = try FileHandle(forWritingTo: file)
        defer { try? handle.close() }
        try handle.synchronize()
        return snapshot
    }
    func remove(_ scope: String, id: UUID) throws {
        lock.lock(); defer { lock.unlock() }
        if try readUnlocked(scope)?.id == id, let file = url(scope) { try FileManager.default.removeItem(at: file) }
    }
}
