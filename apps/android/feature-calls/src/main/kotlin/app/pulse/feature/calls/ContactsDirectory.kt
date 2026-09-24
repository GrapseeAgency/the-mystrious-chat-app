package app.pulse.feature.calls

import app.pulse.domain.model.User

/**
 * R5-B ITEM 2 — pure A–Z directory grouping for the contacts screen.
 * Ported 1:1 from web contacts-tab.tsx (indexLetterOf :54-57, sections
 * useMemo :126-137): first letter of the display name, case-insensitive,
 * non-letter first glyphs fall in the "#" bucket, which always sorts LAST;
 * people keep their incoming order inside each bucket (the web iterates
 * `others` without re-sorting the rows). UI-free so the JVM tests pin the
 * exact web semantics.
 */
data class ContactSection(val letter: String, val people: List<User>)

/**
 * Uppercase index bucket for a person: "A"…"Z", anything else → "#"
 * (web contacts-tab.tsx:54-57 — trim, first char, /[A-Z]/ test).
 */
fun indexLetterOf(name: String): String {
    val first = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (first in 'A'..'Z') first.toString() else "#"
}

/**
 * Group people into A–Z sections in index order with the "#" bucket last.
 * Empty input → empty list. Within a section, people preserve the order
 * they were handed in (server order — web parity).
 */
fun groupContactsByLetter(people: List<User>): List<ContactSection> {
    if (people.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<User>>()
    for (person in people) {
        buckets.getOrPut(indexLetterOf(person.name)) { mutableListOf() }.add(person)
    }
    // Web comparator verbatim: (a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b))
    return buckets.entries
        .sortedWith { a, b ->
            when {
                a.key == "#" -> 1
                b.key == "#" -> -1
                else -> a.key.compareTo(b.key)
            }
        }
        .map { (letter, list) -> ContactSection(letter, list.toList()) }
}
