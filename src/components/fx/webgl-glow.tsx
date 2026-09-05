// ─────────────────────────────────────────────────────────────
// Pulse premium FX (R26-e) — multi-mode WebGL ambient system.
// Raw WebGL1 (no three.js): one fullscreen quad + a per-mode
// fragment shader. Five modes ship today:
//
//   off      · renders nothing (background ownership released)
//   aurora   · drifting soft color curtains (emerald/teal/violet)
//   caustics · glassy refraction ripples (aqua interference)
//   mesh     · slowly morphing gradient blobs (the R22 hero glow)
//   stars    · three-depth parallax starfield with twinkle
//
// Contract (R26-a lead): the active mode is read from the prefs
// store key 'fx.webglMode' (string, one of WEBGL_MODES; default
// DEFAULT_WEBGL_MODE = 'aurora'). Settings (R26-c) writes it via
// usePrefs().save({ 'fx.webglMode': mode }) — until prefs-defaults
// carries the key, the tolerant reader below validates whatever it
// finds and falls back to the default. A `mode` prop overrides the
// pref (used by WebglGlow for back-compat and by tests).
//
// Performance & safety guards:
// · Device-pixel-ratio capped at 1.5 (times a per-mode render
//   scale — these are gradients/soft points, not text).
// · Paused when offscreen (IntersectionObserver) or tab hidden
//   (visibilitychange).
// · prefers-reduced-motion (media query OR prefs.reducedMotion)
//   → renders ONE static frame, no loop.
// · WebGL unavailable / program failure / context loss → renders
//   nothing (no crash); `cssFallback` opts into a static CSS
//   gradient instead (WebglGlow keeps its R22 behavior).
// · Full canvas cleanup on unmount (observers, listeners, rAF,
//   loseContext). The <canvas> is keyed by mode so switching modes
//   always gets a fresh, never-lost context.
// · Dark/light aware: explicit `dark` prop wins, otherwise the
//   documentElement 'dark' class (next-themes) is observed live.
//
// Coordination: on a successful GL init this module publishes a
// module-level availability signal (isWebglAmbientAvailable /
// useWebglAmbientAvailable) that particle-layer.tsx consumes so the
// DOM particle layer and the WebGL ambient never overdraw at the
// same time. Zero emojis — code and comments alike.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { cn } from '@/lib/utils'
import { usePrefs } from '@/lib/prefs'
import { prefersReducedMotion } from '@/lib/motion'

// ── Mode registry + prefs contract ──────────────────────────

export const WEBGL_MODES = ['off', 'aurora', 'caustics', 'mesh', 'stars'] as const
export type WebGLMode = (typeof WEBGL_MODES)[number]

/** Prefs store key that links Settings (R26-c) to this ambient system. */
export const PREF_KEY_WEBGL_MODE = 'fx.webglMode'

/** Shipped default when the pref is absent or malformed. */
export const DEFAULT_WEBGL_MODE: WebGLMode = 'aurora'

export function isWebglMode(v: unknown): v is WebGLMode {
  return typeof v === 'string' && (WEBGL_MODES as readonly string[]).includes(v)
}

// ── Ambient availability signal (consumed by particle-layer) ─

const AMBIENT_AVAILABLE_EVENT = 'pulse:webgl-ambient-availability'

let ambientAvailable = false

function setAmbientAvailable(next: boolean): void {
  if (ambientAvailable === next) return
  ambientAvailable = next
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(AMBIENT_AVAILABLE_EVENT))
  }
}

/** True while a WebGLAmbient canvas is actually rendering. */
export function isWebglAmbientAvailable(): boolean {
  return ambientAvailable
}

function subscribeAmbient(onChange: () => void): () => void {
  window.addEventListener(AMBIENT_AVAILABLE_EVENT, onChange)
  return () => window.removeEventListener(AMBIENT_AVAILABLE_EVENT, onChange)
}

const getServerAmbient = (): boolean => false

/** React hook — is the WebGL ambient canvas currently alive? */
export function useWebglAmbientAvailable(): boolean {
  return useSyncExternalStore(subscribeAmbient, isWebglAmbientAvailable, getServerAmbient)
}

// ── Shaders ─────────────────────────────────────────────────

const VERT_SRC = `
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}
`

