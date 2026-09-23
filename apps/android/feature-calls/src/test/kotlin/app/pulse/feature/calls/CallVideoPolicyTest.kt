package app.pulse.feature.calls

import app.pulse.domain.model.CallKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave R1-W2D — the PURE video decision contract (web useCallSession parity,
 * src/components/chat/call-overlay.tsx + src/lib/call-types.ts):
 *   • the wire video flag is the call's `kind` ("voice" | "video");
 *   • a wanted video call degrades to voice BEFORE the offer without a camera;
 *   • the callee only opens the camera when the offer SDP really declares a
 *     usable m=video line.
 */
class CallVideoPolicyTest {

    // ── offerHasVideo — SDP m=video check ────────────────────────

    private val audioOnlySdp =
        "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "a=sendrecv\r\na=mid:0\r\n"

    private val videoSdp =
        "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "a=sendrecv\r\na=mid:0\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
            "a=sendrecv\r\na=mid:1\r\n"

    @Test
    fun `audio-only offer has no video line`() {
        assertFalse(CallVideoPolicy.offerHasVideo(audioOnlySdp))
    }

    @Test
    fun `offer with an active video m-line is detected`() {
        assertTrue(CallVideoPolicy.offerHasVideo(videoSdp))
    }

    @Test
    fun `rejected video m-line port 0 is NOT video`() {
        val rejected = videoSdp.replace("m=video 9 ", "m=video 0 ")
        assertFalse(CallVideoPolicy.offerHasVideo(rejected))
    }

    @Test
    fun `inactive video m-line is NOT usable video`() {
        val paused = videoSdp.replace("a=mid:1", "a=inactive\r\na=mid:1")
        assertFalse(CallVideoPolicy.offerHasVideo(paused))
    }

    @Test
    fun `LF-only line endings parse the same as CRLF`() {
        assertTrue(CallVideoPolicy.offerHasVideo(videoSdp.replace("\r\n", "\n")))
    }

    @Test
    fun `null blank and garbage SDPs are honestly video-free`() {
        assertFalse(CallVideoPolicy.offerHasVideo(null))
        assertFalse(CallVideoPolicy.offerHasVideo(""))
        assertFalse(CallVideoPolicy.offerHasVideo("   "))
        assertFalse(CallVideoPolicy.offerHasVideo("v=0\r\nbroken-line"))
    }

    // ── camera capability + kind resolution (web acquireMedia fallback) ──

    @Test
    fun `camera capable requires permission support and a device`() {
        assertFalse(
            CallVideoPolicy.isCameraCapable(
                hasCameraPermission = false,
                cameraApiSupported = true,
                deviceNames = listOf("0"),
            ),
        )
        assertFalse(
            CallVideoPolicy.isCameraCapable(
                hasCameraPermission = true,
                cameraApiSupported = false,
                deviceNames = listOf("0"),
            ),
        )
        assertFalse(
            CallVideoPolicy.isCameraCapable(
                hasCameraPermission = true,
                cameraApiSupported = true,
                deviceNames = emptyList(),
            ),
        )
        assertTrue(
            CallVideoPolicy.isCameraCapable(
                hasCameraPermission = true,
                cameraApiSupported = true,
                deviceNames = listOf("1"),
            ),
        )
    }

    @Test
    fun `wanted video with no usable camera degrades to VOICE (web fallback)`() {
        assertEquals(
            CallKind.VOICE,
            CallVideoPolicy.resolveOutgoingKind(CallKind.VIDEO, cameraCapable = false),
        )
    }

    @Test
    fun `wanted video with a camera stays VIDEO`() {
        assertEquals(
            CallKind.VIDEO,
            CallVideoPolicy.resolveOutgoingKind(CallKind.VIDEO, cameraCapable = true),
        )
    }

    @Test
    fun `voice calls never become video and never degrade`() {
        assertEquals(
            CallKind.VOICE,
            CallVideoPolicy.resolveOutgoingKind(CallKind.VOICE, cameraCapable = false),
        )
        assertEquals(
            CallKind.VOICE,
            CallVideoPolicy.resolveOutgoingKind(CallKind.VOICE, cameraCapable = true),
        )
    }

    // ── shouldAttachVideo — the callee/caller camera gate ────────

    @Test
    fun `callee attaches video only for video kind AND usable m=video AND camera`() {
        assertTrue(
            CallVideoPolicy.shouldAttachVideo(
                CallKind.VIDEO,
                offerSdp = videoSdp,
                cameraCapable = true,
            ),
        )
    }

    @Test
    fun `callee with no camera stays audio-only even for video offers`() {
        assertFalse(
            CallVideoPolicy.shouldAttachVideo(
                CallKind.VIDEO,
                offerSdp = videoSdp,
                cameraCapable = false,
            ),
        )
    }

    @Test
    fun `callee does NOT open the camera for audio-only offers`() {
        assertFalse(
            CallVideoPolicy.shouldAttachVideo(
                CallKind.VOICE,
                offerSdp = audioOnlySdp,
                cameraCapable = true,
            ),
        )
    }

    @Test
    fun `wire says video but the offer SDP carries no usable video m-line`() {
        // Caller fell back to voice after dispatching kind 'video' — the
        // honest callee answer keeps its camera closed.
        assertFalse(
            CallVideoPolicy.shouldAttachVideo(
                CallKind.VIDEO,
                offerSdp = audioOnlySdp,
                cameraCapable = true,
            ),
        )
        assertFalse(
            CallVideoPolicy.shouldAttachVideo(
                CallKind.VIDEO,
                offerSdp = null,
                cameraCapable = true,
            ),
        )
    }
}
