package app.pulse.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pulse.domain.model.ConvTheme
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.WirePulsePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    }

    /** Resolved prefs — defaults guaranteed on every emission. */
    val prefs: Flow<WirePulsePrefs> = context.pulsePrefs.data.map { p ->
        PulseWave8Logic.mergePrefs(p[Keys.PREFS])
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
    }
}