// aurora — drifting soft color bands
const AURORA_FRAG = `
precision mediump float;
varying vec2 v_uv;
uniform vec2 u_res;
uniform float u_time;
uniform float u_intensity;
uniform float u_dark;

float curtain(vec2 uv, float aspect, float t, float yBase, float amp, float freq, float speed, float thick) {
  float x = uv.x * aspect;
  float c = yBase
    + amp * sin(x * freq + t * speed)
    + amp * 0.5 * sin(x * freq * 2.3 - t * speed * 0.6 + 1.7);
  float d = uv.y - c;
  return exp(-(d * d) / (thick * thick));
}

void main() {
  float aspect = u_res.x / max(u_res.y, 1.0);
  vec2 uv = v_uv;
  float t = u_time * 0.55;

  float b1 = curtain(uv, aspect, t, 0.62, 0.10, 2.0, 0.45, 0.16);
  float b2 = curtain(uv, aspect, t * 1.25, 0.47, 0.13, 2.9, -0.32, 0.20);
  float b3 = curtain(uv, aspect, t * 0.8, 0.33, 0.08, 3.7, 0.24, 0.12);

  vec3 emerald = vec3(0.063, 0.725, 0.506);
  vec3 teal    = vec3(0.078, 0.722, 0.651);
  vec3 violet  = vec3(0.545, 0.365, 0.965);

  vec3 darkCol = vec3(0.012, 0.045, 0.038);
  darkCol += emerald * b1 * 0.85 + teal * b2 * 0.70 + violet * b3 * 0.45;
  darkCol = 1.0 - exp(-darkCol * u_intensity * 1.7);

  vec3 lightCol = vec3(0.965, 0.976, 0.972);
  lightCol -= (emerald * b1 * 0.16 + teal * b2 * 0.12 + violet * b3 * 0.08) * u_intensity;

  vec3 col = mix(lightCol, darkCol, u_dark);
  vec2 q = v_uv - 0.5;
  col *= mix(1.0, 1.0 - 0.5 * dot(q, q), u_dark);
  gl_FragColor = vec4(col, 1.0);
}
`

// caustics — glassy refraction ripples (interference minima)
const CAUSTICS_FRAG = `
precision mediump float;
varying vec2 v_uv;
uniform vec2 u_res;
uniform float u_time;
uniform float u_intensity;
uniform float u_dark;

void main() {
  float aspect = u_res.x / max(u_res.y, 1.0);
  vec2 uv = v_uv;
  float t = u_time * 0.62;

  vec2 p = vec2(uv.x * aspect, uv.y) * 3.4;
  vec2 i = p;
  float c = 1.0;
  float inten = 0.005;
  for (int n = 0; n < 4; n++) {
    float tt = t * (1.0 - (3.5 / float(n + 1)));
    i = p + vec2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
    c += 1.0 / length(vec2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
  }
  c /= 4.0;
  c = 1.17 - pow(c, 1.4);
  float v = clamp(pow(abs(c), 8.0), 0.0, 1.6);

  vec3 aqua = vec3(0.30, 0.91, 0.85);

  vec3 darkCol = vec3(0.008, 0.050, 0.055);
  darkCol += aqua * v * 0.55 * u_intensity + vec3(0.063, 0.725, 0.506) * v * 0.20;
  darkCol = 1.0 - exp(-darkCol * 1.7);

  vec3 lightCol = vec3(0.940, 0.970, 0.970);
  lightCol -= vec3(0.0, 0.28, 0.30) * v * 0.35 * u_intensity;

  vec3 col = mix(lightCol, darkCol, u_dark);
  vec2 q = v_uv - 0.5;
  col *= mix(1.0, 1.0 - 0.5 * dot(q, q), u_dark);
  gl_FragColor = vec4(col, 1.0);
}
`

