package app.pulse.android.mirror

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteFinalizerTest {
    @Test fun stopFinalizesFileBeforeReleaseAndValidation() {
        val file = File.createTempFile("voice-test-", ".m4a")
        val events = mutableListOf<String>()
        try {
            val result = finalizeVoiceNote(file,
                stop = { events += "stop"; file.writeBytes(byteArrayOf(1, 2, 3)) },
                release = { events += "release" })
            assertTrue(result.isSuccess)
            assertEquals(listOf("stop", "release"), events)
            assertEquals(3L, file.length())
        } finally { file.delete() }
    }

    @Test fun failedStopStillReleasesAndDeletesUnusableCapture() {
        val file = File.createTempFile("voice-test-", ".m4a")
        var releases = 0
        val result = finalizeVoiceNote(file, { error("too short") }, { releases++ })
        assertTrue(result.isFailure)
        assertEquals(1, releases)
        assertFalse(file.exists())
    }

    @Test fun emptyCaptureCannotUploadEvenWhenRecorderStopReturns() {
        val file = File.createTempFile("voice-test-", ".m4a")
        assertTrue(finalizeVoiceNote(file, {}, {}).isFailure)
        assertFalse(file.exists())
    }

    @Test fun alreadyFinalizedFileCanRetryWithoutAnotherRecorder() {
        val file = File.createTempFile("voice-test-", ".m4a")
        try {
            file.writeBytes(byteArrayOf(1))
            assertTrue(finalizeVoiceNote(file, {}, {}).isSuccess)
            assertTrue(finalizeVoiceNote(file, {}, {}).isSuccess)
        } finally { file.delete() }
    }
}
