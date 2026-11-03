import Foundation
import GRDB

/// Section 14 item 10 - safe recovery of LEGACY unowned data on iOS.
///
/// The pre-account-split `pulse.sqlite` was deliberately never adopted
/// ("its rows have no trustworthy account ownership"). This gate makes the
/// decision explicit, provable and conservative:
///
///  - the legacy file is opened READ-ONLY and only ever scanned; it is never
///    written or deleted, and the import runs over a throwaway COPY.
///  - old legacy schemas (before conversation.wireJson, v11) carry NO
///    membership proof at all: rows exist but ownership is unprovable, so
///    the gate REFUSES and retains (it never guesses an owner).
///  - when the schema does carry wireJson envelopes, the data may be adopted
///    ONLY when EVERY conversation lists this owner in its members and every
///    cached message was authored by a member of its own conversation.
///  - queued sends are NEVER imported (the legacy outbox schema carries no
///    sender proof); drafts import only for proven conversations.
///  - the verdict (with the file size+mtime it was rendered for) is stamped
///    into the account's own wave7Cache, so restarts never re-decide.
enum PulseLegacyDataRecovery {

    enum Verdict: Equatable {
        /// No legacy file exists.
        case absent
        /// Unprovable or unreadable - nothing imported, file retained.
        case refuse(reason: String)
        /// Proven ownable; the listed tables were imported (outbox always 0).
        case adopt(imported: [String: Int])
    }

    /// A bounded read of the legacy file (every nil = unreadable).
    struct Scan {
        var conversations: Int? = 0
        var messages: Int? = 0
        var outbox: Int? = 0
        var drafts: Int? = 0
        /// True only when conversation.wireJson exists (v11+ legacy).
        var memberProof: Bool? = false
        /// A conversation without this owner in its members.
        var foreignConversation: Bool? = false
        /// A message authored outside its conversation's members.
        var orphanAuthor: Bool? = false
    }

    /// The pure ownership verdict (unit-tested without any database).
    static func verdict(owner: String, scan: Scan) -> Verdict {
        guard let conversations = scan.conversations,
              let messages = scan.messages,
              let outbox = scan.outbox,
              let drafts = scan.drafts else {
            return .refuse(reason: "legacy database unreadable")
        }
        if conversations == 0 && messages == 0 && outbox == 0 && drafts == 0 {
            return .adopt(imported: [:])
        }
        // Old schemas have no membership proof on conversations: ownership of
        // the rows is unprovable, so the data stays unadopted (retained).
        guard scan.memberProof == true else {
            return .refuse(reason: "legacy rows have no provable account ownership")
        }
        if scan.foreignConversation == true {
            return .refuse(reason: "a conversation does not list this owner")
        }
        if scan.orphanAuthor == true {
            return .refuse(reason: "a cached message was authored outside its conversation members")
        }
        // Outbox rows are NEVER imported: the legacy outbox schema has no
        // sender identity, so queued sends cannot be proven owner-authored.
        return .adopt(imported: ["conversation": conversations, "message": messages, "draft": drafts])
    }
}

extension PulseStore {

