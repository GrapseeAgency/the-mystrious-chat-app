package app.pulse.data.remote

import android.content.Context
import android.util.Log
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.SecureSessionStore
import app.pulse.data.local.SessionVault
import app.pulse.protocol.PulseJson
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Manifest-driven endpoint override (Wave 0 — the deployment hook):
 * `update-manifest.json` may carry non-empty `gateway` / `socket` string
 * fields next to the LiveUpdater's apk metadata. When they appear, the
 * client retargets BOTH the REST base and the realtime relay — and the
 * values persist in the [SecureSessionStore] vault so they survive restarts.
 *
 * Cold start order (beats any network): [applyPersisted] re-applies the
 * vault overrides first; only then does [fetchAndApply] hit the network.
 * Today's manifest carries neither key — the client stays offline-first
 * on the baked CDN until a real host is deployed; the hook simply works
 * the day they appear. Every failure is silent (3s timeout) by design.
 */
@Singleton
class ManifestEndpoints @Inject constructor(
    private val secureSessionStore: SecureSessionStore,
) {

    /** Cold start: re-apply persisted overrides BEFORE any network runs. */
    suspend fun applyPersisted() {
        val json = secureSessionStore.load() ?: return
        val vault = runCatching { PulseJson.decodeFromString(SessionVault.serializer(), json) }
            .getOrElse { Log.w(TAG, "vault decode failed", it); return }
        PulseEndpoints.applyOverride(vault.gatewayOverride, vault.socketOverride)
        PulseEndpoints.applyIceOverride(vault.iceOverride)
    }

    /**
     * Fetch the manifest and adopt gateway/socket when present. The current
     * distribution gateway is the repo CDN where the manifest lives under
     * download/ — both layouts are probed, quietly.
     */
    suspend fun fetchAndApply() = withContext(Dispatchers.IO) {
        val urls = buildList {
            add(PulseEndpoints.http("/update-manifest.json"))
            if (PulseEndpoints.gatewayHttpUrl.contains("raw.githubusercontent.com")) {
                add(PulseEndpoints.http("/download/update-manifest.json"))
            }
        }
        for (url in urls) {
            val (gateway, socket, ice) = fetchOverrides(url)
            if (gateway != null || socket != null || ice != null) {
                // v0.1.8 semantics: a manifest `gateway` drives BOTH rest and
                // realtime (the edge serves both); an explicit `socket` field
                // overrides the relay separately when deployments split them.
                // Wave 3-HW: an `ice` array (TURN/STUN with credentials) is
                // adopted as raw JSON and consumed by the call engines.
                val effectiveSocket = socket ?: gateway
                PulseEndpoints.applyOverride(gateway, effectiveSocket)
                PulseEndpoints.applyIceOverride(ice)
                persistOverrides(gateway, effectiveSocket, ice)
                Log.i(TAG, "endpoint override adopted — gateway=$gateway socket=$effectiveSocket ice=${ice != null}")
                return@withContext
            }
        }
    }

    private suspend fun persistOverrides(gateway: String?, socket: String?, ice: String?) {
        runCatching {
            val current = secureSessionStore.load()
                ?.let { runCatching { PulseJson.decodeFromString(SessionVault.serializer(), it) }.getOrNull() }
                ?: SessionVault()
            secureSessionStore.save(
                PulseJson.encodeToString(
                    SessionVault.serializer(),
                    current.copy(
                        gatewayOverride = gateway ?: current.gatewayOverride,
                        socketOverride = socket ?: current.socketOverride,
                        iceOverride = ice ?: current.iceOverride,
                    ),
                ),
            )
        }
    }

    /** 3s-timeout GET — returns (gateway?, socket?, ice?) with nulls on any failure. */
    private fun fetchOverrides(url: String): Triple<String?, String?, String?> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) {
                Triple(null, null, null)
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                Triple(
                    obj.optString("gateway", "").takeIf { it.isNotBlank() },
                    obj.optString("socket", "").takeIf { it.isNotBlank() },
                    obj.optJSONArray("ice")?.takeIf { it.length() > 0 }?.toString(),
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        Triple(null, null, null)
    }

    private companion object {
        const val TAG = "ManifestEndpoints"
    }
}
