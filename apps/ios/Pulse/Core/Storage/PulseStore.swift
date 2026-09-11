import Foundation
import GRDB

/// GRDB (SQLite) local cache — the iOS mirror of Android's Room schema.
/// Offline-first: reads come from here, refreshes upsert from the wire.
///
/// Wave 0 v2 — adds the offline core: `outbox` (queued sends) + `draft`
/// (per-conversation composer state), mirroring Android Room v4 and the web
/// outbox/drafts stores. v1 stays untouched (non-destructive GRDB migrator
/// applies v1 → v2 in order on any database).
/// W1-DATA-B v3 — full-fidelity message cache (mirrors Android Room v5 / spec
/// §3): the message table gains parentId (THREAD ROOT — replyToId keeps the
/// inline-quote id), the media columns, editedAt/deletedAt, reactionsJson
/// (grouped [{emoji,userIds}] parity) and senderColor. `upsert(messages:)`
/// persists ALL of them — the lossy v2 cache is over.
public final class PulseStore: Sendable {
    private let dbQueue: DatabaseQueue

    public init(path: String) throws {
        dbQueue = try DatabaseQueue(path: path)
        try migrator.migrate(dbQueue)
    }

    /// In-memory store (previews/tests).
    public init() throws {
        dbQueue = try DatabaseQueue()
        try migrator.migrate(dbQueue)
    }

