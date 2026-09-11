package app.pulse.data.local

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pulse.protocol.PulseJson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** The encrypted-at-rest session payload (viewer identity + endpoint overrides). */
@Serializable
data class SessionVault(
    val viewerId: String? = null,
    val viewerName: String? = null,
    val viewerUsername: String? = null,
    val viewerColor: String? = null,
    val gatewayOverride: String? = null,
    val socketOverride: String? = null,
    val iceOverride: String? = null,
)

/**
 * Secure session storage (Wave 0) — the durable, offline copy of WHO the
 * viewer is and WHERE the deployment points. The payload JSON is encrypted
 * with an AndroidKeyStore AES-GCM key ("pulse.session.key") and stored in
 * the existing DataStore prefs under "session.vault".
 *
 * Migration: v0.1.x stored the viewer identity as plaintext prefs keys
 * (viewer.id/name/color). On first load those are folded into the vault
 * and removed (they survive only as the read-only UI mirror the
 * SessionViewModel re-seeds from the vault).
 *
 * API < 23 has no AndroidKeyStore AES — the vault degrades to prefixed
 * cleartext storage there (honest, documented).
 */
@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val VAULT = stringPreferencesKey("session.vault")
        val LEGACY_VIEWER_ID = stringPreferencesKey("viewer.id")
        val LEGACY_VIEWER_NAME = stringPreferencesKey("viewer.name")
        val LEGACY_VIEWER_COLOR = stringPreferencesKey("viewer.color")
    }

    suspend fun save(json: String) {
        context.pulsePrefs.edit { it[Keys.VAULT] = encrypt(json) }
    }

    suspend fun load(): String? {
        val prefs = context.pulsePrefs.data.first()
        prefs[Keys.VAULT]?.let { stored -> return decryptOrNull(stored) }

        // Legacy migration — plaintext viewer.* keys → encrypted vault → old keys removed.
        val legacyId = prefs[Keys.LEGACY_VIEWER_ID] ?: return null
        val vault = SessionVault(
            viewerId = legacyId,
            viewerName = prefs[Keys.LEGACY_VIEWER_NAME],
            viewerColor = prefs[Keys.LEGACY_VIEWER_COLOR],
        )
        val json = PulseJson.encodeToString(SessionVault.serializer(), vault)
        context.pulsePrefs.edit { p ->
            p[Keys.VAULT] = encrypt(json)
            p.remove(Keys.LEGACY_VIEWER_ID)
            p.remove(Keys.LEGACY_VIEWER_NAME)
            p.remove(Keys.LEGACY_VIEWER_COLOR)
        }
        return json
    }

    fun delete() {
        scope.launch { context.pulsePrefs.edit { it.remove(Keys.VAULT) } }
    }

    // ── crypto ──────────────────────────────────────────────────

    private fun encrypt(plain: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PREFIX_PLAIN + plain
        return runCatching { keystoreEncrypt(plain) }.getOrElse { PREFIX_PLAIN + plain }
    }

    private fun decryptOrNull(stored: String): String? {
        if (stored.startsWith(PREFIX_PLAIN)) return stored.removePrefix(PREFIX_PLAIN)
        if (!stored.startsWith(PREFIX_ENC)) return null // unknown format — treat as absent
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching { keystoreDecrypt(stored.removePrefix(PREFIX_ENC)) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX_ENC + Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreDecrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "vault payload too short" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pulse.session.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val PREFIX_ENC = "enc:v1:"
        const val PREFIX_PLAIN = "plain:"
    }
}
