import Foundation
import GRDB

/// GRDB (SQLite) local cache — the iOS mirror of Android's Room schema v1.
/// Offline-first: reads come from here, refreshes upsert from the wire.
public final class PulseStore: Sendable {
    private let dbQueue: any DatabaseQueue

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

    public func observeConversations() throws -> DatabasePublishers.Value<[PulseConversation]> {
        try PulseConversationRow.all().observationForAll().mapAll { rows in rows.map { $0.toDomain() } }
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
