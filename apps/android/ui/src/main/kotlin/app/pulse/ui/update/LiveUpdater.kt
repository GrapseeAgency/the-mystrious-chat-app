package app.pulse.ui.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
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

/** LiveUpdate state machine — Idle → Available → Downloading(pct) → Ready → Installing → [system installer] / Failed(reason). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val versionName: String, val versionCode: Long, val notes: String?) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Ready : UpdateState
    data object Installing : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * LiveUpdate — Pulse's self-update engine for Android sideloading, pure
 * platform APIs: HttpURLConnection (byte-range resume) + org.json (manifest)
 * + PackageManager (integrity gates) + PackageInstaller (system install).
 *
 * 1. Quiet check — every app start (10-min throttle, silent on failure), it
 *    fetches update-manifest.json from the repo CDN (raw.githubusercontent).
 *    Newer versionCode → [UpdateState.Available]; the update surfaces appear.
 * 2. Download — one tap → streaming download into cacheDir/liveupdate as a
 *    .part staging file, with byte-range resume: a dropped connection sends
 *    `Range: bytes=<len>-` and continues from the exact byte. If the primary
 *    URL (GitHub release asset) fails at the network level, the downloader
 *    falls back to the manifest's `apkUrlMirror` (raw CDN) from a clean
 *    slate — the sha256 gate still protects content identity either way.
 *    Single-flight — a second tap can never spawn a second writer.
 * 3. Integrity gates before the installer ever sees the file: truncation
 *    gate (done == total), APK ZIP magic `PK\x03\x04`, a real
 *    PackageManager.getPackageArchiveInfo parse, packageName + exact
 *    expected versionCode match, full ZIP CRC sweep, manifest-declared
 *    SHA-256, and a signer-identity check (the update must carry the same
 *    signing certificate as the installed app — a foreign key is reported
 *    honestly instead of dying in the system installer). Corrupt/stale
 *    leftovers are deleted, never installed.
 * 4. Install — PackageInstaller session (the platform's own channel, same
 *    mechanism the Play Store uses): the verified APK is streamed into the
 *    session and committed with a status callback. The callback surfaces the
 *    EXACT system verdict (pending-user-action / success / the real failure
 *    reason: BLOCKED, INVALID, CONFLICT, STORAGE, INCOMPATIBLE…) in the
 *    update UI — no more silent "There was a problem parsing the package"
 *    with zero diagnosis. Classic FileProvider + ACTION_VIEW handoff remains
 *    as the automatic fallback for OEM platforms that break sessions. If
 *    "install unknown apps" is off, the UI says so instead of failing
 *    silently ([openInstallPermissionSettings]).
 * 5. Honest failure — one auto-resume retry after 2.5 s (per URL, mirror
 *    gets its own fresh attempt), then Failed(reason); every further tap
 *    resumes from where bytes stopped. Progress never resets to 0% except
 *    when switching to the mirror.
 * 6. Why overwrites work: every release is signed with the committed
 *    keystores/pulse-release.keystore, so Android allows straight
 *    overwrite installs — no uninstall between versions.
 */
object LiveUpdater {

    /** The one place Pulse learns where updates live (public repo CDN, no auth). */
    const val MANIFEST_URL: String =
        "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/download/update-manifest.json"

    /** Action for the PackageInstaller session status callback (package-scoped). */
    private const val STATUS_ACTION = "app.pulse.android.action.UPDATE_STATUS"

    private const val CHECK_THROTTLE_MS = 10 * 60 * 1000L
    private const val DIR = "liveupdate"
    private const val APK_NAME = "pulse-update.apk"
    private const val PART_NAME = "$APK_NAME.part"

