package app.pulse.data.local

import org.junit.Assert.*
import org.junit.Test

class AccountStorageKeyTest {
    @Test fun equivalentOriginsShareNamespace() {
        assertEquals(AccountStorageKey.namespace("https://EXAMPLE.com:443/", "viewer"),
            AccountStorageKey.namespace("https://example.com", "viewer"))
    }
    @Test fun accountGatewayAndPathNeverShareFiles() {
        val keys = listOf(
            AccountStorageKey.databaseName("https://a.example", "alice"),
            AccountStorageKey.databaseName("https://a.example", "bob"),
            AccountStorageKey.databaseName("https://b.example", "alice"),
            AccountStorageKey.databaseName("https://a.example/api", "alice"),
        )
        assertEquals(4, keys.toSet().size)
        assertTrue(keys.none { it.contains("alice") || it.contains("bob") || it.contains("example") })
    }
    @Test fun lengthPrefixesPreventAmbiguousIdentityConcatenation() {
        assertNotEquals(AccountStorageKey.namespace("https://a.example/x", "yz"),
            AccountStorageKey.namespace("https://a.example/xy", "z"))
    }
    @Test fun embeddedCredentialsAndQueryAreRejected() {
        listOf("https://user:secret@example.com", "https://example.com?token=secret", "file:///private").forEach {
            assertTrue(runCatching { AccountStorageKey.origin(it) }.isFailure)
        }
    }
}
