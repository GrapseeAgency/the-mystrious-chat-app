package app.pulse.android.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import app.pulse.core.fx.PulseFx

/**
 * Pulse ambient FX — the NATIVE rebuild of the web WebGL ambient system
 * (src/components/fx/webgl-glow.tsx). Same six modes, same palette, same
 * outcome — implemented with Android graphics APIs:
 *   · API 33+ (AGSL): the actual GLSL fragment shaders ported to AGSL
 *     and run through RuntimeShader — the platform's WebGL analogue.
 *   · API 26-32: an animated Canvas gradient approximation of the mode.
 *   · Reduced motion: one static frame, no loop (web contract parity).
 */
enum class FxMode(val id: String, val label: String) {
    OFF("off", "Off"),
    AURORA("aurora", "Aurora"),
    CAUSTICS("caustics", "Caustics"),
    MESH("mesh", "Mesh"),
    STARS("stars", "Stars"),
    LIQUID("liquid", "Liquid");

    companion object {
        fun from(id: String?): FxMode = entries.firstOrNull { it.id == id } ?: AURORA
    }
}

// ── AGSL shader sources (ports of the web GLSL ES fragments) ─────

private const val AGSL_AURORA = """
uniform float2 resolution;
uniform float time;
uniform float intensity;
uniform float dark;

float curtain(float2 uv, float aspect, float t, float yBase, float amp, float freq, float speed, float thick) {
    float x = uv.x * aspect;
    float c = yBase + amp * sin(x * freq + t * speed) + amp * 0.5 * sin(x * freq * 2.3 - t * speed * 0.6 + 1.7);
    float d = uv.y - c;
    return exp(-(d * d) / (thick * thick));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / max(resolution.y, 1.0);
    float t = time * 0.55;

    float b1 = curtain(uv, aspect, t, 0.62, 0.10, 2.0, 0.45, 0.16);
    float b2 = curtain(uv, aspect, t * 1.25, 0.47, 0.13, 2.9, -0.32, 0.20);
    float b3 = curtain(uv, aspect, t * 0.8, 0.33, 0.08, 3.7, 0.24, 0.12);

    float3 emerald = float3(0.063, 0.725, 0.506);
    float3 teal = float3(0.078, 0.722, 0.651);
    float3 violet = float3(0.545, 0.365, 0.965);

    float3 darkCol = float3(0.012, 0.045, 0.038);
    darkCol += emerald * b1 * 0.85 + teal * b2 * 0.70 + violet * b3 * 0.45;
    darkCol = 1.0 - exp(-darkCol * intensity * 1.7);

    float3 lightCol = float3(0.965, 0.976, 0.972);
    lightCol -= (emerald * b1 * 0.16 + teal * b2 * 0.12 + violet * b3 * 0.08) * intensity;

    float3 col = mix(lightCol, darkCol, dark);
    float2 q = uv - 0.5;
    col *= mix(1.0, 1.0 - 0.5 * dot(q, q), dark);
    return half4(col, 1.0);
}
"""

