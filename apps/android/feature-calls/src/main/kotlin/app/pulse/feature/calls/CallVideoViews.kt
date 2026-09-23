package app.pulse.feature.calls

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Wave R1-W2D — REAL WebRTC video renderers for the call overlay (web
 * call-overlay.tsx parity):
 *   • remote video  → full-bleed surface (web `absolute inset-0 object-cover`)
 *   • local camera  → small PiP self-view, mirrored (web `scaleX(-1)`, 96×144dp
 *     ≈ the web h-36 w-24 tile, rounded border)
 *
 * Both wrap [SurfaceViewRenderer] and MUST share the engine's factory EGL
 * context. Sink attach/detach + renderer release are lifecycle-driven so
 * camera surfaces never leak past the call.
 */

/**
 * One video track on one renderer. Recreated per track instance — addSink
 * happens after the effect commits, release on dispose (no double-sink).
 */
@Composable
fun VideoRenderer(
    track: VideoTrack?,
    eglBaseContext: EglBase.Context,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    if (track == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember(track) { SurfaceViewRenderer(context) }
    DisposableEffect(track) {
        renderer.init(eglBaseContext, null)
        if (mirrored) renderer.setMirror(true)
        runCatching { track.addSink(renderer) }
        onDispose {
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        }
    }
    AndroidView(
        factory = { renderer },
        modifier = modifier,
    )
}

/**
 * The peer's video, full-bleed behind the call UI. Renders only once the
 * remote video track actually arrives over the negotiated m=video line.
 */
@Composable
fun RemoteVideoSurface(engine: CallEngine, modifier: Modifier = Modifier) {
    val track by engine.remoteVideoTrack.collectAsStateWithLifecycle()
    VideoRenderer(
        track = track,
        eglBaseContext = engine.eglBaseContext,
        mirrored = false,
        modifier = modifier,
    )
}

/**
 * The local camera PiP — mirrored self-view (web parity), bottom-end tile
 * above the controls. Only rendered while capture is actually running AND
 * the camera toggle is on (muted camera ⇒ hidden, web cameraEnabled parity).
 */
@Composable
fun LocalVideoPip(engine: CallEngine, modifier: Modifier = Modifier) {
    val track by engine.localVideoTrack.collectAsStateWithLifecycle()
    val cameraEnabled by engine.cameraEnabled.collectAsStateWithLifecycle()
    if (!cameraEnabled) return
    VideoRenderer(
        track = track,
        eglBaseContext = engine.eglBaseContext,
        mirrored = true,
        modifier = modifier
            .width(96.dp)
            .height(144.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
    )
}
