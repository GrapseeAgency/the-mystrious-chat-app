import CryptoKit
import Foundation
import GRDB

/// GRDB (SQLite) local cache - the iOS mirror of Android's Room schema.
/// Offline-first: reads come from here, refreshes upsert from the wire.
///
/// Wave 0 v2 - adds the offline core: `outbox` (queued sends) + `draft`
/// (per-conversation composer state), mirroring Android Room v4 and the web
/// outbox/drafts stores. v1 stays untouched (non-destructive GRDB migrator
/// applies v1 → v2 in order on any database).
/// W1-DATA-B v3 - full-fidelity message cache (mirrors Android Room v5 / spec
/// §3): the message table gains parentId (THREAD ROOT - replyToId keeps the
/// inline-quote id), the media columns, editedAt/deletedAt, reactionsJson
/// (grouped [{emoji,userIds}] parity) and senderColor. `upsert(messages:)`
/// persists ALL of them - the lossy v2 cache is over.
/// W2-DATA-B v4 - Wave 2 message depth (spec §2.2, mirrors Android Room v6):
/// the message table gains the view-once burn stamp (viewedAt), the voice
/// transcript cache (transcript/transcribedAt), pollJson + linkPreviewJson
/// objects and topicId; NEW tables topics (Zulip-style sub-streams) and
/// savedMessages (saved-library mirror). All additive, same non-destructive
/// migrator - v1 stays untouched.
/// W3-b v5 - Wave 3 native calls: `callLogCache` (GET /api/calls mirror,
/// server-capped at 50 rows) and `callLogQueue` (offline queue for the
/// single-writer POST /api/calls - network-failed rows flush on socket
/// reconnect / app start, mirroring the PulseOutboxEngine trigger style).
public final class PulseStore: Sendable {
    /// Legacy pulse.sqlite is intentionally left untouched: its rows have no
    /// provable account ownership. Never adopt another user's pending sends.
    public static func accountFilename(gateway: String, viewerId: String) -> String {
        let origin = gateway.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let identity = "\(origin.utf8.count):\(origin)\(viewerId.utf8.count):\(viewerId)"
        let digest = SHA256.hash(data: Data(identity.utf8))
            .map { String(format: "%02x", $0) }.joined()
        return "pulse-account-\(digest).sqlite"
    }

    public static func openAccount(gateway: String, viewerId: String) throws -> PulseStore {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
        return try PulseStore(path: directory.appendingPathComponent(
            accountFilename(gateway: gateway, viewerId: viewerId)
        ).path)
    }

    private let dbQueue: DatabaseQueue
    let storageIdentity: String

    public init(path: String) throws {
        storageIdentity = URL(fileURLWithPath: path).standardizedFileURL.path
        dbQueue = try DatabaseQueue(path: path)
        try migrator.migrate(dbQueue)
    }

    /// In-memory store (previews/tests).
    public init() throws {
        storageIdentity = "memory:" + UUID().uuidString
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
            // Queued sends - web pulse-outbox.ts parity (MAX_QUEUE 50).
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
            // W1-DATA-B - additive ALTERs only (spec §3): threads, media,
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
            // W2-DATA-B - additive ALTERs only (spec §2.2): view-once burn
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
            // Zulip-style topic rail (General is NOT a row - unfiltered room).
            try db.create(table: "topics") { t in
                t.column("id", .text).primaryKey()
                t.column("conversationId", .text).notNull().indexed()
                t.column("name", .text).notNull()
                // R18-b - topic emoji values are icon IDS (server default 'chat').
                t.column("emoji", .text).notNull().defaults(to: "chat")
                t.column("lastMessageAt", .text)
                t.column("messageCount", .integer).notNull().defaults(to: 0)
            }
            // Saved-library mirror (GET /api/users/{id}/saved, cap 100) -
            // the message row itself lives in `message` via upsert(savedItems:).
            try db.create(table: "savedMessages") { t in
                t.column("messageId", .text).primaryKey()
                t.column("conversationId", .text).notNull()
                t.column("savedAt", .text).notNull()
            }
            try db.create(indexOn: "savedMessages", columns: ["conversationId"])
        }
        m.registerMigration("v5") { db in
            // W3-b - Wave 3 call history cache. Peer identity is denormalized
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
        m.registerMigration("v6") { db in
            // W4 - stories snapshot cache. One canonical JSON blob per key
            // ("stories:<viewerId>"), mirroring Android's Room v8 story_cache:
            // the tray renders instantly on cold start and offline, and the
            // next successful fetch overwrites it. Pure cache - server truth
            // (viewedByMe/viewCount/expiresAt) always wins on reconcile.
            try db.create(table: "storyCache") { t in
                t.column("key", .text).primaryKey()
                t.column("groupsJson", .text).notNull()
                t.column("updatedAt", .integer).notNull()
            }
        }
        m.registerMigration("v7") { db in
            // W7 - collaboration & hub snapshot cache (storyCache precedent:
            // one canonical JSON blob per key) + the raw rich-object carrier
            // payload column on messages. Non-destructive additive only.
            try db.create(table: "wave7Cache") { t in
                t.column("key", .text).primaryKey()
                t.column("json", .text).notNull()
                t.column("updatedAt", .integer).notNull()
            }
            try db.alter(table: "message") { t in
                t.add(column: "payloadJson", .text)
            }
        }
        m.registerMigration("v8") { db in
            // R1-W2B - additive ALTERs only:
            //   • outbox.payloadJson - the queued FORWARD envelope (D28:
            //     F-MS-10 "queued if offline"; carries kind + stored media
            //     paths so the flush re-POSTs the exact forward body).
            //   • message.translationsJson - persisted LLM translations
            //     (F-MD-06; the translation:added relay + POST /translate
            //     responses cache offline).
            try db.alter(table: "outbox") { t in
                t.add(column: "payloadJson", .text)
            }
            try db.alter(table: "message") { t in
                t.add(column: "translationsJson", .text)
            }
        }
        m.registerMigration("v9") { db in
            try db.alter(table: "outbox") { t in t.add(column: "failureReason", .text) }
        }
        m.registerMigration("v10") { db in
            try db.alter(table: "message") { t in
                t.add(column: "wireJson", .text)
                t.add(column: "expiresAtEpoch", .double)
            }
            // Older caches omitted TTL and view-once flags. Treat them as
            // unknown, not permanent/unrestricted media. Keep durable local
            // operations and drafts; confirmed history reloads from the server.
            try db.execute(sql: "DELETE FROM message WHERE substr(id, 1, 6) != 'local_'")
            try db.execute(sql: "UPDATE conversation SET lastMessagePreview = NULL")
        }
        m.registerMigration("v11") { db in
            try db.alter(table: "conversation") { t in t.add(column: "wireJson", .text) }
            try db.execute(sql: "UPDATE conversation SET lastMessagePreview = NULL, unreadCount = 0")
        }
        m.registerMigration("v12") { db in
            try db.execute(sql: "CREATE INDEX IF NOT EXISTS message_quote_source ON message(replyToId)")
            try db.execute(sql: "CREATE INDEX IF NOT EXISTS message_thread_parent ON message(parentId)")
            try Self.repairCachedQuotes(db: db, changedId: nil)
        }
        m.registerMigration("v13") { db in
            // Old decoding stored malformed non-null expiry as SQL NULL.
            // Rebuild only those ambiguous rows from their retained envelope.
            var after = ""
            while true {
                let rows = try Row.fetchAll(db, sql: "SELECT id, wireJson FROM message WHERE expiresAtEpoch IS NULL AND wireJson IS NOT NULL AND id > ? ORDER BY id LIMIT 200", arguments: [after])
                guard let last = rows.last, let lastID: String = last["id"] else { break }
                for row in rows {
                    guard let id: String = row["id"], let wire: String = row["wireJson"] else { continue }
                    let decoded = try? JSONDecoder().decode(WireChatMessage.self, from: Data(wire.utf8))
                    let expiry: TimeInterval?
                    if let decoded { expiry = PulseRemediationLogic.expiryEpoch(decoded.expiresAt) }
                    else { expiry = -Double.greatestFiniteMagnitude }
                    if let expiry { try db.execute(sql: "UPDATE message SET expiresAtEpoch = ? WHERE id = ?", arguments: [expiry, id]) }
                }
                after = lastID
            }
            try Self.repairCachedQuotes(db: db, changedId: nil)
        }
        m.registerMigration("v14") { db in
            try db.alter(table: "topics") { t in t.add(column: "unreadCount", .integer) }
        }
        m.registerMigration("v15") { db in
            try db.alter(table: "topics") { t in t.add(column: "readStateJson", .text) }
        }
        return m
    }