private const val AGSL_MESH = """
uniform float2 resolution;
uniform float time;
uniform float intensity;
uniform float dark;

float blob(float2 p, float2 c, float r) {
    return 1.0 - smoothstep(0.0, r, distance(p, c));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / max(resolution.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y);
    float t = time;

    float2 c1 = float2(aspect * 0.50 + 0.24 * sin(t * 0.31), 0.60 + 0.16 * cos(t * 0.23));
    float2 c2 = float2(aspect * 0.30 + 0.18 * cos(t * 0.21 + 1.7), 0.28 + 0.18 * sin(t * 0.27 + 0.6));
    float2 c3 = float2(aspect * 0.72 + 0.20 * sin(t * 0.17 + 3.9), 0.44 + 0.22 * cos(t * 0.19 + 2.4));

    float2 wuv = p + 0.07 * float2(sin(uv.y * 3.3 + t * 0.45), cos(uv.x * 2.9 - t * 0.38));

    float b1 = max(blob(p, c1, 0.62), blob(wuv, c1, 0.55) * 0.85);
    float b2 = max(blob(p, c2, 0.55), blob(wuv, c2, 0.48) * 0.85);
    float b3 = max(blob(p, c3, 0.58), blob(wuv, c3, 0.50) * 0.85);

    float3 darkCol = float3(0.012, 0.048, 0.040);
    darkCol += float3(0.063, 0.725, 0.506) * b1 * 0.95;
    darkCol += float3(0.078, 0.722, 0.651) * b2 * 0.80;
    darkCol += float3(0.545, 0.365, 0.965) * b3 * 0.38;
    darkCol = 1.0 - exp(-darkCol * intensity * 1.7);

    float3 lightCol = float3(0.972, 0.978, 0.974);
    lightCol -= float3(0.063, 0.725, 0.506) * b1 * 0.22 * intensity;
    lightCol -= float3(0.078, 0.722, 0.651) * b2 * 0.18 * intensity;
    lightCol -= float3(0.545, 0.365, 0.965) * b3 * 0.10 * intensity;

    float3 col = mix(lightCol, darkCol, dark);
    float2 q = uv - 0.5;
    col *= mix(1.0, 1.0 - 0.5 * dot(q, q), dark);
    return half4(col, 1.0);
}
"""

private const val AGSL_STARS = """
uniform float2 resolution;
uniform float time;
uniform float intensity;
uniform float dark;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float starLayer(float2 uv, float scale, float t) {
    float2 g = uv * scale;
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash21(id);
    float2 off = float2(hash21(id + 7.13), hash21(id + 3.71)) - 0.5;
    float d = length(f - off * 0.8);
    float core = smoothstep(0.10, 0.0, d);
    float tw = 0.35 + 0.65 * (0.5 + 0.5 * sin(t * (0.6 + h * 2.4) + h * 6.2831));
    return core * step(0.82, h) * tw;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / max(resolution.y, 1.0);
    float t = time;

    float l1 = starLayer(float2((uv.x + t * 0.006) * aspect, uv.y + t * 0.002), 26.0, t);
    float l2 = starLayer(float2((uv.x + t * 0.012) * aspect, uv.y - t * 0.004) + 0.37, 44.0, t * 1.3);
    float l3 = starLayer(float2((uv.x + t * 0.021) * aspect, uv.y + t * 0.006) + 0.71, 70.0, t * 1.7);

    float3 darkCol = float3(0.015, 0.030, 0.045);
    darkCol += float3(0.92, 0.98, 1.0) * (l1 * 0.9 + l2 * 0.6 + l3 * 0.4) * intensity;
    darkCol += float3(0.063, 0.500, 0.420) * 0.05 * (0.5 + 0.5 * sin(uv.y * 3.0 + t * 0.1)) * dark;

    float3 lightCol = float3(0.955, 0.965, 0.975);
    lightCol -= float3(0.25, 0.30, 0.38) * (l1 * 0.35 + l2 * 0.25 + l3 * 0.15) * intensity;

    float3 col = mix(lightCol, darkCol, dark);
    float2 q = uv - 0.5;
    col *= mix(1.0, 1.0 - 0.55 * dot(q, q), dark);
    return half4(col, 1.0);
}
"""

