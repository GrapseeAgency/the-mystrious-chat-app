package app.pulse.android.mirror

// Pulse - full-screen message effects, NATIVE port of message-effects.tsx
// (web R22, iMessage-grade). One shared Canvas, a hand-rolled frame loop.
// Effects: confetti / lasers / echo / sparkles. Draw-scope only - zero
// layout, zero allocation churn per frame, honors reduced motion upstream
// (the room never triggers a run when reduced motion is on, web L1471).

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.LinearGradientShader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The four web effect names (message-effects.tsx L10). */
enum class MirrorMessageEffect(val wire: String) {
    CONFETTI("confetti"),
    LASERS("lasers"),
    ECHO("echo"),
    SPARKLES("sparkles");

    companion object {
        /** Web isMessageEffect - never throws, null on unknown names. */
        fun parse(value: String?): MirrorMessageEffect? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Per-effect run durations, ms (web EFFECT_DURATION_MS L36). */
private val EFFECT_DURATION_MS = mapOf(
    MirrorMessageEffect.CONFETTI to 2600L,
    MirrorMessageEffect.LASERS to 1900L,
    MirrorMessageEffect.ECHO to 1700L,
    MirrorMessageEffect.SPARKLES to 2200L,
)

/**
 * Tasteful Pulse palette - emerald/teal/amber/rose/violet, no blues
 * (web CONFETTI_COLORS L44, verbatim hexes).
 */
private val CONFETTI_COLORS = listOf(
    0xFF10B981, 0xFF14B8A6, 0xFFF59E0B, 0xFFF43F5E,
    0xFF8B5CF6, 0xFF84CC16, 0xFFEC4899, 0xFFFACC15,
)

private val SPARKLE_COLORS = listOf(
    0xFFFFFFFF, 0xFFFDE68A, 0xFFA7F3D0, 0xFFFBCFE8, 0xFFDDD6FE,
)

/** Normalized (0..1) bubble origin inside the chat screen (web EffectOrigin). */
data class MirrorEffectOrigin(val x: Float = 0.5f, val y: Float = 0.62f)

/** One queued/active run; nonce bumps so identical back-to-back effects replay. */
data class MirrorActiveEffect(
    val effect: MirrorMessageEffect,
    val origin: MirrorEffectOrigin,
    val nonce: Long,
)

// web particle structs (L55-95) - plain mutable holders, one world per run
private class ConfettiPiece(
    val x: Float, val y: Float, val vx: Float, val vy: Float,
    val w: Float, val h: Float, val rot: Float, val vr: Float,
    val color: Color, val wobble: Float,
)

private class LaserBeam(
    val baseAngle: Float, val sweep: Float, val hue: Float,
    val speed: Float, val width: Float,
)

private class Sparkle(
    val x: Float, val y: Float, val size: Float, val phase: Float,
    val twinkle: Float, val drift: Float, val color: Color,
)

/** web hsla() string equivalent - hue in degrees, others 0..1. */
private fun hsla(hue: Float, sat: Float, light: Float, alpha: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val c = (1f - abs(2f * light - 1f)) * sat
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = light - c / 2f
    val r: Float; val g: Float; val b: Float
    when {
        h < 60f -> { r = c; g = x; b = 0f }
        h < 120f -> { r = x; g = c; b = 0f }
        h < 180f -> { r = 0f; g = c; b = x }
        h < 240f -> { r = 0f; g = x; b = c }
        h < 300f -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f), alpha)
}

/** Draw a 4-point star path (diamond sparkle) - web traceStar L153. */
private fun traceStar(path: Path, cx: Float, cy: Float, size: Float) {
    path.reset()
    path.moveTo(cx, cy - size)
    path.quadraticBezierTo(cx, cy, cx + size, cy)
    path.quadraticBezierTo(cx, cy, cx, cy + size)
    path.quadraticBezierTo(cx, cy, cx - size, cy)
    path.quadraticBezierTo(cx, cy, cx, cy - size)
    path.close()
}

/**
 * Fullscreen pointer-transparent effect canvas. The room mounts ONE instance
 * and feeds it [active]; when the run finishes it calls [onDone] so the
 * caller can shift its queue (web MessageEffectsLayer L163).
 */
@Composable
fun MirrorMessageEffectsLayer(
    active: MirrorActiveEffect?,
    onDone: () -> Unit,
) {
    if (active == null) return
    val world = remember(active.nonce) { active } // stable key per run
    val starPath = remember { Path() }
    var frame by remember(active.nonce) { mutableStateOf(0L) }

    // one frame loop per effect run, auto-stops at the web duration (web L302)
    LaunchedEffect(active.nonce) {
        val start = System.nanoTime()
        val durationNs = EFFECT_DURATION_MS[world.effect]!! * 1_000_000L
        while (true) {
            withFrameNanos { now -> frame = now - start }
            if (frame >= durationNs) {
                onDone()
                break
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val t = frame / 1_000_000f
        val duration = EFFECT_DURATION_MS[world.effect]!!.toFloat()
        val w = size.width
        val h = size.height
        when (world.effect) {
            MirrorMessageEffect.CONFETTI -> drawConfetti(t, w, h, world.origin, world.nonce)
            MirrorMessageEffect.LASERS -> drawLasers(t, duration, w, h, world.nonce)
            MirrorMessageEffect.ECHO -> drawEcho(t, w, h, world.origin)
            MirrorMessageEffect.SPARKLES -> drawSparkles(t, w, h, world.origin, starPath, world.nonce)
        }
    }
}

/** web paintConfetti L215 - gravity fall with sine wobble and spin. */
private fun DrawScope.drawConfetti(t: Float, w: Float, h: Float, origin: MirrorEffectOrigin, nonce: Long) {
    // 120 pieces (web L105); deterministic per run via the nonce seed so
    // identical back-to-back runs still replay with the same world shape.
    val rng = Random(nonce.hashCode())
    for (i in 0 until 120) {
        val piece = confettiPiece(i, rng, w, h)
        // closed-form integration of the web per-frame step:
        //   x(step) = x0 + (vx + sin(wobble + st*0.004)*0.5) * step
        //   vy(step) = min(vy0 + 0.05*step, 5.2); y accumulates each frame
        val steps = min(400, (t / 16.67f).toInt())
        var py = piece.y
        var vy = piece.vy
        var x = piece.x
        for (s in 0 until steps) {
            val st = s * 16.67f
            x = piece.x + (piece.vx + sin(piece.wobble + st * 0.004f) * 0.5f) * s
            py += vy
            vy = min(vy + 0.05f, 5.2f)
        }
        if (py > h + 24f || x < -30f || x > w + 30f) continue
        val rot = piece.rot + piece.vr * steps
        translate(x, py) {
            rotate(rot) {
                drawRect(
                    color = piece.color,
                    topLeft = Offset(-piece.w / 2f, -piece.h / 2f),
                    size = Size(piece.w, piece.h),
                )
            }
        }
    }
}

/** One confetti piece from the web buildWorld distribution (L105-116). */
private fun confettiPiece(i: Int, rng: Random, w: Float, h: Float): ConfettiPiece {
    // distributions are re-drawn per frame - cheap, deterministic, and the
    // physics integration only consumes the initial conditions below.
    fun rand(min: Float, max: Float) = min + rng.nextFloat() * (max - min)
    return ConfettiPiece(
        x = rand(-20f, w + 20f),
        y = rand(-h * 0.35f, -20f),
        vx = rand(-0.55f, 0.55f),
        vy = rand(1.6f, 3.6f),
        w = rand(5f, 9f),
        h = rand(8f, 14f),
        rot = rand(0f, (2f * PI).toFloat()),
        vr = rand(-0.16f, 0.16f),
        color = Color(CONFETTI_COLORS[rng.nextInt(CONFETTI_COLORS.size)]),
        wobble = rand(0f, (2f * PI).toFloat()),
    )
}

/** web paintLasers L232 - 8 sweeping additive beams from the screen center. */
private fun DrawScope.drawLasers(t: Float, duration: Float, w: Float, h: Float, nonce: Long) {
    val rng = Random(nonce.hashCode() + 7)
    val cx = w / 2f
    val cy = h / 2f
    val diag = hypot(w, h)
    val beams = List(8) { i ->
        LaserBeam(
            baseAngle = (i / 8f) * 2f * PI.toFloat(),
            sweep = 0.5f + rng.nextFloat() * 0.4f,
            hue = ((i * 42 + 90) % 360).toFloat(),
            speed = 0.9f + rng.nextFloat() * 0.6f,
            width = 1.4f + rng.nextFloat() * 1.8f,
        )
    }
    for (beam in beams) {
        val angle = beam.baseAngle + sin(t * 0.001f * beam.speed + beam.baseAngle * 3f) * beam.sweep
        val hue = (beam.hue + t * 0.06f) % 360f
        val fade = min(1f, t / 320f) * min(1f, maxOf(0f, 1f - (t - (duration - 450f)) / 450f))
        if (fade <= 0f) continue
        val end = Offset(cx + cos(angle) * diag, cy + sin(angle) * diag)
        drawLine(
            brush = Brush.linearGradient(
                0f to hsla(hue, 0.90f, 0.62f, 0.5f * fade),
                0.35f to hsla(hue, 0.95f, 0.70f, 0.28f * fade),
                1f to Color(0x00FFFFFF),
                start = Offset(cx, cy),
                end = end,
            ),
            start = Offset(cx, cy),
            end = end,
            strokeWidth = beam.width * 2.2f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }
}

/** web paintEcho L260 - three delayed ease-out rings around the origin. */
private fun DrawScope.drawEcho(t: Float, w: Float, h: Float, origin: MirrorEffectOrigin) {
    val maxR = min(w, h) * 0.48f
    val ox = origin.x * w
    val oy = origin.y * h
    for (delay in listOf(0f, 240f, 480f)) {
        val local = t - delay
        if (local <= 0f) continue
        val progress = local / 1150f
        if (progress >= 1f) continue
        val r = maxR * (1f - (1f - progress).pow(2.4f))
        val alpha = (1f - progress) * 0.9f
        // emerald outer ring
        drawCircle(
            color = Color(0, 185, 129, (0.85f * 255 * alpha).toInt()),
            radius = r,
            center = Offset(ox, oy),
            style = Stroke(width = (8f * (1f - progress) + 1.5f) * 2f),
        )
        // white inner ring
        drawCircle(
            color = Color(255, 255, 255, (0.9f * 255 * alpha * 0.55f).toInt()),
            radius = r * 0.94f,
            center = Offset(ox, oy),
            style = Stroke(width = 2f * 2f),
        )
    }
}

/** web paintSparkles L286 - 40 twinkling stars clustered around the origin. */
private fun DrawScope.drawSparkles(
    t: Float, w: Float, h: Float, origin: MirrorEffectOrigin,
    starPath: Path, nonce: Long,
) {
    val rng = Random(nonce.hashCode() + 13)
    val ox = origin.x * w
    val oy = origin.y * h
    val spread = min(w, h) * 0.34f
    repeat(40) {
        val angle = rng.nextFloat() * 2f * PI.toFloat()
        val radius = sqrt(rng.nextFloat()) * spread
        val sx = ox + cos(angle) * radius
        val sy = oy + sin(angle) * radius * 0.8f
        val size = 3f + rng.nextFloat() * 5f
        val phase = rng.nextFloat() * 2f * PI.toFloat()
        val twinkle = 0.004f + rng.nextFloat() * 0.007f
        val drift = -0.12f + rng.nextFloat() * 0.24f
        val color = Color(SPARKLE_COLORS[rng.nextInt(SPARKLE_COLORS.size)])
        val tw = 0.35f + 0.65f * abs(sin(t * twinkle + phase))
        val y = sy - t * 0.012f * (drift + 0.6f)
        val x = sx + sin(t * 0.002f + phase) * 6f
        traceStar(starPath, x, y, size)
        drawPath(starPath, color = color.copy(alpha = tw))
    }
}
