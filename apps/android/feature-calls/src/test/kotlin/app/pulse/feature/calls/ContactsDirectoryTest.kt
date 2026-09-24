package app.pulse.feature.calls

import app.pulse.domain.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5-B ITEM 2 — A–Z directory grouping parity with web contacts-tab.tsx
 * (indexLetterOf :54-57, sections :126-137): case-insensitive first letter,
 "#" bucket for non-letter glyphs, "#" sorts LAST, input order preserved
 * within a bucket.
 */
class ContactsDirectoryTest {

    private fun person(id: String, name: String) = User(id = id, name = name, handle = id)

    @Test
    fun `mixed case names group under the same uppercase letter`() {
        val sections = groupContactsByLetter(
            listOf(person("1", "alice"), person("2", "ALICE2"), person("3", "Bob")),
        )
        assertEquals(listOf("A", "B"), sections.map { it.letter })
        // case-insensitive bucket + input order preserved
        assertEquals(listOf("1", "2"), sections[0].people.map { it.id })
        assertEquals(listOf("3"), sections[1].people.map { it.id })
    }

    @Test
    fun `non-letter first glyph falls in the hash bucket which sorts last`() {
        val sections = groupContactsByLetter(
            listOf(
                person("1", "zoe"),
                person("2", "4lex"),
                person("3", "_mia"),
                person("4", "安"),
                person("5", "adam"),
            ),
        )
        assertEquals(listOf("A", "Z", "#"), sections.map { it.letter })
        assertEquals(listOf("2", "3", "4"), sections[2].people.map { it.id })
    }

    @Test
    fun `blank names land in the hash bucket`() {
        val sections = groupContactsByLetter(listOf(person("1", "   ")))
        assertEquals(listOf("#"), sections.map { it.letter })
        assertEquals(listOf("1"), sections[0].people.map { it.id })
        assertTrue(indexLetterOf("").let { it == "#" })
    }

    @Test
    fun `index letter mirrors web first-char rule`() {
        assertEquals("A", indexLetterOf("alice"))
        assertEquals("B", indexLetterOf("  bob "))
        assertEquals("#", indexLetterOf("3am"))
        assertEquals("#", indexLetterOf("🌙night"))
    }

    @Test
    fun `empty input yields empty sections`() {
        assertTrue(groupContactsByLetter(emptyList()).isEmpty())
    }
}