    data class Manifest(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val apkUrlMirror: String?,
        val notes: String?,
        val sha256: String?,
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
                is UpdateState.Downloading, is UpdateState.Ready, is UpdateState.Installing -> return
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
                if (_state.value is UpdateState.Installing) {
                    // The session is already committed and (probably) waiting on
                    // the user's confirm dialog — re-open the installer with the
                    // same verified bytes instead of re-downloading 14 MB.
                    val cached = File(File(app.cacheDir, DIR), APK_NAME)
                    if (cached.exists()) {
                        install(app, cached)
                    } else {
                        val m = manifest ?: fetchManifest()?.also { manifest = it }
                        _state.value = if (m != null) {
                            UpdateState.Available(m.versionName, m.versionCode, m.notes)
                        } else {
                            UpdateState.Failed("update manifest unreachable — check connection")
                        }
                    }
                    return@launch
                }
                val m = manifest ?: fetchManifest()?.also { manifest = it } ?: run {
                    _state.value = UpdateState.Failed("update manifest unreachable — check connection")
                    return@launch
                }
                if (_state.value !is UpdateState.Downloading) {
                    _state.value = UpdateState.Downloading(0)
                }
                val apk = downloadWithResume(app, m)
                if (apk != null && passesGates(app, apk, m)) {
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
            apkUrlMirror = json.optString("apkUrlMirror").takeIf { it.isNotBlank() },
            notes = json.optString("notes").takeIf { it.isNotBlank() },
            sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Streams the APK with byte-range resume. The manifest's primary apkUrl is
     * tried first; on network failure the mirror (raw CDN) gets one fresh
     * attempt — both serve identical bytes and the sha256 gate enforces it.
     * Throws on network failure; the caller decides retry policy.
     */
    private suspend fun downloadWithResume(context: Context, m: Manifest): File? {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val part = File(dir, PART_NAME)
        val urls = buildList {
            add(m.apkUrl)
            if (!m.apkUrlMirror.isNullOrBlank() && m.apkUrlMirror != m.apkUrl) add(m.apkUrlMirror)
        }
        var urlIndex = 0
        var autoResumeUsed = false
        while (true) {
            try {
                val resuming = part.exists() && part.length() > 0
                val conn = open(urls[urlIndex], connectTimeoutMs = 12_000, readTimeoutMs = 30_000)
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
                if (urlIndex + 1 < urls.size) {
                    // Primary exhausted → mirror from a clean slate. Mixed bytes
                    // from two sources are never risked; the sha256 gate would
                    // catch a lie anyway, but a fresh start is simply correct.
                    urlIndex++
                    part.delete()
                    autoResumeUsed = false
                    continue
                }
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
        // Gate 1 — the PackageManager parse: a real manifest, the right app, the
        // exact expected version. Cheap, catches the coarse cases.
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (info == null) {
            apk.delete()
            return reject("update failed integrity check — tap to re-download")
        }
        if (info.packageName != context.packageName || versionCodeOf(info) != m.versionCode) {
            apk.delete()
            return reject("update stale or foreign — tap to re-download")
        }
        // Gate 2 — full ZIP sweep with CRC: every entry read and checksummed.
        // getPackageArchiveInfo only reads AndroidManifest.xml — a bit-flipped
        // classes.dex mid-file passed v0.1.2's gates and THEN died in the system
        // installer as "There was a problem parsing the package". This sweep
        // catches that corruption here, with an honest message, before the
        // installer ever opens the file.
        if (!zipSweepClean(apk)) {
            apk.delete()
            return reject("update corrupted in transit — tap to re-download")
        }
        // Gate 3 — manifest-declared SHA-256 (publishers append it to
        // update-manifest.json). End-to-end content identity, immune to any
        // byte-preserving tamper on the path. Absent field = legacy manifest, skip.
        if (m.sha256 != null) {
            val actual = apk.inputStream().use { input ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(m.sha256, ignoreCase = true)) {
                apk.delete()
                return reject("update checksum mismatch — tap to re-download")
            }
        }
        // Gate 4 — signer identity. The system installer refuses an update
        // signed with a different key than the installed app, and some ROMs
        // report that refusal as a bare "There was a problem parsing the
        // package". Catch it here and say exactly what to do instead.
        when (archiveSignedByInstalled(context, apk)) {
            false -> {
                apk.delete()
                return reject("installed Pulse was signed with a different key — uninstall it once, then install this update")
            }
            else -> Unit // true = same signer; null = ROM cannot tell — proceed
        }
        return true
    }

    /** Reads every ZIP entry (ZipFile CRC-validates on read). False on any damage. */
    private fun zipSweepClean(apk: File): Boolean = try {
        java.util.zip.ZipFile(apk).use { zip ->
            val buf = ByteArray(64 * 1024)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                    }
                }
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Null = cannot determine on this ROM; true = same signer as the installed
     * app; false = foreign signer (the system installer would refuse).
     */
    @Suppress("DEPRECATION")
    private fun archiveSignedByInstalled(app: Context, apk: File): Boolean? {
        return try {
            val pm = app.packageManager
            if (Build.VERSION.SDK_INT >= 28) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
                val installed = pm.getPackageInfo(app.packageName, flags)
                val a = archive.signingInfo?.apkContentsSigners?.toList() ?: return null
                val i = installed.signingInfo?.apkContentsSigners?.toList() ?: return null
                a.toSet() == i.toSet()
            } else {
                val flags = PackageManager.GET_SIGNATURES
                val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
                val installed = pm.getPackageInfo(app.packageName, flags)
                archive.signatures?.toSet() == installed.signatures?.toSet()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun install(context: Context, apk: File) {
        // API 26+: per-app "Install unknown apps" consent. The method itself is
        // API-26-only — on Android 5-7 the session path runs directly and the
        // system shows its own dialog (a bare call here would be a
        // NoSuchMethodError crash on those devices).
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.Failed("allow “Install unknown apps” for Pulse first")
            return
        }
        _state.value = UpdateState.Ready
        val viaSession = runCatching { installViaSession(context, apk) }
        if (viaSession.isFailure) {
            // Some OEM platforms break PackageInstaller sessions for sideloads —
            // the classic FileProvider handoff is the proven fallback.
            runCatching { installViaFileProvider(context, apk) }
                .onFailure {
                    val why = viaSession.exceptionOrNull()?.message ?: it.message
                    _state.value = UpdateState.Failed(why?.take(120) ?: "installer did not open")
                }
        }
    }

    /**
     * Platform PackageInstaller session: stream the verified APK into the
     * session, commit with a status callback. The callback carries the EXACT
     * system verdict — including the real reason for any rejection — which the
     * update surfaces show verbatim. No more unexplained parse dialog.
     */
    private fun installViaSession(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        // Stay in Installing — the system dialog owns it now.
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        runCatching { app.unregisterReceiver(this) }
                        _state.value = UpdateState.Idle
                    }
                    else -> {
                        runCatching { app.unregisterReceiver(this) }
                        _state.value = UpdateState.Failed(
                            installFailureText(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                        )
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(STATUS_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        val pi = PendingIntent.getBroadcast(
            app,
            sessionId,
            Intent(STATUS_ACTION).setPackage(app.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("pulse-update", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            session.commit(pi.intentSender)
        } catch (t: Throwable) {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { session.abandon() }
            throw t
        } finally {
            session.close()
        }
        _state.value = UpdateState.Installing
    }

    /** Classic FileProvider + ACTION_VIEW handoff — fallback for OEM quirks. */
    private fun installViaFileProvider(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updater", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        _state.value = UpdateState.Installing
    }

    private fun installFailureText(status: Int, message: String?): String {
        val why = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "install cancelled before finishing"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by the device (Play Protect or unknown-apps policy)"
            PackageInstaller.STATUS_FAILURE_INVALID -> "the system could not parse the update — tap to re-download"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflicts with an installed app — uninstall the old Pulse first"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage to install"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "this update is incompatible with the device"
            else -> message ?: "system installer failed"
        }
        return if (message.isNullOrBlank()) why else "$why — $message"
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
