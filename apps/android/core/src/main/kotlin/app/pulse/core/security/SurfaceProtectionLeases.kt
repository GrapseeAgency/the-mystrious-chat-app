package app.pulse.core.security

/** Share a platform protection flag without letting one closing surface disable
 * another, or stripping a flag that was already set before our first lease. */
class SurfaceProtectionLeases {
    private class Entry(val wasProtected: Boolean, val setProtected: (Boolean) -> Unit) {
        val owners = mutableSetOf<Any>()
    }
    private val entries = java.util.WeakHashMap<Any, Entry>()
    fun acquire(surface: Any, isProtected: () -> Boolean, setProtected: (Boolean) -> Unit): () -> Unit = synchronized(entries) {
        val entry = entries[surface] ?: Entry(isProtected(), setProtected).also {
            setProtected(true)
            entries[surface] = it
        }
        val owner = Any()
        entry.owners.add(owner)
        return@synchronized {
            synchronized(entries) {
                if (entry.owners.remove(owner) && entry.owners.isEmpty() && entries[surface] === entry) {
                    entries.remove(surface)
                    if (!entry.wasProtected) entry.setProtected(false)
                }
                Unit
            }
        }
    }
}