// R2-A item 11 — caustics + liquid complete the six-mode set (web
// webgl-glow.tsx CAUSTICS_FRAG / LIQUID_FRAG ports; AGSL is GLSL-ES-flavoured
// so the translation is nearly verbatim).
private const val AGSL_CAUSTICS = """
uniform float2 resolution;
uniform float time;
uniform float intensity;
uniform float dark;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / max(resolution.y, 1.0);
    float t = time * 0.62;

    float2 p = float2(uv.x * aspect, uv.y) * 3.4;
    float2 i = p;
    float c = 1.0;
    float inten = 0.005;
    for (int n = 0; n < 4; n++) {
        float tt = t * (1.0 - (3.5 / float(n + 1)));
        i = p + float2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(float2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 4.0;
    c = 1.17 - pow(c, 1.4);
    float v = clamp(pow(abs(c), 8.0), 0.0, 1.6);

    float3 aqua = float3(0.30, 0.91, 0.85);

    float3 darkCol = float3(0.008, 0.050, 0.055);
    darkCol += aqua * v * 0.55 * intensity + float3(0.063, 0.725, 0.506) * v * 0.20;
    darkCol = 1.0 - exp(-darkCol * 1.7);

    float3 lightCol = float3(0.940, 0.970, 0.970);
    lightCol -= float3(0.0, 0.28, 0.30) * v * 0.35 * intensity;

    float3 col = mix(lightCol, darkCol, dark);
    float2 q = uv - 0.5;
    col *= mix(1.0, 1.0 - 0.5 * dot(q, q), dark);
    return half4(col, 1.0);
}
"""

// liquid — slow metaball fluid (web R31-b). The five ball centers are
// TIME-ONLY data: the CPU uploads u_balls once per frame (web drawBalls
// parity) so the fragment shader stays free of per-pixel trig.
private const val AGSL_LIQUID = """
uniform float2 resolution;
uniform float time;
uniform float intensity;
uniform float dark;
uniform float3 u_balls[5]; // xy center (aspect space), z radius

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// polynomial smooth-min (IQ) — nearby blobs merge into one fluid body
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / max(resolution.y, 1.0);
    float2 p = float2(uv.x * aspect, uv.y);

    float f = smin(u_balls[0].z - distance(p, u_balls[0].xy),
                   u_balls[1].z - distance(p, u_balls[1].xy), 0.20);
    f = smin(f, u_balls[2].z - distance(p, u_balls[2].xy), 0.22);
    f = smin(f, u_balls[3].z - distance(p, u_balls[3].xy), 0.22);
    f = smin(f, u_balls[4].z - distance(p, u_balls[4].xy), 0.22);

    float body  = 1.0 - smoothstep(-0.03, 0.07, f);
    float core  = smoothstep(0.60, 0.92, body);
    float mid   = smoothstep(0.34, 0.58, body) - core;
    float outer = smoothstep(0.12, 0.32, body) - smoothstep(0.34, 0.58, body);
    float rim   = smoothstep(0.42, 0.62, body) * (1.0 - smoothstep(0.66, 0.88, body));
    float halo  = 0.5 / (1.0 + max(-f, 0.0) * 12.0);

    float3 emerald = float3(0.063, 0.725, 0.506);
    float3 teal    = float3(0.078, 0.722, 0.651);
    float3 rose    = float3(0.957, 0.247, 0.369);

    float3 darkCol = float3(0.010, 0.042, 0.036);
    darkCol += emerald * core * 0.85;
    darkCol += teal    * mid  * 0.55;
    darkCol += rose    * outer * 0.16;
    darkCol += float3(0.86, 0.97, 0.93) * rim * 0.10;
    darkCol += emerald * halo * 0.28;
    darkCol = 1.0 - exp(-darkCol * intensity * 1.7);

    float3 lightCol = float3(0.968, 0.976, 0.972);
    lightCol -= emerald * core * 0.20 * intensity;
    lightCol -= teal    * mid  * 0.14 * intensity;
    lightCol -= rose    * outer * 0.05 * intensity;
    lightCol += float3(1.0) * rim * 0.16;

    float3 col = mix(lightCol, darkCol, dark);
    col += (hash21(uv * resolution) - 0.5) * 0.012;

    float2 q = uv - 0.5;
    col *= mix(1.0, 1.0 - 0.5 * dot(q, q), dark);
    return half4(col, 1.0);
}
"""

