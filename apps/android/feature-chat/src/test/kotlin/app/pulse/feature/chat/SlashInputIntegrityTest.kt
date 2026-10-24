package app.pulse.feature.chat

import org.junit.Assert.*
import org.junit.Test

class SlashInputIntegrityTest {
    @Test fun commandsDoNotTrimTheEndOfTheMessageOrSplitUnicodeToMakeItFit() {
        val text = "🌿".repeat(999)
        assertEquals(PulseSlash.Outcome.Send("_${text}_"), PulseSlash.applySlash("/me $text"))
        for(input in listOf("/me ${text}a", "/me " + "a".repeat(2000), "/shrug " + "a".repeat(1995)))
            assertTrue(PulseSlash.applySlash(input) is PulseSlash.Outcome.Error)
    }
}