// mesh — slowly morphing gradient blobs (the R22 hero glow)
const MESH_FRAG = `
precision mediump float;
varying vec2 v_uv;
uniform vec2 u_res;
uniform float u_time;
uniform float u_intensity;
uniform float u_dark;

float blob(vec2 p, vec2 c, float r) {
  return 1.0 - smoothstep(0.0, r, distance(p, c));
}

void main() {
  float aspect = u_res.x / max(u_res.y, 1.0);
  vec2 uv = vec2(v_uv.x * aspect, v_uv.y);
  float t = u_time;

  vec2 c1 = vec2(aspect * 0.50 + 0.24 * sin(t * 0.31), 0.60 + 0.16 * cos(t * 0.23));
  vec2 c2 = vec2(aspect * 0.30 + 0.18 * cos(t * 0.21 + 1.7), 0.28 + 0.18 * sin(t * 0.27 + 0.6));
  vec2 c3 = vec2(aspect * 0.72 + 0.20 * sin(t * 0.17 + 3.9), 0.44 + 0.22 * cos(t * 0.19 + 2.4));

  vec2 wuv = uv + 0.07 * vec2(sin(uv.y * 3.3 + t * 0.45), cos(uv.x * 2.9 - t * 0.38));

  float b1 = max(blob(uv, c1, 0.62), blob(wuv, c1, 0.55) * 0.85);
  float b2 = max(blob(uv, c2, 0.55), blob(wuv, c2, 0.48) * 0.85);
  float b3 = max(blob(uv, c3, 0.58), blob(wuv, c3, 0.50) * 0.85);

  vec3 darkCol = vec3(0.012, 0.048, 0.040);
  darkCol += vec3(0.063, 0.725, 0.506) * b1 * 0.95;
  darkCol += vec3(0.078, 0.722, 0.651) * b2 * 0.80;
  darkCol += vec3(0.545, 0.365, 0.965) * b3 * 0.38;
  darkCol = 1.0 - exp(-darkCol * u_intensity * 1.7);

  vec3 lightCol = vec3(0.972, 0.978, 0.974);
  lightCol -= vec3(0.063, 0.725, 0.506) * b1 * 0.22 * u_intensity;
  lightCol -= vec3(0.078, 0.722, 0.651) * b2 * 0.18 * u_intensity;
  lightCol -= vec3(0.545, 0.365, 0.965) * b3 * 0.10 * u_intensity;

  vec3 col = mix(lightCol, darkCol, u_dark);
  vec2 q = v_uv - 0.5;
  col *= mix(1.0, 1.0 - 0.5 * dot(q, q), u_dark);
  gl_FragColor = vec4(col, 1.0);
}
`

// stars — parallax particle starfield with twinkle
const STARS_FRAG = `
precision mediump float;
varying vec2 v_uv;
uniform vec2 u_res;
uniform float u_time;
uniform float u_intensity;
uniform float u_dark;

float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

float starLayer(vec2 uv, float scale, float t) {
  vec2 g = uv * scale;
  vec2 id = floor(g);
  vec2 f = fract(g) - 0.5;
  float h = hash21(id);
  vec2 off = vec2(hash21(id + 7.13), hash21(id + 3.71)) - 0.5;
  float d = length(f - off * 0.8);
  float core = smoothstep(0.10, 0.0, d);
  float tw = 0.35 + 0.65 * (0.5 + 0.5 * sin(t * (0.6 + h * 2.4) + h * 6.2831));
  return core * step(0.82, h) * tw;
}

void main() {
  float aspect = u_res.x / max(u_res.y, 1.0);
  vec2 uv = v_uv;
  float t = u_time;

  vec2 s1 = uv + vec2(t * 0.006, t * 0.002);
  vec2 s2 = uv + vec2(t * 0.012, -t * 0.004) + 0.37;
  vec2 s3 = uv + vec2(t * 0.021, t * 0.006) + 0.71;

  float l1 = starLayer(vec2(s1.x * aspect, s1.y), 26.0, t);
  float l2 = starLayer(vec2(s2.x * aspect, s2.y), 44.0, t * 1.3);
  float l3 = starLayer(vec2(s3.x * aspect, s3.y), 70.0, t * 1.7);

  vec3 darkCol = vec3(0.015, 0.030, 0.045);
  darkCol += vec3(0.92, 0.98, 1.0) * (l1 * 0.9 + l2 * 0.6 + l3 * 0.4) * u_intensity;
  darkCol += vec3(0.063, 0.500, 0.420) * 0.05 * (0.5 + 0.5 * sin(v_uv.y * 3.0 + t * 0.1)) * u_dark;

  vec3 lightCol = vec3(0.955, 0.965, 0.975);
  lightCol -= vec3(0.25, 0.30, 0.38) * (l1 * 0.35 + l2 * 0.25 + l3 * 0.15) * u_intensity;

  vec3 col = mix(lightCol, darkCol, u_dark);
  vec2 q = v_uv - 0.5;
  col *= mix(1.0, 1.0 - 0.55 * dot(q, q), u_dark);
  gl_FragColor = vec4(col, 1.0);
}
`

const FRAG_BY_MODE: Record<Exclude<WebGLMode, 'off'>, string> = {
  aurora: AURORA_FRAG,
  caustics: CAUSTICS_FRAG,
  mesh: MESH_FRAG,
  stars: STARS_FRAG,
}

/** Backing-store scale per mode (multiplied by the capped DPR). */
const MODE_RENDER_SCALE: Record<Exclude<WebGLMode, 'off'>, number> = {
  aurora: 0.5,
  caustics: 0.5,
  mesh: 0.5,
  stars: 0.8,
}