    static func legacyDatabasePath() -> URL? {
        guard let directory = try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: false
        ) else { return nil }
        return directory.appendingPathComponent("pulse.sqlite")
    }

    private static let legacyStampKey = "legacy-recovery:v1"

    /// Renders the legacy verdict for this account; imports only on an
    /// `.adopt`. `legacyPath` injectable for tests (nil = the real path).
    func legacyRecovery(viewerId: String, legacyPath: URL? = nil) -> PulseLegacyDataRecovery.Verdict {
        let path = legacyPath ?? Self.legacyDatabasePath()
        guard let path, FileManager.default.fileExists(atPath: path.path) else {
            return .absent
        }
        let attributes = try? FileManager.default.attributesOfItem(atPath: path.path)
        let size = (attributes?[.size] as? NSNumber)?.intValue
        let mtime = (attributes?[.modificationDate] as? Date).map { Int($0.timeIntervalSince1970) }
        if let stamp = readLegacyStamp(),
           let size, let mtime,
           stamp.contains("\"size\":\(size)"), stamp.contains("\"mtime\":\(mtime)") {
            return Self.decodeStampVerdict(stamp)
        }

        let scan = Self.scanLegacy(at: path, owner: viewerId)
        var verdict = PulseLegacyDataRecovery.verdict(owner: viewerId, scan: scan)
        if case .adopt(let imported) = verdict, !imported.isEmpty {
            do {
                let counts = try importLegacyRows(copyOf: path)
                verdict = .adopt(imported: counts)
            } catch {
                verdict = .refuse(reason: "import refused: \(error.localizedDescription)")
            }
        }
        writeLegacyStamp(size: size ?? -1, mtime: mtime ?? -1, verdict: verdict)
        return verdict
    }

    // MARK: - scan (read-only)

    private static func scanLegacy(at path: URL, owner: String) -> PulseLegacyDataRecovery.Scan {
        var scan = PulseLegacyDataRecovery.Scan()
        var configuration = Configuration()
        configuration.readonly = true
        guard let queue = try? DatabaseQueue(path: path.path, configuration: configuration) else {
            scan.conversations = nil
            return scan
        }
        do {
            let tables = try queue.read { db in
                try Set(String.fetchAll(db, sql: "SELECT name FROM sqlite_master WHERE type='table'"))
            }
            guard tables.contains("conversation") && tables.contains("message") else {
                scan.conversations = nil
                return scan
            }
            scan.conversations = try queue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM conversation") ?? 0 }
            scan.messages = try queue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM message") ?? 0 }
            scan.outbox = tables.contains("outbox") ? try queue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM outbox") ?? 0 } : 0
            scan.drafts = tables.contains("draft") ? try queue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM draft") ?? 0 } : 0
            let conversationColumns = try queue.read { db in
                try Set(String.fetchAll(db, sql: "PRAGMA table_info(conversation)"))
            }
            guard let total = scan.conversations, total > 0, conversationColumns.contains("wireJson") else { return scan }
            scan.memberProof = true
            // Bounded membership proof: every conversation must list the owner.
            var memberIdsByConversation: [String: Set<String>] = [:]
            let rows = try queue.read { db in
                try Row.fetchAll(db, sql: "SELECT id, wireJson FROM conversation ORDER BY id LIMIT 501")
            }
            for row in rows {
                guard let id: String = row["id"], let wire: String = row["wireJson"],
                      let summary = try? JSONDecoder().decode(WireConversationSummary.self, from: Data(wire.utf8)) else {
                    scan.foreignConversation = true
                    continue
                }
                let members = Set(summary.members.map { $0.id })
                memberIdsByConversation[id] = members
                if !members.contains(owner) { scan.foreignConversation = true }
            }
            // Bounded author proof: every sampled message must come from a
            // member of its own conversation.
            let sampled = try queue.read { db in
                try Row.fetchAll(db, sql: "SELECT conversationId, authorId FROM message ORDER BY id LIMIT 501")
            }
            for row in sampled {
                guard let conversationId: String = row["conversationId"], let authorId: String = row["authorId"] else { continue }
                guard let members = memberIdsByConversation[conversationId] else {
                    // the conversation's proof was unparseable or beyond the bound
                    scan.orphanAuthor = true
                    continue
                }
                if !members.contains(authorId) { scan.orphanAuthor = true }
            }
            // More conversations than the bound = unprovable, not "proofed".
            if scan.conversations ?? 0 > 501 { scan.foreignConversation = true }
            if scan.messages ?? 0 > 501 { scan.orphanAuthor = true }
        } catch {
            scan.conversations = nil
        }
        return scan
    }

    // MARK: - import (over a throwaway copy; the original is never attached)

    private func importLegacyRows(copyOf path: URL) throws -> [String: Int] {
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("legacy-copy-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: temp) }
        try FileManager.default.copyItem(at: path, to: temp)
        // Bring the copy to the head schema on ITS OWN connection first.
        try Self.buildMigrator().migrate(try DatabaseQueue(path: temp.path))

        return try withLegacyAttached(copyAt: temp) { db in
            var counts: [String: Int] = [:]
            try db.inSavepoint {
                for table in ["conversation", "message", "draft"] {
                    let cols = try Self.commonColumns(db, table: table)
                    guard !cols.isEmpty else { throw NSError(domain: "PulseLegacy", code: 1, userInfo: [NSLocalizedDescriptionKey: "no importable columns for \(table)"]) }
                    let list = cols.joined(separator: ",")
                    try db.execute(sql: "INSERT OR IGNORE INTO `\(table)` (\(list)) SELECT \(list) FROM legacy_src.`\(table)`")
                    counts[table] = try Int.fetchOne(db, sql: "SELECT changes()") ?? 0
                }
                return .commit
            }
            return counts
        }
    }

    /// ATTACH runs OUTSIDE a transaction (SQLite forbids ATTACH inside one);
    /// the import body runs inside ONE savepoint so a mid-import fault leaves
    /// no partial copy behind.
    private func withLegacyAttached<T>(copyAt url: URL, _ body: (Database) throws -> T) throws -> T {
        try dbQueue.writeWithoutTransaction { db in
            try db.execute(sql: "ATTACH DATABASE ? AS legacy_src", arguments: [url.path])
        }
        do {
            let result = try dbQueue.writeWithoutTransaction { db in try body(db) }
            try? dbQueue.writeWithoutTransaction { db in try db.execute(sql: "DETACH DATABASE legacy_src") }
            return result
        } catch {
            try? dbQueue.writeWithoutTransaction { db in try db.execute(sql: "DETACH DATABASE legacy_src") }
            throw error
        }
    }

    private static func commonColumns(_ db: Database, table: String) throws -> [String] {
        let target = try Set(String.fetchAll(db, sql: "PRAGMA table_info(`\(table)`)"))
        let source = try Set(String.fetchAll(db, sql: "PRAGMA legacy_src.table_info(`\(table)`)"))
        let sourceOrder = try String.fetchAll(db, sql: "PRAGMA legacy_src.table_info(`\(table)`)")
        return sourceOrder.filter { target.contains($0) }
    }

    // MARK: - verdict stamp (idempotence)

    private func readLegacyStamp() -> String? {
        try? dbQueue.read { db in
            try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [Self.legacyStampKey])
        }
    }

    private func writeLegacyStamp(size: Int, mtime: Int, verdict: PulseLegacyDataRecovery.Verdict) {
        let kind: String
        var reason: String?
        switch verdict {
        case .absent: kind = "absent"
        case .refuse(let text): kind = "refuse"; reason = text
        case .adopt: kind = "adopt"
        }
        var json = "{\"v\":1,\"size\":\(size),\"mtime\":\(mtime),\"verdict\":\"\(kind)\""
        if let reason {
            let safe = String(reason.prefix(120)).replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
            json += ",\"reason\":\"\(safe)\""
        }
        json += "}"
        try? dbQueue.write { db in
            try db.execute(
                sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt",
                arguments: [Self.legacyStampKey, json, Date().timeIntervalSince1970],
            )
        }
    }

    private static func decodeStampVerdict(_ stamp: String) -> PulseLegacyDataRecovery.Verdict {
        if stamp.contains("\"verdict\":\"refuse\"") {
            if let range = stamp.range(of: "\"reason\":\"([^\"]*)\"", options: .regularExpression) {
                let reason = String(stamp[range]).replacingOccurrences(of: "\"reason\":\"", with: "").dropLast(1)
                return .refuse(reason: String(reason))
            }
            return .refuse(reason: "stamped refusal")
        }
        if stamp.contains("\"verdict\":\"absent\"") { return .absent }
        return .adopt(imported: [:])
    }
}