/** API-gated holder so remember never touches RuntimeShader below API 33. */
private class AgslShaderHolder private constructor(val shader: Any, private val hasBalls: Boolean) {
    companion object {
        fun create(mode: FxMode): AgslShaderHolder? {
            if (Build.VERSION.SDK_INT < 33) return null
            val src = when (mode) {
                FxMode.AURORA -> AGSL_AURORA
                FxMode.CAUSTICS -> AGSL_CAUSTICS
                FxMode.MESH -> AGSL_MESH
                FxMode.STARS -> AGSL_STARS
                FxMode.LIQUID -> AGSL_LIQUID
                else -> return null
            }
            return runCatching {
                AgslShaderHolder(android.graphics.RuntimeShader(src), hasBalls = mode == FxMode.LIQUID)
            }.getOrNull()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun brush(): ShaderBrush = ShaderBrush(shader as Shader)

    fun setUniforms(time: Float, intensity: Float, dark: Float, w: Float, h: Float) {
        if (Build.VERSION.SDK_INT < 33) return
        val s = shader as android.graphics.RuntimeShader
        s.setFloatUniform("resolution", w, h)
        s.setFloatUniform("time", time)
        s.setFloatUniform("intensity", intensity)
        s.setFloatUniform("dark", dark)
        // liquid-only: the five metaball centers (web drawBalls parity —
        // same Lissajous constants, 10 sin/cos per FRAME not per pixel).
        if (!hasBalls) return
        val aspect = w / h.coerceAtLeast(1f)
        val balls = FloatArray(15)
        fun put(i: Int, x: Float, y: Float, r: Float) {
            balls[i * 3] = x * aspect
            balls[i * 3 + 1] = y
            balls[i * 3 + 2] = r
        }
        put(0, 0.50f + 0.15f * sin(0.21f * time), 0.56f + 0.13f * cos(0.16f * time), 0.185f)
        put(1, 0.28f + 0.13f * cos(0.14f * time + 1.3f), 0.30f + 0.12f * sin(0.24f * time + 0.8f), 0.170f)
        put(2, 0.72f + 0.14f * sin(0.18f * time + 2.9f), 0.42f + 0.14f * cos(0.13f * time + 2.1f), 0.180f)
        put(3, 0.40f + 0.16f * cos(0.28f * time + 4.2f), 0.70f + 0.11f * sin(0.15f * time + 3.4f), 0.165f)
        put(4, 0.64f + 0.12f * sin(0.34f * time + 5.1f), 0.24f + 0.12f * cos(0.17f * time + 1.7f), 0.175f)
        runCatching { s.setFloatUniform("u_balls", balls) }
    }
}

/** Fullscreen ambient field — mount ONE instance behind the app shell. */
@Composable
fun AmbientField(
    mode: FxMode,
    dark: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    if (mode == FxMode.OFF) return
    var time by remember { mutableFloatStateOf(14.2f) } // start at a pleasing moment (web parity)

    // Drive the clock with the frame loop — but never when motion is reduced.
    LaunchedEffect(mode, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                time += dt
            }
        }
    }

    val holder = remember(mode) { AgslShaderHolder.create(mode) }

    Box(modifier) {
        when {
            holder != null -> Canvas(Modifier.fillMaxSize()) {
                holder.setUniforms(time, intensity, if (dark) 1f else 0f, size.width, size.height)
                drawRect(holder.brush())
            }
            else -> CanvasFallbackField(mode, dark, time, intensity)
        }
    }
}