    private var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()
        m.registerMigration("v1") { db in
            try db.create(table: "conversation") { t in
                t.column("id", .text).primaryKey()
                t.column("kind", .text).notNull()
                t.column("title", .text).notNull()
                t.column("lastMessagePreview", .text)
                t.column("lastActivityAt", .text)
                t.column("unreadCount", .integer).notNull().defaults(to: 0)
                t.column("isPinned", .boolean).notNull().defaults(to: false)
                t.column("isMuted", .boolean).notNull().defaults(to: false)
                t.column("isArchived", .boolean).notNull().defaults(to: false)
            }
            try db.create(table: "message") { t in
                t.column("id", .text).primaryKey()
                t.column("conversationId", .text).notNull().indexed()
                t.column("authorId", .text).notNull()
                t.column("authorName", .text).notNull()
                t.column("kind", .text).notNull()
                t.column("body", .text).notNull()
                t.column("createdAt", .text).notNull()
                t.column("replyToId", .text)
                t.column("pinnedAt", .text)
            }
            // FTS5 mirror arrives with the offline-search wave (web parity).
        }
        m.registerMigration("v2") { db in
            // Queued sends — web pulse-outbox.ts parity (MAX_QUEUE 50).
            try db.create(table: "outbox") { t in
                t.column("id", .integer).primaryKey(autoincrement: true)
                t.column("conversationId", .text).notNull()
                t.column("clientId", .text).notNull().unique()
                t.column("content", .text).notNull()
                t.column("kind", .text).notNull().defaults(to: "text")
                t.column("createdAt", .text).notNull()
                t.column("attempts", .integer).notNull().defaults(to: 0)
            }
            try db.create(indexOn: "outbox", columns: ["clientId"])
            // Per-conversation composer drafts (web pulse-drafts parity).
            try db.create(table: "draft") { t in
                t.column("conversationId", .text).primaryKey()
                t.column("text", .text).notNull()
                t.column("updatedAt", .text).notNull()
            }
        }
        m.registerMigration("v3") { db in
            // W1-DATA-B — additive ALTERs only (spec §3): threads, media,
            // edit/delete tombstones, grouped reactions, sender color.
            try db.alter(table: "message") { t in
                t.add(column: "parentId", .text)
                t.add(column: "imagePath", .text)
                t.add(column: "audioPath", .text)
                t.add(column: "durationMs", .real)
                t.add(column: "filePath", .text)
                t.add(column: "fileName", .text)
                t.add(column: "fileSize", .integer)
                t.add(column: "editedAt", .text)
                t.add(column: "deletedAt", .text)
                t.add(column: "reactionsJson", .text).notNull().defaults(to: "[]")
                t.add(column: "senderColor", .text)
            }
        }
        return m
    }

    // ── conversation cache ───────────────────────────────────
    public func upsert(conversations: [PulseConversation]) throws {
        try dbQueue.write { db in
            for c in conversations {
                try db.execute(
                    sql: """
                    INSERT INTO conversation (id, kind, title, lastMessagePreview, lastActivityAt,
                                              unreadCount, isPinned, isMuted, isArchived)
                    VALUES (:id, :kind, :title, :preview, :activity, :unread, :pinned, :muted, :archived)
                    ON CONFLICT(id) DO UPDATE SET
                      kind=:kind, title=:title, lastMessagePreview=:preview, lastActivityAt=:activity,
                      unreadCount=:unread, isPinned=:pinned, isMuted=:muted, isArchived=:archived
                    """,
                    arguments: [
                        "id": c.id, "kind": String(describing: c.kind), "title": c.title,
                        "preview": c.lastMessagePreview, "activity": c.lastActivityAt,
                        "unread": c.unreadCount, "pinned": c.isPinned, "muted": c.isMuted,
                        "archived": c.isArchived,
                    ],
                )
            }
        }
    }

    public func observeConversations() -> DatabasePublishers.Value<[PulseConversation]> {
        ValueObservation.tracking { db in
            try PulseConversationRow.fetchAll(db)
        }
        .map { rows in rows.map { $0.toDomain() } }
        .publisher(in: dbQueue)
    }

    /// One-shot sync read of the cached conversations (tests + Chats seed).
    public func cachedConversations() throws -> [PulseConversation] {
        try dbQueue.read { db in
            try PulseConversationRow.fetchAll(db).map { $0.toDomain() }
        }
    }

    // ── message cache (N3-b — room offline-first seed; v3 full fidelity) ──
    public func upsert(messages: [WireChatMessage]) throws {
        try dbQueue.write { db in
            for m in messages {
                try db.execute(
                    sql: """
                    INSERT INTO message (id, conversationId, authorId, authorName, kind, body,
                                         createdAt, replyToId, pinnedAt, parentId, imagePath,
                                         audioPath, durationMs, filePath, fileName, fileSize,
                                         editedAt, deletedAt, reactionsJson, senderColor)
                    VALUES (:id, :conversationId, :authorId, :authorName, :kind, :body,
                            :createdAt, :replyToId, :pinnedAt, :parentId, :imagePath,
                            :audioPath, :durationMs, :filePath, :fileName, :fileSize,
                            :editedAt, :deletedAt, :reactionsJson, :senderColor)
                    ON CONFLICT(id) DO UPDATE SET
                      authorName=:authorName, kind=:kind, body=:body, pinnedAt=:pinnedAt,
                      parentId=:parentId, imagePath=:imagePath, audioPath=:audioPath,
                      durationMs=:durationMs, filePath=:filePath, fileName=:fileName,
                      fileSize=:fileSize, editedAt=:editedAt, deletedAt=:deletedAt,
                      reactionsJson=:reactionsJson, senderColor=:senderColor
                    """,
                    arguments: [
                        "id": m.id, "conversationId": m.conversationId, "authorId": m.senderId,
                        "authorName": m.sender?.name ?? "", "kind": m.kind, "body": m.content,
                        "createdAt": m.createdAt, "replyToId": m.replyTo?.id,
                        "pinnedAt": m.pinnedAt, "parentId": m.parentId,
                        "imagePath": m.imagePath, "audioPath": m.audioPath,
                        "durationMs": m.durationMs, "filePath": m.filePath,
                        "fileName": m.fileName, "fileSize": m.fileSize,
                        "editedAt": m.editedAt, "deletedAt": m.deletedAt,
                        "reactionsJson": Self.reactionsJsonData(m.reactions),
                        "senderColor": m.sender?.color,
                    ],
                )
            }
        }
    }

    public func deleteMessage(id: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM message WHERE id = :id", arguments: ["id": id])
        }
    }

    public func messages(conversationId: String, limit: Int = 300) throws -> [WireChatMessage] {
        let rows = try dbQueue.read { db in
            try Row.fetchAll(
                db,
                sql: "SELECT * FROM message WHERE conversationId = :cid ORDER BY createdAt ASC LIMIT :limit",
                arguments: ["cid": conversationId, "limit": limit],
            )
        }
        return rows.compactMap(Self.messageRow(from:))
    }

    /// One cached row by id (jump anchoring: a search hit that lands on a
    /// thread reply needs its parentId to target the river). nil = not cached.
    public func message(id: String) throws -> WireChatMessage? {
        try dbQueue.read { db in
            try Row.fetchOne(
                db,
                sql: "SELECT * FROM message WHERE id = :id",
                arguments: ["id": id],
            )
            .flatMap(Self.messageRow(from:))
        }
    }

    /// Thread replies for one root, oldest → newest (v3 parentId column —
    /// replyToId keeps the inline-quote id and is never mixed in).
    public func messages(threadRootId: String, limit: Int = 300) throws -> [WireChatMessage] {
        let rows = try dbQueue.read { db in
            try Row.fetchAll(
                db,
                sql: "SELECT * FROM message WHERE parentId = :root ORDER BY createdAt ASC LIMIT :limit",
                arguments: ["root": threadRootId, "limit": limit],
            )
        }
        return rows.compactMap(Self.messageRow(from:))
    }

    /// Reply counts per cached thread root — one query powers every river
    /// "N replies" chip (no per-thread COUNT round-trips).
    public func threadReplyCounts() throws -> [String: Int] {
        try dbQueue.read { db in
            var counts: [String: Int] = [:]
            let rows = try Row.fetchAll(
                db,
                sql: "SELECT parentId, COUNT(*) AS replyCount FROM message WHERE parentId IS NOT NULL GROUP BY parentId",
            )
            for row in rows {
                if let root: String = row["parentId"] {
                    let count: Int = row["replyCount"] ?? 0
                    counts[root] = count
                }
            }
            return counts
        }
    }

    /// Rehydrate a wire-ish message from a cached row (v3 full fidelity:
    /// thread root, media, edit/delete stamps and grouped reactions all
    /// round-trip; only the sender OBJECT is reduced to name + color).
    private static func messageRow(from row: Row) -> WireChatMessage? {
        guard let id: String = row["id"],
              let conversationId: String = row["conversationId"],
              let authorId: String = row["authorId"],
              let kind: String = row["kind"],
              let body: String = row["body"],
              let createdAt: String = row["createdAt"] else { return nil }
        let authorName: String? = row["authorName"]
        let pinnedAt: String? = row["pinnedAt"]
        let parentId: String? = row["parentId"]
        let imagePath: String? = row["imagePath"]
        let audioPath: String? = row["audioPath"]
        let durationMs: Double? = row["durationMs"]
        let filePath: String? = row["filePath"]
        let fileName: String? = row["fileName"]
        let fileSize: Int? = row["fileSize"]
        let editedAt: String? = row["editedAt"]
        let deletedAt: String? = row["deletedAt"]
        let senderColor: String? = row["senderColor"]
        let decodedReactions = Self.reactions(fromJson: row["reactionsJson"])
        return WireChatMessage(
            id: id, conversationId: conversationId, senderId: authorId,
            content: body, kind: kind, createdAt: createdAt,
            editedAt: editedAt, deletedAt: deletedAt,
            sender: authorName.map {
                WireSender(id: authorId, name: $0, username: nil, color: senderColor, avatar: nil)
            },
            reactions: decodedReactions.isEmpty ? nil : decodedReactions, replyTo: nil, parentId: parentId,
            imagePath: imagePath, audioPath: audioPath, durationMs: durationMs,
            filePath: filePath, fileName: fileName, fileSize: fileSize,
            pinnedAt: pinnedAt, viewOnce: nil, anon: nil, anonAlias: nil,
        )
    }

    // ── reactionsJson codec (v3 cache column ⇄ grouped wire reactions) ──

    /// [WireReactionGroup] → column text ("[]" for nil/empty — the v3 column
    /// default keeps NOT NULL satisfied for rows written before a reaction).
    static func reactionsJsonData(_ groups: [WireReactionGroup]?) -> String {
        guard let groups, !groups.isEmpty,
              let data = try? JSONEncoder().encode(groups) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    /// Column text → [WireReactionGroup]; anything unreadable is an empty
    /// list (a corrupt cache row must never crash the read path).
    static func reactions(fromJson json: String?) -> [WireReactionGroup] {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([WireReactionGroup].self, from: data)) ?? []
    }

    // ── outbox (Wave 0 — queued sends, web pulse-outbox parity) ──

    /// Appends one queued send (clientId dedupes; UNIQUE constraint drops
    /// double-enqueues exactly like the web store's `some(q.clientId === …)`).
    public func appendOutbox(conversationId: String, clientId: String, content: String, kind: String = "text") throws {
        let row = OutboxRow(
            id: nil,
            conversationId: conversationId,
            clientId: clientId,
            content: content,
            kind: kind,
            createdAt: PulseOutboxClock.now(),
            attempts: 0,
        )
        try dbQueue.write { db in
            try row.insert(db)
        }
    }

    /// Queue snapshot — FIFO drain order (oldest first).
    public func outboxAll() throws -> [OutboxRow] {
        try dbQueue.read { db in
            try OutboxRow.fetchAll(db, sql: "SELECT * FROM outbox ORDER BY id ASC")
        }
    }

    public func deleteOutbox(id: Int64) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM outbox WHERE id = :id", arguments: ["id": id])
        }
    }

    public func deleteOutbox(clientId: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM outbox WHERE clientId = :cid", arguments: ["cid": clientId])
        }
    }

    public func bumpOutboxAttempts(id: Int64) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "UPDATE outbox SET attempts = attempts + 1 WHERE id = :id", arguments: ["id": id])
        }
    }

    public func countOutbox() -> Int {
        (try? dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM outbox")
        }) ?? 0
    }

    // ── drafts (Wave 0 — composer persistence, web pulse-drafts parity) ──

    /// Upserts the composer draft. Empty text clears the row (a blank local
    /// draft must never shadow the server's myDraft).
    public func saveDraft(conversationId: String, text: String) throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            try deleteDraft(conversationId: conversationId)
            return
        }
        try dbQueue.write { db in
            try db.execute(
                sql: """
                INSERT INTO draft (conversationId, text, updatedAt)
                VALUES (:cid, :text, :updatedAt)
                ON CONFLICT(conversationId) DO UPDATE SET text=:text, updatedAt=:updatedAt
                """,
                arguments: ["cid": conversationId, "text": text, "updatedAt": PulseOutboxClock.now()],
            )
        }
    }

    /// Local draft for a conversation; nil when absent/blank.
    public func draft(conversationId: String) -> String? {
        let text: String? = try? dbQueue.read { db in
            try String.fetchOne(
                db,
                sql: "SELECT text FROM draft WHERE conversationId = :cid",
                arguments: ["cid": conversationId],
            )
        }
        guard let text, !text.isEmpty else { return nil }
        return text
    }

    /// Every local draft (chats-list preview rehydration).
    public func allDrafts() -> [String: String] {
        let rows: [(String, String)]? = try? dbQueue.read { db in
            let pairs = try Row.fetchAll(db, sql: "SELECT conversationId, text FROM draft")
            return pairs.compactMap { row in
                guard let cid: String = row["conversationId"],
                      let text: String = row["text"],
                      !text.isEmpty else { return nil }
                return (cid, text)
            }
        }
        guard let rows else { return [:] }
        return Dictionary(rows, uniquingKeysWith: { _, later in later })
    }

    public func deleteDraft(conversationId: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM draft WHERE conversationId = :cid", arguments: ["cid": conversationId])
        }
    }
}

