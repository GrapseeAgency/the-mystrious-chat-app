import Foundation
import GRDB

/// GRDB (SQLite) local cache — the iOS mirror of Android's Room schema.
/// Offline-first: reads come from here, refreshes upsert from the wire.
///
/// Wave 0 v2 — adds the offline core: `outbox` (queued sends) + `draft`
/// (per-conversation composer state), mirroring Android Room v4 and the web
/// outbox/drafts stores. v1 stays untouched (non-destructive GRDB migrator
/// applies v1 → v2 in order on any database).
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

    // ── message cache (N3-b — room offline-first seed) ──────
    public func upsert(messages: [WireChatMessage]) throws {
        try dbQueue.write { db in
            for m in messages {
                try db.execute(
                    sql: """
                    INSERT INTO message (id, conversationId, authorId, authorName, kind, body,
                                         createdAt, replyToId, pinnedAt)
                    VALUES (:id, :conversationId, :authorId, :authorName, :kind, :body,
                            :createdAt, :replyToId, :pinnedAt)
                    ON CONFLICT(id) DO UPDATE SET
                      authorName=:authorName, kind=:kind, body=:body,
                      pinnedAt=:pinnedAt
                    """,
                    arguments: [
                        "id": m.id, "conversationId": m.conversationId, "authorId": m.senderId,
                        "authorName": m.sender?.name ?? "", "kind": m.kind, "body": m.content,
                        "createdAt": m.createdAt, "replyToId": m.replyTo?.id ?? m.parentId,
                        "pinnedAt": m.pinnedAt,
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

    /// Rehydrate a wire-ish message from a cached row (sender/reactions are
    /// not cached; bubbles fall back to authorName + member color).
    private static func messageRow(from row: Row) -> WireChatMessage? {
        guard let id: String = row["id"],
              let conversationId: String = row["conversationId"],
              let authorId: String = row["authorId"],
              let kind: String = row["kind"],
              let body: String = row["body"],
              let createdAt: String = row["createdAt"] else { return nil }
        let authorName: String? = row["authorName"]
        let replyToId: String? = row["replyToId"]
        let pinnedAt: String? = row["pinnedAt"]
        return WireChatMessage(
            id: id, conversationId: conversationId, senderId: authorId,
            content: body, kind: kind, createdAt: createdAt,
            editedAt: nil, deletedAt: nil,
            sender: authorName.map { WireSender(id: authorId, name: $0, username: nil, color: nil, avatar: nil) },
            reactions: nil, replyTo: nil, parentId: replyToId,
            imagePath: nil, audioPath: nil, durationMs: nil,
            filePath: nil, fileName: nil, pinnedAt: pinnedAt,
            viewOnce: nil, anon: nil, anonAlias: nil,
        )
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