/** Animated gradient approximation for API 26-32 — same palette, softer physics. */
@Composable
private fun CanvasFallbackField(mode: FxMode, dark: Boolean, time: Float, intensity: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val aspect = w / h.coerceAtLeast(1f)

        val base = if (dark) Color(0xFF030B09) else Color(0xFFF6F8F7)
        drawRect(base)

        val emerald = Color(0xFF10B981)
        val teal = Color(0xFF14B8A6)
        val violet = Color(0xFF8B5CF6)
        val white = Color(0xFFEAF6FF)

        val strength = if (dark) 0.30f else 0.10f
        val blend = if (dark) BlendMode.Plus else BlendMode.Multiply

        when (mode) {
            FxMode.STARS -> {
                val rnd = Random(42)
                repeat(90) { i ->
                    val sx = rnd.nextFloat() * w
                    val sy = rnd.nextFloat() * h
                    val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * (0.6f + (i % 7) * 0.34f) + i))
                    val r = (1.1f + (i % 3)) * density * 0.5f * tw
                    drawCircle(white.copy(alpha = 0.7f * tw * strength * 2f), r, Offset(sx, sy))
                }
            }
            FxMode.CAUSTICS -> {
                // aqua interference rings — brighter + cooler than the blobs
                val aqua = Color(0xFF4DE8D8)
                repeat(3) { ring ->
                    val cx = w * (0.5f + 0.22f * sin(time * (0.24f + ring * 0.11f) + ring * 2.1f))
                    val cy = h * (0.5f + 0.20f * cos(time * (0.19f + ring * 0.13f) + ring * 1.3f))
                    val radius = h * (0.30f + ring * 0.14f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, aqua.copy(alpha = strength * intensity), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                        blendMode = blend,
                    )
                }
            }
            FxMode.LIQUID -> {
                // five fused metaballs — emerald/teal body with a rose rim
                val emerald = Color(0xFF10B981)
                val rose = Color(0xFFF43F5E)
                repeat(5) { ball ->
                    val cx = w * (0.5f + 0.15f * sin(time * 0.21f + ball * 1.7f))
                    val cy = h * (0.5f + 0.14f * cos(time * 0.16f + ball * 2.3f))
                    val radius = h * (0.22f + ball * 0.02f)
                    val bodyColor = if (ball % 2 == 0) emerald else teal
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                bodyColor.copy(alpha = (strength + 0.12f) * intensity),
                                rose.copy(alpha = 0.05f * intensity),
                                Color.Transparent,
                            ),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                        blendMode = blend,
                    )
                }
            }
            else -> {
                // three drifting curtains/blobs — aurora/mesh shared geometry
                val blobs = listOf(
                    Triple(emerald, 0.32f * sin(time * 0.31f), 0.60f + 0.14f * cos(time * 0.23f)),
                    Triple(teal, 0.26f * cos(time * 0.21f + 1.7f), 0.34f + 0.16f * sin(time * 0.27f + 0.6f)),
                    Triple(violet, 0.28f * sin(time * 0.17f + 3.9f), 0.46f + 0.18f * cos(time * 0.19f + 2.4f)),
                )
                blobs.forEach { (color, dx, dy) ->
                    val cx = w * (0.5f + dx / aspect)
                    val cy = h * dy
                    val radius = h * 0.55f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = strength * intensity), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                        blendMode = blend,
                    )
                }
            }
        }

        // vignette (web parity)
        val q = Offset(w * 0.5f, h * 0.5f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, base.copy(alpha = if (dark) 0.55f else 0.12f)),
                center = q,
                radius = sqrt(w * w + h * h) * 0.62f,
            ),
        )
    }
}

// ── Particle bursts (native port of particle-layer.tsx) ──────────

private val CONFETTI_COLORS = listOf(
    Color(0xFF10B981), Color(0xFF14B8A6), Color(0xFFF59E0B),
    Color(0xFFFB7185), Color(0xFF8B5CF6), Color(0xFFFFFFFF),
)
private val HEART_COLORS = listOf(Color(0xFFFB7185), Color(0xFFF43F5E), Color(0xFFFDA4AF), Color(0xFFFF6B81))
private val STAR_COLORS = listOf(Color(0xFFFDE68A), Color(0xFFFFFFFF), Color(0xFFA7F3D0), Color(0xFF99F6E4))
private val BURST_COLORS = listOf(Color(0xFF10B981), Color(0xFF5EEAD4), Color(0xFFFFFFFF), Color(0xFFFBBF24))

