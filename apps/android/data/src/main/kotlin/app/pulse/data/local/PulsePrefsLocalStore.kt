package app.pulse.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pulse.domain.model.ConvTheme
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.PulseWhiteboardDraftLogic
import app.pulse.protocol.WirePulsePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Wave 8 — DataStore-backed user-preferences blob (the `pulse.prefs` file
 * shared with the identity prefs + session vault).
 *
 * Rules (web parity with src/lib/prefs.ts):
 *  - every read resolves through [PulseWave8Logic.mergePrefs] → the canonical
 *    defaults back any missing/garbage field, so the UI never sees nulls;
 *  - the SERVER value wins on fetch ([replaceFromServer]);
 *  - a settings toggle applies locally FIRST ([applyLocal]) and the PATCH
 *    follows — a failed PATCH keeps the local change (honest offline parity).
 */
@Singleton
class PulsePrefsLocalStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val PREFS = stringPreferencesKey("wave8.prefs.json")
        /**
         * R1-W2F — per-conversation themes (F-FX-05). EXACTLY the web prefs
         * blob key (src/lib/conv-theme.ts CONV_THEMES_KEY): one JSON object
         * { "<conversationId>": { "wallpaper": "aurora", "tint": "rose" } }
         * — tint omitted when unset. Lives in the SAME DataStore file as the
         * Wave-8 rendering prefs (the shared prefs mechanism).
         */
        val CONV_THEMES = stringPreferencesKey("chat.convThemes")
        /** R2-C item 3 — design-language selection (web localStorage key). */
        val UI_THEME = stringPreferencesKey("pulse.uiTheme.v2")
        /** R2-C item 7 — spotlight recent searches (web localStorage key). */
        val SPOTLIGHT_RECENTS = stringPreferencesKey("pulse.spotlight.recents.v1")
    }

    /** Resolved prefs — defaults guaranteed on every emission. */
    val prefs: Flow<WirePulsePrefs> = context.pulsePrefs.data.map { p ->
        PulseWave8Logic.mergePrefs(p[Keys.PREFS])
    }

    // ── R2-C item 3 — design-language selection (pulse.uiTheme.v2) ──

    /**
     * Live design-language id — one of [UI_THEME_IDS] (the web value
     * strings verbatim); anything unreadable falls back to "glass".
     */
    val uiTheme: Flow<String> = context.pulsePrefs.data.map { p ->
        (p[Keys.UI_THEME])?.takeIf { it in UI_THEME_IDS } ?: UI_THEME_GLASS
    }

    suspend fun setUiTheme(id: String) {
        if (id !in UI_THEME_IDS) return
        context.pulsePrefs.edit { it[Keys.UI_THEME] = id }
    }

    // ── R2-C item 7 — spotlight recent searches ─────────────────

    /**
     * Last 5 non-blank queries, deduped case-insensitively, newest first
     * (web spotlight.tsx readRecents/pushRecent parity).
     */
    val spotlightRecents: Flow<List<String>> = context.pulsePrefs.data.map { p ->
        decodeSpotlightRecents(p[Keys.SPOTLIGHT_RECENTS])
    }

    suspend fun pushSpotlightRecent(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        context.pulsePrefs.edit { p ->
            val current = decodeSpotlightRecents(p[Keys.SPOTLIGHT_RECENTS])
                .filter { it.lowercase() != q.lowercase() }
            val next = (listOf(q) + current).take(SPOTLIGHT_RECENTS_MAX)
            p[Keys.SPOTLIGHT_RECENTS] = encodeSpotlightRecents(next)
        }
    }

    suspend fun clearSpotlightRecents() {
        context.pulsePrefs.edit { it.remove(Keys.SPOTLIGHT_RECENTS) }
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

    // ── R2-C item 4 — whiteboard pending-stroke draft store ─────

    /** Per-conversation DataStore key (iOS `whiteboard.draft:<id>` style). */
    private fun whiteboardDraftKey(conversationId: String) =
        stringPreferencesKey("whiteboard.draft:$conversationId")

    /** The pending strokes for a conversation, oldest first (tolerant). */
    suspend fun whiteboardDraft(conversationId: String): List<app.pulse.protocol.WhiteboardStrokePostDto> {
        if (!CONV_ID_OK.matches(conversationId)) return emptyList()
        val raw = context.pulsePrefs.data.firstOrNull()?.get(whiteboardDraftKey(conversationId))
        return PulseWhiteboardDraftLogic.decode(raw)
    }

    /** Draw-time write-through — persists the stroke BEFORE any sync attempt. */
    suspend fun appendWhiteboardDraft(conversationId: String, stroke: app.pulse.protocol.WhiteboardStrokePostDto) {
        if (!CONV_ID_OK.matches(conversationId)) return
        context.pulsePrefs.edit { p ->
            val pending = PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)])
            p[whiteboardDraftKey(conversationId)] =
                PulseWhiteboardDraftLogic.encode(pending + stroke)
        }
    }

    /** Flush-verdict purge — the acknowledged batch leaves the queue front. */
    suspend fun dropFirstWhiteboardDraft(conversationId: String, count: Int) {
        if (!CONV_ID_OK.matches(conversationId)) return
        context.pulsePrefs.edit { p ->
            val pending = PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)])
            p[whiteboardDraftKey(conversationId)] =
                PulseWhiteboardDraftLogic.encode(PulseWhiteboardDraftLogic.droppingFirst(pending, count))
        }
    }

    /** Undo purge — the newest pending stroke leaves the queue tail. */
    suspend fun dropLastWhiteboardDraft(conversationId: String) {
        if (!CONV_ID_OK.matches(conversationId)) return
        context.pulsePrefs.edit { p ->
            val pending = PulseWhiteboardDraftLogic.decode(p[whiteboardDraftKey(conversationId)])
            p[whiteboardDraftKey(conversationId)] =
                PulseWhiteboardDraftLogic.encode(PulseWhiteboardDraftLogic.droppingLast(pending))
        }
    }

    /** Restore-time swap — rewrites the whole draft (the dedupe pass). */
    suspend fun replaceAllWhiteboardDraft(conversationId: String, strokes: List<app.pulse.protocol.WhiteboardStrokePostDto>) {
        if (!CONV_ID_OK.matches(conversationId)) return
        context.pulsePrefs.edit { p ->
            p[whiteboardDraftKey(conversationId)] = PulseWhiteboardDraftLogic.encode(strokes)
        }
    }

    /** Server-side board reset — the pending queue is gone with the board. */
    suspend fun clearWhiteboardDraft(conversationId: String) {
        if (!CONV_ID_OK.matches(conversationId)) return
        context.pulsePrefs.edit { it.remove(whiteboardDraftKey(conversationId)) }
    }

    // ── R1-W2F — per-conversation themes (F-FX-05) ────────────────

    /**
     * Live `chat.convThemes` map, sanitized like the web
     * sanitizeConvThemeMap: unknown wallpaper tokens / tints / junk keys are
     * dropped, the [ConvTheme.MAX_MAP_ENTRIES] budget caps the payload.
     * JSON object key order IS the LRU order (touched entry last).
     */
    val convThemes: Flow<Map<String, ConvTheme>> = context.pulsePrefs.data.map { p ->
        sanitizeConvThemes(p[Keys.CONV_THEMES])
    }

    /**
     * Upsert/remove ONE conversation's theme (null = remove → follows the
     * global default). LRU semantics: a touched entry moves to the END of the
     * map; beyond [ConvTheme.MAX_MAP_ENTRIES] the OLDEST entry (map head) is
     * evicted. Every write re-serializes the whole map — entries are ~70
     * bytes so the DataStore write stays trivial.
     */
    suspend fun setConvTheme(conversationId: String, theme: ConvTheme?) {
        context.pulsePrefs.edit { p ->
            val current = LinkedHashMap(sanitizeConvThemes(p[Keys.CONV_THEMES]))
            current.remove(conversationId)
            if (theme != null && theme.wallpaper in ConvTheme.WALLPAPERS) {
                current[conversationId] = theme
            }
            while (current.size > ConvTheme.MAX_MAP_ENTRIES) {
                val eldest = current.keys.firstOrNull() ?: break
                current.remove(eldest)
            }
            p[Keys.CONV_THEMES] = encodeConvThemes(current)
        }
    }

    /** Web sanitizeConvThemeMap parity — never throws, never stores junk. */
    private fun sanitizeConvThemes(raw: String?): Map<String, ConvTheme> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { PulseJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return emptyMap()
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

    /** { "<id>": { wallpaper, tint? } } — tint key omitted when null (web shape). */
    private fun encodeConvThemes(map: Map<String, ConvTheme>): String {
        val obj = buildJsonObject {
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
        return PulseJson.encodeToString(JsonObject.serializer(), obj)
    }

    /** Server fetch → full replace (server value wins), re-resolved for tolerance. */
    suspend fun replaceFromServer(server: WirePulsePrefs) {
        val resolved = PulseWave8Logic.mergePatch(PulseWave8Logic.DEFAULTS, server)
        context.pulsePrefs.edit {
            it[Keys.PREFS] = PulseJson.encodeToString(WirePulsePrefs.serializer(), resolved)
        }
    }

    /** Local optimistic toggle — shallow merge over the CURRENT blob. */
    suspend fun applyLocal(patch: WirePulsePrefs) {
        context.pulsePrefs.edit { p ->
            val current = PulseWave8Logic.mergePrefs(p[Keys.PREFS])
            p[Keys.PREFS] = PulseJson.encodeToString(
                WirePulsePrefs.serializer(),
                PulseWave8Logic.mergePatch(current, patch),
            )
        }
    }

    companion object {
        /** Web conv-theme.ts CONV_ID_OK — cuid-style conversation keys only. */
        private val CONV_ID_OK = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

        /** R2-C — the web ui-theme value strings (src/lib/ui-theme.ts). */
        const val UI_THEME_GLASS = "glass"
        val UI_THEME_IDS = setOf("glass", "kinetic", "minimal", "dynamic", "aero")

        /** Web spotlight.tsx RECENTS_MAX. */
        private const val SPOTLIGHT_RECENTS_MAX = 5
    }
}
