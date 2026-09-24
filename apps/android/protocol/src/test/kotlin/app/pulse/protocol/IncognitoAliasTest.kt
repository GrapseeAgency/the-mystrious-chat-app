package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R3-B item 4 — JVM pins for the deterministic incognito alias. The expected
 * strings are computed by the canonical web implementation
 * (chat-room.tsx anonStableHash / anonAliasPreview) — the exact values the
 * server's messages route will store, so any drift breaks these first.
 */
class IncognitoAliasTest {

    @Test
    fun `fnv1a matches the web anonStableHash reference vectors`() {
        // node: anonStableHash('user-1:conv-1') === 3972075490
        assertEquals(3972075490u, IncognitoAlias.fnv1a("user-1:conv-1"))
        // node: anonStableHash('alice:room42') === 1427486394
        assertEquals(1427486394u, IncognitoAlias.fnv1a("alice:room42"))
        // node: anonStableHash('u_café:conv') === 793300128 (UTF-16 units, é is one unit)
        assertEquals(793300128u, IncognitoAlias.fnv1a("u_café:conv"))
        // FNV-1a of the empty string is the offset basis 0x811c9dc5.
        assertEquals(2166136261u, IncognitoAlias.fnv1a(""))
    }

    @Test
    fun `aliasFor matches the web anonAliasPreview reference vectors`() {
        // node: anonAliasPreview('user-1','conv-1') === 'Neon the Comet'
        assertEquals("Neon the Comet", IncognitoAlias.aliasFor("user-1", "conv-1"))
        // node: anonAliasPreview('alice','room42') === 'Neon the Fox'
        assertEquals("Neon the Fox", IncognitoAlias.aliasFor("alice", "room42"))
        // node: anonAliasPreview('u_café','conv') === 'Swift the Comet'
        assertEquals("Swift the Comet", IncognitoAlias.aliasFor("u_café", "conv"))
        // node: anonAliasPreview('U&utm','x') === 'Swift the Otter'
        assertEquals("Swift the Otter", IncognitoAlias.aliasFor("U&utm", "x"))
    }

    @Test
    fun `alias is deterministic and word lists keep the server order`() {
        assertEquals(
            IncognitoAlias.aliasFor("user-1", "conv-1"),
            IncognitoAlias.aliasFor("user-1", "conv-1"),
        )
        assertEquals(8, IncognitoAlias.ANON_ADJECTIVES.size)
        assertEquals(8, IncognitoAlias.ANON_ANIMALS.size)
        // The first entries pin the server's list order (chat-room.tsx:354-355).
        assertEquals("Swift", IncognitoAlias.ANON_ADJECTIVES.first())
        assertEquals("Silent", IncognitoAlias.ANON_ADJECTIVES.last())
        assertEquals("Falcon", IncognitoAlias.ANON_ANIMALS.first())
        assertEquals("Fox", IncognitoAlias.ANON_ANIMALS.last())
    }

    @Test
    fun `alias always reads Adjective the Animal`() {
        // Sweep the 64-bit adjective×animal space is overkill; a deterministic
        // sample of key shapes must keep the exact "A the B" sentence shape.
        val keys = listOf(
            "a:b", "user:conversation", "0:0", "z:",
            "user-1:conv-1", "alice:room42",
        )
        for (key in keys) {
            val alias = IncognitoAlias.aliasFor(key.substringBefore(':'), key.substringAfter(':'))
            val parts = alias.split(" the ")
            assertEquals(2, parts.size, "two words joined by ' the ': $alias")
            assertTrue(parts[0] in IncognitoAlias.ANON_ADJECTIVES) { "adjective off-list: $alias" }
            assertTrue(parts[1] in IncognitoAlias.ANON_ANIMALS) { "animal off-list: $alias" }
        }
    }
}