private class P(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float,
    val size: Float, val color: Color,
    var rot: Float, val vrot: Float,
    val drag: Float, val gravity: Float,
    val sway: Float, val phase: Float, val twinkle: Float,
    val kind: PulseFx.BurstKind,
)

private fun makeParticle(kind: PulseFx.BurstKind, w: Float, h: Float): P {
    val rnd = Random.Default
    fun rand(min: Float, max: Float) = min + rnd.nextFloat() * (max - min)
    fun <T> pick(list: List<T>) = list[rnd.nextInt(list.size)]
    val x = w * 0.5f
    val y = h * 0.6f
    val size = rand(3f, 7f)
    return when (kind) {
        PulseFx.BurstKind.CONFETTI -> {
            val angle = (-PI / 2 + rand(-1.05f, 1.05f).toDouble()).toFloat()
            val speed = rand(4f, 11.5f) * 60f // px/frame → px/s
            P(x, y, cos(angle) * speed, sin(angle) * speed, rand(70f, 130f) / 60f, 130f / 60f,
                size, pick(CONFETTI_COLORS), rand(0f, (PI * 2).toFloat()), rand(-0.28f, 0.28f) * 60f,
                0.985f, 0.16f * 3600f, 0f, 0f, 0f, kind)
        }
        PulseFx.BurstKind.HEARTS -> {
            val angle = (-PI / 2 + rand(-0.55f, 0.55f).toDouble()).toFloat()
            val speed = rand(1.2f, 3.2f) * 60f
            P(x, y, cos(angle) * speed, sin(angle) * speed - rand(0.4f, 1.1f) * 60f,
                rand(90f, 160f) / 60f, 160f / 60f, rand(4f, 8f), pick(HEART_COLORS),
                0f, 0f, 0.992f, -0.004f * 3600f, rand(0.018f, 0.05f) * 60f, rand(0f, (PI * 2).toFloat()), 0f, kind)
        }
        PulseFx.BurstKind.STARS -> {
            val angle = rand(0f, (PI * 2).toFloat())
            val speed = rand(1.5f, 4.5f) * 60f
            P(x, y, cos(angle) * speed, sin(angle) * speed - rand(0.5f, 1.4f) * 60f,
                rand(70f, 130f) / 60f, 130f / 60f, rand(3.5f, 7f), pick(STAR_COLORS),
                rand(0f, PI.toFloat()), rand(-0.05f, 0.05f) * 60f,
                0.99f, -0.003f * 3600f, 0f, rand(0f, (PI * 2).toFloat()), rand(0.15f, 0.35f) * 60f, kind)
        }
        PulseFx.BurstKind.BURST -> {
            val angle = rand(0f, (PI * 2).toFloat())
            val speed = rand(5f, 13f) * 60f
            P(x, y, cos(angle) * speed, sin(angle) * speed, rand(26f, 52f) / 60f, 52f / 60f,
                size, pick(BURST_COLORS), 0f, 0f, 0.94f, 0.02f * 3600f, 0f, 0f, 0f, kind)
        }
    }
}

private fun heartPath(size: Float): Path = Path().apply {
    val s = size / 16f
    moveTo(8f * s, 15f * s)
    cubicTo(3f * s, 11f * s, 0.5f * s, 8f * s, 0.5f * s, 5.4f * s)
    cubicTo(0.5f * s, 3f * s, 2.4f * s, 1.4f * s, 4.6f * s, 1.4f * s)
    cubicTo(6.2f * s, 1.4f * s, 7.4f * s, 2.4f * s, 8f * s, 3.6f * s)
    cubicTo(8.6f * s, 2.4f * s, 9.8f * s, 1.4f * s, 11.4f * s, 1.4f * s)
    cubicTo(13.6f * s, 1.4f * s, 15.5f * s, 3f * s, 15.5f * s, 5.4f * s)
    cubicTo(15.5f * s, 8f * s, 13f * s, 11f * s, 8f * s, 15f * s)
    close()
}

