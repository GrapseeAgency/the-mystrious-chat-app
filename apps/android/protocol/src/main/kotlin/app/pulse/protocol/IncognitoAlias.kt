package app.pulse.protocol

// ─────────────────────────────────────────────────────────────────────────
// R3-B item 4 — F-MS-17 deterministic incognito alias (groups only).
//
// Verbatim port of the canonical client implementation in
// src/components/chat/chat-room.tsx:351-371 (anonStableHash /
// anonAliasPreview), which itself mirrors the server's messages route:
//   · FNV-1a 32-bit over the UTF-16 code units of "userId:conversationId"
//     (JS charCodeAt walks UTF-16 units; Math.imul is a 32-bit truncating
//     multiply — Kotlin UInt arithmetic wraps modulo 2^32, same result);
//   · word lists "Adjective the Animal" — server order is load-bearing;
//   · adjective  = hash % 8 · animal = (hash / 8) % 8.
//
// The alias is DISPLAY-ONLY (web parity): the outgoing wire payload carries
// anon:true and the server derives and stores the identical alias, so the
// optimistic bubble can already wear the exact mask the server will keep.
// ─────────────────────────────────────────────────────────────────────────

/** Server word lists (chat-room.tsx:354-355 / messages route) — order matters. */
object IncognitoAlias {
    val ANON_ADJECTIVES: List<String> = listOf(
        "Swift", "Quiet", "Neon", "Ember", "Frost", "Lucky", "Cosmic", "Silent",
    )
    val ANON_ANIMALS: List<String> = listOf(
        "Falcon", "Otter", "Panda", "Wolf", "Comet", "Tiger", "Raven", "Fox",
    )

    /**
     * Stable 32-bit FNV-1a — byte-for-byte the JS `anonStableHash`: iterate
     * UTF-16 code units (a Kotlin String iteration IS its UTF-16 code units),
     * xor the low 32 bits, multiply with wrap-around (Math.imul parity).
     */
    fun fnv1a(value: String): UInt {
        var hash = 0x811C9DC5u
        for (unit in value) {
            hash = hash xor unit.code.toUInt()
            hash = hash * 0x01000193u
        }
        return hash
    }

    /**
     * "Adjective the Animal" — the deterministic alias for THIS viewer in
     * THIS conversation (web anonAliasPreview parity; key "userId:conversationId").
     */
    fun aliasFor(userId: String, conversationId: String): String {
        val hash = fnv1a("$userId:$conversationId")
        val adjective = ANON_ADJECTIVES[(hash % ANON_ADJECTIVES.size.toUInt()).toInt()]
        val animal = ANON_ANIMALS[
            ((hash / ANON_ADJECTIVES.size.toUInt()) % ANON_ANIMALS.size.toUInt()).toInt(),
        ]
        return "$adjective the $animal"
    }
}
