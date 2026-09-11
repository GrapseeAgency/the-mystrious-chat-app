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
/// W2-DATA-B v4 — Wave 2 message depth (spec §2.2, mirrors Android Room v6):
/// the message table gains the view-once burn stamp (viewedAt), the voice
/// transcript cache (transcript/transcribedAt), pollJson + linkPreviewJson
/// objects and topicId; NEW tables topics (Zulip-style sub-streams) and
/// savedMessages (saved-library mirror). All additive, same non-destructive
/// migrator — v1 stays untouched.
/// W3-b v5 — Wave 3 native calls: `callLogCache` (GET /api/calls mirror,
/// server-capped at 50 rows) and `callLogQueue` (offline queue for the
/// single-writer POST /api/calls — network-failed rows flush on socket
/// reconnect / app start, mirroring the PulseOutboxEngine trigger style).
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
        m.registerMigration("v4") { db in
            // W2-DATA-B — additive ALTERs only (spec §2.2): view-once burn
            // stamp, voice-transcript cache, poll + link-preview JSON cards,
            // topic filing. pollJson/linkPreviewJson are nullable (nil = no
            // card), unlike the NOT-NULL reactionsJson v3 default.
            try db.alter(table: "message") { t in
                t.add(column: "viewedAt", .text)
                t.add(column: "transcript", .text)
                t.add(column: "transcribedAt", .text)
                t.add(column: "pollJson", .text)
                t.add(column: "linkPreviewJson", .text)
                t.add(column: "topicId", .text)
            }
            // Zulip-style topic rail (General is NOT a row — unfiltered room).
            try db.create(table: "topics") { t in
                t.column("id", .text).primaryKey()
                t.column("conversationId", .text).notNull().indexed()
                t.column("name", .text).notNull()
                t.column("emoji", .text).notNull().defaults(to: "💬")
                t.column("lastMessageAt", .text)
                t.column("messageCount", .integer).notNull().defaults(to: 0)
            }
            // Saved-library mirror (GET /api/users/{id}/saved, cap 100) —
            // the message row itself lives in `message` via upsert(savedItems:).
            try db.create(table: "savedMessages") { t in
                t.column("messageId", .text).primaryKey()
                t.column("conversationId", .text).notNull()
                t.column("savedAt", .text).notNull()
            }
            try db.create(indexOn: "savedMessages", columns: ["conversationId"])
        }
        m.registerMigration("v5") { db in
            // W3-b — Wave 3 call history cache. Peer identity is denormalized
            // (the server resolves the OTHER party per viewer; the cache must
            // render offline exactly what GET /api/calls renders online).
            try db.create(table: "callLogCache") { t in
                t.column("id", .text).primaryKey()
                t.column("conversationId", .text).notNull()
                t.column("callerId", .text).notNull()
                t.column("calleeId", .text).notNull()
                t.column("kind", .text).notNull().defaults(to: "voice")
                t.column("status", .text).notNull()
                t.column("durationSec", .integer).notNull().defaults(to: 0)
                t.column("startedAt", .text).notNull()
                t.column("outgoing", .boolean).notNull().defaults(to: false)
                t.column("peerId", .text).notNull().defaults(to: "")
                t.column("peerName", .text).notNull().defaults(to: "Unknown")
                t.column("peerUsername", .text)
                t.column("peerColor", .text)
                t.column("peerAvatar", .text)
            }
            try db.create(indexOn: "callLogCache", columns: ["startedAt"])
            // Offline queue for the single-writer POST /api/calls (payloadJson
            // UNIQUE dedupes double-enqueues exactly like outbox.clientId).
            try db.create(table: "callLogQueue") { t in
                t.column("id", .integer).primaryKey(autoincrement: true)
                t.column("payloadJson", .text).notNull().unique()
                t.column("attempts", .integer).notNull().defaults(to: 0)
                t.column("createdAt", .text).notNull()
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

    // ── message cache (N3-b — room offline-first seed; v3/v4 full fidelity) ──
    public func upsert(messages: [WireChatMessage]) throws {
        try dbQueue.write { db in
            for m in messages {
                try Self.writeMessage(m, db: db)
            }
        }
    }

    /// One message row → message table (INSERT … ON CONFLICT full overwrite:
    /// a fresh envelope row is AUTHORITATIVE — spec §0 — so every column,
    /// v1 through v4, lands in both the INSERT and the conflict SET clause).
    /// Shared by the bulk cache upsert and the saved-library sync.
    private static func writeMessage(_ m: WireChatMessage, db: Database) throws {
        try db.execute(
            sql: """
            INSERT INTO message (id, conversationId, authorId, authorName, kind, body,
                                 createdAt, replyToId, pinnedAt, parentId, imagePath,
                                 audioPath, durationMs, filePath, fileName, fileSize,
                                 editedAt, deletedAt, reactionsJson, senderColor,
                                 viewedAt, transcript, transcribedAt, pollJson,
                                 linkPreviewJson, topicId)
            VALUES (:id, :conversationId, :authorId, :authorName, :kind, :body,
                    :createdAt, :replyToId, :pinnedAt, :parentId, :imagePath,
                    :audioPath, :durationMs, :filePath, :fileName, :fileSize,
                    :editedAt, :deletedAt, :reactionsJson, :senderColor,
                    :viewedAt, :transcript, :transcribedAt, :pollJson,
                    :linkPreviewJson, :topicId)
            ON CONFLICT(id) DO UPDATE SET
              authorName=:authorName, kind=:kind, body=:body, pinnedAt=:pinnedAt,
              parentId=:parentId, imagePath=:imagePath, audioPath=:audioPath,
              durationMs=:durationMs, filePath=:filePath, fileName=:fileName,
              fileSize=:fileSize, editedAt=:editedAt, deletedAt=:deletedAt,
              reactionsJson=:reactionsJson, senderColor=:senderColor,
              viewedAt=:viewedAt, transcript=:transcript, transcribedAt=:transcribedAt,
              pollJson=:pollJson, linkPreviewJson=:linkPreviewJson, topicId=:topicId
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
                "viewedAt": m.viewedAt, "transcript": m.transcript,
                "transcribedAt": m.transcribedAt,
                "pollJson": Self.pollJsonData(m.poll),
                "linkPreviewJson": Self.linkPreviewJsonData(m.linkPreview),
                "topicId": m.topicId,
            ],
        )
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
    /// W2-DATA-B v4 — poll/linkPreview JSON cards, the burn stamp,
    /// transcript pair and topicId rebuild too (viewedBy/linkUrl are NOT
    /// cached columns; they arrive again with the next wire refresh).
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
        let viewedAt: String? = row["viewedAt"]
        let transcript: String? = row["transcript"]
        let transcribedAt: String? = row["transcribedAt"]
        let topicId: String? = row["topicId"]
        let decodedPoll = Self.poll(fromJson: row["pollJson"])
        let decodedLinkPreview = Self.linkPreview(fromJson: row["linkPreviewJson"])
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
            viewedAt: viewedAt, viewedBy: nil, transcript: transcript,
            transcribedAt: transcribedAt, topicId: topicId, linkUrl: nil,
            linkPreview: decodedLinkPreview, poll: decodedPoll,
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

    // ── poll/linkPreviewJson codecs (v4 cache columns ⇄ wire cards) ──

    /// WirePoll → column text; nil poll → NULL (the v4 column is nullable,
    /// unlike the NOT-NULL reactionsJson v3 default — no card, no bytes).
    static func pollJsonData(_ poll: WirePoll?) -> String? {
        guard let poll, let data = try? JSONEncoder().encode(poll) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Column text → WirePoll; anything unreadable is nil (a corrupt cache
    /// row must never crash the read path).
    static func poll(fromJson json: String?) -> WirePoll? {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(WirePoll.self, from: data)
    }

    /// WireLinkPreview → column text; nil preview → NULL.
    static func linkPreviewJsonData(_ preview: WireLinkPreview?) -> String? {
        guard let preview, let data = try? JSONEncoder().encode(preview) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Column text → WireLinkPreview; corrupt rows degrade to nil.
    static func linkPreview(fromJson json: String?) -> WireLinkPreview? {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(WireLinkPreview.self, from: data)
    }

    // ── topics cache (W2-DATA-B — Zulip-style sub-streams) ──

    /// Server topics page → cache, one atomic transaction: prune rows the
    /// server no longer returns (a topic deleted by another member must not
    /// haunt the rail), then upsert the page. General is never a row.
    public func upsert(topics: [WireTopic], conversationId: String) throws {
        try dbQueue.write { db in
            let keepIds = Set(topics.map(\.id))
            let existing = try String.fetchAll(
                db,
                sql: "SELECT id FROM topics WHERE conversationId = :cid",
                arguments: ["cid": conversationId],
            )
            for staleId in existing where !keepIds.contains(staleId) {
                try db.execute(sql: "DELETE FROM topics WHERE id = :id", arguments: ["id": staleId])
            }
            for topic in topics {
                try db.execute(
                    sql: """
                    INSERT INTO topics (id, conversationId, name, emoji, lastMessageAt, messageCount)
                    VALUES (:id, :cid, :name, :emoji, :lastMessageAt, :messageCount)
                    ON CONFLICT(id) DO UPDATE SET
                      conversationId=:cid, name=:name, emoji=:emoji,
                      lastMessageAt=:lastMessageAt, messageCount=:messageCount
                    """,
                    arguments: [
                        "id": topic.id, "cid": conversationId, "name": topic.name,
                        "emoji": topic.emoji ?? "💬", "lastMessageAt": topic.lastMessageAt,
                        "messageCount": topic.messageCount ?? 0,
                    ],
                )
            }
        }
    }

    /// Cached topics for one room, newest activity first (server ORDER BY).
    public func topics(conversationId: String) throws -> [WireTopic] {
        try dbQueue.read { db in
            let rows = try Row.fetchAll(
                db,
                sql: "SELECT * FROM topics WHERE conversationId = :cid ORDER BY lastMessageAt DESC",
                arguments: ["cid": conversationId],
            )
            return rows.compactMap(Self.topicRow(from:))
        }
    }

    private static func topicRow(from row: Row) -> WireTopic? {
        guard let id: String = row["id"],
              let name: String = row["name"] else { return nil }
        let emoji: String? = row["emoji"]
        let lastMessageAt: String? = row["lastMessageAt"]
        let messageCount: Int? = row["messageCount"]
        return WireTopic(id: id, name: name, emoji: emoji, lastMessageAt: lastMessageAt, messageCount: messageCount)
    }

    // ── saved library cache (W2-DATA-B — GET /users/{id}/saved mirror) ──

    /// Saved-library sync: upserts every item's message row (full fidelity,
    /// shared writer) AND its savedMessages marker (savedAt ISO from wire).
    public func upsert(savedItems: [WireSavedItem]) throws {
        try dbQueue.write { db in
            for item in savedItems {
                try Self.writeMessage(item.message, db: db)
                try db.execute(
                    sql: """
                    INSERT INTO savedMessages (messageId, conversationId, savedAt)
                    VALUES (:mid, :cid, :savedAt)
                    ON CONFLICT(messageId) DO UPDATE SET
                      conversationId=:cid, savedAt=:savedAt
                    """,
                    arguments: [
                        "mid": item.message.id, "cid": item.message.conversationId,
                        "savedAt": item.savedAt,
                    ],
                )
            }
        }
    }

    /// Keeps only the given saved rows — paired with upsert(savedItems:) to
    /// make a full server-list refresh also prune what was unsaved elsewhere.
    public func replaceSaved(messageIds: [String]) throws {
        try dbQueue.write { db in
            let keepIds = Set(messageIds)
            let existing = try String.fetchAll(db, sql: "SELECT messageId FROM savedMessages")
            for staleId in existing where !keepIds.contains(staleId) {
                try db.execute(sql: "DELETE FROM savedMessages WHERE messageId = :id", arguments: ["id": staleId])
            }
        }
    }

    /// Every currently-saved message id (row-check + dock badge source).
    public func savedIds() throws -> Set<String> {
        try dbQueue.read { db in
            Set(try String.fetchAll(db, sql: "SELECT messageId FROM savedMessages"))
        }
    }

    /// Unsave one message locally (row action "Unsave").
    public func deleteSaved(messageId: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM savedMessages WHERE messageId = :id", arguments: ["id": messageId])
        }
    }

    /// Patches the cached voice-note row with a transcription verdict
    /// (POST …/transcribe) — the transcript strip renders without a refetch.
    public func updateTranscription(messageId: String, transcript: String, transcribedAt: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE message SET transcript = :transcript, transcribedAt = :at WHERE id = :id",
                arguments: ["transcript": transcript, "at": transcribedAt, "id": messageId],
            )
        }
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

    // ── call log (W3-b — Wave 3 native calls) ─────────────

    /// GET /api/calls page → cache, one atomic transaction: full overwrite
    /// per row (the server row is authoritative) + prune beyond the server's
    /// own HISTORY_CAP of 50 so the cache can never outgrow the wire.
    public func upsert(callLog rows: [CallLogEntry]) throws {
        try dbQueue.write { db in
            for row in rows {
                // save = INSERT … ON CONFLICT DO UPDATE: a repeated GET must
                // refresh rows in place, never abort the transaction.
                try row.save(db)
            }
            let keep = Array(
                try String.fetchAll(
                    db,
                    sql: "SELECT id FROM callLogCache ORDER BY startedAt DESC LIMIT 50"
                )
            )
            let keepSet = Set(keep)
            let stale = try String.fetchAll(db, sql: "SELECT id FROM callLogCache")
            for id in stale where !keepSet.contains(id) {
                try db.execute(sql: "DELETE FROM callLogCache WHERE id = :id", arguments: ["id": id])
            }
        }
    }

    /// GET /api/calls reconcile — maps the wire rows (peer identity already
    /// resolved per-viewer server-side) into the cache and prunes everything
    /// the server no longer lists.
    public func syncCallLog(from items: [WireCallLogItem]) throws {
        let rows = items.map { item in
            CallLogEntry(
                id: item.id,
                conversationId: item.conversationId,
                callerId: item.callerId,
                calleeId: item.calleeId,
                kind: item.kind ?? "voice",
                status: item.status,
                durationSec: item.durationSec ?? 0,
                startedAt: item.startedAt,
                outgoing: item.outgoing,
                peerId: item.peer?.id ?? "",
                peerName: item.peer?.name ?? "Unknown",
                peerUsername: item.peer?.username,
                peerColor: item.peer?.color,
                peerAvatar: item.peer?.avatar,
            )
        }
        try upsert(callLog: rows)
    }

    /// Cached history, newest first (mirrors the server's startedAt DESC).
    public func callLog() throws -> [CallLogEntry] {
        try dbQueue.read { db in
            try CallLogEntry.fetchAll(
                db,
                sql: "SELECT * FROM callLogCache ORDER BY startedAt DESC"
            )
        }
    }

    public func clearCallLog() throws {
        // Wipes the visible history cache only — queued single-writer rows
        // stay queued (they still owe the server a POST).
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM callLogCache")
        }
    }

    /// Enqueues one terminal POST /api/calls body for the offline flush
    /// (payload dedupe via the UNIQUE payloadJson column — double-enqueue is
    /// a no-op, mirroring the outbox clientId rule).
    public func appendCallLogQueue(payload: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: payload)
        let json = String(data: data, encoding: .utf8) ?? ""
        guard !json.isEmpty else { return }
        let row = CallLogQueueRow(
            id: nil,
            payloadJson: json,
            attempts: 0,
            createdAt: PulseOutboxClock.now()
        )
        try dbQueue.write { db in
            try db.execute(
                sql: """
                INSERT INTO callLogQueue (payloadJson, attempts, createdAt)
                VALUES (:payload, 0, :createdAt)
                ON CONFLICT(payloadJson) DO NOTHING
                """,
                arguments: ["payload": json, "createdAt": row.createdAt]
            )
        }
    }

    /// Queue snapshot — FIFO drain order (oldest first).
    public func callLogQueueAll() throws -> [CallLogQueueRow] {
        try dbQueue.read { db in
            try CallLogQueueRow.fetchAll(db, sql: "SELECT * FROM callLogQueue ORDER BY id ASC")
        }
    }

    public func deleteCallLogQueue(id: Int64) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM callLogQueue WHERE id = :id", arguments: ["id": id])
        }
    }

    public func bumpCallLogQueueAttempts(id: Int64) throws {
        _ = try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE callLogQueue SET attempts = attempts + 1 WHERE id = :id",
                arguments: ["id": id]
            )
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

/// One call-history row (W3-b — mirror of the server CallLogItem with the
/// peer denormalized so the offline list renders exactly the online one).
public struct CallLogEntry: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {
    public static let databaseTableName = "callLogCache"

    public var id: String
    public var conversationId: String
    public var callerId: String
    public var calleeId: String
    /// "voice" | "video" (wire CallKind).
    public var kind: String
    /// "completed" | "missed" | "declined" (wire CallStatus).
    public var status: String
    public var durationSec: Int
    /// ISO-8601 (wire startedAt).
    public var startedAt: String
    /// true when the listing viewer was the caller of this row.
    public var outgoing: Bool
    // Denormalized peer (the OTHER party relative to the viewer).
    public var peerId: String
    public var peerName: String
    public var peerUsername: String?
    public var peerColor: String?
    public var peerAvatar: String?

    public init(
        id: String,
        conversationId: String,
        callerId: String,
        calleeId: String,
        kind: String,
        status: String,
        durationSec: Int,
        startedAt: String,
        outgoing: Bool,
        peerId: String,
        peerName: String,
        peerUsername: String? = nil,
        peerColor: String? = nil,
        peerAvatar: String? = nil
    ) {
        self.id = id
        self.conversationId = conversationId
        self.callerId = callerId
        self.calleeId = calleeId
        self.kind = kind
        self.status = status
        self.durationSec = durationSec
        self.startedAt = startedAt
        self.outgoing = outgoing
        self.peerId = peerId
        self.peerName = peerName
        self.peerUsername = peerUsername
        self.peerColor = peerColor
        self.peerAvatar = peerAvatar
    }

    /// The peer as a domain CallPeer (avatar/name UI rendering).
    public var peer: CallPeer {
        CallPeer(id: peerId, name: peerName, color: peerColor, avatar: peerAvatar)
    }
}

/// One queued POST /api/calls body (offline flush — PulseOutboxEngine parity).
public struct CallLogQueueRow: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {
    public static let databaseTableName = "callLogQueue"

    public var id: Int64?
    /// The serialized POST body ({ userId, conversationId, peerId, kind,
    /// status, durationSec }) — UNIQUE, so a retry storm can't double-write.
    public var payloadJson: String
    public var attempts: Int
    public var createdAt: String

    public init(id: Int64?, payloadJson: String, attempts: Int, createdAt: String) {
        self.id = id
        self.payloadJson = payloadJson
        self.attempts = attempts
        self.createdAt = createdAt
    }

    /// Decoded POST body; corrupt rows are skipped by the flusher.
    public func payload() -> [String: Any]? {
        guard let data = payloadJson.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: data),
              JSONSerialization.isValidJSONObject(payload) else { return nil }
        return payload as? [String: Any]
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
