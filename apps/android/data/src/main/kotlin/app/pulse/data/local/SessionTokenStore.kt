package app.pulse.data.local

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wave 8 — session-token crypto seam. The STORE logic is JVM-testable with a
 * fake cipher; the production cipher is [KeystoreSessionTokenCipher] (real
 * AndroidKeyStore AES-GCM, validated by the androidTest suite).
 */
interface SessionTokenCipher {
    /** Encrypt/encode a plaintext token into its at-rest form. */
    fun encrypt(plain: String): String

    /** Decode the at-rest form back (null = unreadable — treated as absent). */
    fun decryptOrNull(stored: String): String?
}

/**
 * The at-rest persistence seam (DataStore prefs in production). Abstracted so
 * the store logic round-trips in JVM tests without an Android context.
 */
interface SessionTokenPersistence {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun clear()
}

/**
 * Wave 8 session tokens (spec §3.11 A-1/A-2) — the durable, encrypted-at-rest
 * copy of the caller's bearer credential.
 *
 * - The token is 64 hex chars, issued by POST /api/users and rotated by
 *   POST /api/users/login; only its sha256 lives server-side, so the raw
 *   value survives ONLY here (AndroidKeyStore AES-GCM ciphertext in the
 *   shared DataStore prefs file, same discipline as [SecureSessionStore]).
 * - [cached] mirrors the loaded token for synchronous consumers (the Ktor
 *   request pipeline reads it per-request; no suspend hops on the hot path).
 * - [invalidated] flips when a live 401 / socket join:error proves the token
 *   rotated away — the app clears the session and surfaces an honest
 *   re-login notice.
 *
 * API < 23 has no AndroidKeyStore AES — the token degrades to prefixed
 * cleartext in the app-private DataStore (honest, documented; the same
 * degradation [SecureSessionStore] has shipped since Wave 0).
 */
@Singleton
class SessionTokenStore @Inject constructor(
    private val persistence: SessionTokenPersistence,
    private val cipher: SessionTokenCipher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Synchronous mirror for the request pipeline (null = no token). */
    @Volatile
    var cached: String? = null
        private set

    private val _invalidated = MutableStateFlow(false)

    private companion object {
        const val TAG = "SessionToken"
    }

    /** One-shot (per store instance) flag: the server rejected our token. */
    val invalidated: StateFlow<Boolean> = _invalidated.asStateFlow()

    /** Load + decrypt the persisted token into the cache (idempotent). */
    suspend fun load(): String? {
        cached?.let { return it }
        val stored = persistence.read() ?: return null
        val plain = cipher.decryptOrNull(stored)
        if (plain.isNullOrBlank()) {
            // unreadable at-rest payload (key invalidated by a backup restore,
            // format drift) — drop it rather than loop on guaranteed 401s
            scope.launch { persistence.clear() }
            return null
        }
        cached = plain
        return plain
    }

    /** Persist + cache a freshly issued/rotated token. */
    suspend fun save(token: String) {
        val clean = token.trim().takeIf { it.isNotEmpty() } ?: return
        persistence.write(cipher.encrypt(clean))
        cached = clean
        _invalidated.value = false
    }

    /** Identity forget/switch — the credential must not outlive the identity. */
    suspend fun clear() {
        persistence.clear()
        cached = null
        _invalidated.value = false
    }

    /**
     * The gateway answered 401 (or the relay refused our join): the stored
     * token is invalid or has been rotated. Clear the credential and raise
     * the invalidated flag — the app routes to onboarding with an honest
     * notice (web parity: session-rotated handling).
     */
    suspend fun markInvalid(reason: String?) {
        if (cached == null && !_invalidated.value) return
        Log.w(TAG, "session token rejected — clearing ($reason)")
        persistence.clear()
        cached = null
        _invalidated.value = true
    }

    /**
     * Fire-and-forget variant for non-suspend hooks (the Ktor failure mapper /
     * the socket join:error callback). Runs on the store's own IO scope.
     */
    fun markInvalidAsync(reason: String?) {
        scope.launch { runCatching { markInvalid(reason) } }
    }
}

/**
 * Production persistence — the shared `pulse.prefs` DataStore file under the
 * key "session.token.enc" (one file: prefs + session vault + this token).
 */
class DataStoreSessionTokenPersistence @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : SessionTokenPersistence {

    private object Keys {
        val TOKEN = stringPreferencesKey("session.token.enc")
    }

    override suspend fun read(): String? = context.pulsePrefs.data.first()[Keys.TOKEN]

    override suspend fun write(value: String) {
        context.pulsePrefs.edit { it[Keys.TOKEN] = value }
    }

    override suspend fun clear() {
        context.pulsePrefs.edit { it.remove(Keys.TOKEN) }
    }
}

/**
 * Production cipher — AndroidKeyStore AES-GCM, byte-compatible with the
 * [SecureSessionStore] vault format ("enc:v1:" + base64(iv‖ct), GCM tag 128
 * bits, dedicated alias "pulse.session.token.key"). API < 23 (and any
 * unexpected Keystore failure) degrades to "plain:"-prefixed cleartext in the
 * app-private DataStore — honest, the same trade the session vault ships.
 */
class KeystoreSessionTokenCipher @Inject constructor() : SessionTokenCipher {

    override fun encrypt(plain: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PREFIX_PLAIN + plain
        return runCatching { keystoreEncrypt(plain) }.getOrElse { PREFIX_PLAIN + plain }
    }

    override fun decryptOrNull(stored: String): String? {
        if (stored.startsWith(PREFIX_PLAIN)) return stored.removePrefix(PREFIX_PLAIN)
        if (!stored.startsWith(PREFIX_ENC)) return null // unknown format — treat as absent
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching { keystoreDecrypt(stored.removePrefix(PREFIX_ENC)) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreKey(): javax.crypto.SecretKey {
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance("AES", KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreEncrypt(plain: String): String {
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX_ENC + Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreDecrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "token payload too short" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            keystoreKey(),
            javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pulse.session.token.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val PREFIX_ENC = "enc:v1:"
        const val PREFIX_PLAIN = "plain:"
    }
}
