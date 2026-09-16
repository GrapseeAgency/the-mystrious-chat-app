package app.pulse.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 8 — SessionTokenStore logic under a FAKE cipher + FAKE persistence
 * (JVM — AndroidKeyStore needs an emulator; the real-cipher round-trip lives
 * in the androidTest KeystoreSessionTokenCipherTest).
 *
 * The fake cipher is a real reversible transform (prefix + reversed payload),
 * so encrypt→decrypt round-trips honestly; a "broken" mode simulates an
 * unreadable at-rest payload (backup-restore key loss) to lock the
 * self-healing path.
 */
class SessionTokenStoreTest {

    /** Reversible fake: "enc:" + reversed payload. */
    private class FakeCipher(val broken: Boolean = false) : SessionTokenCipher {
        override fun encrypt(plain: String): String = "enc:" + plain.reversed()
        override fun decryptOrNull(stored: String): String? {
            if (!stored.startsWith("enc:")) return null
            if (broken) return null
            return stored.removePrefix("enc:").reversed()
        }
    }

    /** In-memory persistence mirror. */
    private class FakePersistence : SessionTokenPersistence {
        val backing = mutableListOf<String>()
        override suspend fun read(): String? = backing.lastOrNull()
        override suspend fun write(value: String) {
            backing.clear(); backing.add(value)
        }
        override suspend fun clear() { backing.clear() }
    }

    private val TOKEN_64 = "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8"

    @Test
    fun `save persists ciphertext and warms the synchronous cache`() = runTest {
        val persistence = FakePersistence()
        val store = SessionTokenStore(persistence, FakeCipher())
        assertNull(store.cached)

        store.save(TOKEN_64)

        // at-rest payload is NOT plaintext (fake cipher reversed it)
        assertEquals(1, persistence.backing.size)
        assertEquals("enc:" + TOKEN_64.reversed(), persistence.backing[0])
        assertFalse(persistence.backing[0].contains(TOKEN_64))
        // synchronous mirror is warm for the request pipeline
        assertEquals(TOKEN_64, store.cached)
        assertFalse(store.invalidated.value)
    }

    @Test
    fun `load round-trips through the cipher`() = runTest {
        val persistence = FakePersistence()
        val writer = SessionTokenStore(persistence, FakeCipher())
        writer.save(TOKEN_64)

        val reader = SessionTokenStore(persistence, FakeCipher())
        assertNull(reader.cached)
        assertEquals(TOKEN_64, reader.load())
        assertEquals(TOKEN_64, reader.cached)
        // idempotent — a second load re-serves the cache
        assertEquals(TOKEN_64, reader.load())
    }

    @Test
    fun `load drops an unreadable payload instead of looping on guaranteed 401s`() = runTest {
        val persistence = FakePersistence()
        persistence.write("enc:" + TOKEN_64.reversed())

        val store = SessionTokenStore(persistence, FakeCipher(broken = true))
        assertNull(store.load())
        assertNull(store.cached)
        // self-heal is async (store scope on Dispatchers.IO) — wait deterministically
        for (i in 1..300) {
            if (persistence.backing.isEmpty()) break
            Thread.sleep(10)
        }
        // self-healed: the dead payload was removed from at-rest storage
        assertTrue(persistence.backing.isEmpty())
    }

    @Test
    fun `clear wipes both the cache and the at-rest copy`() = runTest {
        val persistence = FakePersistence()
        val store = SessionTokenStore(persistence, FakeCipher())
        store.save(TOKEN_64)

        store.clear()

        assertNull(store.cached)
        assertTrue(persistence.backing.isEmpty())
        assertFalse(store.invalidated.value)
    }

    @Test
    fun `markInvalid clears the credential and raises the flag`() = runTest {
        val persistence = FakePersistence()
        val store = SessionTokenStore(persistence, FakeCipher())
        store.save(TOKEN_64)

        store.markInvalid("Session token is invalid or has been rotated. Log in again.")

        assertNull(store.cached)
        assertTrue(persistence.backing.isEmpty())
        assertTrue(store.invalidated.value)
    }

    @Test
    fun `markInvalid without a stored token is a no-op`() = runTest {
        val persistence = FakePersistence()
        val store = SessionTokenStore(persistence, FakeCipher())
        store.markInvalid(null)
        assertFalse(store.invalidated.value)
    }

    @Test
    fun `save after invalidation re-arms the store`() = runTest {
        val persistence = FakePersistence()
        val store = SessionTokenStore(persistence, FakeCipher())
        store.save(TOKEN_64)
        store.markInvalid("rotated")
        assertTrue(store.invalidated.value)

        val fresh = TOKEN_64.reversed()
        store.save(fresh)
        assertEquals(fresh, store.cached)
        assertFalse(store.invalidated.value)
    }

    @Test
    fun `blank tokens are never persisted`() = runTest {
        val persistence = FakePersistence()
        val store = SessionTokenStore(persistence, FakeCipher())
        store.save("   ")
        assertNull(store.cached)
        assertTrue(persistence.backing.isEmpty())
    }
}
