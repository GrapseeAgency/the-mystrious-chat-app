package app.pulse.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComposerReceiptTest {
    private val intent = ComposerIntentDto("me","draft","room","room",null,"00000000-0000-4000-8000-000000000001","mine","")
    private val flight = ComposerFlight(intent.desiredId,"mine","member:0")
    private val good = ComposerReceiptDto(1,"me","draft","room",null,"room","mine","member:1",null,flight.clientId,"applied")
    @Test fun receiptBindsOwnerScopeIdentityAndExactAcceptedText() {
        good.validateComposer(intent,flight)
        for (bad in listOf(good.copy(userId="other"),good.copy(rootId="thread"),good.copy(clientId="different"),
            good.copy(text="changed"),good.copy(revision="member:1\n"),good.copy(outcome="unknown"))) {
            assertTrue(runCatching { bad.validateComposer(intent,flight) }.isFailure)
        }
        good.copy(text="other device",outcome="conflict").validateComposer(intent,flight)
    }
    @Test fun immutableFlightRoundTripsAlongsideNewerDesiredText() {
        val newer = intent.copy(text="new desired",flight=flight,baseRevision="member:0")
        val restored = PulseJson.decodeFromString(ComposerIntentDto.serializer(),PulseJson.encodeToString(ComposerIntentDto.serializer(),newer))
        assertEquals("new desired",restored.text)
        assertEquals("mine",restored.flight?.text)
        assertEquals("member:0",restored.baseRevision)
    }
    @Test fun unknownBaselineDoesNotOverwriteAnExplicitRemoteClear() {
        assertFalse(good.copy(text="",revision="member:2").hasSafeDraftBaseline(intent))
        assertTrue(good.copy(text="",revision="member:0").hasSafeDraftBaseline(intent))
        assertTrue(good.hasSafeDraftBaseline(intent))
        assertTrue(good.copy(text="",revision="member:2").hasSafeDraftBaseline(intent.copy(baseRevision="member:2")))
    }

    @Test fun acceptedBaselineStoresOnlyMetadataAndExactTextFingerprint() {
        val baseline = good.copy(text="private e\u0301").metadataBaseline()
        assertEquals("",baseline.text)
        assertTrue(baseline.matchesBaselineText("private e\u0301"))
        assertFalse(baseline.matchesBaselineText("private é"))
        assertFalse(PulseJson.encodeToString(ComposerReceiptDto.serializer(),baseline).contains("private"))
    }

}
