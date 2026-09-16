package app.pulse.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pulse.protocol.PulseJson
import app.pulse.protocol.PulseWave8Logic
import app.pulse.protocol.WirePulsePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    }

    /** Resolved prefs — defaults guaranteed on every emission. */
    val prefs: Flow<WirePulsePrefs> = context.pulsePrefs.data.map { p ->
        PulseWave8Logic.mergePrefs(p[Keys.PREFS])
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
}
