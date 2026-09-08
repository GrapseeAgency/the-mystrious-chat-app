package app.pulse.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** LiveUpdate state machine — Idle → Available → Downloading(pct) → Ready → [installer] / Failed(reason). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val versionName: String, val versionCode: Long, val notes: String?) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Ready : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * LiveUpdate — Pulse's self-update engine for Android sideloading, pure
 * platform APIs: HttpURLConnection (byte-range resume) + org.json (manifest)
 * + PackageManager (integrity gates) + FileProvider (system installer).
 *
 * 1. Quiet check — every app start (10-min throttle, silent on failure), it
 *    fetches update-manifest.json from the repo CDN (raw.githubusercontent).
 *    Newer versionCode → [UpdateState.Available]; the update surfaces appear.
 * 2. Download — one tap → streaming download into cacheDir/liveupdate as a
 *    .part staging file, with byte-range resume: a dropped connection sends
 *    `Range: bytes=<len>-` and continues from the exact byte (the GitHub CDN
 *    advertises ranges). Handles 206 Partial Content; 416 → clean restart.
 *    Single-flight — a second tap can never spawn a second writer.
 * 3. Integrity gates before the installer ever sees the file: truncation
 *    gate (done == total), APK ZIP magic `PK\x03\x04`, a real
 *    PackageManager.getPackageArchiveInfo parse, then packageName + exact
 *    expected versionCode match. Corrupt/stale leftovers are deleted, never
 *    installed.
 * 4. Install — .part renamed to .apk, FileProvider URI + ACTION_VIEW system
 *    installer. If "install unknown apps" is off, the UI says so instead of
 *    failing silently ([openInstallPermissionSettings]).
 * 5. Honest failure — one auto-resume retry after 2.5 s, then Failed(reason);
 *    every further tap resumes from where bytes stopped. Progress never
 *    resets to 0%.
 * 6. Why overwrites work: every release is signed with the committed
 *    keystores/pulse-release.keystore, so Android allows straight
 *    overwrite installs — no uninstall between versions.
 */
object LiveUpdater {

    /** The one place Pulse learns where updates live (public repo CDN, no auth). */
    const val MANIFEST_URL: String =
        "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/download/update-manifest.json"

    private const val CHECK_THROTTLE_MS = 10 * 60 * 1000L
    private const val DIR = "liveupdate"
    private const val APK_NAME = "pulse-update.apk"
    private const val PART_NAME = "$APK_NAME.part"

    data class Manifest(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val notes: String?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastCheckAtMs = 0L
    @Volatile private var manifest: Manifest? = null

    /** Quiet check — throttled, silent. Safe to call on every app start. */
    suspend fun syncFrom(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) return
        try {
            when (_state.value) {
                is UpdateState.Downloading, is UpdateState.Ready -> return
                else -> Unit
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastCheckAtMs < CHECK_THROTTLE_MS) return
            lastCheckAtMs = now

            val m = fetchManifest() ?: return
            manifest = m
            val current = installedVersionCode(app)
            when {
                m.versionCode > current -> {
                    val stale = _state.value
                    if (stale is UpdateState.Idle || stale is UpdateState.Failed ||
                        (stale is UpdateState.Available && stale.versionCode != m.versionCode)
                    ) {
                        _state.value = UpdateState.Available(m.versionName, m.versionCode, m.notes)
                    }
                }
                else -> {
                    val stale = _state.value
                    if (stale is UpdateState.Available || stale is UpdateState.Failed) {
                        _state.value = UpdateState.Idle
                    }
                }
            }
        } catch (_: Exception) {
            // Quiet checks stay silent — the UI never pops errors for them.
        } finally {
            inFlight.set(false)
        }
    }

