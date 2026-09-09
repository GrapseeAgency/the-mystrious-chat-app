import Foundation
import GRDB

/// GRDB (SQLite) local cache — the iOS mirror of Android's Room schema v1.
/// Offline-first: reads come from here, refreshes upsert from the wire.
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
