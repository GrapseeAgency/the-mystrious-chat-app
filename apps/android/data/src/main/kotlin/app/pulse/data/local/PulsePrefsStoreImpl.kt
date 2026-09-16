package app.pulse.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pulse.domain.repository.PulsePrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Shared DataStore instance — prefs + the SecureSessionStore vault live in one file. */
internal val Context.pulsePrefs by preferencesDataStore(name = "pulse.prefs")

/**
 * DataStore-backed prefs — the single source of device-side settings
 * (viewer identity, ambient FX mode, dark override, reduced motion).
 * Note: the DataStore instance is shared with [SecureSessionStore] (the
 * encrypted "session.vault" key lives in this same file).
 */
@Singleton
class PulsePrefsStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PulsePrefsStore {

    private object Keys {
        val VIEWER_ID = stringPreferencesKey("viewer.id")
        val VIEWER_NAME = stringPreferencesKey("viewer.name")
        val VIEWER_COLOR = stringPreferencesKey("viewer.color")
        val FX_MODE = stringPreferencesKey("fx.ambientMode")
        val DARK = stringPreferencesKey("ui.darkOverride")
        val REDUCED = booleanPreferencesKey("ui.reducedMotion")
        val CHATS_FILTER = stringPreferencesKey("chats.listFilter")
        val SERVER_BASE = stringPreferencesKey("net.serverBase")
        val VOICE_RATE = floatPreferencesKey("voice.rate")
        /** Web parity key: pulse-voice-captions (live-caption toggle for voice rooms). */
        val VOICE_CAPTIONS = booleanPreferencesKey("voice.captions")
        // ── Wave 8 — LOCAL settings (web pulse.settings.v1 parity) ──
        val HAPTICS_ON = booleanPreferencesKey("pulse.settings.hapticsOn")
        val QUIET_HOURS_ON = booleanPreferencesKey("pulse.settings.quietHoursOn")
        val QUIET_START = stringPreferencesKey("pulse.settings.quietStart")
        val QUIET_END = stringPreferencesKey("pulse.settings.quietEnd")
    }

    override val viewerId: Flow<String?> = context.pulsePrefs.data.map { it[Keys.VIEWER_ID] }
    override val viewerName: Flow<String?> = context.pulsePrefs.data.map { it[Keys.VIEWER_NAME] }
    override val viewerColor: Flow<String?> = context.pulsePrefs.data.map { it[Keys.VIEWER_COLOR] }
    override val fxMode: Flow<String> = context.pulsePrefs.data.map { it[Keys.FX_MODE] ?: "aurora" }
    override val darkOverride: Flow<String> = context.pulsePrefs.data.map { it[Keys.DARK] ?: "system" }
    override val reducedMotion: Flow<Boolean> = context.pulsePrefs.data.map { it[Keys.REDUCED] ?: false }
    override val chatsListFilter: Flow<String> = context.pulsePrefs.data.map { it[Keys.CHATS_FILTER] ?: "all" }
    override val serverBase: Flow<String?> = context.pulsePrefs.data.map { it[Keys.SERVER_BASE]?.takeIf { v -> v.isNotBlank() } }
    override val voiceRate: Flow<Float> = context.pulsePrefs.data.map { it[Keys.VOICE_RATE] ?: 1f }
    override val voiceCaptions: Flow<Boolean> = context.pulsePrefs.data.map { it[Keys.VOICE_CAPTIONS] ?: false }

    // Wave 8 — local-only settings: web defaults off/22:00/07:00, haptics true.
    override val hapticsOn: Flow<Boolean> = context.pulsePrefs.data.map { it[Keys.HAPTICS_ON] ?: true }
    override val quietHoursOn: Flow<Boolean> = context.pulsePrefs.data.map { it[Keys.QUIET_HOURS_ON] ?: false }
    override val quietStart: Flow<String> = context.pulsePrefs.data.map { it[Keys.QUIET_START] ?: "22:00" }
    override val quietEnd: Flow<String> = context.pulsePrefs.data.map { it[Keys.QUIET_END] ?: "07:00" }

    override suspend fun setHapticsOn(value: Boolean) {
        context.pulsePrefs.edit { it[Keys.HAPTICS_ON] = value }
    }

    override suspend fun setQuietHoursOn(value: Boolean) {
        context.pulsePrefs.edit { it[Keys.QUIET_HOURS_ON] = value }
    }

    override suspend fun setQuietStart(value: String) {
        context.pulsePrefs.edit { it[Keys.QUIET_START] = value }
    }

    override suspend fun setQuietEnd(value: String) {
        context.pulsePrefs.edit { it[Keys.QUIET_END] = value }
    }

    override suspend fun setViewer(id: String?, name: String?, color: String?) {
        context.pulsePrefs.edit { p ->
            if (id == null) {
                p.remove(Keys.VIEWER_ID)
                p.remove(Keys.VIEWER_NAME)
                p.remove(Keys.VIEWER_COLOR)
            } else {
                p[Keys.VIEWER_ID] = id
                if (name != null) p[Keys.VIEWER_NAME] = name else p.remove(Keys.VIEWER_NAME)
                if (color != null) p[Keys.VIEWER_COLOR] = color
            }
        }
    }

    override suspend fun setFxMode(mode: String) {
        context.pulsePrefs.edit { it[Keys.FX_MODE] = mode }
    }

    override suspend fun setDarkOverride(value: String) {
        context.pulsePrefs.edit { it[Keys.DARK] = value }
    }

    override suspend fun setReducedMotion(value: Boolean) {
        context.pulsePrefs.edit { it[Keys.REDUCED] = value }
    }

    override suspend fun setChatsListFilter(value: String) {
        context.pulsePrefs.edit { it[Keys.CHATS_FILTER] = value }
    }

    override suspend fun setVoiceRate(value: Float) {
        context.pulsePrefs.edit { it[Keys.VOICE_RATE] = value }
    }

    override suspend fun setVoiceCaptions(value: Boolean) {
        context.pulsePrefs.edit { it[Keys.VOICE_CAPTIONS] = value }
    }

    override suspend fun setServerBase(value: String?) {
        context.pulsePrefs.edit { p ->
            val clean = value?.trim()?.trimEnd('/')?.takeIf { v -> v.isNotBlank() }
            if (clean == null) p.remove(Keys.SERVER_BASE) else p[Keys.SERVER_BASE] = clean
        }
    }
}
