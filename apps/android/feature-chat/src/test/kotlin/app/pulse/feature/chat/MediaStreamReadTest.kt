package app.pulse.feature.chat

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MediaStreamReadTest {
    @Test fun `a broken zero-progress provider is an error instead of a busy loop`() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (++reads == 1) 0 else -1
        }
        try { MediaSupport.readBounded(input, 100); fail("zero progress must fail") }
        catch (_: IllegalStateException) { assertEquals(1, reads) }
    }
    @Test fun `cancellation is checked before the next chunk and never converted to success`() {
        var checkpoints = 0
        try {
            MediaSupport.readBounded(ByteArrayInputStream(ByteArray(130_000)), 150_000) {
                if (++checkpoints == 2) throw CancellationException("selection changed")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) { assertEquals(2, checkpoints) }
    }
    @Test fun `exact bound succeeds but the next byte fails`() {
        assertEquals(100, MediaSupport.readBounded(ByteArrayInputStream(ByteArray(100)), 100).size)
        try { MediaSupport.readBounded(ByteArrayInputStream(ByteArray(101)), 100); fail("expected size refusal") }
        catch (_: IllegalArgumentException) { }
    }
}