/** CSS fallback with the same emerald palette (WebglGlow's R22 behavior). */
const FALLBACK_STYLE: React.CSSProperties = {
  background: [
    'radial-gradient(46% 58% at 32% 38%, rgba(16, 185, 129, 0.60), transparent 72%)',
    'radial-gradient(42% 52% at 70% 62%, rgba(20, 184, 166, 0.48), transparent 72%)',
    'radial-gradient(50% 62% at 55% 82%, rgba(139, 92, 246, 0.22), transparent 74%)',
    'linear-gradient(165deg, #021712 0%, #04231b 55%, #020f0c 100%)',
  ].join(', '),
}

function compile(gl: WebGLRenderingContext, type: number, src: string): WebGLShader {
  const shader = gl.createShader(type)
  if (!shader) throw new Error('shader alloc failed')
  gl.shaderSource(shader, src)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader)
    gl.deleteShader(shader)
    throw new Error(`shader compile failed: ${log ?? 'unknown'}`)
  }
  return shader
}

export interface WebGLAmbientProps {
  className?: string
  /** overall brightness of the field (default 1) */
  intensity?: number
  /** Force a mode; omit to follow prefs 'fx.webglMode' (default DEFAULT_WEBGL_MODE). */
  mode?: WebGLMode
  /** Force dark/light; omit to follow the documentElement 'dark' class live. */
  dark?: boolean
  /** Render a static CSS gradient instead of nothing when WebGL is unavailable. */
  cssFallback?: boolean
  /**
   * App-root mount mode: render as a fixed, pointer-transparent blend veil
   * OVER the shell (soft-light in light, screen in dark). Leave false for
   * inline mounts (onboarding hero) that size the canvas themselves.
   */
  layered?: boolean
}

/**
 * Fullscreen ambient WebGL field driven by prefs 'fx.webglMode'.
 * Renders nothing for mode 'off' or when WebGL is unavailable
 * (unless cssFallback). Mount at most one instance (app-root).
 */
