package app.pulse.data.repository

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.Wave7CacheEntity
import java.io.File

/**
 * Section 14 item 10 - safe recovery of LEGACY unowned data.
 *
 * The pre-account-split `pulse.db` was deliberately never adopted because its
 * rows could not prove which account owned them. This gate makes the decision
 * explicit, provable and conservative instead of a blanket refusal:
 *
 *  - the legacy file is opened READ-ONLY and only ever scanned; it is never
 *    written, never deleted, and never attached to the account database.
 *  - the data may be imported into the CURRENT account only when EVERY
 *    conversation lists this owner as a member and every cached message was
 *    authored by a member of its own conversation (the cache is then provably
 *    this owner's view of conversations they belong to).
 *  - queued sends (outbox rows) are imported ONLY when each row has a
 *    matching `local_` echo authored by this owner; unprovable queue rows are
 *    skipped, never replayed under a foreign credential.
 *  - any check that cannot be proven refuses the ENTIRE import and retains
 *    the file untouched - wrong-account assignment is impossible by design.
 *
 * The verdict (and the file size+mtime it was rendered for) is stamped into
 * the account's own wave7 KV journal, so the decision is idempotent across
 * restarts and is revisited only when the legacy file changes.
 */
class LegacyDataRecovery {

    /** What the gate decided; every refusal RETAINS the legacy file. */
    sealed interface Verdict {
        /** No legacy file exists - nothing to decide, nothing stamped. */
        data object Absent : Verdict
        /** The file exists but cannot be read as a Pulse database - retained. */
        data class Unreadable(val reason: String) : Verdict
        /** The data (or part of it) cannot be proven to belong to this owner - retained. */
        data class Refuse(val reason: String) : Verdict
        /** Proven ownable; the listed row counts may be imported. */
        data class Adopt(
            val conversations: Long,
            val messages: Long,
            val drafts: Long,
            val outbox: Long,
            val outboxSkipped: Long,
        ) : Verdict
    }

    /**
     * The read-only scan seam (production = raw SQL over the legacy file;
     * JVM tests = fakes). Every `null` means "cannot read".
     */
    interface Scan {
        fun conversationCount(): Long?
        fun messageCount(): Long?
        fun draftCount(): Long?
        fun hasForeignConversation(owner: String): Boolean?
        fun hasOrphanAuthor(): Boolean?
        fun outboxCount(): Long?
        fun outboxProven(owner: String): Long?
    }

    fun verdict(owner: String, scan: Scan): Verdict {
        val conversations = scan.conversationCount() ?: return Verdict.Unreadable("conversations unreadable")
        val queued = scan.outboxCount() ?: return Verdict.Unreadable("outbox unreadable")
        if (conversations == 0L) {
            if (queued > 0) return Verdict.Refuse("queued sends without a provable owner")
            return Verdict.Adopt(0, scan.messageCount() ?: 0, scan.draftCount() ?: 0, 0, 0)
        }
        if (scan.hasForeignConversation(owner) == true) {
            return Verdict.Refuse("conversation without this owner in its members")
        }
        if (scan.hasOrphanAuthor() == true) {
            return Verdict.Refuse("message authored outside its conversation's members")
        }
        val proven = scan.outboxProven(owner) ?: return Verdict.Unreadable("outbox proof unreadable")
        return Verdict.Adopt(
            conversations = conversations,
            messages = scan.messageCount() ?: return Verdict.Unreadable("messages unreadable"),
            drafts = scan.draftCount() ?: 0,
            outbox = proven,
            outboxSkipped = queued - proven,
        )
    }

