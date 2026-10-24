package app.pulse.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsAcknowledgementTest {
    @Test fun `partial response does not fabricate privacy acknowledgements`() {
        val partial = """{"preferences":{"density":"compact"}}""".decodeSettingsEnvelope().preferences!!
        assertNull(partial.readReceipts)
        assertEquals(setOf("density"), partial.settingsFields().keys)
        assertNull("""{"preferences":null}""".decodeSettingsEnvelope().preferences)
        assertNull("""{"preferences":{"readReceipts":"false"}}""".decodeSettingsEnvelope().preferences)
    }
    @Test fun `dotted FX key round trips and legacy local spelling remains readable`() {
        val wire = """{"preferences":{"fx.webglMode":"aurora"}}""".decodeSettingsEnvelope().preferences!!
        assertEquals("aurora", wire.fxWebglMode)
        val persisted = PulseJson.encodeToString(WirePulsePrefs.serializer(), wire)
        assertEquals("aurora", PulseWave8Logic.mergePrefs(persisted).fxWebglMode)
        assertEquals("aurora", PulseWave8Logic.mergePrefs("""{"fxWebglMode":"aurora"}""").fxWebglMode)
        assertEquals(setOf("fx.webglMode"), wire.settingsFields().keys)
    }
}