/// GRDB row record for the conversation cache.
struct PulseConversationRow: Codable, FetchableRecord, PersistableRecord, TableRecord {
    static let databaseTableName = "conversation"

    var id: String
    var kind: String
    var title: String
    var lastMessagePreview: String?
    var lastActivityAt: String?
    var unreadCount: Int
    var isPinned: Bool
    var isMuted: Bool
    var isArchived: Bool

    func toDomain() -> PulseConversation {
        PulseConversation(
            id: id,
            kind: PulseConversation.Kind(rawValue: kind) ?? .DM,
            title: title,
            avatar: nil,
            lastMessagePreview: lastMessagePreview,
            lastActivityAt: lastActivityAt,
            unreadCount: unreadCount,
            isPinned: isPinned,
            isMuted: isMuted,
            isArchived: isArchived,
        )
    }
}

/// One held-for-later outgoing message (web QueuedMessage parity).
public struct OutboxRow: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {
    public static let databaseTableName = "outbox"

    public var id: Int64?
    public var conversationId: String
    public var clientId: String
    public var content: String
    public var kind: String
    public var createdAt: String
    public var attempts: Int

    public init(id: Int64?, conversationId: String, clientId: String, content: String,
                kind: String, createdAt: String, attempts: Int) {
        self.id = id
        self.conversationId = conversationId
        self.clientId = clientId
        self.content = content
        self.kind = kind
        self.createdAt = createdAt
        self.attempts = attempts
    }
}

/// One per-conversation composer draft (web draft store parity).
public struct DraftRow: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {
    public static let databaseTableName = "draft"

    public var conversationId: String
    public var text: String
    public var updatedAt: String

    public init(conversationId: String, text: String, updatedAt: String) {
        self.conversationId = conversationId
        self.text = text
        self.updatedAt = updatedAt
    }
}

/// Shared timestamp helper for outbox/draft rows (ISO-8601, wire-compatible).
enum PulseOutboxClock {
    private static let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func now() -> String {
        formatter.string(from: Date())
    }
}