    companion object {
        private const val STAMP_KEY = "legacy-recovery:v1"

        /**
         * Renders the verdict for the legacy `pulse.db` and, when it is an
         * [Verdict.Adopt], imports the proven rows into THIS account's Room
         * database through a throwaway copy (the original file is never
         * attached, so it physically cannot be modified by the import).
         * Idempotent per legacy-file fingerprint.
         */
        suspend fun recover(context: Context, db: PulseDatabase, owner: String): Verdict {
            val legacy = context.getDatabasePath("pulse.db")
            if (!legacy.exists()) return Verdict.Absent
            val size = legacy.length()
            val mtime = legacy.lastModified()
            val stamp = runCatching { db.wave7Dao().readJournal(STAMP_KEY).firstOrNull() }.getOrNull()
            if (stamp != null && stamp.json.contains("\"size\":$size") && stamp.json.contains("\"mtime\":$mtime")) {
                return decodeStampVerdict(stamp.json)
            }
            val verdict = LegacyDataRecovery().verdict(owner, FileScan(legacy))
            when (verdict) {
                is Verdict.Adopt -> {
                    val imported = runCatching { importProvenRows(legacy, db, owner) }
                        .getOrElse { failure ->
                            return Verdict.Refuse("import refused: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    stampVerdict(db, size, mtime, verdict, imported)
                }
                else -> stampVerdict(db, size, mtime, verdict, null)
            }
            return verdict
        }

        private fun decodeStampVerdict(json: String): Verdict {
            val kind = Regex("\"verdict\":\"([a-z]+)\"").find(json)?.groupValues?.getOrNull(1)
                ?: return Verdict.Unreadable("stamp unreadable")
            return when (kind) {
                "refuse" -> Verdict.Refuse(Regex("\"reason\":\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1) ?: "stamped refusal")
                "unreadable" -> Verdict.Unreadable("stamped unreadable")
                else -> Verdict.Adopt(0, 0, 0, 0, 0)
            }
        }

        private suspend fun stampVerdict(
            db: PulseDatabase,
            size: Long,
            mtime: Long,
            verdict: Verdict,
            imported: Map<String, Long>?,
        ) {
            val json = buildString {
                append("{\"v\":1,\"size\":").append(size).append(",\"mtime\":").append(mtime).append(",\"verdict\":\"")
                append(
                    when (verdict) {
                        is Verdict.Refuse -> "refuse"
                        is Verdict.Unreadable -> "unreadable"
                        Verdict.Absent -> "absent"
                        is Verdict.Adopt -> "adopt"
                    },
                )
                append('"')
                val reason = (verdict as? Verdict.Refuse)?.reason ?: (verdict as? Verdict.Unreadable)?.reason
                if (reason != null) append(",\"reason\":\"").append(reason.take(120)).append('"')
                if (imported != null) {
                    append(",\"imported\":{")
                    append(imported.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
                    append('}')
                }
                append('}')
            }
            runCatching {
                db.wave7Dao().upsert(Wave7CacheEntity(key = STAMP_KEY, json = json, updatedAt = System.currentTimeMillis()))
            }
        }

        private inline fun <T> readOpen(file: File, block: (SQLiteDatabase) -> T): T? = runCatching {
            val handle = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                block(handle)
            } finally {
                handle.close()
            }
        }.getOrNull()

        /** Read-only scan of the legacy file with defensive table/column checks. */
        private class FileScan(private val file: File) : Scan {
            private fun exists(db: SQLiteDatabase, table: String): Boolean =
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

            private fun column(db: SQLiteDatabase, table: String, column: String): Boolean =
                db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
                    found
                }

            private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String> = emptyArray()): Long? = runCatching {
                db.rawQuery(sql, args).use { cursor: Cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            }.getOrNull()

            override fun conversationCount(): Long? = readOpen(file) { db ->
                if (!exists(db, "conversations")) null
                else scalar(db, "SELECT COUNT(*) FROM conversations") ?: 0L
            } ?: null

            override fun messageCount(): Long? = readOpen(file) { db ->
                if (!exists(db, "messages")) null
                else scalar(db, "SELECT COUNT(*) FROM messages") ?: 0L
            } ?: null

            override fun draftCount(): Long? = readOpen(file) { db ->
                if (!exists(db, "draft")) 0L
                else scalar(db, "SELECT COUNT(*) FROM draft") ?: 0L
            } ?: null

            override fun hasForeignConversation(owner: String): Boolean? = readOpen(file) { db ->
                if (!exists(db, "conversations") || !column(db, "conversations", "memberIdsCsv")) null
                else db.rawQuery(
                    "SELECT 1 FROM conversations WHERE instr(','||memberIdsCsv||',', ',$owner,') = 0 LIMIT 1",
                    null,
                ).use { it.moveToFirst() }
            } ?: null

            override fun hasOrphanAuthor(): Boolean? = readOpen(file) { db ->
                if (!exists(db, "messages") || !exists(db, "conversations") ||
                    !column(db, "messages", "authorId") || !column(db, "conversations", "memberIdsCsv")
                ) null
                else db.rawQuery(
                    "SELECT 1 FROM messages m JOIN conversations c ON m.conversationId = c.id " +
                        "WHERE instr(','||c.memberIdsCsv||',', ','||m.authorId||',') = 0 LIMIT 1",
                    null,
                ).use { it.moveToFirst() }
            } ?: null

            override fun outboxCount(): Long? = readOpen(file) { db ->
                if (!exists(db, "outbox")) 0L
                else scalar(db, "SELECT COUNT(*) FROM outbox") ?: 0L
            } ?: null

            override fun outboxProven(owner: String): Long? = readOpen(file) { db ->
                if (!exists(db, "outbox")) 0L
                else if (!exists(db, "messages") || !column(db, "messages", "authorId")) 0L
                else db.rawQuery(
                    "SELECT COUNT(*) FROM outbox o WHERE EXISTS (" +
                        "SELECT 1 FROM messages m WHERE m.id = 'local_' || o.clientId AND m.authorId = ?)",
                    arrayOf(owner),
                ).use { it.moveToFirst(); it.getLong(0) }
            } ?: null
        }

        /**
         * Copies the legacy file to a throwaway temp, ATTACHes the COPY to the
         * account database and INSERT OR IGNOREs the provably-ownable rows
         * (conversations/messages/drafts wholesale; outbox only proven rows)
         * inside ONE transaction. Column lists are the INTERSECTION of both
         * schemas, so an older legacy schema imports with its newer target
         * columns at defaults; any structural surprise throws and the caller
         * refuses with the file retained.
         */
        private fun importProvenRows(legacy: File, db: PulseDatabase, owner: String): Map<String, Long> {
            val temp = File.createTempFile("legacy-copy", ".db", legacy.parentFile)
            try {
                legacy.copyTo(temp, overwrite = true)
                val sqlite = db.openHelper.writableDatabase
                sqlite.execSQL("ATTACH DATABASE '${temp.path}' AS legacy_src")
                try {
                    fun columns(side: String, table: String): List<String> =
                        sqlite.rawQuery("PRAGMA $side(`$table`)", null).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) add(cursor.getString(1))
                            }
                        }

                    fun common(table: String): List<String> {
                        val target = columns("table_info", table)
                        val source = columns("legacy_src.table_info", table)
                        return source.filter { it in target }
                    }

                    val counts = mutableMapOf<String, Long>()
                    sqlite.beginTransaction()
                    try {
                        for (table in listOf("conversations", "messages", "draft")) {
                            val cols = common(table)
                            if (cols.isEmpty()) throw IllegalStateException("no importable columns for $table")
                            val list = cols.joinToString(",")
                            sqlite.execSQL("INSERT OR IGNORE INTO `$table` ($list) SELECT $list FROM legacy_src.`$table`")
                            counts[table] = sqlite.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getLong(0) }
                        }
                        val outboxCols = common("outbox")
                        if (outboxCols.isNotEmpty()) {
                            val list = outboxCols.joinToString(",")
                            sqlite.execSQL(
                                "INSERT OR IGNORE INTO `outbox` ($list) SELECT $list FROM legacy_src.`outbox` o " +
                                    "WHERE EXISTS (SELECT 1 FROM legacy_src.`messages` m " +
                                    "WHERE m.id = 'local_' || o.clientId AND m.authorId = ?)",
                                arrayOf(owner),
                            )
                            counts["outbox"] = sqlite.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getLong(0) }
                        }
                        sqlite.setTransactionSuccessful()
                    } finally {
                        sqlite.endTransaction()
                    }
                    return counts
                } finally {
                    sqlite.execSQL("DETACH DATABASE legacy_src")
                }
            } finally {
                temp.delete()
            }
        }
    }
}