    /** User tap — download (resume) → gates → system installer. Single-flight. */
    fun beginInstallFlow(context: Context) {
        val app = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val m = manifest ?: fetchManifest()?.also { manifest = it } ?: run {
                    _state.value = UpdateState.Failed("update manifest unreachable — check connection")
                    return@launch
                }
                if (_state.value !is UpdateState.Downloading) {
                    _state.value = UpdateState.Downloading(0)
                }
                val apk = downloadWithResume(app, m)
                if (apk != null && passesGates(app, apk, m)) {
                    _state.value = UpdateState.Ready
                    install(app, apk)
                }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(friendly(e))
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        val app = context.applicationContext
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun installedVersionLabel(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    // ---- internals -------------------------------------------------------

    private fun fetchManifest(): Manifest? {
        val conn = open(MANIFEST_URL, connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
        conn.responseCode.let { if (it != 200) return null }
        val body = conn.inputStream.use { it.readBytes() }.decodeToString()
        val json = JSONObject(body)
        return Manifest(
            versionName = json.optString("versionName"),
            versionCode = json.optLong("versionCode", 0L),
            apkUrl = json.optString("apkUrl"),
            notes = json.optString("notes").takeIf { it.isNotBlank() },
        )
    }

    /** Streams the APK with byte-range resume. Throws on network failure; the caller decides retry policy. */
    private suspend fun downloadWithResume(context: Context, m: Manifest): File? {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val part = File(dir, PART_NAME)
        var autoResumeUsed = false
        while (true) {
            try {
                val resuming = part.exists() && part.length() > 0
                val conn = open(m.apkUrl, connectTimeoutMs = 12_000, readTimeoutMs = 30_000)
                var base = 0L
                if (resuming) {
                    conn.setRequestProperty("Range", "bytes=${part.length()}-")
                }
                val code = conn.responseCode
                when {
                    code == 206 -> base = part.length()
                    code == 200 -> {
                        // Server ignored the range (or fresh start) — truncate.
                        base = 0
                        FileOutputStream(part).use { /* truncate */ }
                    }
                    code == 416 -> {
                        // Range beyond EOF — stale leftover; clean restart.
                        part.delete()
                        continue
                    }
                    else -> throw IOException("CDN responded $code")
                }

                val contentRange = conn.getHeaderField("Content-Range")
                val total = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    ?: conn.getHeaderFieldLong("Content-Length", -1L).takeIf { it > 0 }
                        ?.let { if (code == 206) it + base else it }
                if (total == null || total <= 0) throw IOException("unknown update size")

                var lastPercent = -1
                conn.inputStream.use { input ->
                    FileOutputStream(part, /* append = */ base > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            copied += n
                            val percent = (((base + copied) * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                if (_state.value is UpdateState.Downloading) {
                                    _state.value = UpdateState.Downloading(percent)
                                }
                            }
                        }
                    }
                }
                if (part.length() != total) throw IOException("download incomplete (${part.length()}/$total bytes)")
                return part
            } catch (e: Exception) {
                if (!autoResumeUsed) {
                    autoResumeUsed = true
                    delay(2_500)
                    continue // one auto-resume — bytes continue, never a reset
                }
                throw e
            }
        }
    }

    /** Returns true (file renamed to .apk) or deletes the file and reports false. */
    private fun passesGates(context: Context, part: File, m: Manifest): Boolean {
        fun reject(reason: String): Boolean {
            _state.value = UpdateState.Failed(reason)
            return false
        }
        val magic = ByteArray(4)
        part.inputStream().use { input ->
            var read = 0
            while (read < 4) {
                val n = input.read(magic, read, 4 - read)
                if (n == -1) break
                read += n
            }
            if (read < 4) return reject("download truncated — tap to resume")
        }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte() ||
            magic[2] != 0x03.toByte() || magic[3] != 0x04.toByte()
        ) {
            part.delete()
            return reject("not a valid APK — tap to re-download")
        }
        val apk = File(part.parentFile, APK_NAME)
        if (!part.renameTo(apk)) return reject("could not finalize update file")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (info == null) {
            apk.delete()
            return reject("update failed integrity check — tap to re-download")
        }
        if (info.packageName != context.packageName || versionCodeOf(info) != m.versionCode) {
            apk.delete()
            return reject("update stale or foreign — tap to re-download")
        }
        return true
    }

    private fun install(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.Failed("allow “Install unknown apps” for Pulse first")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updater", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Pulse-LiveUpdate/1 (Android)")
        return conn
    }

    private fun friendly(e: Exception): String = when (e) {
        is IOException -> "network interrupted — tap to resume"
        else -> e.message?.take(120) ?: "update failed — tap to retry"
    }
}
