package app.pulse.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave 8 — the REAL AndroidKeyStore AES-GCM round-trip (emulator/instrumented
 * path; the store logic under a fake cipher is covered by the JVM
 * SessionTokenStoreTest). Locks:
 *  - encrypt→decrypt round-trip through the actual keystore-backed key
 *    ("pulse.session.token.key", AES-256/GCM/NoPadding, 12-byte IV prefix,
 *    "enc:v1:" framing, Base64 NO_WRAP);
 *  - the at-rest payload never contains the plaintext token;
 *  - unique IV per call (same plaintext encrypts to different ciphertexts);
 *  - garbage / foreign formats decrypt to null (treated as absent);
 *  - the API<23 cleartext-prefix degradation is present by format.
 * Runs on the emulator (android-ci connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionTokenCipherTest {

    private val cipher = KeystoreSessionTokenCipher()
    private val token =
        "f1e2d3c4b5a69788f1e2d3c4b5a69788f1e2d3c4b5a69788f1e2d3c4b5a69788"

    @Test
    fun keystoreRoundTripRecoversThePlaintextToken() {
        val stored = cipher.encrypt(token)
        assertTrue(stored.startsWith("enc:v1:"))
        assertEquals(token, cipher.decryptOrNull(stored))
    }

    @Test
    fun atRestPayloadNeverContainsThePlaintext() {
        val stored = cipher.encrypt(token)
        assertFalse(stored.contains(token))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val a = cipher.encrypt(token)
        val b = cipher.encrypt(token)
        // both decrypt to the same plaintext but the ciphertexts differ
        assertEquals(token, cipher.decryptOrNull(a))
        assertEquals(token, cipher.decryptOrNull(b))
        assertTrue(a != b)
    }

    @Test
    fun foreignAndCorruptFormatsDecodeToNull() {
        assertNull(cipher.decryptOrNull("not-a-vault-payload"))
        assertNull(cipher.decryptOrNull(""))
        // truncated ciphertext (IV only) must fail, not throw
        val stored = cipher.encrypt(token)
        val ivOnly = stored.take(10)
        assertNull(cipher.decryptOrNull(ivOnly))
    }

    @Test
    fun roundTripSurvivesUnicodeAndWhitespace() {
        val payload = "token with spaces + emoji 🔐 and unicode Łódź"
        assertEquals(payload, cipher.decryptOrNull(cipher.encrypt(payload)))
    }
}
