package app.pulse.feature.calls

import app.pulse.domain.model.CallKind

/**
 * Wave R1-W2D — PURE video decision logic for 1:1 calls (no Android imports
 * so the JVM test suite drives it directly).
 *
 * Behavioral spec = the web `useCallSession` hook (src/components/chat/
 * call-overlay.tsx):
 *   • the wire video flag is the call's `kind` field ("voice" | "video",
 *     src/lib/call-types.ts:15) — NOT a separate boolean;
 *   • a wanted VIDEO call degrades to VOICE BEFORE the offer when no usable
 *     camera exists (`acquireMedia` NotFoundError/OverconstrainedError/
 *     NotReadableError fallback, call-overlay.tsx:262-282) — the offer kind
 *     always carries the ACTUAL kind;
 *   • the callee additionally requires the offer SDP to actually declare an
 *     `m=video` line (a voice offer must never open a camera).
 */
object CallVideoPolicy {

    /** Local capture profile — web parity: `video: { width: {ideal: 1280}, height: {ideal: 720} }`. */
    const val VIDEO_WIDTH: Int = 1280
    const val VIDEO_HEIGHT: Int = 720
    const val VIDEO_FPS: Int = 30

    /**
     * True when the SDP declares at least one USABLE video m-line:
     *   • a media line whose kind token is `video` AND
     *   • whose port is not 0 (rejected m-line) AND
     *   • whose section does not carry `a=inactive` (paused by the peer).
     * Handles both CRLF and LF line endings. Pure string logic — unit tested.
     */
    fun offerHasVideo(sdp: String?): Boolean {
        if (sdp.isNullOrBlank()) return false
        var sectionIsVideo = false
        var sectionRejected = false // port 0 or a=inactive inside the current m-section
        var found = false
        fun flushSection() {
            if (sectionIsVideo && !sectionRejected) found = true
            sectionIsVideo = false
            sectionRejected = false
        }
        for (rawLine in sdp.split("\r\n", "\n")) {
            val line = rawLine.trim()
            if (line.startsWith("m=")) {
                flushSection()
                // m=<media> <port> <proto> <fmt> ...
                val tokens = line.split(Regex("\\s+"))
                if (tokens.size >= 3 && tokens[1] == "video") {
                    sectionIsVideo = true
                    sectionRejected = tokens[2].toIntOrNull() == 0
                }
            } else if (sectionIsVideo && line == "a=inactive") {
                sectionRejected = true
            }
        }
        flushSection()
        return found
    }

    /**
     * Camera capability probe (inputs kept primitive so this stays pure):
     *   • [hasCameraPermission] — android.permission.CAMERA granted;
     *   • [cameraApiSupported] — Camera2Enumerator.isSupported(context);
     *   • [deviceNames] — Camera2Enumerator(context).deviceNames (any camera
     *     counts; the web uses ANY camera, front is only a preference).
     */
    fun isCameraCapable(
        hasCameraPermission: Boolean,
        cameraApiSupported: Boolean,
        deviceNames: List<String>,
    ): Boolean = hasCameraPermission && cameraApiSupported && deviceNames.isNotEmpty()

    /**
     * Web acquireMedia parity — the kind the OFFER/WIRE actually carries.
     * A wanted VIDEO call with no usable camera degrades to VOICE before
     * StartOutgoing is dispatched (the machine's kind is immutable per call).
     */
    fun resolveOutgoingKind(wanted: CallKind, cameraCapable: Boolean): CallKind =
        if (wanted == CallKind.VIDEO && !cameraCapable) CallKind.VOICE else wanted

    /**
     * Should THIS device attach its local camera for the current call?
     *   • CALLER — the engine resolved the wire kind at startOutgoing
     *     ([resolveOutgoingKind]), so attach iff kind==VIDEO.
     *   • CALLEE — also require the offer SDP to declare m=video: a wire kind
     *     of 'video' with a rejected/absent video m-line (e.g. the caller
     *     fell back mid-prompt) must not open the camera.
     * [cameraCapable] gates both: permission-denied / no-device ⇒ audio-only.
     */
    fun shouldAttachVideo(kind: CallKind, offerSdp: String?, cameraCapable: Boolean): Boolean =
        kind == CallKind.VIDEO && offerHasVideo(offerSdp) && cameraCapable
}
