package app.pulse.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pulse.domain.model.ConvTheme
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.PulseWhiteboardDraftLogic
import app.pulse.protocol.WirePulsePrefs
import app.pulse.protocol.settingsFields
import app.pulse.protocol.selectSettingsFields
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Account/gateway DataStore settings. Unknown privacy is conservative; local
 * intent and its receipt ledger commit together. Owned reads merge only fields
 * not pending, and stale read revisions cannot undo confirmed choices. */
@Singleton
class PulsePrefsLocalStore internal constructor(
    private val storage: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.pulsePrefs)
    internal val roomPrivacy = RoomPrivacyLocalStore(storage)

    private fun walletKey(intent: String) = stringPreferencesKey("wallet.operation." +
        java.security.MessageDigest.getInstance("SHA-256").digest(intent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) })

    /** Account-scoped DataStore commit completes BEFORE any monetary request. */
    suspend fun beginWalletOperation(intent: String): String {
        val key = walletKey(intent)
        var id = ""
        storage.edit { prefs ->
            id = prefs[key] ?: java.util.UUID.randomUUID().toString().also { prefs[key] = it }
            require(id.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Unreadable pending wallet operation; review ledger before resetting storage" }
        }
        return id
    }

    suspend fun finishWalletOperation(intent: String, id: String) {
        val key = walletKey(intent)
        storage.edit { prefs -> if (prefs[key] == id) prefs.remove(key) }
    }

    // This DataStore is already bound to gateway + owner by AccountRepositories.
    // Keep creation keys separate from wallet instructions and retain uncertain sends.
    private fun creationKey(intent: String) = stringPreferencesKey("room.creation." +
        java.security.MessageDigest.getInstance("SHA-256").digest(intent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) })

    suspend fun beginRoomCreation(intent: String): String {
        val key = creationKey(intent)
        var id = ""
        storage.edit { prefs ->
            id = prefs[key] ?: java.util.UUID.randomUUID().toString().also { prefs[key] = it }
            require(id.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Unreadable pending creation; do not resend with a new key" }
        }
        return id
    }
    suspend fun finishRoomCreation(intent: String, id: String) {
        val key = creationKey(intent)
        storage.edit { prefs -> if (prefs[key] == id) prefs.remove(key) }
    }

    private object Keys {
        val PREFS = stringPreferencesKey("wave8.prefs.json")
        /**
         * R1-W2F - per-conversation themes (F-FX-05). EXACTLY the web prefs
         * blob key (src/lib/conv-theme.ts CONV_THEMES_KEY): one JSON object
         * { "<conversationId>": { "wallpaper": "aurora", "tint": "rose" } }
         * - tint omitted when unset. Lives in the SAME DataStore file as the
         * Wave-8 rendering prefs (the shared prefs mechanism).
         */
        val CONV_THEMES = stringPreferencesKey("chat.convThemes")
        /** R2-C item 3 - design-language selection (web localStorage key). */
        val UI_THEME = stringPreferencesKey("pulse.uiTheme.v2")
        /** R4-B item 3 - navigation style (web localStorage key, byte-exact). */
        val NAV_STYLE = stringPreferencesKey(app.pulse.protocol.PulseNavStyle.PREFS_KEY)
        /** R2-C item 7 - spotlight recent searches (web localStorage key). */
        val SPOTLIGHT_RECENTS = stringPreferencesKey("pulse.spotlight.recents.v1")
    }

    /** Resolved prefs - defaults guaranteed on every emission. */
    val prefs: Flow<WirePulsePrefs> = storage.observeResilient(
        initial = PulseWave8Logic.localPrefs(null),
        unavailable = { it.copy(notifPreviews = false, lastSeenVisible = false, readReceipts = false, typingVisible = false) },
    ) { p ->
        PulseWave8Logic.localPrefs(p[Keys.PREFS])
    }

    // R2-C item 3 - design-language selection (pulse.uiTheme.v2)

    /**
     * Live design-language id - one of [UI_THEME_IDS] (the web value
     * strings verbatim); anything unreadable falls back to "glass".
     */
    val uiTheme: Flow<String> = storage.observeResilient(initial = UI_THEME_GLASS) { p ->
        (p[Keys.UI_THEME])?.takeIf { it in UI_THEME_IDS } ?: UI_THEME_GLASS
    }

    suspend fun setUiTheme(id: String) {
        if (id !in UI_THEME_IDS) return
        storage.edit { it[Keys.UI_THEME] = id }
    }

    // R4-B item 3 - navigation style (pulse.navStyle.v2)

    /**
     * Live navigation style - one of the 13 [PulseNavStyle] ids (the web
     * value strings verbatim); junk and wrong-case values fall back to the
     * capsule default (the same resolve the web's getNavStyleMeta fallback
     * performs).
     */
    val navStyle: Flow<app.pulse.protocol.PulseNavStyle> = storage.observeResilient(initial = app.pulse.protocol.PulseNavStyle.CAPSULE) { p ->
        app.pulse.protocol.PulseNavStyle.fromPersisted(p[Keys.NAV_STYLE])
    }

    suspend fun setNavStyle(style: app.pulse.protocol.PulseNavStyle) {
        storage.edit { it[Keys.NAV_STYLE] = style.id }
    }

    // R2-C item 7 - spotlight recent searches

    /**
     * Last 5 non-blank queries, deduped case-insensitively, newest first
     * (web spotlight.tsx readRecents/pushRecent parity).
     */
    val spotlightRecents: Flow<List<String>> = storage.observeResilient(initial = emptyList<String>()) { p ->
        decodeSpotlightRecents(p[Keys.SPOTLIGHT_RECENTS])
    }

    suspend fun pushSpotlightRecent(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        storage.edit { p ->
            val current = decodeSpotlightRecents(p[Keys.SPOTLIGHT_RECENTS])
                .filter { it.lowercase() != q.lowercase() }
            val next = (listOf(q) + current).take(SPOTLIGHT_RECENTS_MAX)
            p[Keys.SPOTLIGHT_RECENTS] = encodeSpotlightRecents(next)
        }
    }

    suspend fun clearSpotlightRecents() {
        storage.edit { it.remove(Keys.SPOTLIGHT_RECENTS) }
    }

    private fun decodeSpotlightRecents(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { PulseJson.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray }
            .getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content }
            .filter { it.isNotBlank() }
            .take(SPOTLIGHT_RECENTS_MAX)
    }

    private fun encodeSpotlightRecents(list: List<String>): String {
        val arr = kotlinx.serialization.json.buildJsonArray {
            list.forEach { add(JsonPrimitive(it)) }
        }
        return PulseJson.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr)
    }

    // R2-C item 4 - whiteboard pending-stroke draft store

    /** Per-conversation DataStore key (iOS `whiteboard.draft:<id>` style). */
    private fun whiteboardDraftKey(conversationId: String) =
        stringPreferencesKey("whiteboard.draft:$conversationId")

    /** The pending strokes for a conversation, oldest first (tolerant). */
    suspend fun whiteboardDraft(conversationId: String): List<app.pulse.protocol.WhiteboardStrokePostDto> {
        if (!CONV_ID_OK.matches(conversationId)) return emptyList()
        val raw = storage.data.firstOrNull()?.get(whiteboardDraftKey(conversationId))
        return PulseWhiteboardDraftLogic.decode(raw)
    }

    /** Atomic read/modify/write across overlapping screen instances. */
    suspend fun updateWhiteboardDraft(
        conversationId: String,
        transform: (List<app.pulse.protocol.WhiteboardStrokePostDto>) -> List<app.pulse.protocol.WhiteboardStrokePostDto>,
    ): List<app.pulse.protocol.WhiteboardStrokePostDto> {
        var result = emptyList<app.pulse.protocol.WhiteboardStrokePostDto>()
        storage.edit { p ->
            result = transform(PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)]))
            p[whiteboardDraftKey(conversationId)] = PulseWhiteboardDraftLogic.encode(result)
        }
        return result
    }

    /** Draw-time write-through - persists the stroke BEFORE any sync attempt. */
    suspend fun appendWhiteboardDraft(conversationId: String, stroke: app.pulse.protocol.WhiteboardStrokePostDto) {
        if (!CONV_ID_OK.matches(conversationId)) return
        storage.edit { p ->
            val pending = PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)])
            p[whiteboardDraftKey(conversationId)] =
                PulseWhiteboardDraftLogic.encode(pending + stroke)
        }
    }

    /** Flush-verdict purge - the acknowledged batch leaves the queue front. */
    suspend fun dropFirstWhiteboardDraft(conversationId: String, count: Int) {
        if (!CONV_ID_OK.matches(conversationId)) return
        storage.edit { p ->
            val pending = PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)])
            p[whiteboardDraftKey(conversationId)] =
                PulseWhiteboardDraftLogic.encode(PulseWhiteboardDraftLogic.droppingFirst(pending, count))
        }
    }

    /** Undo purge - the newest pending stroke leaves the queue tail. */
    suspend fun dropLastWhiteboardDraft(conversationId: String) {
        if (!CONV_ID_OK.matches(conversationId)) return
        storage.edit { p ->
            val pending = PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)])
            p[whiteboardDraftKey(conversationId)] =
                PulseWhiteboardDraftLogic.encode(PulseWhiteboardDraftLogic.droppingLast(pending))
        }
    }

    /** Restore-time swap - rewrites the whole draft (the dedupe pass). */
    suspend fun replaceAllWhiteboardDraft(conversationId: String, strokes: List<app.pulse.protocol.WhiteboardStrokePostDto>) {
        if (!CONV_ID_OK.matches(conversationId)) return
        storage.edit { p ->
            p[whiteboardDraftKey(conversationId)] = PulseWhiteboardDraftLogic.encode(strokes)
        }
    }

    /** Server-side board reset - the pending queue is gone with the board. */
    suspend fun clearWhiteboardDraft(conversationId: String) {
        if (!CONV_ID_OK.matches(conversationId)) return
        storage.edit { it.remove(whiteboardDraftKey(conversationId)) }
    }

    // R1-W2F - per-conversation themes (`chat.convThemes`)

    /**
     * Live `chat.convThemes` map, sanitized like the web
     * sanitizeConvThemeMap: unknown wallpaper tokens / tints / junk keys are
     * dropped, the [ConvTheme.MAX_MAP_ENTRIES] budget caps the payload.
     * JSON object key order IS the LRU order (touched entry last).
     *
     * Section 13 item 4: the canonical copy now lives INSIDE the staged
     * settings blob (the web's prefs blob shape); the pre-Section-13
     * standalone `chat.convThemes` DataStore key stays as a read fallback so
     * an upgrade keeps already-stored themes until the next staged write
     * migrates the whole map into the blob.
     */
    val convThemes: Flow<Map<String, ConvTheme>> = storage.observeResilient(initial = emptyMap<String, ConvTheme>()) { p ->
        val blobThemes = PulseWave8Logic.localPrefs(p[Keys.PREFS]).convThemes
        sanitizeConvThemes(blobThemes) ?: sanitizeConvThemes(p[Keys.CONV_THEMES])
    }

    /**
     * Section 13 item 4 - the settings patch that stages ONE conversation's
     * theme change. Computes the next LRU map from the current merged state
     * (blob first, legacy key fallback) and returns it as a carried
     * `chat.convThemes` field, ready for [stageSettings] + the durable
     * settings write. Whole-map granularity matches the web's prefs-blob
     * write; two near-simultaneous theme stages converge through the server's
     * last-write-wins receipt instead of merging.
     */
    internal suspend fun convThemePatch(conversationId: String, theme: ConvTheme?): WirePulsePrefs {
        if (!CONV_ID_OK.matches(conversationId)) return WirePulsePrefs()
        val next = LinkedHashMap(convThemes.first())
        next.remove(conversationId)
        if (theme != null && theme.wallpaper in ConvTheme.WALLPAPERS) {
            next[conversationId] = theme
        }
        while (next.size > ConvTheme.MAX_MAP_ENTRIES) {
            val eldest = next.keys.firstOrNull() ?: break
            next.remove(eldest)
        }
        return WirePulsePrefs(convThemes = encodeConvThemesObject(next))
    }

    /** Web sanitizeConvThemeMap parity - never throws, never stores junk. */
    private fun sanitizeConvThemes(raw: String?): Map<String, ConvTheme> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { PulseJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return sanitizeConvThemes(obj) ?: emptyMap()
    }

    /** Object overload - null = the blob carried no theme map (not an empty one). */
    private fun sanitizeConvThemes(obj: JsonObject?): Map<String, ConvTheme>? {
        if (obj == null) return null
        val out = LinkedHashMap<String, ConvTheme>()
        for ((key, value) in obj) {
            if (out.size >= ConvTheme.MAX_MAP_ENTRIES) break
            // cuid-style id guard + prototype-pollution keys (web parity)
            if (!CONV_ID_OK.matches(key)) continue
            val entry = value as? JsonObject ?: continue
            val wallpaper = (entry["wallpaper"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: continue
            if (wallpaper !in ConvTheme.WALLPAPERS) continue
            val tint = (entry["tint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (tint != null && tint !in ConvTheme.TINTS) continue
            out[key] = ConvTheme(wallpaper = wallpaper, tint = tint)
        }
        return out
    }

    /** { "<id>": { wallpaper, tint? } } - tint key omitted when null (web shape). */
    private fun encodeConvThemesObject(map: Map<String, ConvTheme>): JsonObject {
        return buildJsonObject {
            map.forEach { (id, theme) ->
                put(
                    id,
                    buildJsonObject {
                        put("wallpaper", JsonPrimitive(theme.wallpaper))
                        if (theme.tint != null) put("tint", JsonPrimitive(theme.tint))
                    },
                )
            }
        }
    }

    private val settingsPendingKey = stringPreferencesKey("settings.pending.fields.v1")
    private val settingsRevisionKey = stringPreferencesKey("settings.intent.revision.v1")
    /** Section 13 item 1 - per-field stage moments (epoch ms) of the pending intent. */
    private val settingsStampsKey = stringPreferencesKey("settings.fieldStamps.v1")
    /** Section 13 item 1 - the durable patch identity for the CURRENT pending set. */
    private val settingsEnvelopeKey = stringPreferencesKey("settings.intent.envelope.v1")
    /** Section 13 - the server's monotonic settings revision (last observed receipt). */
    private val settingsServerRevisionKey = stringPreferencesKey("settings.server.revision.v1")

    internal data class SettingsReadTicket(val revision: String?)
    internal suspend fun settingsReadTicket() = SettingsReadTicket(storage.data.first()[settingsRevisionKey])

    /** The server's monotonic settings revision as of the last confirmed read/write. */
    internal suspend fun storedServerRevision(): Long? =
        storage.data.first()[settingsServerRevisionKey]?.toLongOrNull()

    internal suspend fun storeServerRevision(revision: Long?) {
        if (revision == null) return
        storage.edit { p -> p[settingsServerRevisionKey] = revision.toString() }
    }

    /**
     * One staged settings write. Beyond the patch and its per-field receipt
     * tokens it carries the Section 13 durable-receipt identity: a patch-level
     * clientId, the stage moment, and each field's stage moment (epoch ms).
     * Legacy callers (and journals persisted before Section 13) leave the new
     * fields at their defaults - the wire body then omits them.
     */
    internal data class SettingsTicket(
        val patch: WirePulsePrefs,
        val fields: Map<String, String>,
        val clientId: String? = null,
        val stagedAtMs: Long? = null,
        val fieldStagedAtMs: Map<String, Long> = emptyMap(),
    )

    private fun pendingFields(p: androidx.datastore.preferences.core.Preferences): Map<String, String> {
        val raw = p[settingsPendingKey] ?: return emptyMap()
        val obj = PulseJson.parseToJsonElement(raw) as? JsonObject ?: error("Unreadable pending settings")
        return obj.mapValues { (_, value) ->
            val token = value as? JsonPrimitive ?: error("Unreadable pending settings")
            check(token.isString && token.content.isNotBlank()) { "Unreadable pending settings" }
            token.content
        }
    }
    private fun storePending(p: androidx.datastore.preferences.core.MutablePreferences, pending: Map<String, String>) {
        if (pending.isEmpty()) p.remove(settingsPendingKey)
        else p[settingsPendingKey] = JsonObject(pending.mapValues { JsonPrimitive(it.value) }).toString()
    }

    // Section 13 item 1 - the pending intent's stage moments and durable patch
    // identity live in the SAME DataStore transaction as the pending tokens, so
    // a process restart replays the SAME clientId + intentAt + fieldTimes.

    /** The web's url-safe clientId contract (32 hex chars, 16-128 allowed). */
    private fun newSettingsClientId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun fieldStamps(p: androidx.datastore.preferences.core.Preferences): Map<String, Long> {
        val raw = p[settingsStampsKey] ?: return emptyMap()
        val obj = PulseJson.parseToJsonElement(raw) as? JsonObject ?: error("Unreadable pending settings")
        return obj.mapValues { (_, value) ->
            val ms = value as? JsonPrimitive ?: error("Unreadable pending settings")
            ms.content.toLongOrNull() ?: error("Unreadable pending settings")
        }
    }

    private fun storeStamps(p: androidx.datastore.preferences.core.MutablePreferences, stamps: Map<String, Long>) {
        if (stamps.isEmpty()) p.remove(settingsStampsKey)
        else p[settingsStampsKey] = JsonObject(stamps.mapValues { JsonPrimitive(it.value.toString()) }).toString()
    }

    private data class IntentEnvelope(val clientId: String, val atMs: Long, val fields: Set<String>)

    private val settingsClientIdOk = Regex("[A-Za-z0-9_-]{16,128}")

    private fun intentEnvelope(p: androidx.datastore.preferences.core.Preferences): IntentEnvelope? {
        val raw = p[settingsEnvelopeKey] ?: return null
        val obj = PulseJson.parseToJsonElement(raw) as? JsonObject ?: error("Unreadable pending settings")
        val clientId = (obj["clientId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Unreadable pending settings")
        require(clientId.matches(settingsClientIdOk)) { "Unreadable pending settings" }
        val atMs = (obj["atMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: error("Unreadable pending settings")
        val fields = (obj["fields"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content }
            ?: error("Unreadable pending settings")
        require(fields.isNotEmpty()) { "Unreadable pending settings" }
        return IntentEnvelope(clientId, atMs, fields.toSet())
    }

    private fun storeEnvelope(p: androidx.datastore.preferences.core.MutablePreferences, envelope: IntentEnvelope) {
        p[settingsEnvelopeKey] = buildJsonObject {
            put("clientId", JsonPrimitive(envelope.clientId))
            put("atMs", JsonPrimitive(envelope.atMs.toString()))
            put("fields", kotlinx.serialization.json.JsonArray(envelope.fields.sorted().map { JsonPrimitive(it) }))
        }.toString()
    }

    /**
     * Section 13 item 1 - the durable identity for the fields ABOUT TO BE SENT.
     * The stored envelope is reused ONLY when it was minted for exactly this
     * field set (same instructions ⇒ the server answers from its stored result
     * with `replayed: true`); any other set mints a fresh clientId, so a reused
     * identity can never carry different instructions (the server's 409
     * settings_operation_mismatch stays unreachable from honest retries).
     */
    internal suspend fun settingsReceiptIdentity(sentFields: Set<String>): SettingsReceiptIdentity {
        var identity = SettingsReceiptIdentity(newSettingsClientId(), System.currentTimeMillis(), emptyMap())
        if (sentFields.isEmpty()) return identity
        storage.edit { p ->
            val stamps = fieldStamps(p)
            val stored = intentEnvelope(p)
            val reuse = stored != null && stored.fields == sentFields
            val clientId = if (reuse) stored!!.clientId else newSettingsClientId()
            val atMs = if (reuse) stored!!.atMs else System.currentTimeMillis()
            if (!reuse) storeEnvelope(p, IntentEnvelope(clientId, atMs, sentFields))
            identity = SettingsReceiptIdentity(clientId, atMs, sentFields.associateWith { stamps[it] ?: atMs })
        }
        return identity
    }

    internal data class SettingsReceiptIdentity(val clientId: String, val intentAtMs: Long, val fieldTimesMs: Map<String, Long>)
    val hasPendingSettings: Flow<Boolean> = storage.observeResilient(initial = true, unavailable = { true }) { p ->
        // Corruption is not permission to report all settings synchronized.
        runCatching { pendingFields(p).isNotEmpty() }.getOrDefault(true)
    }
    suspend fun pendingSettingsPatch(): WirePulsePrefs = pendingSettingsTicket().patch
    internal suspend fun pendingSettingsTicket(): SettingsTicket {
        val p = storage.data.first()
        val pending = pendingFields(p)
        val patch = PulseWave8Logic.localPrefs(p[Keys.PREFS]).selectSettingsFields(pending.keys)
        check(patch.settingsFields().keys.containsAll(pending.keys)) { "Unreadable pending settings; nothing was discarded" }
        return SettingsTicket(patch, pending)
    }

    /** Persist local intent and per-field receipt identities in ONE transaction.
     * Section 13 item 1: the SAME transaction also records each field's stage
     * moment and mints the patch-level durable clientId for the (new) pending
     * set, so a lost-ACK retry replays with identical instructions. */
    internal suspend fun stageSettings(patch: WirePulsePrefs, owns: () -> Boolean): SettingsTicket {
        val fields = patch.settingsFields().keys.associateWith { java.util.UUID.randomUUID().toString() }
        require(fields.isNotEmpty()) { "No settings changes to save" }
        val stagedAtMs = System.currentTimeMillis()
        val clientId = newSettingsClientId()
        storage.edit { p ->
            if (!owns()) throw kotlinx.coroutines.CancellationException("Settings session ended")
            val pending = pendingFields(p) + fields
            val stamps = fieldStamps(p).toMutableMap()
            fields.keys.forEach { stamps[it] = stagedAtMs }
            p[Keys.PREFS] = PulseJson.encodeToString(WirePulsePrefs.serializer(),
                PulseWave8Logic.mergePatch(PulseWave8Logic.localPrefs(p[Keys.PREFS]), patch))
            storePending(p, pending)
            storeStamps(p, stamps)
            // The instruction set changed, so the durable identity is minted fresh.
            storeEnvelope(p, IntentEnvelope(clientId, stagedAtMs, pending.keys))
            p[settingsRevisionKey] = java.util.UUID.randomUUID().toString()
        }
        return SettingsTicket(patch, fields, clientId, stagedAtMs, fields.keys.associateWith { stagedAtMs })
    }
    internal suspend fun currentSettingsPatch(ticket: SettingsTicket): WirePulsePrefs {
        val pending = pendingFields(storage.data.first())
        return ticket.patch.selectSettingsFields(ticket.fields.filter { (key, id) -> pending[key] == id }.keys)
    }

    /**
     * Confirm a staged settings write against the server's receipt.
     *
     * Section 13 item 1: [receipt] (the PATCH response envelope) is optional so
     * legacy callers keep compiling. Its `superseded` array names the fields
     * that LOST the last-write-wins comparison against a newer intent from
     * another device - for those the local optimistic value is REPLACED by the
     * winning value from the response preferences (the device's own intent must
     * never resurface), and the pending token retires exactly like an accepted
     * field. A `replayed: true` receipt confirms like a fresh apply.
     */
    internal suspend fun acknowledgeSettings(
        ticket: SettingsTicket,
        sent: WirePulsePrefs,
        server: WirePulsePrefs,
        receipt: app.pulse.protocol.SettingsEnvelopeDto? = null,
        owns: () -> Boolean,
    ) {
        val keys = sent.settingsFields().keys
        check(server.settingsFields().keys.containsAll(keys)) { "The server did not confirm the requested settings fields" }
        storage.edit { p ->
            if (!owns()) throw kotlinx.coroutines.CancellationException("Settings session ended")
            val pending = pendingFields(p).toMutableMap()
            // Every field whose token still matches retires on this receipt. For
            // accepted fields the server re-confirms the stored value; for fields
            // the receipt lists as superseded, response.preferences instead carries
            // the WINNING value - merging it here is the adoption that keeps a
            // losing local intent from resurfacing (locally or on any retry).
            val retired = keys.filter { pending[it] == ticket.fields[it] }.toSet()
            val current = PulseWave8Logic.localPrefs(p[Keys.PREFS])
            p[Keys.PREFS] = PulseJson.encodeToString(WirePulsePrefs.serializer(),
                PulseWave8Logic.mergePatch(current, server.selectSettingsFields(retired)))
            retired.forEach { pending.remove(it) }
            storePending(p, pending)
            if (retired.isNotEmpty()) {
                val stamps = fieldStamps(p).toMutableMap()
                retired.forEach { stamps.remove(it) }
                storeStamps(p, stamps)
                // The pending set changed: a stored patch identity no longer
                // matches what a retry would send - the next ticket mints fresh.
                p.remove(settingsEnvelopeKey)
                p[settingsRevisionKey] = java.util.UUID.randomUUID().toString()
            }
        }
        // Server truth for the account as of this receipt (supersede adoption
        // and `replayed` receipts included). Independent of the retirement above.
        storeServerRevision(receipt?.revision)
    }

    /** A startup/read snapshot cannot erase local intent awaiting confirmation.
     * Section 13: [revision] (the GET response's server revision) is stored when
     * the snapshot applies so later reads can reason about freshness. */
    internal suspend fun replaceFromServer(
        server: WirePulsePrefs,
        readTicket: SettingsReadTicket? = null,
        owns: () -> Boolean = { true },
        revision: Long? = null,
    ) {
        storage.edit { p ->
            if (!owns()) throw kotlinx.coroutines.CancellationException("Settings session ended")
            if (readTicket != null && p[settingsRevisionKey] != readTicket.revision) return@edit
            val pending = pendingFields(p)
            val current = PulseWave8Logic.localPrefs(p[Keys.PREFS])
            val safe = server.selectSettingsFields(server.settingsFields().keys - pending.keys)
            p[Keys.PREFS] = PulseJson.encodeToString(WirePulsePrefs.serializer(), PulseWave8Logic.mergePatch(current, safe))
            if (revision != null) p[settingsServerRevisionKey] = revision.toString()
        }
    }

    /** Non-network local preference callers; server writes use stageSettings. */
    suspend fun applyLocal(patch: WirePulsePrefs) {
        storage.edit { p ->
            p[Keys.PREFS] = PulseJson.encodeToString(WirePulsePrefs.serializer(),
                PulseWave8Logic.mergePatch(PulseWave8Logic.localPrefs(p[Keys.PREFS]), patch))
        }
    }

    companion object {
        /** Web conv-theme.ts CONV_ID_OK - cuid-style conversation keys only. */
        private val CONV_ID_OK = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

        /** R2-C - the web ui-theme value strings (src/lib/ui-theme.ts). */
        const val UI_THEME_GLASS = "glass"
        val UI_THEME_IDS = setOf("glass", "kinetic", "minimal", "dynamic", "aero")

        /** Web spotlight.tsx RECENTS_MAX. */
        private const val SPOTLIGHT_RECENTS_MAX = 5
    }
}