export function WebGLAmbient({
  className,
  intensity = 1,
  mode: modeProp,
  dark: darkProp,
  cssFallback = false,
  layered = false,
}: WebGLAmbientProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const intensityRef = useRef(intensity)
  const [failed, setFailed] = useState(false)

  // prefs-driven mode (tolerant read — prefs-defaults may not carry
  // the key yet; unknown/invalid values resolve to the default)
  const prefsModeRaw = usePrefs((s) => (s.prefs as unknown as Record<string, unknown>)[PREF_KEY_WEBGL_MODE])
  const prefsReduced = usePrefs((s) => s.prefs.reducedMotion)
  const reducedRef = useRef(prefsReduced)
  reducedRef.current = prefsReduced

  // theme: explicit prop wins; otherwise track next-themes' html.dark class
  const darkRef = useRef<boolean>(
    typeof document !== 'undefined' ? document.documentElement.classList.contains('dark') : true,
  )
  useEffect(() => {
    if (darkProp != null) {
      darkRef.current = darkProp
      return
    }
    const root = document.documentElement
    const read = () => {
      darkRef.current = root.classList.contains('dark')
    }
    read()
    const mo = new MutationObserver(read)
    mo.observe(root, { attributes: true, attributeFilter: ['class'] })
    return () => mo.disconnect()
  }, [darkProp])

  // keep the uniform current without rebuilding the GL pipeline
  useEffect(() => {
    intensityRef.current = intensity
  }, [intensity])

  const mode: WebGLMode = modeProp ?? (isWebglMode(prefsModeRaw) ? prefsModeRaw : DEFAULT_WEBGL_MODE)

  useEffect(() => {
    if (mode === 'off') return
    const canvas = canvasRef.current
    if (!canvas) return

    let disposed = false
    let raf = 0
    let visible = true
    let running = false
    let time = 14.2 // start at a pleasing moment of the animation
    let last = 0
    const reduced = prefersReducedMotion() || reducedRef.current
    const renderScale = MODE_RENDER_SCALE[mode]
    setFailed(false)

    try {
      const gl = canvas.getContext('webgl', {
        alpha: false,
        antialias: false,
        depth: false,
        stencil: false,
        powerPreference: 'low-power',
      })
      if (!gl) throw new Error('webgl unavailable')

      const vert = compile(gl, gl.VERTEX_SHADER, VERT_SRC)
      const frag = compile(gl, gl.FRAGMENT_SHADER, FRAG_BY_MODE[mode])
      const program = gl.createProgram()
      if (!program) throw new Error('program alloc failed')
      gl.attachShader(program, vert)
      gl.attachShader(program, frag)
      gl.linkProgram(program)
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error(`program link failed: ${gl.getProgramInfoLog(program) ?? 'unknown'}`)
      }
      gl.useProgram(program)

      // fullscreen quad as a triangle strip
      const buffer = gl.createBuffer()
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
      const aPos = gl.getAttribLocation(program, 'a_pos')
      gl.enableVertexAttribArray(aPos)
      gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0)

      const uRes = gl.getUniformLocation(program, 'u_res')
      const uTime = gl.getUniformLocation(program, 'u_time')
      const uIntensity = gl.getUniformLocation(program, 'u_intensity')
      const uDark = gl.getUniformLocation(program, 'u_dark')

      const draw = (t: number) => {
        gl.uniform2f(uRes, canvas.width, canvas.height)
        gl.uniform1f(uTime, t)
        gl.uniform1f(uIntensity, intensityRef.current)
        gl.uniform1f(uDark, darkRef.current ? 1 : 0)
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      }

      // capped-DPR backing store, CSS-scaled up
      const resize = () => {
        const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
        const w = Math.max(1, Math.round(canvas.clientWidth * dpr * renderScale))
        const h = Math.max(1, Math.round(canvas.clientHeight * dpr * renderScale))
        if (w === canvas.width && h === canvas.height) return
        canvas.width = w
        canvas.height = h
        gl.viewport(0, 0, w, h)
        if (reduced) draw(time) // static frame must track resizes
      }
      resize()

      const frame = (now: number) => {
        raf = 0
        const dt = Math.min((now - last) / 1000, 0.05) // clamp tab-switch jumps
        last = now
        time += dt
        draw(time)
        if (running && !disposed) raf = requestAnimationFrame(frame)
      }

      const setRunning = (next: boolean) => {
        next = next && !disposed && !document.hidden
        if (next === running) return
        running = next
        if (running) {
          last = performance.now()
          raf = requestAnimationFrame(frame)
        } else if (raf !== 0) {
          cancelAnimationFrame(raf)
          raf = 0
        }
      }

      const ro = new ResizeObserver(() => {
        resize()
      })
      ro.observe(canvas)

      const io = new IntersectionObserver(([entry]) => {
        visible = entry?.isIntersecting ?? true
        setRunning(visible)
      })
      io.observe(canvas)

      const onVisibility = () => setRunning(visible)
      const onContextLost = (e: Event) => {
        e.preventDefault()
        if (raf !== 0) cancelAnimationFrame(raf)
        raf = 0
        running = false
        if (!disposed) {
          setAmbientAvailable(false)
          if (cssFallback) setFailed(true) // static CSS stand-in
          // cssFallback === false → render nothing, never crash
        }
      }

      document.addEventListener('visibilitychange', onVisibility)
      canvas.addEventListener('webglcontextlost', onContextLost)

      setAmbientAvailable(true) // publish: the ambient canvas is alive

      if (reduced) {
        draw(time) // one static frame, no loop
      } else {
        setRunning(true)
      }

      return () => {
        disposed = true
        running = false
        setAmbientAvailable(false)
        if (raf !== 0) cancelAnimationFrame(raf)
        raf = 0
        ro.disconnect()
        io.disconnect()
        document.removeEventListener('visibilitychange', onVisibility)
        canvas.removeEventListener('webglcontextlost', onContextLost)
        try {
          gl.getExtension('WEBGL_lose_context')?.loseContext()
        } catch {
          // best-effort cleanup only
        }
      }
    } catch {
      setAmbientAvailable(false)
      if (!disposed) setFailed(true)
      return () => {
        disposed = true
      }
    }
  }, [mode, cssFallback])

  if (mode === 'off') return null
  if (failed) {
    return cssFallback ? <div aria-hidden className={cn('h-full w-full', className)} style={FALLBACK_STYLE} /> : null
  }

  const canvas = (
    <canvas
      key={mode} // fresh canvas per mode — contexts are never reused after loseContext
      ref={canvasRef}
      aria-hidden
      className={cn('block h-full w-full', className)}
    />
  )

  if (layered) {
    return (
      <div
        aria-hidden
        className={cn(
          'pointer-events-none fixed inset-0 z-[94]',
          'opacity-[0.34] mix-blend-soft-light dark:opacity-[0.5] dark:mix-blend-screen',
        )}
      >
        {canvas}
      </div>
    )
  }

  return canvas
}

export interface WebglGlowProps {
  className?: string
  /** overall brightness of the field (default 1) */
  intensity?: number
}

/**
 * R22 hero glow — kept as the mesh mode of the ambient system so
 * onboarding-screen keeps its exact look, palette and CSS fallback.
 */
export function WebglGlow({ className, intensity = 1 }: WebglGlowProps) {
  return <WebGLAmbient mode="mesh" intensity={intensity} className={className} cssFallback />
}