    // stories cache (Wave 4)
    public func saveStoryCache(key: String, groupsJson: String, updatedAt: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "INSERT INTO storyCache (key, groupsJson, updatedAt) VALUES (:key, :json, :updatedAt) " +
                    "ON CONFLICT(key) DO UPDATE SET groupsJson = :json, updatedAt = :updatedAt",
                arguments: ["key": key, "json": groupsJson, "updatedAt": updatedAt],
            )
        }
    }

    public func loadStoryCache(key: String) throws -> (groupsJson: String, updatedAt: Int64)? {
        try dbQueue.read { db in
            let row = try Row.fetchOne(
                db,
                sql: "SELECT groupsJson, updatedAt FROM storyCache WHERE key = :key",
                arguments: ["key": key],
            )
            guard let row else { return nil }
            return (groupsJson: row["groupsJson"] as String, updatedAt: row["updatedAt"] as Int64)
        }
    }

    // wave7 collaboration & hub snapshot cache (v7)
    public func saveWave7Cache(key: String, json: String, updatedAt: Int64) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: "INSERT INTO wave7Cache (key, json, updatedAt) VALUES (:key, :json, :updatedAt) " +
                    "ON CONFLICT(key) DO UPDATE SET json = :json, updatedAt = :updatedAt",
                arguments: ["key": key, "json": json, "updatedAt": updatedAt],
            )
        }
    }

    public func deleteWave7Cache(key: String) throws {
        try dbQueue.write { db in try db.execute(sql: "DELETE FROM wave7Cache WHERE key = ?", arguments: [key]) }
    }

    public func loadWave7Cache(key: String) throws -> (json: String, updatedAt: Int64)? {
        try dbQueue.read { db in
            let row = try Row.fetchOne(
                db,
                sql: "SELECT json, updatedAt FROM wave7Cache WHERE key = :key",
                arguments: ["key": key],
            )
            guard let row else { return nil }
            return (json: row["json"] as String, updatedAt: row["updatedAt"] as Int64)
        }
    }

    func enqueueRead(_ observation: PulseQueuedRead) throws -> PulseQueuedRead {
        let json = String(decoding: try JSONEncoder().encode(observation), as: UTF8.self)
        return try dbQueue.write { db in
            try db.execute(sql: "INSERT OR IGNORE INTO wave7Cache (key, json, updatedAt) VALUES (?, ?, ?)",
                arguments: [observation.key, json, Int64(Date().timeIntervalSince1970 * 1000)])
            let retained = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key = ?", arguments: [observation.key])!
            return try JSONDecoder().decode(PulseQueuedRead.self, from: Data(retained.utf8))
        }
    }

    func pendingReads(owner: String) throws -> [PulseQueuedRead] {
        try dbQueue.read { db in
            let prefix = "read-journal:v1|\(owner)|"
            return try String.fetchAll(db, sql: "SELECT json FROM wave7Cache WHERE substr(key, 1, length(?)) = ? ORDER BY updatedAt, key",
                arguments: [prefix, prefix]).map { try JSONDecoder().decode(PulseQueuedRead.self, from: Data($0.utf8)) }
        }
    }

    /// Called only after a validated exact HTTP ACK, never on enqueue or refusal.
    /// Merge into the current row so edits/deletions and other readers survive.
    @discardableResult
    func recordConfirmedRead(_ observation: PulseQueuedRead) throws -> Bool {
        let parts = observation.intent.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty else { return false }
        let membership = String(parts[1])
        return try dbQueue.write { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [observation.message]),
                  var message = Self.messageRow(from: row), message.id == observation.message, message.conversationId == observation.room,
                  !message.id.hasPrefix("local_"), !(message.readByMembershipIds ?? []).contains(membership) else { return false }
            message.readByMembershipIds = (message.readByMembershipIds ?? []) + [membership]
            try Self.writeMessage(message, db: db)
            return true
        }
    }

    /// A canonical GET may remove hidden/retired proof as well as add readers.
    /// Change only this field on the current row, preserving concurrent content.
    @discardableResult
    func replaceMessageReadState(conversationId: String, messageId: String, memberships: [String]) throws -> Bool {
        try dbQueue.write { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [messageId]),
                  var message = Self.messageRow(from: row), message.conversationId == conversationId,
                  !message.id.hasPrefix("local_"), (message.readByMembershipIds ?? []) != memberships else { return false }
            message.readByMembershipIds = memberships
            try Self.writeMessage(message, db: db)
            return true
        }
    }

    func acknowledgeRead(_ observation: PulseQueuedRead) throws {
        try dbQueue.write { db in
            // Drain pre-upgrade records too, but never let their delayed ACK
            // remove a distinct newer visit. Both keys are checked atomically.
            for key in [observation.key, observation.legacyKey] {
                if let json = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key = ?", arguments: [key]),
                   let current = try? JSONDecoder().decode(PulseQueuedRead.self, from: Data(json.utf8)), current == observation {
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key = ?", arguments: [key])
                }
            }
        }
    }

    // Account-scoped access refusals use a reserved metadata namespace, not message tombstones.
    static func threadDenialKey(_ rootId: String) -> String { "thread-access-denied:v1|" + rootId }

    public struct ThreadAccessError: LocalizedError {
        public var errorDescription: String? { "This thread is unavailable. Your draft and saved replies are kept." }
    }

    private static func threadIsDenied(_ db: Database, rootId: String) throws -> Bool {
        try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM wave7Cache WHERE key = ?)",
            arguments: [threadDenialKey(rootId)]) ?? false
    }

    public func threadAccessDenied(rootId: String) throws -> Bool {
        try dbQueue.read { try Self.threadIsDenied($0, rootId: rootId) }
    }

    public func denyThread(rootId: String) throws {
        try saveWave7Cache(key: Self.threadDenialKey(rootId), json: "denied", updatedAt: Int64(Date().timeIntervalSince1970 * 1000))
    }

    /// A valid refreshed parent and its rows commit with removal of the refusal.
    /// Ordinary message upserts can never clear a denial.
    public func acceptThreadSnapshot(rootId: String, roomId: String, messages: [WireChatMessage]) throws -> WireThreadPage {
        try dbQueue.write { db in
            var replies: [WireChatMessage] = []
            for message in messages where message.conversationId == roomId && (message.id == rootId || message.parentId == rootId) {
                let saved = try Self.writeMessage(message, db: db)
                if saved.parentId == rootId { replies.append(saved) }
            }
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [rootId]),
                  let parent = Self.messageRow(from: row),
                  PulseRemediationLogic.threadRootAvailable(parent, roomId: roomId, rootId: rootId) else {
                throw ThreadAccessError()
            }
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key = ?", arguments: [Self.threadDenialKey(rootId)])
            return WireThreadPage(parent: parent, replies: try Self.projectCachedQuotes(replies, db: db))
        }
    }

    // conversation cache
    public func upsert(conversations: [PulseConversation], replacingSnapshot: Bool = false) throws {
        try dbQueue.write { db in
            if replacingSnapshot { try db.execute(sql: "DELETE FROM conversation") }
            for c in conversations {
                try db.execute(
                    sql: """
                    INSERT INTO conversation (id, kind, title, lastMessagePreview, lastActivityAt,
                                              unreadCount, isPinned, isMuted, isArchived, wireJson)
                    VALUES (:id, :kind, :title, :preview, :activity, :unread, :pinned, :muted, :archived, :wire)
                    ON CONFLICT(id) DO UPDATE SET
                      kind=:kind, title=:title, lastMessagePreview=:preview, lastActivityAt=:activity,
                      unreadCount=:unread, isPinned=:pinned, isMuted=:muted, isArchived=:archived, wireJson=:wire
                    """,
                    arguments: [
                        "id": c.id, "kind": String(describing: c.kind), "title": c.title,
                        "preview": c.lastMessagePreview, "activity": c.lastActivityAt,
                        "unread": c.unreadCount, "pinned": c.isPinned, "muted": c.isMuted,
                        "archived": c.isArchived, "wire": c.wireJson,
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

    /// Indexed lookup for the active account's notification mute policy.
    public func cachedConversation(id: String) throws -> PulseConversation? {
        try dbQueue.read { db in
            try PulseConversationRow.fetchOne(db, key: id)?.toDomain()
        }
    }

    // message cache (N3-b - room offline-first seed; v3/v4 full fidelity)
    public func upsert(messages: [WireChatMessage]) throws {
        _ = try mergeMessages(messages)
    }

    /// Return the same privacy-preserving rows that committed, not stale HTTP inputs.
    public func mergeMessages(_ messages: [WireChatMessage]) throws -> [WireChatMessage] {
        try dbQueue.write { db in
            for message in messages { try Self.writeMessage(message, db: db) }
            // A later source in the same page may have revoked an earlier quote.
            return try Self.projectCachedQuotes(messages, db: db)
        }
    }

    /// Re-project only existing rows; never replace a paged river with a truncated cache window.
    public func projectCachedQuotes(_ messages: [WireChatMessage]) throws -> [WireChatMessage] {
        try dbQueue.read { db in try Self.projectCachedQuotes(messages, db: db) }
    }

    private static func projectCachedQuotes(_ messages: [WireChatMessage], db: Database) throws -> [WireChatMessage] {
        var known: [String: WireChatMessage] = [:]
        func load(_ ids: Set<String>) throws {
            let values = Array(ids.subtracting(Set(known.keys)))
            for start in stride(from: 0, to: values.count, by: 400) {
                let part = Array(values[start..<min(start + 400, values.count)])
                let placeholders = Array(repeating: "?", count: part.count).joined(separator: ",")
                let rows = try Row.fetchAll(db, sql: "SELECT * FROM message WHERE id IN (\(placeholders))", arguments: StatementArguments(part))
                for row in rows { if let message = Self.messageRow(from: row) { known[message.id] = message } }
            }
        }
        try load(Set(messages.flatMap { [$0.id] + [$0.replyTo?.id].compactMap { $0 } }))
        try load(Set(known.values.compactMap { $0.replyTo?.id }))
        try load(Set(known.values.compactMap { $0.parentId }))
        return PulseQuotePrivacy.project(messages, known: Array(known.values))
    }

    /// Bounded reverse dependency walk, in the SAME write transaction as the source.
    private static func repairCachedQuotes(db: Database, changedId: String?) throws {
        var after = ""
        while true {
            var arguments: StatementArguments = ["after": after]
            let affected: String
            if let changedId {
                affected = "AND (id = :changed OR replyToId = :changed OR replyToId IN (SELECT id FROM message WHERE parentId = :changed))"
                arguments += ["changed": changedId]
            } else { affected = "" }
            let rows = try Row.fetchAll(db, sql: "SELECT * FROM message WHERE replyToId IS NOT NULL AND id > :after \(affected) ORDER BY id LIMIT 200", arguments: arguments)
            guard let last = rows.last, let lastID: String = last["id"] else { break }
            let before = rows.compactMap(Self.messageRow(from:))
            let projected = try Self.projectCachedQuotes(before, db: db)
            for (old, safe) in zip(before, projected) where old.replyTo != safe.replyTo {
                try db.execute(sql: "UPDATE message SET wireJson = ? WHERE id = ?",
                    arguments: [String(decoding: try JSONEncoder().encode(safe), as: UTF8.self), safe.id])
            }
            after = lastID
        }
    }

    /// One message row → message table (INSERT … ON CONFLICT full overwrite:
    /// a fresh envelope row is AUTHORITATIVE - spec §0 - so every column,
    /// v1 through v4, lands in both the INSERT and the conflict SET clause).
    /// Shared by the bulk cache upsert and the saved-library sync.
    @discardableResult
    private static func writeMessage(_ incoming: WireChatMessage, db: Database) throws -> WireChatMessage {
        let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [incoming.id])
        let current = row.flatMap { Self.messageRow(from: $0) }
        var m = PulseMessageMerge.merge(current, incoming: incoming)
        if let deleted: String = row?["deletedAt"], !deleted.isEmpty { m = m.deletedCopy() }
        if let viewed: String = row?["viewedAt"] { m.viewedAt = viewed; m.viewOnce = true }
        m = try Self.projectCachedQuotes([m], db: db).first ?? m
        try db.execute(
            sql: """
            INSERT INTO message (id, conversationId, authorId, authorName, kind, body,
                                 createdAt, replyToId, pinnedAt, parentId, imagePath,
                                 audioPath, durationMs, filePath, fileName, fileSize,
                                 editedAt, deletedAt, reactionsJson, senderColor,
                                 viewedAt, transcript, transcribedAt, pollJson,
                                 linkPreviewJson, topicId, payloadJson, translationsJson, wireJson, expiresAtEpoch)
            VALUES (:id, :conversationId, :authorId, :authorName, :kind, :body,
                    :createdAt, :replyToId, :pinnedAt, :parentId, :imagePath,
                    :audioPath, :durationMs, :filePath, :fileName, :fileSize,
                    :editedAt, :deletedAt, :reactionsJson, :senderColor,
                    :viewedAt, :transcript, :transcribedAt, :pollJson,
                    :linkPreviewJson, :topicId, :payloadJson, :translationsJson, :wireJson, :expiresAtEpoch)
            ON CONFLICT(id) DO UPDATE SET
              authorName=:authorName, kind=:kind, body=:body, pinnedAt=:pinnedAt, replyToId=:replyToId,
              parentId=:parentId, imagePath=:imagePath, audioPath=:audioPath,
              durationMs=:durationMs, filePath=:filePath, fileName=:fileName,
              fileSize=:fileSize, editedAt=:editedAt, deletedAt=:deletedAt,
              reactionsJson=:reactionsJson, senderColor=:senderColor,
              viewedAt=:viewedAt, transcript=:transcript, transcribedAt=:transcribedAt,
              pollJson=:pollJson, linkPreviewJson=:linkPreviewJson, topicId=:topicId,
              payloadJson=:payloadJson, translationsJson=:translationsJson,
              wireJson=:wireJson, expiresAtEpoch=:expiresAtEpoch
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
                "payloadJson": m.payload,
                "translationsJson": Self.translationsJsonData(m.translations),
                "wireJson": String(decoding: try JSONEncoder().encode(m), as: UTF8.self),
                "expiresAtEpoch": PulseRemediationLogic.expiryEpoch(m.expiresAt),
            ],
        )
        try Self.repairCachedQuotes(db: db, changedId: m.id)
        return try Self.projectCachedQuotes([m], db: db).first ?? m
    }

    /// Persist operation and optimistic bubble before any network I/O.
    public func enqueueMessage(_ message: WireChatMessage, clientId: String, envelope: PulseOutboxForward,
                               forwarding source: WireChatMessage? = nil) throws {
        _ = try enqueueMessage(message, clientId: clientId, envelope: envelope, consumingDraft: nil, forwarding: source)
    }

    @discardableResult
    func enqueueMessage(_ message: WireChatMessage, clientId: String, envelope: PulseOutboxForward,
                        consumingDraft token: PulseDraftConsumption?, forwarding source: WireChatMessage? = nil) throws -> Bool {
        guard let payload = PulseOutboxForward.encode(envelope) else { throw CocoaError(.coderInvalidValue) }
        if let token, token.storageIdentity != storageIdentity || token.conversationId != message.conversationId || token.parentId != message.parentId || token.parentId != envelope.parentId {
            throw CocoaError(.userCancelled)
        }
        let commit: (Bool) throws -> Void = { consume in
            try self.dbQueue.write { db in
                if let source {
                    guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [source.id]),
                          let current = Self.messageRow(from: row), current.conversationId == source.conversationId,
                          current.deletedAt == nil, current.viewOnce != true,
                          PulseRemediationLogic.isAlive(current.expiresAt) else { throw ThreadAccessError() }
                    if let root = current.parentId {
                        if try Self.threadIsDenied(db, rootId: root) { throw ThreadAccessError() }
                        guard let parent = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [root]),
                              let parentMessage = Self.messageRow(from: parent),
                              PulseRemediationLogic.threadRootAvailable(parentMessage, roomId: current.conversationId, rootId: root) else { throw ThreadAccessError() }
                    }
                }
                if let root = envelope.parentId, try Self.threadIsDenied(db, rootId: root) { throw ThreadAccessError() }
                try Self.writeMessage(message, db: db)
                try OutboxRow(id: nil, conversationId: message.conversationId, clientId: clientId,
                    content: message.content, kind: envelope.kind ?? "text", createdAt: PulseOutboxClock.now(),
                    attempts: 0, payloadJson: payload).insert(db)
                if consume, let token { try Self.writeDraftSnapshot(db, room: token.storageKey, text: "") }
            }
        }
        if let token {
            return try draftFence.enqueue(token.storageKey, expected: token.revision, action: commit)
        }
        try commit(false)
        return false
    }

    /// The acknowledgement is one SQLite transaction, never three independent writes.
    public func resolveDelivery(clientId: String, message: WireChatMessage) throws {
        try dbQueue.write { db in
            try Self.writeMessage(message, db: db)
            try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + clientId])
            try db.execute(sql: "DELETE FROM outbox WHERE clientId = ?", arguments: [clientId])
        }
    }

    public func clearMessagePin(id: String) throws {
        try dbQueue.write { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [id]),
                  var message = Self.messageRow(from: row) else { return }
            message.pinnedAt = nil
            try Self.writeMessage(message, db: db)
        }
    }

    public func deleteMessage(id: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM message WHERE id = :id", arguments: ["id": id])
        }
    }

    public func messages(conversationId: String, limit: Int = 300, now: Date = Date()) throws -> [WireChatMessage] {
        let rows = try dbQueue.read { db in
            try Row.fetchAll(
                db,
                sql: "SELECT * FROM (SELECT * FROM message WHERE conversationId = :cid AND NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || message.parentId) AND (expiresAtEpoch IS NULL OR expiresAtEpoch > :now) ORDER BY createdAt DESC, id DESC LIMIT :limit) ORDER BY createdAt ASC, id ASC",
                arguments: ["cid": conversationId, "limit": limit, "now": now.timeIntervalSince1970],
            )
        }
        return rows.compactMap(Self.messageRow(from:))
    }

    /// One cached row by id (jump anchoring: a search hit that lands on a
    /// thread reply needs its parentId to target the river). nil = not cached.
    public func message(id: String, now: Date = Date()) throws -> WireChatMessage? {
        try dbQueue.read { db in
            try Row.fetchOne(
                db,
                sql: "SELECT * FROM message WHERE id = :id AND (expiresAtEpoch IS NULL OR expiresAtEpoch > :now)",
                arguments: ["id": id, "now": now.timeIntervalSince1970],
            )
            .flatMap(Self.messageRow(from:))
        }
    }

    /// Thread replies for one root, oldest → newest (v3 parentId column -
    /// replyToId keeps the inline-quote id and is never mixed in).
    public func messages(threadRootId: String, limit: Int = 300, now: Date = Date()) throws -> [WireChatMessage] {
        let rows = try dbQueue.read { db in
            try Row.fetchAll(
                db,
                sql: "SELECT * FROM (SELECT * FROM message WHERE parentId = :root AND NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || :root) AND (expiresAtEpoch IS NULL OR expiresAtEpoch > :now) ORDER BY createdAt DESC, id DESC LIMIT :limit) ORDER BY createdAt ASC, id ASC",
                arguments: ["root": threadRootId, "limit": limit, "now": now.timeIntervalSince1970],
            )
        }
        return rows.compactMap(Self.messageRow(from:))
    }

    /// Resolve an offline reply and its root from one read snapshot. A cached
    /// reply alone never proves its parent is still displayable.
    func cachedThreadTarget(roomId: String, rootId: String, targetId: String, now: Date = Date()) throws
        -> (parent: WireChatMessage, reply: WireChatMessage)? {
        try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT m.* FROM message m
                WHERE m.conversationId = :room AND m.id IN (:root, :target)
                  AND NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || :root)
                  AND NOT EXISTS (SELECT 1 FROM outbox o WHERE m.id = 'local_' || o.clientId AND o.failureReason IS NOT NULL)
                """, arguments: ["room": roomId, "root": rootId, "target": targetId])
            let messages = rows.compactMap(Self.messageRow(from:))
            guard let parent = messages.first(where: { $0.id == rootId }),
                  PulseRemediationLogic.threadRootAvailable(parent, roomId: roomId, rootId: rootId, now: now),
                  let reply = PulseThreadNavigation.target(in: messages, roomId: roomId, rootId: rootId, targetId: targetId, now: now)
            else { return nil }
            return (parent, reply)
        }
    }

    /// Keyset pages keep offline history reachable beyond the initial 300-row window.
    /// Cursor comes from raw rows (including local echoes), so tombstones, failed decode
    /// or a subsequently removed anchor cannot stall paging or skip timestamp ties.
    func cachedThreadPage(roomId: String, rootId: String, before: PulseCachedThreadCursor? = nil,
                          limit: Int = 100, now: Date = Date()) throws -> PulseCachedThreadPage {
        if let before, before.roomId != roomId || before.rootId != rootId { throw CocoaError(.coderInvalidValue) }
        let size = min(300, max(1, limit))
        return try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT reply.* FROM message reply
                JOIN message root ON root.id = reply.parentId AND root.conversationId = reply.conversationId
                WHERE reply.conversationId = :room AND reply.parentId = :root
                  AND root.parentId IS NULL AND substr(root.id, 1, 6) != 'local_'
                  AND root.deletedAt IS NULL AND (root.expiresAtEpoch IS NULL OR root.expiresAtEpoch > :now)
                  AND (reply.expiresAtEpoch IS NULL OR reply.expiresAtEpoch > :now)
                  AND NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || :root)
                  AND NOT EXISTS (SELECT 1 FROM outbox o WHERE reply.id = 'local_' || o.clientId AND o.failureReason IS NOT NULL)
                  AND (:beforeCreated IS NULL OR reply.createdAt < :beforeCreated
                       OR (reply.createdAt = :beforeCreated AND reply.id < :beforeId))
                ORDER BY reply.createdAt DESC, reply.id DESC LIMIT :limit
                """, arguments: ["room": roomId, "root": rootId, "now": now.timeIntervalSince1970,
                    "beforeCreated": before?.createdAt, "beforeId": before?.id, "limit": size + 1])
            let selected = Array(rows.prefix(size))
            let cursor = selected.last.map { row in
                PulseCachedThreadCursor(roomId: roomId, rootId: rootId, createdAt: row["createdAt"], id: row["id"])
            }
            return PulseCachedThreadPage(messages: selected.reversed().compactMap(Self.messageRow(from:)),
                                         cursor: cursor, hasMore: rows.count > size)
        }
    }

    /// Reply counts per cached thread root - one query powers every river
    /// "N replies" chip (no per-thread COUNT round-trips).
    public func threadReplyCounts(now: Date = Date()) throws -> [String: Int] {
        try dbQueue.read { db in
            var counts: [String: Int] = [:]
            let rows = try Row.fetchAll(
                db,
                sql: "SELECT parentId, COUNT(*) AS replyCount FROM message WHERE parentId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || message.parentId) AND deletedAt IS NULL AND (expiresAtEpoch IS NULL OR expiresAtEpoch > :now) GROUP BY parentId",
                arguments: ["now": now.timeIntervalSince1970],
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

    func observeThreadReplyCounts(conversationId: String) -> DatabasePublishers.Value<ThreadReplyCountSnapshot> {
        ValueObservation.tracking { db in
            try Self.threadReplyCountSnapshot(db, conversationId: conversationId, now: Date())
        }.publisher(in: dbQueue)
    }

    func threadReplyCountSnapshot(conversationId: String, now: Date = Date()) throws -> ThreadReplyCountSnapshot {
        try dbQueue.read { try Self.threadReplyCountSnapshot($0, conversationId: conversationId, now: now) }
    }

    private static func threadReplyCountSnapshot(_ db: Database, conversationId: String, now: Date) throws -> ThreadReplyCountSnapshot {
        let rows = try Row.fetchAll(db, sql: """
            SELECT reply.parentId, COUNT(*) AS replyCount,
                   MIN(reply.expiresAtEpoch) AS replyExpiry, root.expiresAtEpoch AS rootExpiry
            FROM message reply JOIN message root ON root.id = reply.parentId AND root.conversationId = reply.conversationId
            WHERE reply.conversationId = :room AND root.parentId IS NULL AND substr(root.id, 1, 6) != 'local_'
              AND NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || root.id)
              AND root.deletedAt IS NULL AND (root.expiresAtEpoch IS NULL OR root.expiresAtEpoch > :now)
              AND reply.deletedAt IS NULL AND (reply.expiresAtEpoch IS NULL OR reply.expiresAtEpoch > :now)
              AND NOT EXISTS (SELECT 1 FROM outbox o WHERE reply.id = 'local_' || o.clientId AND o.failureReason IS NOT NULL)
            GROUP BY reply.parentId, root.expiresAtEpoch
            """, arguments: ["room": conversationId, "now": now.timeIntervalSince1970])
        var snapshot = ThreadReplyCountSnapshot()
        for row in rows {
            let root: String = row["parentId"]
            let count: Int = row["replyCount"]
            snapshot.counts[root] = count
            let replyExpiry: Double? = row["replyExpiry"], rootExpiry: Double? = row["rootExpiry"]
            if let earliest = [replyExpiry, rootExpiry].compactMap({ $0 }).min() {
                let deadline = Date(timeIntervalSince1970: earliest)
                snapshot.nextExpiry = min(snapshot.nextExpiry ?? deadline, deadline)
            }
        }
        return snapshot
    }

    /// Full authoritative envelope for new rows; column fallback only for
    /// legacy local operations preserved by the privacy-cache migration.
    private static func messageRow(from row: Row) -> WireChatMessage? {
        if let wire: String = row["wireJson"] {
            // Corrupt full envelopes must not fall back to missing privacy flags.
            return (try? JSONDecoder().decode(WireChatMessage.self, from: Data(wire.utf8)))
                .map { incoming in
                    var safe = PulseMessageMerge.merge(nil, incoming: incoming)
                    // Legacy column-only terminal writes must also constrain
                    // dependent quotes, not just the next write of this source.
                    if let deleted: String = row["deletedAt"], !deleted.isEmpty { safe = safe.deletedCopy() }
                    if let viewed: String = row["viewedAt"] { safe.viewOnce = true; safe.viewedAt = viewed }
                    return safe
                }
        }
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
        return PulseMessageMerge.merge(nil, incoming: WireChatMessage(
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
            payload: row["payloadJson"],
            translations: Self.translations(fromJson: row["translationsJson"]),
        ))
    }

    // reactionsJson codec (v3 cache column ⇄ grouped wire reactions)

    /// [WireReactionGroup] → column text ("[]" for nil/empty - the v3 column
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

    // poll/linkPreviewJson codecs (v4 cache columns ⇄ wire cards)

    /// WirePoll → column text; nil poll → NULL (the v4 column is nullable,
    /// unlike the NOT-NULL reactionsJson v3 default - no card, no bytes).
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

    // translationsJson codec (v8 cache column ⇄ wire translations)

    /// [WireTranslation] → column text; nil/empty → NULL (no card, no bytes).
    static func translationsJsonData(_ translations: [WireTranslation]?) -> String? {
        guard let translations, !translations.isEmpty,
              let data = try? JSONEncoder().encode(translations) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Column text → [WireTranslation]; corrupt rows degrade to nil.
    static func translations(fromJson json: String?) -> [WireTranslation]? {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode([WireTranslation].self, from: data)
    }

    // topics cache (W2-DATA-B - Zulip-style sub-streams)

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
                    INSERT INTO topics (id, conversationId, name, emoji, lastMessageAt, messageCount, unreadCount, readStateJson)
                    VALUES (:id, :cid, :name, :emoji, :lastMessageAt, :messageCount, :unreadCount, :readStateJson)
                    ON CONFLICT(id) DO UPDATE SET
                      conversationId=:cid, name=:name, emoji=:emoji,
                      lastMessageAt=:lastMessageAt, messageCount=:messageCount, unreadCount=:unreadCount, readStateJson=:readStateJson
                    """,
                    arguments: [
                        "id": topic.id, "cid": conversationId, "name": topic.name,
                        "emoji": topic.emoji ?? "chat", "lastMessageAt": topic.lastMessageAt,
                        "messageCount": topic.messageCount ?? 0, "unreadCount": topic.unreadCount,
                        "readStateJson": String(decoding: try JSONEncoder().encode(topic), as: UTF8.self),
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
        if let raw: String = row["readStateJson"],
           let topic = try? JSONDecoder().decode(WireTopic.self, from: Data(raw.utf8)), topic.id == id {
            // JSON adds read metadata; normalized structural SQL fields remain
            // authoritative (including the existing nil-emoji -> chat default).
            return WireTopic(unreadCount: topic.unreadCount, firstUnreadMessageId: topic.firstUnreadMessageId,
                unreadExpirations: topic.unreadExpirations, id: id, name: name,
                emoji: row["emoji"], lastMessageAt: row["lastMessageAt"], messageCount: row["messageCount"])
                .removingExpiredUnread()
        }
        let emoji: String? = row["emoji"]
        let lastMessageAt: String? = row["lastMessageAt"]
        let messageCount: Int? = row["messageCount"]
        return WireTopic(unreadCount: row["unreadCount"], id: id, name: name, emoji: emoji, lastMessageAt: lastMessageAt, messageCount: messageCount)
    }

    // saved library cache (W2-DATA-B - GET /users/{id}/saved mirror)

    /// Saved-library sync: upserts every item's message row (full fidelity,
    /// shared writer) AND its savedMessages marker (savedAt ISO from wire).
    public func upsert(savedItems: [WireSavedItem], replacingSnapshot: Bool = false, preservingMessageIds: Set<String> = []) throws {
        try dbQueue.write { db in
            if replacingSnapshot { try db.execute(sql: "DELETE FROM savedMessages") }
            for item in savedItems {
                if !preservingMessageIds.contains(item.message.id) { try Self.writeMessage(item.message, db: db) }
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

    /// Keeps only the given saved rows - paired with upsert(savedItems:) to
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

    /// Offline read path for the saved library. Missing conversation metadata
    /// remains unknown; the saved message body is still usable without a feed fetch.
    public func cachedSavedItems(now: Date = Date()) throws -> [WireSavedItem] {
        try dbQueue.read { db in try Self.savedItems(in: db, now: now) }
    }

    public func observeSavedItems() -> DatabasePublishers.Value<[WireSavedItem]> {
        ValueObservation.tracking { db in try Self.savedItems(in: db, now: Date()) }.publisher(in: dbQueue)
    }

    private static func savedItems(in db: Database, now: Date) throws -> [WireSavedItem] {
        try Row.fetchAll(db, sql: """
            SELECT m.*, s.savedAt AS savedStamp, c.title AS savedConversationName,
                   c.kind AS savedConversationKind, p.expiresAtEpoch AS savedRootExpiry
            FROM savedMessages s JOIN message m ON m.id = s.messageId
            LEFT JOIN conversation c ON c.id = s.conversationId
            LEFT JOIN message p ON p.id = m.parentId
            WHERE NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || m.parentId)
              AND (p.id IS NULL OR (p.conversationId = m.conversationId AND p.parentId IS NULL AND p.deletedAt IS NULL
                AND (p.expiresAtEpoch IS NULL OR p.expiresAtEpoch > :now)))
              AND m.deletedAt IS NULL AND (m.expiresAtEpoch IS NULL OR m.expiresAtEpoch > :now)
            ORDER BY s.savedAt DESC, s.messageId DESC
            """, arguments: ["now": now.timeIntervalSince1970]).compactMap { row in
                guard var message = Self.messageRow(from: row), message.viewOnce != true,
                      let savedAt: String = row["savedStamp"] else { return nil }
                // Preview deadline only; leave the canonical reply expiry untouched.
                if let rootExpiry: Double = row["savedRootExpiry"],
                   (message.expiresAt.flatMap(PulseFormat.date)?.timeIntervalSince1970 ?? .infinity) > rootExpiry {
                    message.expiresAt = ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: rootExpiry))
                }
                let kind: String? = row["savedConversationKind"]
                return WireSavedItem(
                    savedAt: savedAt,
                    conversation: WireSavedConversation(
                        id: message.conversationId,
                        isGroup: kind.map { $0 != "DM" },
                        name: row["savedConversationName"]
                    ),
                    message: message
                )
            }
    }

    /// Every currently-saved message id (row-check + dock badge source).
    public func savedIds() throws -> Set<String> {
        Set(try cachedSavedItems().map { $0.message.id })
    }

    /// Unsave one message locally (row action "Unsave").
    public func deleteSaved(messageId: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM savedMessages WHERE messageId = :id", arguments: ["id": messageId])
        }
    }

    /// Patches the cached voice-note row with a transcription verdict
    /// (POST …/transcribe) - the transcript strip renders without a refetch.
    public func updateTranscription(messageId: String, transcript: String, transcribedAt: String) throws {
        _ = try dbQueue.write { db in
            try db.execute(
                sql: "UPDATE message SET transcript = :transcript, transcribedAt = :at, wireJson = CASE WHEN wireJson IS NOT NULL THEN json_set(wireJson, '$.transcript', :transcript, '$.transcribedAt', :at) END WHERE id = :id",
                arguments: ["transcript": transcript, "at": transcribedAt, "id": messageId],
            )
        }
    }

    // outbox (Wave 0 - queued sends, web pulse-outbox parity)

    /// Appends one queued send (clientId dedupes; UNIQUE constraint drops
    /// double-enqueues exactly like the web store's `some(q.clientId === …)`).
    /// `payloadJson` carries the queued FORWARD envelope (D28) - plain sends
    /// leave it nil.
    public func appendOutbox(conversationId: String, clientId: String, content: String, kind: String = "text", payloadJson: String? = nil) throws {
        let row = OutboxRow(
            id: nil,
            conversationId: conversationId,
            clientId: clientId,
            content: content,
            kind: kind,
            createdAt: PulseOutboxClock.now(),
            attempts: 0,
            payloadJson: payloadJson,
        )
        try dbQueue.write { db in
            if let existing = try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE clientId = ?", arguments: [clientId]) {
                guard existing.conversationId == conversationId, existing.content == content,
                      existing.kind == kind, existing.payloadJson == payloadJson else { throw CocoaError(.coderInvalidValue) }
                return
            }
            try row.insert(db)
        }
    }

    /// Commit a notification reply as pending or held in ONE transaction. A
    /// held reply never exposes an intermediate pending row to the flusher.
    /// Return the committed pending state; no fallible post-commit read should
    /// tell the OS a saved reply failed. Never overwrite the composer draft.
    func persistNotificationReply(conversationId: String, clientId: String, content: String, reason: String?) throws -> Bool {
        let row = OutboxRow(id: nil, conversationId: conversationId, clientId: clientId,
            content: content, kind: "text", createdAt: PulseOutboxClock.now(), attempts: 0, failureReason: reason)
        return try dbQueue.write { db in
            if let existing = try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE clientId = ?", arguments: [clientId]) {
                guard existing.conversationId == conversationId, existing.content == content,
                      existing.kind == "text", existing.payloadJson == nil else {
                    throw CocoaError(.coderInvalidValue)
                }
                return existing.failureReason == nil
            }
            try row.insert(db)
            return reason == nil
        }
    }

    /// Observe this account's rejected intents, scoped by both room and retained routing envelope.
    public func observeFailedThreadReplies(conversationId: String, rootId: String) -> DatabasePublishers.Value<[OutboxRow]> {
        ValueObservation.tracking { db in
            try OutboxRow.fetchAll(db, sql: "SELECT * FROM outbox WHERE conversationId = ? AND failureReason IS NOT NULL ORDER BY id ASC",
                arguments: [conversationId]).filter { PulseOutboxForward.decode($0.payloadJson)?.parentId == rootId }
        }.publisher(in: dbQueue)
    }

    /// The user confirms discarding a rejected intent, not a subsequently retried send.
    public func discardFailedThreadReply(clientId: String, conversationId: String, rootId: String) throws -> Bool {
        try dbQueue.write { db in
            guard let row = try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE clientId = ? AND conversationId = ? AND failureReason IS NOT NULL",
                arguments: [clientId, conversationId]), PulseOutboxForward.decode(row.payloadJson)?.parentId == rootId else { return false }
            try db.execute(sql: "DELETE FROM outbox WHERE clientId = ?", arguments: [clientId])
            try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + clientId])
            return true
        }
    }

    /// Queue snapshot - FIFO drain order (oldest first).
    public func outboxAll() throws -> [OutboxRow] {
        try dbQueue.read { db in
            try OutboxRow.fetchAll(db, sql: "SELECT * FROM outbox ORDER BY id ASC")
        }
    }

    /// Failed intent remains recoverable, but is never automatically re-posted.
    public func outboxPending() throws -> [OutboxRow] {
        try dbQueue.read { db in
            try OutboxRow.fetchAll(db, sql: "SELECT * FROM outbox WHERE failureReason IS NULL ORDER BY id ASC")
        }
    }

    /// Apply room exclusions in SQLite before LIMIT, not to a truncated FIFO snapshot.
    public func nextPendingOutbox(excludingConversations: Set<String>) throws -> OutboxRow? {
        let rooms = excludingConversations.sorted()
        let placeholders = Array(repeating: "?", count: rooms.count).joined(separator: ",")
        let exclusion = rooms.isEmpty ? "" : " AND conversationId NOT IN (\(placeholders))"
        return try dbQueue.read { db in
            try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE failureReason IS NULL" + exclusion + " ORDER BY id ASC LIMIT 1",
                arguments: StatementArguments(rooms))
        }
    }

    /// Unreadable storage is not an empty queue. Callers must keep recovery alive.
    public func countPendingOutbox() throws -> Int {
        try dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM outbox WHERE failureReason IS NULL") ?? 0
        }
    }

    /// Do not touch the composer. Keep content + full envelope and retract only
    /// the pending echo, atomically. A racing delivery that already won stays won.
    @discardableResult
    public func rejectOutbox(clientId: String, reason: String) throws -> Bool {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE outbox SET failureReason = ? WHERE clientId = ? AND failureReason IS NULL",
                arguments: [reason, clientId])
            let changed = db.changesCount > 0
            if changed {
                try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + clientId])
            }
            return changed
        }
    }

    /// Retain the upload receipt before the message POST. Never resurrect a discarded send.
    public func recordOutboxUpload(clientId: String, envelope: PulseOutboxForward) throws -> Bool {
        guard envelope.uploadDataUrl == nil, let payload = PulseOutboxForward.encode(envelope) else {
            throw CocoaError(.coderInvalidValue)
        }
        return try dbQueue.write { db in
            try db.execute(sql: "UPDATE outbox SET payloadJson = ? WHERE clientId = ? AND failureReason IS NULL",
                           arguments: [payload, clientId])
            return db.changesCount > 0
        }
    }

    /// Explicit retry keeps the original identity and metadata, including upload receipt.
    public func retryOutbox(clientId: String, message: WireChatMessage, threadRootId: String? = nil) throws -> Bool {
        guard message.id == "local_" + clientId else { throw CocoaError(.coderInvalidValue) }
        if let threadRootId, message.parentId != threadRootId { return false }
        return try dbQueue.write { db in
            if let rootId = message.parentId {
                guard try !Self.threadIsDenied(db, rootId: rootId), message.parentId == rootId,
                      let retained = try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE clientId = ? AND conversationId = ? AND failureReason IS NOT NULL", arguments: [clientId, message.conversationId]),
                      PulseOutboxForward.decode(retained.payloadJson)?.parentId == rootId,
                      let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [rootId]),
                      let root = Self.messageRow(from: row),
                      PulseRemediationLogic.threadRootAvailable(root, roomId: message.conversationId, rootId: rootId) else { return false }
            }
            try db.execute(sql: "UPDATE outbox SET failureReason = NULL, attempts = 0 WHERE clientId = ? AND conversationId = ? AND failureReason IS NOT NULL",
                           arguments: [clientId, message.conversationId])
            guard db.changesCount > 0 else { return false }
            try Self.writeMessage(message, db: db)
            return true
        }
    }

    public func isPendingOutbox(clientId: String) throws -> Bool {
        try dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM outbox WHERE clientId = ? AND failureReason IS NULL",
                arguments: [clientId]) == 1
        }
    }

    /// A stale failed-send row must not discard an operation retried since display.
    func discardOutboxIfUnchanged(_ expected: OutboxRow) throws -> Bool {
        try dbQueue.write { db in
            guard let current = try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE clientId = ?", arguments: [expected.clientId]),
                  current.conversationId == expected.conversationId, current.createdAt == expected.createdAt,
                  current.failureReason == expected.failureReason else { return false }
            try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + expected.clientId])
            try db.execute(sql: "DELETE FROM outbox WHERE clientId = ?", arguments: [expected.clientId])
            return true
        }
    }

    public func deleteOutbox(id: Int64) throws {
        try dbQueue.write { db in
            if let clientId = try String.fetchOne(db, sql: "SELECT clientId FROM outbox WHERE id = ?", arguments: [id]) {
                try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + clientId])
                try db.execute(sql: "DELETE FROM outbox WHERE id = ?", arguments: [id])
            }
        }
    }

    public func deleteOutbox(clientId: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + clientId])
            try db.execute(sql: "DELETE FROM outbox WHERE clientId = ?", arguments: [clientId])
        }
    }

    public func bumpOutboxAttempts(id: Int64) throws {
        _ = try dbQueue.write { db in
            try db.execute(sql: "UPDATE outbox SET attempts = attempts + 1 WHERE id = :id", arguments: ["id": id])
        }
    }

    public func countOutbox() throws -> Int {
        try dbQueue.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM outbox") ?? 0
        }
    }

    // drafts (Wave 0 - composer persistence, web pulse-drafts parity)

    /// Empty text is a local tombstone, overriding stale server drafts.
    public func saveDraft(conversationId: String, text: String) throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            try deleteDraft(conversationId: conversationId)
            return
        }
        try saveDraftSnapshot(conversationId: conversationId, text: text)
    }

    /// All draft writes and composer sends for this database share an ordering fence.
    private var draftFence: PulseDraftWriteFence { PulseDraftWriteFence.shared(for: storageIdentity) }

    func reserveDraftWrite(conversationId: String) -> UInt64 { draftFence.reserve(conversationId) }
    func captureDraftConsumption(conversationId: String, parentId: String? = nil) -> PulseDraftConsumption {
        PulseDraftConsumption(storageIdentity: storageIdentity, conversationId: conversationId,
            revision: draftFence.current(PulseThreadDraft.key(conversationId, parentId: parentId)), parentId: parentId)
    }

    func saveDraftSnapshot(conversationId: String, text: String, expectedRevision: UInt64? = nil) throws {
        try draftFence.write(conversationId, expected: expectedRevision) {
            try dbQueue.write { db in try Self.writeDraftSnapshot(db, room: conversationId, text: text) }
        }
    }

    private static func writeDraftSnapshot(_ db: Database, room: String, text: String) throws {
        try db.execute(sql: """
            INSERT INTO draft (conversationId, text, updatedAt) VALUES (:cid, :text, :updatedAt)
            ON CONFLICT(conversationId) DO UPDATE SET text=:text, updatedAt=:updatedAt
            """, arguments: ["cid": room, "text": text, "updatedAt": PulseOutboxClock.now()])
    }

    /// Local draft for a conversation; nil when absent/blank (legacy consumers).
    public func draft(conversationId: String) -> String? {
        guard let text = draftSnapshot(conversationId: conversationId), !text.isEmpty else { return nil }
        return text
    }

    /// nil means never edited locally; an empty string means explicitly cleared.
    func draftSnapshot(conversationId: String) -> String? {
        try? dbQueue.read { db in
            try String.fetchOne(db, sql: "SELECT text FROM draft WHERE conversationId = :cid",
                arguments: ["cid": conversationId])
        }
    }

    /// Every local draft (chats-list preview rehydration).
    public func allDrafts(includingClears: Bool = false) -> [String: String] {
        let rows: [(String, String)]? = try? dbQueue.read { db in
            let pairs = try Row.fetchAll(db, sql: "SELECT conversationId, text FROM draft")
            return pairs.compactMap { row in
                guard let cid: String = row["conversationId"],
                      let text: String = row["text"],
                      (includingClears || !text.isEmpty) else { return nil }
                return (cid, text)
            }
        }
        guard let rows else { return [:] }
        return Dictionary(rows, uniquingKeysWith: { _, later in later })
    }

    /// Wave 8 - full draft rows (id + text + updatedAt) oldest-first, the
    /// Drafts & outbox manager's feed. Blank rows never surface.
    public func allDraftRows() throws -> [DraftRow] {
        try dbQueue.read { db in
            try DraftRow
                .fetchAll(db, sql: "SELECT * FROM draft ORDER BY updatedAt ASC")
                .filter { !$0.text.isEmpty }
        }
    }

    /// Clear only the exact draft the manager displayed, not a newer composer write.
    func deleteDraftIfUnchanged(_ row: DraftRow, revision: UInt64) throws -> Bool {
        try draftFence.removeIfCurrent(row.conversationId, expected: revision) {
            try dbQueue.write { db in
                let current = try DraftRow.fetchOne(db, sql: "SELECT * FROM draft WHERE conversationId = ?", arguments: [row.conversationId])
                guard current == row else { return false }
                try Self.writeDraftSnapshot(db, room: row.conversationId, text: "")
                return true
            }
        }
    }

    public func deleteDraft(conversationId: String) throws {
        try saveDraftSnapshot(conversationId: conversationId, text: "")
    }

    // call log (W3-b - Wave 3 native calls)

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

    /// GET /api/calls reconcile - maps the wire rows (peer identity already
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
        // Server truth - prune rows the endpoint no longer lists (the cap
        // prune inside upsert(callLog:) is NOT enough: locally-written rows
        // that failed their POST must not outlive the server's own list).
        try dbQueue.write { db in
            let keep = Set(rows.map(\.id))
            let stale = try String.fetchAll(db, sql: "SELECT id FROM callLogCache")
            for id in stale where !keep.contains(id) {
                try db.execute(sql: "DELETE FROM callLogCache WHERE id = :id", arguments: ["id": id])
            }
        }
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
        // Wipes the visible history cache only - queued single-writer rows
        // stay queued (they still owe the server a POST).
        _ = try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM callLogCache")
        }
    }

    /// Enqueues one terminal POST /api/calls body for the offline flush
    /// (payload dedupe via the UNIQUE payloadJson column - double-enqueue is
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

    /// Queue snapshot - FIFO drain order (oldest first).
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
    var wireJson: String?

    func toDomain() -> PulseConversation {
        if let wireJson, let data = wireJson.data(using: .utf8),
           let summary = try? JSONDecoder().decode(WireConversationSummary.self, from: data) {
            return summary.removingExpiredSummary().toDomain()
        }
        return PulseConversation(
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
            wireJson: wireJson,
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
    /// R1-W2B D28 - the queued forward envelope (JSON string of
    /// PulseOutboxForward); nil for plain text sends. Optional decodes as
    /// nil on rows written before the v8 column existed.
    public var payloadJson: String?
    public var failureReason: String?

    public init(id: Int64?, conversationId: String, clientId: String, content: String,
                kind: String, createdAt: String, attempts: Int, payloadJson: String? = nil, failureReason: String? = nil) {
        self.id = id
        self.conversationId = conversationId
        self.clientId = clientId
        self.content = content
        self.kind = kind
        self.createdAt = createdAt
        self.attempts = attempts
        self.payloadJson = payloadJson
        self.failureReason = failureReason
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

/// One call-history row (W3-b - mirror of the server CallLogItem with the
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

/// One queued POST /api/calls body (offline flush - PulseOutboxEngine parity).
public struct CallLogQueueRow: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {
    public static let databaseTableName = "callLogQueue"

    public var id: Int64?
    /// The serialized POST body ({ userId, conversationId, peerId, kind,
    /// status, durationSec }) - UNIQUE, so a retry storm can't double-write.
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

/// The visibility clock re-queries only when a relevant cached root/reply expires.
struct ThreadReplyCountSnapshot: Equatable {
    var counts: [String: Int] = [:]
    var nextExpiry: Date?
}
