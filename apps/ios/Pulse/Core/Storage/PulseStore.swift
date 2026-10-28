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
        ).path, composerOwner: viewerId)
    }

    private let dbQueue: DatabaseQueue
    private let draftCheckpoints: PulseDraftCheckpointJournal
    private let savedSnapshotVersions = MessageSnapshotVersions()
    private let messageSnapshotVersions = MessageSnapshotVersions()

    func messageSnapshotVersion(_ id: String) -> UInt64 { messageSnapshotVersions.version(id) }
    /// Capture before a read-only HTTP request; never use this lane for mutation ACKs.
    func beginMessageSnapshot() -> UInt64 { messageSnapshotVersions.begin() }
    struct SupersededMessageReadState: Error {}

    let storageIdentity: String

    let composerOwner: String?
    public init(path: String, composerOwner: String? = nil) throws {
        self.composerOwner = composerOwner
        draftCheckpoints = PulseDraftCheckpointJournal(path: path)
        storageIdentity = URL(fileURLWithPath: path).standardizedFileURL.path
        dbQueue = try DatabaseQueue(path: path)
        try migrator.migrate(dbQueue)
    }

    /// In-memory store (previews/tests).
    public init(owner: String? = nil) throws {
        composerOwner = owner
        draftCheckpoints = PulseDraftCheckpointJournal(path: nil)
        storageIdentity = "memory:" + UUID().uuidString
        dbQueue = try DatabaseQueue()
        try migrator.migrate(dbQueue)
    }

    /// Retry metadata is account-scoped with the database. Terminal operation
    /// receipts are deliberately not touched by expiry of these admission holds.
    func holdOutboxRetry(scope: String, until: TimeInterval) throws -> TimeInterval {
        guard until.isFinite else { throw CocoaError(.coderInvalidValue) }
        let key = "outbox-retry:v1|" + SHA256.hash(data: Data(scope.utf8)).map { String(format: "%02x", $0) }.joined()
        return try dbQueue.write { db in
            let previous = try Double.fetchOne(db, sql: "SELECT updatedAt FROM wave7Cache WHERE key=?", arguments: [key]) ?? 0
            let deadline = max(previous, until)
            try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt", arguments: [key,scope,deadline])
            return deadline
        }
    }
    func outboxRetryDeadlines(now: TimeInterval) throws -> [String: TimeInterval] {
        try dbQueue.write { db in
            let prefix = "outbox-retry:v1|"
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key IN (SELECT key FROM wave7Cache WHERE substr(key,1,length(?))=? AND updatedAt<=? ORDER BY updatedAt LIMIT 500)", arguments: [prefix,prefix,now])
            let rows = try Row.fetchAll(db, sql: "SELECT json,updatedAt FROM wave7Cache WHERE substr(key,1,length(?))=? AND updatedAt>?", arguments: [prefix,prefix,now])
            return try Dictionary(rows.map { row in
                let scope: String = row["json"], until: Double = row["updatedAt"]
                guard until.isFinite else { throw CocoaError(.coderReadCorrupt) }
                return (scope,until)
            }, uniquingKeysWith: max)
        }
    }

    /// Compact consumption receipts survive; only independently acknowledged
    /// checkpoint bodies are removed. Persist the scan cursor across restarts.
    func pruneConsumedDraftCheckpoints() throws -> Int {
        let cursorKey = "draft-checkpoint-scan:v1"
        let cursor = try dbQueue.read { try String.fetchOne($0, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [cursorKey]) ?? "" }
        let page = try draftCheckpoints.page(after: cursor)
        guard !page.snapshots.isEmpty || !cursor.isEmpty || !page.next.isEmpty else { return 0 }
        // Rehydrate unsubmitted edit / displaced-window snapshots into the
        // recovery list, never into the outbound intent namespace. ACK the UUID
        // read, not whichever file a concurrent typing writer creates later.
        try dbQueue.write { db in
            for snapshot in page.snapshots {
                let owner = composerOwner ?? "local"
                let edit = snapshot.scope.hasPrefix("composer-local-edit:v1|\(owner)|")
                let detached = snapshot.scope.hasPrefix("composer-detached:v1|\(owner)|")
                guard edit || detached, try draftCheckpoints.read(snapshot.scope)?.id == snapshot.id else { continue }
                let ack = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [draftCheckpoints.receiptKey(snapshot.scope)])
                guard ack != snapshot.id.uuidString else { continue }
                if edit {
                    guard (try? JSONDecoder().decode(PulseComposerLocalEdit.self, from: Data(snapshot.text.utf8))) != nil else { continue }
                } else {
                    guard (try? JSONDecoder().decode(PulseDetachedDraft.self, from: Data(snapshot.text.utf8))) != nil else { continue }
                }
                try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt", arguments: [snapshot.scope,snapshot.text,Date().timeIntervalSince1970])
                try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,0) ON CONFLICT(key) DO UPDATE SET json=excluded.json", arguments: [draftCheckpoints.receiptKey(snapshot.scope),snapshot.id.uuidString])
            }
        }
        let consumed = try dbQueue.read { db in
            try page.snapshots.filter { snapshot in
                try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [draftCheckpoints.receiptKey(snapshot.scope)]) == snapshot.id.uuidString
            }
        }
        // Never unlink on the strength of an uncommitted SQLite transaction.
        for snapshot in consumed { try draftCheckpoints.remove(snapshot.scope, id: snapshot.id) }
        try dbQueue.write { db in
            try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,0) ON CONFLICT(key) DO UPDATE SET json=excluded.json", arguments: [cursorKey,page.next])
        }
        return consumed.count
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
            // Saved-library projection (paged GET /api/users/{id}/saved) -
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
    func replaceMessageReadState(conversationId: String, messageId: String, memberships: [String], snapshotVersion: UInt64? = nil) throws -> Bool {
        try dbQueue.write { db in
            if let snapshotVersion, messageSnapshotVersions.version(messageId) != snapshotVersion {
                throw SupersededMessageReadState()
            }
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [messageId]),
                  var message = Self.messageRow(from: row), message.conversationId == conversationId,
                  !message.id.hasPrefix("local_"), (message.readByMembershipIds ?? []) != memberships else { return false }
            message.readByMembershipIds = memberships
            try Self.writeMessage(message, db: db)
            return true
        }
    }

    /// Persist fair retry order without changing evidence or original authority.
    func deferRead(_ observation: PulseQueuedRead) throws {
        try dbQueue.write { db in
            let prefix = "read-journal:v1|\(observation.owner)|"
            for key in [observation.key, observation.legacyKey] {
                if let json = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key = ?", arguments: [key]),
                   let current = try? JSONDecoder().decode(PulseQueuedRead.self, from: Data(json.utf8)), current == observation {
                    try db.execute(sql: """
                        UPDATE wave7Cache SET updatedAt = (
                            SELECT COALESCE(MAX(updatedAt), 0) + 1 FROM wave7Cache
                            WHERE substr(key, 1, length(?)) = ?
                        ) WHERE key = ? AND json = ?
                        """, arguments: [prefix, prefix, key, json])
                }
            }
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
    public func acceptThreadSnapshot(rootId: String, roomId: String, messages: [WireChatMessage], snapshotRequest: UInt64? = nil) throws -> WireThreadPage {
        try dbQueue.write { db in
            var replies: [WireChatMessage] = []
            let accepted = messages.filter { $0.conversationId == roomId && ($0.id == rootId || $0.parentId == rootId)
                && messageSnapshotVersions.accepts($0.id, request: snapshotRequest) }
            for message in accepted {
                let saved = try Self.writeMessage(message, db: db)
                if saved.parentId == rootId { replies.append(saved) }
            }
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [rootId]),
                  let parent = Self.messageRow(from: row),
                  PulseRemediationLogic.threadRootAvailable(parent, roomId: roomId, rootId: rootId) else {
                throw ThreadAccessError()
            }
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key = ?", arguments: [Self.threadDenialKey(rootId)])
            let projected = try Self.projectCachedQuotes(replies, db: db)
            messageSnapshotVersions.note(accepted.map(\.id), request: snapshotRequest)
            return WireThreadPage(parent: parent, replies: projected)
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
    public func mergeMessages(_ messages: [WireChatMessage], snapshotRequest: UInt64? = nil) throws -> [WireChatMessage] {
        try dbQueue.write { db in
            var committed: [WireChatMessage] = [], accepted: [String] = []
            for message in messages {
                if messageSnapshotVersions.accepts(message.id, request: snapshotRequest) {
                    committed.append(try Self.writeMessage(message, db: db))
                    accepted.append(message.id)
                } else if let row = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [message.id]),
                          let current = Self.messageRow(from: row) {
                    committed.append(current) // the return value must not revive the rejected HTTP row
                }
            }
            // A later source in the same page may have revoked an earlier quote.
            let projected = try Self.projectCachedQuotes(committed, db: db)
            messageSnapshotVersions.note(accepted, request: snapshotRequest)
            return projected
        }
    }

    /// Resolve a previously accepted page again after an actor suspension. This
    /// never writes the old response back over a newer canonical cache commit.
    func currentMessageSnapshot(_ messages: [WireChatMessage]) throws -> [WireChatMessage] {
        try dbQueue.read { db in
            var known: [String: WireChatMessage] = [:]
            let ids = Array(Set(messages.map(\.id)))
            for start in stride(from: 0, to: ids.count, by: 400) {
                let part = Array(ids[start..<min(start + 400, ids.count)])
                let marks = Array(repeating: "?", count: part.count).joined(separator: ",")
                let rows = try Row.fetchAll(db, sql: "SELECT * FROM message WHERE id IN (\(marks))", arguments: StatementArguments(part))
                for row in rows { if let message = Self.messageRow(from: row) { known[message.id] = message } }
            }
            return messages.compactMap { known[$0.id] }
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
        guard composerOwner == nil || composerOwner == message.senderId else { throw CocoaError(.coderInvalidValue) }
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
                if consume, let token { try self.writeDraftSnapshot(db, room: token.storageKey, text: "") }
            }
        }
        if let token {
            return try draftFence.enqueue(token.storageKey, expected: token.revision, action: commit)
        }
        try commit(false)
        return false
    }

    private static func writeAcknowledgedMessage(_ message: WireChatMessage, db: Database) throws -> WireChatMessage {
        let current = try Row.fetchOne(db, sql: "SELECT * FROM message WHERE id = ?", arguments: [message.id]).flatMap(Self.messageRow(from:))
        return try Self.writeMessage(PulseMessageMerge.retainAcknowledgedReadState(current, incoming: message), db: db)
    }

    @discardableResult
    public func mergeMessageAcknowledgement(_ message: WireChatMessage) throws -> WireChatMessage {
        try dbQueue.write { db in
            let retained = try Self.writeAcknowledgedMessage(message, db: db)
            messageSnapshotVersions.note([message.id])
            return retained
        }
    }

    /// The acknowledgement is one SQLite transaction, never three independent writes.
    /// Return the committed row: a faster read receipt must survive UI delivery too.
    @discardableResult
    public func resolveDelivery(clientId: String, message: WireChatMessage) throws -> WireChatMessage {
        try dbQueue.write { db in
            let retained = try Self.writeAcknowledgedMessage(message, db: db)
            try db.execute(sql: "DELETE FROM message WHERE id = ?", arguments: ["local_" + clientId])
            try db.execute(sql: "DELETE FROM outbox WHERE clientId = ?", arguments: [clientId])
            messageSnapshotVersions.note([message.id])
            return retained
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
    func cachedThreadPage(roomId: String, rootId: String, before: PulseCachedThreadCursor? = nil, after: PulseCachedThreadCursor? = nil,
                          limit: Int = 100, now: Date = Date()) throws -> PulseCachedThreadPage {
        if let before, before.roomId != roomId || before.rootId != rootId { throw CocoaError(.coderInvalidValue) }
        if let after, after.roomId != roomId || after.rootId != rootId { throw CocoaError(.coderInvalidValue) }
        if before != nil && after != nil { throw CocoaError(.coderInvalidValue) }
        let forward = after != nil
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
                  AND (:afterCreated IS NULL OR reply.createdAt > :afterCreated
                       OR (reply.createdAt = :afterCreated AND reply.id > :afterId))
                ORDER BY reply.createdAt \(forward ? "ASC" : "DESC"), reply.id \(forward ? "ASC" : "DESC") LIMIT :limit
                """, arguments: ["room": roomId, "root": rootId, "now": now.timeIntervalSince1970,
                    "beforeCreated": before?.createdAt, "beforeId": before?.id,
                    "afterCreated": after?.createdAt, "afterId": after?.id, "limit": size + 1])
            let selected = Array(rows.prefix(size))
            let ordered = forward ? selected : Array(selected.reversed())
            func cursor(_ row: Row?) -> PulseCachedThreadCursor? {
                row.map { PulseCachedThreadCursor(roomId: roomId, rootId: rootId, createdAt: $0["createdAt"], id: $0["id"]) }
            }
            return PulseCachedThreadPage(messages: ordered.compactMap(Self.messageRow(from:)),
                cursor: cursor(ordered.first), hasMore: rows.count > size, newerCursor: cursor(ordered.last))
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
    public func upsert(savedItems: [WireSavedItem], replacingSnapshot: Bool = false, preservingMessageIds: Set<String> = [], snapshotRequest: UInt64? = nil, savedOwner: String? = nil, savedSnapshot: UInt64? = nil) throws {
        try dbQueue.write { db in
            if replacingSnapshot { try db.execute(sql: "DELETE FROM savedMessages") }
            var accepted: [String] = []
            for item in savedItems {
                if !preservingMessageIds.contains(item.message.id), messageSnapshotVersions.accepts(item.message.id, request: snapshotRequest) {
                    try Self.writeMessage(item.message, db: db)
                    accepted.append(item.message.id)
                }
                if let owner = savedOwner, let revision = item.revision {
                    let state = WireSavedStateReceipt(savedReceiptVersion: 2, userId: owner, messageId: item.message.id,
                        saved: true, savedAt: item.savedAt, revision: revision, visible: true)
                    try state.validate(owner: owner, id: item.message.id)
                    try mergeSavedState(state, db: db, snapshot: savedSnapshot ?? beginSavedSnapshot())
                    continue
                }
                guard savedSnapshotVersions.accepts(item.message.id, request: savedSnapshot) else { continue }
                if let owner = savedOwner {
                    if let revision = item.revision { try Self.savedMetadata(db, key: Self.savedVersionKey(owner,item.message.id), json: revision) }
                    if try Self.savedPending(db, owner: owner, id: item.message.id) { continue }
                }
                savedSnapshotVersions.note([item.message.id], request: savedSnapshot)
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
            messageSnapshotVersions.note(accepted, request: snapshotRequest)
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

    func cachedSavedPage(ids: [String]? = nil, now: Date = Date()) throws -> [WireSavedItem] {
        try dbQueue.read { db in try Self.savedItems(in: db, now: now, selectedIds: ids, limit: 100) }
    }
    func observeSavedPage(ids: [String]?) -> DatabasePublishers.Value<[WireSavedItem]> {
        ValueObservation.tracking { db in try Self.savedItems(in: db, now: Date(), selectedIds: ids, limit: 100) }.publisher(in: dbQueue)
    }

    private static func savedItems(in db: Database, now: Date, selectedIds: [String]? = nil, limit: Int = -1) throws -> [WireSavedItem] {
        var arguments: StatementArguments = ["now": now.timeIntervalSince1970, "pageLimit": limit]
        let selection: String
        if let ids = selectedIds {
            selection = ids.isEmpty ? " AND 0" : " AND s.messageId IN (\(Array(repeating: "?", count: ids.count).joined(separator: ",")))"
            arguments += StatementArguments(ids)
        } else { selection = "" }
        return try Row.fetchAll(db, sql: """
            SELECT m.*, s.savedAt AS savedStamp, c.title AS savedConversationName,
                   c.kind AS savedConversationKind, p.expiresAtEpoch AS savedRootExpiry
            FROM savedMessages s JOIN message m ON m.id = s.messageId
            LEFT JOIN conversation c ON c.id = s.conversationId
            LEFT JOIN message p ON p.id = m.parentId
            WHERE NOT EXISTS (SELECT 1 FROM wave7Cache a WHERE a.key = 'thread-access-denied:v1|' || m.parentId)
              AND (p.id IS NULL OR (p.conversationId = m.conversationId AND p.parentId IS NULL AND p.deletedAt IS NULL
                AND (p.expiresAtEpoch IS NULL OR p.expiresAtEpoch > :now)))
              AND m.deletedAt IS NULL AND (m.expiresAtEpoch IS NULL OR m.expiresAtEpoch > :now)
            \(selection)
            ORDER BY s.savedAt DESC, s.messageId DESC LIMIT :pageLimit
            """, arguments: arguments).compactMap { row in
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
        var ids = Set<String>()
        var cursor: String?
        repeat {
            let page = try cachedSavedOfflinePage(owner: "local-id-index", cursor: cursor)
            ids.formUnion(page.items.map { $0.message.id }); cursor = page.nextCursor
        } while cursor != nil
        return ids
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
            try OutboxRow.fetchOne(db, sql: "SELECT * FROM outbox WHERE failureReason IS NULL" + exclusion +
                " AND NOT EXISTS (SELECT 1 FROM outbox earlier WHERE earlier.conversationId = outbox.conversationId AND earlier.failureReason IS NULL AND earlier.id < outbox.id) ORDER BY attempts ASC, id ASC LIMIT 1",
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
            // Try both persistent write paths: a checkpoint error must not prevent a
            // healthy SQLite commit, and a SQLite error keeps the checkpoint.
            let checkpoint = try? draftCheckpoints.write(conversationId, text: text)
            try dbQueue.write { db in try self.writeDraftSnapshot(db, room: conversationId, text: text) }
            if let checkpoint { try? draftCheckpoints.remove(conversationId, id: checkpoint.id) }
        }
    }

    private func writeDraftSnapshot(_ db: Database, room: String, text: String) throws {
        try acknowledgeDraftCheckpoint(db, scope: room)
        if let owner = composerOwner {
            let scope = try PulseThreadDraft.scope(room)
            let before = try String.fetchOne(db, sql: "SELECT text FROM draft WHERE conversationId=?", arguments: [room]) ?? ""
            try Self.stageComposer(db, owner: owner, kind: "draft", target: scope.room, room: scope.room, root: scope.root,
                text: text, baseText: before, editedAt: nil)
        }
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
    private func recoverableDraftCheckpoint(_ db: Database, room: String) throws -> PulseDraftCheckpointJournal.Snapshot? {
        guard let snapshot = try draftCheckpoints.read(room) else { return nil }
        let accepted = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [draftCheckpoints.receiptKey(room)])
        if accepted == snapshot.id.uuidString {
            try? draftCheckpoints.remove(room, id: snapshot.id)
            return nil
        }
        return snapshot
    }
    func draftSnapshot(conversationId: String) -> String? {
        try? dbQueue.read { db in
            if let snapshot = try recoverableDraftCheckpoint(db, room: conversationId) { return snapshot.text }
            return try String.fetchOne(db, sql: "SELECT text FROM draft WHERE conversationId = :cid",
                arguments: ["cid": conversationId])
        }
    }

    /// Every local draft (chats-list preview rehydration).
    public func allDrafts(includingClears: Bool = false) -> [String: String] {
        let rows = (try? dbQueue.read { try allDraftRowsIncludingCheckpoints($0) }) ?? []
        return Dictionary(rows.filter { includingClears || !$0.text.isEmpty }.map { ($0.conversationId,$0.text) }, uniquingKeysWith: { _, later in later })
    }

    private func allDraftRowsIncludingCheckpoints(_ db: Database) throws -> [DraftRow] {
        let saved = try DraftRow.fetchAll(db, sql: "SELECT * FROM draft ORDER BY updatedAt ASC")
        var rows = Dictionary(saved.map { ($0.conversationId,$0) }, uniquingKeysWith: { _, later in later })
        for scope in try draftCheckpoints.scopes() where !scope.hasPrefix("composer-local-edit:v1|") && !scope.hasPrefix("composer-detached:v1|") {
            if let snapshot = try recoverableDraftCheckpoint(db,room: scope) {
                rows[scope] = DraftRow(conversationId: scope,text: snapshot.text,updatedAt: "checkpoint:" + snapshot.id.uuidString)
            }
        }
        return rows.values.sorted { $0.updatedAt == $1.updatedAt ? $0.conversationId < $1.conversationId : $0.updatedAt < $1.updatedAt }
    }
    public func allDraftRows() throws -> [DraftRow] {
        try dbQueue.read { db in try allDraftRowsIncludingCheckpoints(db).filter { !$0.text.isEmpty } }
    }

    /// Clear only the exact draft the manager displayed, not a newer composer write.
    func deleteDraftIfUnchanged(_ row: DraftRow, revision: UInt64) throws -> Bool {
        try draftFence.removeIfCurrent(row.conversationId, expected: revision) {
            try dbQueue.write { db in
                if let snapshot = try recoverableDraftCheckpoint(db,room: row.conversationId) {
                    guard row.updatedAt == "checkpoint:" + snapshot.id.uuidString, row.text == snapshot.text else { return false }
                } else {
                    let current = try DraftRow.fetchOne(db, sql: "SELECT * FROM draft WHERE conversationId = ?", arguments: [row.conversationId])
                    guard current == row else { return false }
                }
                try self.writeDraftSnapshot(db, room: row.conversationId, text: "")
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

/// In-memory ordering evidence, scoped to this account's store lifetime. The
/// database queue serializes writers; the lock also permits a pre-HTTP capture
/// from another executor. This never supplies Seen proof or survives a restart.
private final class MessageSnapshotVersions: @unchecked Sendable {
    private let lock = NSLock()
    private var versions: [String: UInt64] = [:]
    private var requests: [String: UInt64] = [:]
    private var nextRequest: UInt64 = 0
    func begin() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        nextRequest &+= 1
        return nextRequest
    }
    func accepts(_ id: String, request: UInt64?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return request.map { (requests[id] ?? 0) <= $0 } ?? true
    }
    func version(_ id: String) -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        return versions[id] ?? 0
    }
    func note(_ ids: [String], request: UInt64? = nil) {
        lock.lock(); defer { lock.unlock() }
        for id in Set(ids) {
            versions[id, default: 0] &+= 1
            if let request { requests[id] = max(requests[id] ?? 0, request) }
        }
    }
}

// Bookmark journals share the account-owned durable metadata table. They are
// not feature caches: never retire an intent from an unbound HTTP error.
extension PulseStore {
    private static func savedPrefix(_ owner: String, _ id: String? = nil) -> String {
        "saved-intent:v1|\(owner)|" + (id.map { "\($0)|" } ?? "")
    }
    private static func savedVersionKey(_ owner: String, _ id: String) -> String { "saved-version:v1|\(owner)|\(id)" }
    private static func savedPending(_ db: Database, owner: String, id: String) throws -> Bool {
        let prefix = savedPrefix(owner, id)
        return try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM wave7Cache WHERE substr(key,1,length(?)) = ?)", arguments: [prefix, prefix]) ?? false
    }
    private static func savedMetadata(_ db: Database, key: String, json: String, order: Int64 = 0) throws {
        try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt", arguments: [key,json,order])
    }
    func beginSavedSnapshot() -> UInt64 { savedSnapshotVersions.begin() }

    func enqueueSavedIntent(owner: String, message: WireChatMessage, saved: Bool) throws {
        let request = beginSavedSnapshot()
        try dbQueue.write { db in
            let prefix = Self.savedPrefix(owner, message.id)
            let head = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: ["saved-head:v1|\(owner)|\(message.id)"])
            let previous = try head.flatMap { id in try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [prefix + id]) }
                .map { try JSONDecoder().decode(PulseSavedIntent.self, from: Data($0.utf8)) }
            let version = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [Self.savedVersionKey(owner,message.id)]) ?? "0"
            let intent = PulseSavedIntent(owner: owner, messageId: message.id, conversationId: message.conversationId, saved: saved,
                clientId: UUID().uuidString, expectedRevision: previous == nil ? version : nil, predecessorClientId: previous?.clientId)
            let all = Self.savedPrefix(owner)
            let order = try Int64.fetchOne(db, sql: "SELECT COALESCE(MAX(updatedAt),0)+1 FROM wave7Cache WHERE substr(key,1,length(?))=?", arguments: [all,all]) ?? 1
            try Self.savedMetadata(db, key: intent.key, json: String(decoding: JSONEncoder().encode(intent), as: UTF8.self), order: order)
            try Self.savedMetadata(db, key: "saved-head:v1|\(owner)|\(message.id)", json: intent.clientId)
            try Self.savedMetadata(db, key: Self.savedVersionKey(owner,message.id), json: version)
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?", arguments: ["saved-sync-notice:v1|\(owner)"])
            if saved {
                try db.execute(sql: "INSERT INTO savedMessages(messageId,conversationId,savedAt) VALUES (?,?,?) ON CONFLICT(messageId) DO UPDATE SET savedAt=excluded.savedAt",
                    arguments: [message.id,message.conversationId,Self.savedStamp()])
            } else { try db.execute(sql: "DELETE FROM savedMessages WHERE messageId=?", arguments: [message.id]) }
            savedSnapshotVersions.note([message.id], request: request)
        }
    }
    private static func savedStamp() -> String {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime,.withFractionalSeconds]
        return formatter.string(from: Date())
    }
    func pendingSaved(owner: String) throws -> [PulseSavedIntent] {
        try dbQueue.write { db in
            let prefix = Self.savedPrefix(owner)
            let rows = try Row.fetchAll(db, sql: "SELECT key,json FROM wave7Cache WHERE substr(key,1,length(?))=? ORDER BY updatedAt,key LIMIT 100", arguments: [prefix,prefix])
            var intents: [PulseSavedIntent] = []
            for row in rows {
                let key: String = row["key"], json: String = row["json"]
                do {
                    let intent = try JSONDecoder().decode(PulseSavedIntent.self, from: Data(json.utf8))
                    try intent.validate(owner: owner, rowKey: key)
                    intents.append(intent)
                } catch {
                    // Retain unreadable bytes and pending status, but do not let
                    // one damaged record prevent unrelated messages from syncing.
                    try db.execute(sql: "UPDATE wave7Cache SET updatedAt=(SELECT COALESCE(MAX(updatedAt),0)+1 FROM wave7Cache WHERE substr(key,1,length(?))=?) WHERE key=?", arguments: [prefix,prefix,key])
                    try Self.savedMetadata(db, key: "saved-sync-notice:v1|\(owner)", json: "Some saved changes could not be read from storage. They are retained on this device; other changes can still sync.")
                }
            }
            return intents
        }
    }
    func hasPendingSaved(owner: String) throws -> Bool {
        try dbQueue.read { db in
            let prefix = Self.savedPrefix(owner)
            return try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM wave7Cache WHERE substr(key,1,length(?))=?)", arguments: [prefix,prefix]) ?? false
        }
    }
    /// Server revisions order commits, not HTTP starts. Keep the canonical state
    /// even behind an optimistic marker so a late terminal ACK can retire its
    /// intent without restoring an older marker or losing a newer visibility read.
    private func mergeSavedState(_ incoming: WireSavedStateReceipt, db: Database, snapshot: UInt64) throws {
        let key = "saved-canonical:v1|\(incoming.userId)|\(incoming.messageId)"
        let previous = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: [key])
            .map { try JSONDecoder().decode(WireSavedStateReceipt.self, from: Data($0.utf8)) }
        if let previous { try previous.validate(owner: incoming.userId, id: incoming.messageId) }
        let comparison = previous.map { WireSavedStateReceipt.compareRevisions(incoming.revision!, $0.revision!) } ?? 1
        let accepts = comparison > 0 || (comparison == 0 && savedSnapshotVersions.accepts(incoming.messageId, request: snapshot))
        let state = accepts ? incoming : previous!
        if accepts {
            try Self.savedMetadata(db, key: key, json: String(decoding: JSONEncoder().encode(state), as: UTF8.self))
            try Self.savedMetadata(db, key: Self.savedVersionKey(state.userId,state.messageId), json: state.revision!)
            savedSnapshotVersions.note([state.messageId], request: snapshot)
        }
        if try !Self.savedPending(db, owner: state.userId, id: state.messageId) {
            if state.saved && state.visible == true {
                try db.execute(sql: "INSERT INTO savedMessages(messageId,conversationId,savedAt) SELECT id,conversationId,? FROM message WHERE id=? ON CONFLICT(messageId) DO UPDATE SET savedAt=excluded.savedAt", arguments: [state.savedAt,state.messageId])
            } else { try db.execute(sql: "DELETE FROM savedMessages WHERE messageId=?", arguments: [state.messageId]) }
        }
    }
    func acceptSavedState(_ state: WireSavedStateReceipt, intent: PulseSavedIntent? = nil, snapshot: UInt64) throws {
        try state.validate(owner: intent?.owner ?? state.userId, id: intent?.messageId ?? state.messageId, intent: intent)
        try dbQueue.write { db in
            if let intent { try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?", arguments: [intent.key]) }
            try mergeSavedState(state, db: db, snapshot: snapshot)
            if let intent {
                if ["conflict", "rejected"].contains(state.outcome ?? "") {
                    let text = state.outcome == "conflict" ? "Saved state changed on another device. Review it before trying again." : "A saved change was refused because the message or access changed."
                    try Self.savedMetadata(db, key: "saved-sync-notice:v1|\(state.userId)", json: text)
                } else if try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: ["saved-head:v1|\(state.userId)|\(state.messageId)"]) == intent.clientId {
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?", arguments: ["saved-sync-notice:v1|\(state.userId)"])
                }
            }
        }
    }
    func deferSavedIntent(_ intent: PulseSavedIntent) throws {
        try dbQueue.write { db in
            let prefix = Self.savedPrefix(intent.owner)
            try db.execute(sql: "UPDATE wave7Cache SET updatedAt=(SELECT COALESCE(MAX(updatedAt),0)+1 FROM wave7Cache WHERE substr(key,1,length(?))=?) WHERE key=?", arguments: [prefix,prefix,intent.key])
        }
    }
    func seedSavedVersions(owner: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "INSERT OR IGNORE INTO wave7Cache(key,json,updatedAt) SELECT ? || messageId,'0',0 FROM savedMessages", arguments: ["saved-version:v1|\(owner)|"])
        }
    }
    func savedVersionPage(owner: String, after: String) throws -> [String] {
        try dbQueue.read { db in
            let prefix = "saved-version:v1|\(owner)|"
            return try String.fetchAll(db, sql: "SELECT key FROM wave7Cache WHERE substr(key,1,length(?))=? AND key>? ORDER BY key LIMIT 100", arguments: [prefix,prefix,after])
        }
    }
    private static func savedStatus(_ db: Database, owner: String) throws -> String? {
        let prefix = savedPrefix(owner)
        let pending = try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM wave7Cache WHERE substr(key,1,length(?))=?)", arguments: [prefix,prefix]) ?? false
        let notice = try String.fetchOne(db, sql: "SELECT json FROM wave7Cache WHERE key=?", arguments: ["saved-sync-notice:v1|\(owner)"])
        let text = [pending ? "Saved changes are waiting to sync." : nil, notice].compactMap { $0 }.joined(separator: " ")
        return text.isEmpty ? nil : text
    }
    func observeSavedSyncStatus(owner: String) -> DatabasePublishers.Value<String?> {
        ValueObservation.tracking { db in try Self.savedStatus(db, owner: owner) }.publisher(in: dbQueue)
    }
    func cachedSavedOfflinePage(owner: String, cursor: String?) throws -> PulseSavedCachedPage {
        var edge: PulseSavedOfflineCursor?
        if let cursor {
            guard cursor.hasPrefix("local-saved:"), let data = Data(base64Encoded: String(cursor.dropFirst(12))) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Invalid cached saved page.")
            }
            edge = try JSONDecoder().decode(PulseSavedOfflineCursor.self, from: data)
            guard edge?.owner == owner else { throw CancellationError() }
        }
        return try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT messageId,savedAt FROM savedMessages WHERE ? IS NULL OR savedAt<? OR (savedAt=? AND messageId<?) ORDER BY savedAt DESC,messageId DESC LIMIT 101", arguments: [edge?.at,edge?.at,edge?.at,edge?.id])
            let page = Array(rows.prefix(100))
            var next: String?
            if rows.count > 100, let last = page.last {
                next = "local-saved:" + (try JSONEncoder().encode(PulseSavedOfflineCursor(owner: owner, at: last["savedAt"], id: last["messageId"]))).base64EncodedString()
            }
            return PulseSavedCachedPage(items: try Self.savedItems(in: db, now: Date(), selectedIds: page.map { $0["messageId"] }, limit: 100), nextCursor: next)
        }
    }
}

// Composer operations intentionally live outside ordinary cache eviction. Draft
// consumption and its server-clear command share the outgoing SQLite transaction.
extension PulseStore {
    struct ComposerRow { let intent: PulseComposerIntent }
    private static func composerPrefix(_ owner: String) -> String { "composer-intent:v1|\(owner)|" }
    private static func composerHead(_ key: String) -> String { key.replacingOccurrences(of: "composer-intent:v1|", with: "composer-head:v1|") }
    private static func heldComposer(_ key: String) -> String { key.replacingOccurrences(of: "composer-intent:v1|", with: "composer-blocked:v1|") }
    private static func composerKey(owner: String, kind: String, target: String, root: String?) -> String {
        let scope = [kind,target,root ?? ""].map { "\($0.utf16.count):\($0)" }.joined()
        return composerPrefix(owner) + SHA256.hash(data: Data(scope.utf8)).map { String(format: "%02x",$0) }.joined()
    }
    private static func composerKey(_ intent: PulseComposerIntent) -> String {
        composerKey(owner: intent.owner,kind: intent.kind,target: intent.targetId,root: intent.rootId)
    }
    private static func decodeComposer(_ json: String, key: String) throws -> PulseComposerIntent {
        let value = try JSONDecoder().decode(PulseComposerIntent.self,from: Data(json.utf8))
        let expected = composerKey(value)
        guard ["edit","draft"].contains(value.kind), !value.owner.isEmpty, !value.targetId.isEmpty,
              !value.conversationId.isEmpty, UUID(uuidString: value.desiredId) != nil,
              key == expected || key == heldComposer(expected) else { throw CocoaError(.coderInvalidValue) }
        return value
    }
    private static func composer(_ db: Database, key: String) throws -> PulseComposerIntent? {
        guard let json = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [key]) else { return nil }
        return try decodeComposer(json,key: key)
    }
    private static func putComposer(_ db: Database, key: String, value: PulseComposerIntent) throws {
        let prefix = composerPrefix(value.owner)
        let order = try Double.fetchOne(db,sql: "SELECT COALESCE(MAX(updatedAt),0)+1 FROM wave7Cache WHERE substr(key,1,length(?))=?",arguments: [prefix,prefix]) ?? 1
        let json = String(decoding: try JSONEncoder().encode(value),as: UTF8.self)
        try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt",arguments: [key,json,order])
    }
    private static func stageComposer(_ db: Database, owner: String, kind: String, target: String, room: String, root: String?, text: String, baseText: String, editedAt: String?, localEditRevision: String? = nil) throws {
        let key = composerKey(owner: owner,kind: kind,target: target,root: root)
        let previous = try composer(db,key: key) ?? composer(db,key: heldComposer(key))
        if let previous, previous.text.utf16.elementsEqual(text.utf16), previous.blocked == nil { return }
        let baseline = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [composerHead(key)])
            .map { try JSONDecoder().decode(PulseComposerReceipt.self,from: Data($0.utf8)) }
        let value = PulseComposerIntent(owner: owner,kind: kind,targetId: target,conversationId: room,rootId: root,
            desiredId: UUID().uuidString.lowercased(),text: text,baseText: kind == "edit" ? baseText : previous?.baseText ?? baseText,
            baseEditedAt: kind == "edit" ? editedAt : previous != nil ? previous!.baseEditedAt : editedAt,
            baseRevision: kind == "edit" ? (baseline?.matchesBaselineText(baseText) == true && PulseFormat.date(baseline?.editedAt) == PulseFormat.date(editedAt) ? baseline?.revision : nil) : previous != nil ? previous!.baseRevision : baseline?.revision,
            localEditRevision: localEditRevision,flight: previous?.flight,blocked: nil)
        try putComposer(db,key: key,value: value)
        try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [heldComposer(key)])
    }
    func enqueueComposerEdit(_ message: WireChatMessage, text: String, owner: String) throws {
        guard composerOwner == nil || composerOwner == owner, message.senderId == owner else { throw CocoaError(.coderInvalidValue) }
        try draftFence.withEditSnapshot(Self.localEditKey(owner: owner,id: message.id)) { memory in
            let buffered = memory
            try dbQueue.write { db in
                let local = try buffered ?? self.recoveryLocalEdit(db,owner: owner,id: message.id)
                if let local { try Self.putLocalEdit(db,owner: owner,id: message.id,value: local) }
                try self.acknowledgeDraftCheckpoint(db, scope: Self.localEditKey(owner: owner,id: message.id))
                try Self.stageComposer(db,owner: owner,kind: "edit",target: message.id,room: message.conversationId,root: nil,
                    text: text,baseText: local?.baseText ?? message.content,editedAt: local != nil ? local!.baseEditedAt : message.editedAt,localEditRevision: local?.revision)
            }
            memory = nil
        }
    }
    func composerEditPending(owner: String, id: String) throws -> Bool {
        try dbQueue.read { db in
            let key = Self.composerKey(owner: owner,kind: "edit",target: id,root: nil)
            return try Bool.fetchOne(db,sql: "SELECT EXISTS(SELECT 1 FROM wave7Cache WHERE key IN (?,?))",arguments: [key,Self.heldComposer(key)]) ?? false
        }
    }
    func pendingComposerCount(owner: String) throws -> Int {
        try dbQueue.read { db in
            let prefix = Self.composerPrefix(owner)
            return try Int.fetchOne(db,sql: "SELECT COUNT(*) FROM wave7Cache WHERE substr(key,1,length(?))=?",arguments: [prefix,prefix]) ?? 0
        }
    }
    func composerPage(owner: String, held: Bool = false) throws -> [ComposerRow] {
        try dbQueue.write { db in
            let prefix = held ? Self.heldComposer(Self.composerPrefix(owner)) : Self.composerPrefix(owner)
            let rows = try Row.fetchAll(db,sql: "SELECT key,json,updatedAt FROM wave7Cache WHERE substr(key,1,length(?))=? ORDER BY updatedAt,key LIMIT 100",arguments: [prefix,prefix])
            var result: [ComposerRow] = []
            for row in rows {
                let key: String = row["key"], json: String = row["json"]
                do { result.append(ComposerRow(intent: try Self.decodeComposer(json,key: key))) }
                catch {
                    // Keep unparseable bytes; isolate them from the transport queue.
                    if !held {
                        let order: Double = row["updatedAt"]
                        try db.execute(sql: "INSERT OR IGNORE INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?)",arguments: [Self.heldComposer(key),json,order])
                        try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [key,json])
                    }
                }
            }
            return result
        }
    }
    func freezeComposer(_ desired: PulseComposerIntent, revision: String) throws -> PulseComposerIntent? {
        try dbQueue.write { db in
            let key = Self.composerKey(desired)
            guard var latest = try Self.composer(db,key: key), latest.desiredId == desired.desiredId, latest.flight == nil else { return nil }
            latest.flight = PulseComposerFlight(clientId: latest.desiredId,text: latest.text,expectedRevision: revision,localEditRevision: latest.localEditRevision)
            try Self.putComposer(db,key: key,value: latest)
            return latest
        }
    }
    func blockComposer(_ requested: PulseComposerIntent, notice: String) throws {
        try dbQueue.write { db in
            let key = Self.composerKey(requested)
            guard var latest = try Self.composer(db,key: key),
                  (requested.flight != nil ? latest.flight?.clientId == requested.flight?.clientId : latest.desiredId == requested.desiredId) else { return }
            latest.blocked = notice
            try Self.putComposer(db,key: Self.heldComposer(key),value: latest)
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [key])
        }
    }
    func deferComposer(_ requested: PulseComposerIntent) throws {
        try dbQueue.write { db in
            let key = Self.composerKey(requested)
            if let latest = try Self.composer(db,key: key) { try Self.putComposer(db,key: key,value: latest) }
        }
    }
    func acceptComposer(_ requested: PulseComposerIntent, receipt: PulseComposerReceipt) throws {
        try receipt.validate(requested,flight: requested.flight)
        try dbQueue.write { db in
            let key = Self.composerKey(requested)
            guard var latest = try Self.composer(db,key: key), let flight = requested.flight,
                  latest.flight?.clientId == flight.clientId else { return }
            if receipt.outcome != "applied" {
                latest.flight = nil; latest.blocked = "A newer server change won. Your text is retained for review."
                try Self.putComposer(db,key: Self.heldComposer(key),value: latest)
                try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [key])
            } else {
                try Self.recordComposerBaseline(db,receipt: receipt)
                if requested.kind == "edit", let version = flight.localEditRevision,
                   let local = try Self.localEdit(db,owner: requested.owner,id: requested.targetId),
                   local.revision == version, local.text.trimmingCharacters(in: .whitespacesAndNewlines).utf16.elementsEqual(flight.text.utf16) {
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [Self.localEditKey(owner: requested.owner,id: requested.targetId)])
                }
                if requested.kind == "edit", let row = try Row.fetchOne(db,sql: "SELECT * FROM message WHERE id=?",arguments: [requested.targetId]),
                   var current = Self.messageRow(from: row), current.senderId == requested.owner, current.deletedAt == nil,
                   (PulseFormat.date(current.editedAt) ?? .distantPast) <= (PulseFormat.date(receipt.editedAt) ?? .distantPast) {
                    current.content = receipt.text; current.editedAt = receipt.editedAt
                    _ = try Self.writeAcknowledgedMessage(current,db: db)
                    messageSnapshotVersions.note([current.id])
                }
                if latest.desiredId == flight.clientId { try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [key]) }
                else {
                    latest.flight = nil
                    if latest.kind == "draft" { latest.baseText = receipt.text; latest.baseEditedAt = receipt.editedAt; latest.baseRevision = receipt.revision }
                    try Self.putComposer(db,key: key,value: latest)
                }
            }
        }
    }
    private static func recordComposerBaseline(_ db: Database, receipt: PulseComposerReceipt) throws {
        let key = composerHead(composerKey(owner: receipt.userId,kind: receipt.kind,target: receipt.targetId,root: receipt.rootId))
        let json = String(decoding: try JSONEncoder().encode(receipt.metadataBaseline),as: UTF8.self)
        try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt",arguments: [key,json,Date().timeIntervalSince1970])
    }
    private func recoveryLocalEdit(_ db: Database, owner: String, id: String) throws -> PulseComposerLocalEdit? {
        if let snapshot = try recoverableDraftCheckpoint(db, room: Self.localEditKey(owner: owner,id: id)) {
            return try JSONDecoder().decode(PulseComposerLocalEdit.self,from: Data(snapshot.text.utf8))
        }
        return try Self.localEdit(db,owner: owner,id: id)
    }
    private func acknowledgeDraftCheckpoint(_ db: Database, scope: String) throws {
        if let snapshot = try draftCheckpoints.read(scope) {
            try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,0) ON CONFLICT(key) DO UPDATE SET json=excluded.json", arguments: [draftCheckpoints.receiptKey(scope),snapshot.id.uuidString])
        }
    }
    private static func localEditKey(owner: String, id: String) -> String { "composer-local-edit:v1|\(owner)|\(id)" }
    private static func localEdit(_ db: Database, owner: String, id: String) throws -> PulseComposerLocalEdit? {
        guard let json = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [localEditKey(owner: owner,id: id)]) else { return nil }
        return try JSONDecoder().decode(PulseComposerLocalEdit.self,from: Data(json.utf8))
    }
    private static func putLocalEdit(_ db: Database, owner: String, id: String, value: PulseComposerLocalEdit) throws {
        let json = String(decoding: try JSONEncoder().encode(value),as: UTF8.self)
        try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt",arguments: [localEditKey(owner: owner,id: id),json,Date().timeIntervalSince1970])
    }
    func saveComposerEdit(_ message: WireChatMessage, text: String, owner: String) throws {
        guard composerOwner == nil || composerOwner == owner, message.senderId == owner else { throw CocoaError(.coderInvalidValue) }
        try draftFence.withEditSnapshot(Self.localEditKey(owner: owner,id: message.id)) { memory in
            let previous = try memory ?? dbQueue.read { try self.recoveryLocalEdit($0,owner: owner,id: message.id) }
            let value = PulseComposerLocalEdit(text: text,baseText: previous?.baseText ?? message.content,baseEditedAt: previous != nil ? previous!.baseEditedAt : message.editedAt)
            memory = value
            let scope = Self.localEditKey(owner: owner,id: message.id)
            let raw = String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
            let checkpoint = try? draftCheckpoints.write(scope,text: raw)
            try dbQueue.write { db in
                try Self.putLocalEdit(db,owner: owner,id: message.id,value: value)
                try self.acknowledgeDraftCheckpoint(db,scope: scope)
            }
            if let checkpoint { try? draftCheckpoints.remove(scope,id: checkpoint.id) }
            memory = nil
        }
    }
    func recoveredComposerEdit(owner: String, id: String) throws -> String? {
        guard composerOwner == nil || composerOwner == owner else { throw CocoaError(.coderInvalidValue) }
        return try draftFence.withEditSnapshot(Self.localEditKey(owner: owner,id: id)) { memory in
            if let memory { return memory.text }
            return try dbQueue.read { db in
                if let local = try self.recoveryLocalEdit(db,owner: owner,id: id) { return local.text }
                let key = Self.composerKey(owner: owner,kind: "edit",target: id,root: nil)
                return try (Self.composer(db,key: key) ?? Self.composer(db,key: Self.heldComposer(key)))?.text
            }
        }
    }

}

extension PulseStore {
    func hasComposerDraft(owner: String, room: String, root: String?) throws -> Bool {
        try dbQueue.read { db in
            let key = Self.composerKey(owner: owner,kind: "draft",target: room,root: root)
            return try Bool.fetchOne(db,sql: "SELECT EXISTS(SELECT 1 FROM wave7Cache WHERE key IN (?,?))",arguments: [key,Self.heldComposer(key)]) ?? false
        }
    }
    func importComposerDraft(_ state: PulseComposerReceipt, token: PulseDraftConsumption, previous: String?) throws -> Bool {
        guard state.kind == "draft", state.targetId == token.conversationId, state.rootId == token.parentId,
              composerOwner == nil || composerOwner == state.userId else { throw CocoaError(.coderInvalidValue) }
        return try draftFence.removeIfCurrent(token.storageKey,expected: token.revision) {
            try dbQueue.write { db in
                let key = Self.composerKey(owner: state.userId,kind: "draft",target: state.targetId,root: state.rootId)
                guard try recoverableDraftCheckpoint(db, room: token.storageKey) == nil else { return false }
                guard try Self.composer(db,key: key) == nil, try Self.composer(db,key: Self.heldComposer(key)) == nil else { return false }
                let current = try String.fetchOne(db,sql: "SELECT text FROM draft WHERE conversationId=?",arguments: [token.storageKey])
                guard current == previous else { return false }
                try db.execute(sql: "INSERT INTO draft(conversationId,text,updatedAt) VALUES(?,?,?) ON CONFLICT(conversationId) DO UPDATE SET text=excluded.text,updatedAt=excluded.updatedAt",arguments: [token.storageKey,state.text,PulseOutboxClock.now()])
                try Self.recordComposerBaseline(db,receipt: state)
                return true
            }
        }
    }
}

extension PulseStore {
    private static func scheduledCommand(_ db: Database, key: String) throws -> PulseScheduledCommand? {
        guard let raw = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [key]) else { return nil }
        let value = try JSONDecoder().decode(PulseScheduledCommand.self,from: Data(raw.utf8))
        guard ["create","cancel"].contains(value.kind), !value.owner.isEmpty, !value.clientId.isEmpty,
              key == value.key || key == value.heldKey else { throw CocoaError(.coderInvalidValue) }
        return value
    }
    private static func putScheduledCommand(_ db: Database, command: PulseScheduledCommand, held: Bool = false) throws {
        let json = String(decoding: try JSONEncoder().encode(command),as: UTF8.self)
        let prefix = "composer-schedule:v1|\(command.owner)|"
        let order = try Double.fetchOne(db,sql: "SELECT COALESCE(MAX(updatedAt),0)+1 FROM wave7Cache WHERE substr(key,1,length(?))=?",arguments: [prefix,prefix]) ?? 1
        try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt",arguments: [held ? command.heldKey : command.key,json,order])
    }
    func stageScheduledCommand(_ command: PulseScheduledCommand) throws {
        guard composerOwner == nil || composerOwner == command.owner else { throw CocoaError(.coderInvalidValue) }
        try dbQueue.write { db in
            if let json = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [command.ackKey]) {
                try JSONDecoder().decode(PulseScheduledCommandAck.self,from: Data(json.utf8)).validate(command); return
            }
            let previous = try Self.scheduledCommand(db,key: command.key) ?? Self.scheduledCommand(db,key: command.heldKey)
            guard previous == nil || previous!.sameInstructions(command) else { throw CocoaError(.coderInvalidValue) }
            if command.kind == "create" {
                let prior = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [command.headKey])
                guard prior == nil || prior == command.clientId else {
                    throw PulseAPIClient.Failure(kind: .validation,message: "An earlier schedule in this chat still needs confirmation. Resolve it in Drafts & outbox before choosing another time.")
                }
                try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,0) ON CONFLICT(key) DO UPDATE SET json=excluded.json",arguments: [command.headKey,command.clientId])
            }
            try Self.putScheduledCommand(db,command: command)
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [command.heldKey])
        }
    }
    func scheduledCommandResult(_ command: PulseScheduledCommand) throws -> String? {
        try dbQueue.read { db in
            guard let json = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [command.ackKey]) else { return nil }
            let receipt = try JSONDecoder().decode(PulseScheduledCommandAck.self,from: Data(json.utf8)); try receipt.validate(command)
            if command.kind == "cancel" { return "cancelled" }
            guard let item = receipt.item else { throw CocoaError(.coderInvalidValue) }
            let restored = WireScheduledItem(id: item.id,conversationId: item.conversationId,content: command.text,scheduledAt: item.scheduledAt,
                sentAt: item.sentAt,cancelledAt: item.cancelledAt,cancelledReason: item.cancelledReason,creationClientId: command.clientId)
            return String(decoding: try JSONEncoder().encode(restored),as: UTF8.self)
        }
    }
    func pendingSchedules(owner: String) throws -> Int {
        try dbQueue.read { db in
            let prefix = "composer-schedule:v1|\(owner)|"
            return try Int.fetchOne(db,sql: "SELECT COUNT(*) FROM wave7Cache WHERE substr(key,1,length(?))=?",arguments: [prefix,prefix]) ?? 0
        }
    }
    func scheduledCommandPage(owner: String) throws -> [PulseScheduledCommand] {
        try dbQueue.write { db in
            let prefix = "composer-schedule:v1|\(owner)|"
            let rows = try Row.fetchAll(db,sql: "SELECT key,json,updatedAt FROM wave7Cache WHERE substr(key,1,length(?))=? ORDER BY updatedAt,key LIMIT 100",arguments: [prefix,prefix])
            var commands: [PulseScheduledCommand] = []
            for row in rows {
                let key: String = row["key"], json: String = row["json"]
                do { if let value = try Self.scheduledCommand(db,key: key) { commands.append(value) } }
                catch {
                    let held = key.replacingOccurrences(of: "composer-schedule:",with: "composer-schedule-blocked:")
                    let order: Double = row["updatedAt"]
                    try db.execute(sql: "INSERT OR IGNORE INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?)",arguments: [held,json,order])
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [key,json])
                }
            }
            return commands
        }
    }
    private static func scheduledCreationReference(owner: String, id: String) -> String { "composer-schedule-reference:v1|\(owner)|\(id)" }
    private static func releaseScheduledHead(_ db: Database, owner: String, id: String, cancelled: Bool = false) throws {
        let key = scheduledCreationReference(owner: owner,id: id)
        guard let raw = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [key]) else { return }
        let reference = try JSONDecoder().decode(PulseScheduledCommand.self,from: Data(raw.utf8))
        guard reference.owner == owner, reference.kind == "create", !reference.conversationId.isEmpty else { throw CocoaError(.coderInvalidValue) }
        if cancelled, let accepted = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [reference.ackKey]) {
            var receipt = try JSONDecoder().decode(PulseScheduledCommandAck.self,from: Data(accepted.utf8))
            guard receipt.owner == owner, receipt.clientId == reference.clientId, receipt.kind == "create" else { throw CocoaError(.coderInvalidValue) }
            receipt.cancelled = true
            let json = String(decoding: try JSONEncoder().encode(receipt),as: UTF8.self)
            try db.execute(sql: "UPDATE wave7Cache SET json=? WHERE key=?",arguments: [json,reference.ackKey])
        }
        try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [reference.headKey,reference.clientId])
        if cancelled { try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [key,raw]) }
    }
    func finishScheduledCommand(_ command: PulseScheduledCommand, result: String) throws {
        try dbQueue.write { db in
            guard let current = try Self.scheduledCommand(db,key: command.key), current.sameInstructions(command) else { return }
            let item: WireScheduledItem?
            if command.kind == "create" {
                let original = try JSONDecoder().decode(WireScheduledItem.self,from: Data(result.utf8))
                guard !original.id.isEmpty, original.conversationId == command.conversationId,
                      original.creationClientId == command.clientId,
                      original.content.utf16.elementsEqual(command.text.utf16),
                      let expected = PulseFormat.date(command.scheduledAt), let actual = PulseFormat.date(original.scheduledAt),
                      abs(expected.timeIntervalSince(actual)) < 0.001,
                      original.sentAt == nil, original.cancelledAt == nil, original.cancelledReason == nil else { throw CocoaError(.coderInvalidValue) }
                item = WireScheduledItem(id: original.id,conversationId: original.conversationId,content: "",scheduledAt: original.scheduledAt,
                    sentAt: original.sentAt,cancelledAt: original.cancelledAt,cancelledReason: original.cancelledReason,creationClientId: command.clientId)
            } else {
                guard command.kind == "cancel", result == "cancelled" else { throw CocoaError(.coderInvalidValue) }
                item = nil
            }
            let receipt = PulseScheduledCommandAck(owner: command.owner,clientId: command.clientId,kind: command.kind,requestHash: command.requestHash,item: item)
            let json = String(decoding: try JSONEncoder().encode(receipt),as: UTF8.self)
            try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json",arguments: [command.ackKey,json,Date().timeIntervalSince1970])
            if let item {
                var reference = command; reference.text = ""; reference.scheduledAt = ""; reference.notice = nil
                let raw = String(decoding: try JSONEncoder().encode(reference),as: UTF8.self)
                try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,0) ON CONFLICT(key) DO UPDATE SET json=excluded.json",arguments: [Self.scheduledCreationReference(owner: command.owner,id: item.id),raw])
            } else if command.kind == "cancel" { try Self.releaseScheduledHead(db,owner: command.owner,id: command.clientId,cancelled: true) }
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [command.key])
        }
    }
    func acknowledgeScheduledCommand(_ item: WireScheduledItem, owner: String) throws {
        guard composerOwner == nil || composerOwner == owner, let id = item.creationClientId else { throw CocoaError(.coderInvalidValue) }
        let command = PulseScheduledCommand(owner: owner,clientId: id,kind: "create",conversationId: item.conversationId)
        try dbQueue.write { db in
            guard let raw = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [command.ackKey]) else { throw CocoaError(.coderInvalidValue) }
            let receipt = try JSONDecoder().decode(PulseScheduledCommandAck.self,from: Data(raw.utf8))
            guard receipt.owner == owner, receipt.clientId == id, receipt.kind == "create",
                  receipt.item?.id == item.id, receipt.item?.conversationId == item.conversationId else { throw CocoaError(.coderInvalidValue) }
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [command.headKey,id])
            try Self.releaseScheduledHead(db,owner: owner,id: item.id)
        }
    }
    func deferScheduledCommand(_ command: PulseScheduledCommand, notice: String? = nil) throws {
        try dbQueue.write { db in
            guard var current = try Self.scheduledCommand(db,key: command.key), current.sameInstructions(command) else { return }
            current.notice = notice
            try Self.putScheduledCommand(db,command: current,held: notice != nil)
            if notice != nil { try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [command.key]) }
        }
    }
}

extension PulseStore {
    func preserveDetachedDraft(text: String, id: UUID, room: String, root: String?) throws {
        let key = "composer-detached:v1|\(composerOwner ?? "local")|\(id.uuidString)"
        let json = String(decoding: try JSONEncoder().encode(PulseDetachedDraft(conversationId: room, rootId: root, text: text)), as: UTF8.self)
        let checkpoint = try? draftCheckpoints.write(key, text: json)
        try dbQueue.write { db in
            try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json,updatedAt=excluded.updatedAt", arguments: [key,json,Date().timeIntervalSince1970])
            if let checkpoint {
                try db.execute(sql: "INSERT INTO wave7Cache(key,json,updatedAt) VALUES(?,?,0) ON CONFLICT(key) DO UPDATE SET json=excluded.json", arguments: [draftCheckpoints.receiptKey(key),checkpoint.id.uuidString])
            }
        }
        if let checkpoint { try? draftCheckpoints.remove(key,id: checkpoint.id) }
    }
    private static func recoveryVersion(_ json: String) -> String { SHA256.hash(data: Data(json.utf8)).map { String(format: "%02x",$0) }.joined() }
    func composerRecoveryPage(owner: String, after: String? = nil) throws -> PulseComposerRecoveryPage {
        guard composerOwner == nil || composerOwner == owner else { throw CocoaError(.coderInvalidValue) }
        _ = try pruneConsumedDraftCheckpoints()
        let pattern = owner.map { $0 == "[" ? "[[]" : $0 == "*" ? "[*]" : $0 == "?" ? "[?]" : String($0) }.joined()
        return try dbQueue.read { db in
            let patterns = ["composer-intent","composer-blocked","composer-local-edit","composer-schedule","composer-schedule-blocked","composer-schedule-ack","composer-detached"].map { "\($0):v1|\(pattern)|*" }
            let rows = try Row.fetchAll(db,sql: "SELECT key,json FROM wave7Cache WHERE key>? AND (key GLOB ? OR key GLOB ? OR key GLOB ? OR key GLOB ? OR key GLOB ? OR key GLOB ? OR key GLOB ?) ORDER BY key LIMIT 101",arguments: StatementArguments([after ?? ""] + patterns))
            let items = rows.prefix(100).map { row -> PulseComposerRecovery in
                let key: String = row["key"], json: String = row["json"]
                let version = Self.recoveryVersion(json)
                do {
                    if key.hasPrefix("composer-detached:") {
                        let value = try JSONDecoder().decode(PulseDetachedDraft.self,from: Data(json.utf8))
                        return PulseComposerRecovery(id: key,version: version,kind: "Other window draft",targetId: value.rootId ?? value.conversationId,conversationId: value.conversationId,text: value.text,notice: "Another window owns the active draft. This copy was saved separately and was not sent. Copy it before replacing the active draft.")
                    }
                    if key.hasPrefix("composer-local-edit:") {
                        let value = try JSONDecoder().decode(PulseComposerLocalEdit.self,from: Data(json.utf8))
                        let id = String(key.dropFirst("composer-local-edit:v1|\(owner)|".count))
                        let room = try String.fetchOne(db,sql: "SELECT conversationId FROM message WHERE id=?",arguments: [id]) ?? ""
                        return PulseComposerRecovery(id: key,version: version,kind: "Unsubmitted edit",targetId: id,conversationId: room,text: value.text,notice: "Kept on this device. Reopen the message to edit; typing is never sent automatically.")
                    }
                    if key.hasPrefix("composer-schedule-ack:") {
                        let receipt = try JSONDecoder().decode(PulseScheduledCommandAck.self,from: Data(json.utf8))
                        guard receipt.owner == owner, receipt.version == 1 else { throw CocoaError(.coderInvalidValue) }
                        let value = receipt.item
                        return PulseComposerRecovery(id: key,version: version,kind: "Schedule receipt",targetId: value?.id ?? "",conversationId: value?.conversationId ?? "",text: value?.content ?? "",notice: receipt.cancelled == true ? "This scheduled creation was cancelled. It will not send." : value.map { "Scheduled for \($0.scheduledAt). Check Scheduled messages for its current status." } ?? "Cancellation was confirmed.")
                    }
                    if key.hasPrefix("composer-schedule") {
                        let value = try JSONDecoder().decode(PulseScheduledCommand.self,from: Data(json.utf8))
                        guard value.owner == owner else { throw CocoaError(.coderInvalidValue) }
                        return PulseComposerRecovery(id: key,version: version,kind: value.kind == "cancel" ? "Pending cancellation" : "Pending schedule",targetId: value.clientId,conversationId: value.conversationId,text: value.text,
                            notice: value.kind == "cancel" ? (value.notice ?? "NOT cancelled yet. It may still send before cancellation arrives.")
                                : "Original time: \(value.scheduledAt). " + (value.notice ?? "Not confirmed. The original instructions will be retried."),canRetry: key.hasPrefix("composer-schedule-blocked:"))
                    }
                    let value = try Self.decodeComposer(json,key: key)
                    guard value.owner == owner else { throw CocoaError(.coderInvalidValue) }
                    return PulseComposerRecovery(id: key,version: version,kind: value.kind == "edit" ? "Saved edit" : value.rootId == nil ? "Draft mirror" : "Thread draft",targetId: value.targetId,conversationId: value.conversationId,text: value.text,
                        notice: value.blocked ?? "Waiting for server confirmation.",canRetry: value.blocked != nil,canReplace: value.blocked != nil && value.flight == nil)
                } catch { return PulseComposerRecovery(id: key,version: version,kind: "Unreadable recovery",targetId: "",conversationId: "",text: "",notice: "Original recovery bytes are kept on this device. Nothing has been sent or discarded.") }
            }
            return PulseComposerRecoveryPage(items: items,nextCursor: rows.count > 100 ? rows[99]["key"] : nil)
        }
    }
    private static func recoveryJSON(_ db: Database, row: PulseComposerRecovery, owner: String) throws -> String {
        guard ["composer-intent","composer-blocked","composer-local-edit","composer-schedule","composer-schedule-blocked","composer-schedule-ack","composer-detached"].contains(where: { row.id.hasPrefix("\($0):v1|\(owner)|") }),
              let json = try String.fetchOne(db,sql: "SELECT json FROM wave7Cache WHERE key=?",arguments: [row.id]),
              Self.recoveryVersion(json) == row.version else { throw CocoaError(.fileReadUnknown) }
        return json
    }
    func recoveryIntent(_ row: PulseComposerRecovery, owner: String) throws -> PulseComposerIntent {
        try dbQueue.read { db in
            let value = try Self.decodeComposer(Self.recoveryJSON(db,row: row,owner: owner),key: row.id)
            guard value.owner == owner, row.id.hasPrefix("composer-blocked:"), value.flight == nil else { throw CocoaError(.coderInvalidValue) }
            return value
        }
    }
    func recoverComposer(_ row: PulseComposerRecovery, owner: String, action: String, state: PulseComposerReceipt? = nil) throws {
        guard composerOwner == nil || composerOwner == owner else { throw CocoaError(.coderInvalidValue) }
        try draftFence.withEditSnapshot(row.id) { memory in
          if action == "remove", row.id.hasPrefix("composer-local-edit:"), memory != nil { throw CocoaError(.fileWriteUnknown) }
          try dbQueue.write { db in
            let json = try Self.recoveryJSON(db,row: row,owner: owner)
            if action == "remove" {
                if row.id.hasPrefix("composer-local-edit:") {
                    if let snapshot = try recoverableDraftCheckpoint(db, room: row.id), snapshot.text != json {
                        throw CocoaError(.fileWriteUnknown) // A newer failed write must be recovered first.
                    }
                    try acknowledgeDraftCheckpoint(db, scope: row.id)
                }
                if row.id.hasPrefix("composer-schedule:") || row.id.hasPrefix("composer-schedule-blocked:"),
                   let command = try? JSONDecoder().decode(PulseScheduledCommand.self,from: Data(json.utf8)), command.owner == owner, command.kind == "create" {
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [command.headKey,command.clientId])
                }
                if row.id.hasPrefix("composer-schedule-ack:"),
                   let receipt = try? JSONDecoder().decode(PulseScheduledCommandAck.self,from: Data(json.utf8)),
                   receipt.owner == owner, receipt.kind == "create", let item = receipt.item {
                    let command = PulseScheduledCommand(owner: owner,clientId: receipt.clientId,kind: "create",conversationId: item.conversationId)
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [command.headKey,command.clientId])
                    try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [Self.scheduledCreationReference(owner: owner,id: item.id)])
                }
                try db.execute(sql: "DELETE FROM wave7Cache WHERE key=? AND json=?",arguments: [row.id,json]); return
            }
            if row.id.hasPrefix("composer-schedule-blocked:"), action == "retry" {
                var command = try JSONDecoder().decode(PulseScheduledCommand.self,from: Data(json.utf8))
                guard command.owner == owner else { throw CocoaError(.coderInvalidValue) }
                command.notice = nil
                try Self.putScheduledCommand(db,command: command)
                try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [row.id]); return
            }
            guard row.id.hasPrefix("composer-blocked:"), ["replace","retry"].contains(action) else { throw CocoaError(.coderInvalidValue) }
            var value = try Self.decodeComposer(json,key: row.id)
            guard value.owner == owner else { throw CocoaError(.coderInvalidValue) }
            if action == "replace" {
                guard value.flight == nil, let state else { throw CocoaError(.coderInvalidValue) }
                try state.validate(value)
                value.desiredId = UUID().uuidString.lowercased(); value.baseText = state.text; value.baseEditedAt = state.editedAt; value.baseRevision = state.revision
                try Self.recordComposerBaseline(db,receipt: state)
            }
            value.blocked = nil
            try Self.putComposer(db,key: Self.composerKey(value),value: value)
            try db.execute(sql: "DELETE FROM wave7Cache WHERE key=?",arguments: [row.id])
          }
        }
    }
}
