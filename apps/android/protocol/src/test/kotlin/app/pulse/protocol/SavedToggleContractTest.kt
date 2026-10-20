package app.pulse.protocol

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SavedToggleContractTest {
    @Test fun `a missing acknowledgement is not confirmation of removal`() {
        assertThrows(SerializationException::class.java) {
            PulseJson.decodeFromString(SavedToggleDto.serializer(), "{}")
        }
        assertFalse(PulseJson.decodeFromString(SavedToggleDto.serializer(), """{"saved":false}""").saved)
        assertTrue(PulseJson.decodeFromString(SavedToggleDto.serializer(), """{"saved":true}""").saved)
    }
}