private fun sparklePath(r: Float): Path = Path().apply {
    val inner = r * 0.32f
    for (i in 0 until 4) {
        val outerA = (i * PI / 2).toFloat() - (PI / 2).toFloat()
        val innerA = outerA + (PI / 4).toFloat()
        if (i == 0) moveTo(cos(outerA) * r, sin(outerA) * r) else lineTo(cos(outerA) * r, sin(outerA) * r)
        lineTo(cos(innerA) * inner, sin(innerA) * inner)
    }
    close()
}

private fun stepParticles(particles: MutableList<P>, dt: Float, w: Float, h: Float) {
    val it = particles.iterator()
    while (it.hasNext()) {
        val p = it.next()
        p.life -= dt
        if (p.life <= 0f || p.y > h + 40f || p.x < -40f || p.x > w + 40f) {
            it.remove()
            continue
        }
        // per-frame drag exponentiated to the elapsed time (frame-rate independent)
        val d = Math.pow(p.drag.toDouble(), (dt * 60f).toDouble()).toFloat()
        p.vx *= d
        p.vy = p.vy * d + p.gravity * dt
        p.x += p.vx * dt
        p.y += p.vy * dt
        p.rot += p.vrot * dt
    }
}

private fun DrawScope.drawParticle(p: P) {
    val t = (p.life / p.maxLife).coerceIn(0f, 1f)
    val alpha = if (t > 0.75f) 1f else t / 0.75f
    when (p.kind) {
        PulseFx.BurstKind.CONFETTI -> {
            rotate(p.rot, pivot = Offset(p.x, p.y)) {
                val h = p.size * 0.7f * (0.55f + 0.45f * abs(sin(p.rot * 1.7f)))
                drawRect(
                    p.color.copy(alpha = alpha),
                    topLeft = Offset(p.x - p.size * 0.9f, p.y - h / 2f),
                    size = androidx.compose.ui.geometry.Size(p.size * 1.8f, h),
                )
            }
        }
        PulseFx.BurstKind.HEARTS -> {
            val swayX = sin(p.phase + p.x * 0.01f) * 6f
            translate(p.x + swayX - p.size * 2.4f, p.y - p.size * 2.4f) {
                drawPath(heartPath(p.size * 4.8f), p.color.copy(alpha = alpha))
            }
        }
        PulseFx.BurstKind.STARS -> {
            val tw = 0.68f + 0.32f * sin(p.twinkle * p.life * (PI * 2).toFloat() + p.phase)
            val r = p.size * 1.7f * tw.coerceAtLeast(0.2f)
            rotate(p.rot, pivot = Offset(p.x, p.y)) {
                drawPath(sparklePath(r), p.color.copy(alpha = alpha))
            }
        }
        PulseFx.BurstKind.BURST -> {
            drawLine(
                p.color.copy(alpha = alpha),
                Offset(p.x, p.y),
                Offset(p.x - p.vx * 0.04f, p.y - p.vy * 0.04f),
                strokeWidth = (p.size * 0.38f).coerceAtLeast(1f),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/**
 * Fullscreen burst overlay — listens to [PulseFx.bursts], animates only while
 * particles are alive (zero idle cost, web parity), pointer-transparent.
 */
@Composable
fun ParticleBurstHost(modifier: Modifier = Modifier, reducedMotion: Boolean) {
    val particles = remember { mutableStateListOf<P>() }
    val view = LocalView.current
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        PulseFx.bursts.collect { burst ->
            if (reducedMotion) return@collect
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            repeat(burst.count.coerceIn(1, 400)) {
                particles.add(makeParticle(burst.kind, canvasW, canvasH))
            }
        }
    }

    // The physics loop runs only while particles exist — zero idle cost.
    LaunchedEffect(particles.size) {
        if (particles.isEmpty()) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (particles.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                stepParticles(particles, dt, canvasW, canvasH)
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvasW = it.width.toFloat(); canvasH = it.height.toFloat() },
    ) {
        particles.forEach { drawParticle(it) }
    }
}
