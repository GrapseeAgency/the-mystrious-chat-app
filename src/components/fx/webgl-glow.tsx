// ─────────────────────────────────────────────────────────────
// Pulse premium FX (R22) — WebGL liquid-gradient hero.
// Raw WebGL1 (no three.js): one fullscreen quad + a fragment
// shader with three drifting radial gradient blobs (emerald,
// teal, subtle violet) blended through an fbm-ish domain warp
// and a soft filmic curve. Rendered at 0.5 resolution and
// CSS-scaled up — gradients are smooth, so the upscale is free.
//
// Performance & safety:
// · `{ powerPreference: 'low-power' }` context.
// · Paused when offscreen (IntersectionObserver) or tab hidden.
// · prefers-reduced-motion → renders ONE static frame.
// · Any failure (no WebGL, program/link error, context loss)
//   falls back to a CSS radial-gradient div with the same palette.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/utils'

export interface WebglGlowProps {
  className?: string
  /** overall brightness of the field (default 1) */
  intensity?: number
}

const VERT_SRC = `
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}
`

const FRAG_SRC = `
precision mediump float;
varying vec2 v_uv;
uniform vec2 u_res;
uniform float u_time;
uniform float u_intensity;

float blob(vec2 p, vec2 c, float r) {
  return 1.0 - smoothstep(0.0, r, distance(p, c));
}

void main() {
  float aspect = u_res.x / max(u_res.y, 1.0);
  vec2 uv = vec2(v_uv.x * aspect, v_uv.y);
  float t = u_time;

  // drifting blob centers — different frequencies/phases = liquid parallax
  vec2 c1 = vec2(aspect * 0.50 + 0.24 * sin(t * 0.31), 0.60 + 0.16 * cos(t * 0.23));
  vec2 c2 = vec2(aspect * 0.30 + 0.18 * cos(t * 0.21 + 1.7), 0.28 + 0.18 * sin(t * 0.27 + 0.6));
  vec2 c3 = vec2(aspect * 0.72 + 0.20 * sin(t * 0.17 + 3.9), 0.44 + 0.22 * cos(t * 0.19 + 2.4));

  // fbm-ish domain warp: re-sample the field at warped coordinates
  vec2 wuv = uv + 0.07 * vec2(sin(uv.y * 3.3 + t * 0.45), cos(uv.x * 2.9 - t * 0.38));

  float b1 = max(blob(uv, c1, 0.62), blob(wuv, c1, 0.55) * 0.85);
  float b2 = max(blob(uv, c2, 0.55), blob(wuv, c2, 0.48) * 0.85);
  float b3 = max(blob(uv, c3, 0.58), blob(wuv, c3, 0.50) * 0.85);

  vec3 col = vec3(0.012, 0.048, 0.040);            // deep emerald-black base
  col += vec3(0.063, 0.725, 0.506) * b1 * 0.95;    // emerald  #10b981
  col += vec3(0.078, 0.722, 0.651) * b2 * 0.80;    // teal     #14b8a6
  col += vec3(0.545, 0.365, 0.965) * b3 * 0.38;    // subtle violet

  // soft filmic curve — overlaps keep glowing, never clip harshly
  col = 1.0 - exp(-col * u_intensity * 1.7);

  // gentle vignette for depth
  vec2 q = v_uv - 0.5;
  col *= 1.0 - 0.5 * dot(q, q);

  gl_FragColor = vec4(col, 1.0);
}
`

/** CSS fallback with the same palette (used when WebGL is unavailable/lost). */
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

export function WebglGlow({ className, intensity = 1 }: WebglGlowProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const intensityRef = useRef(intensity)
  const [failed, setFailed] = useState(false)

  // keep the uniform current without rebuilding the GL pipeline
  useEffect(() => {
    intensityRef.current = intensity
  }, [intensity])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    let disposed = false
    let raf = 0
    let visible = true
    let running = false
    let time = 14.2 // start at a pleasing moment of the animation
    let last = 0
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

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
      const frag = compile(gl, gl.FRAGMENT_SHADER, FRAG_SRC)
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

      const draw = (t: number) => {
        gl.uniform2f(uRes, canvas.width, canvas.height)
        gl.uniform1f(uTime, t)
        gl.uniform1f(uIntensity, intensityRef.current)
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      }

      // 0.5-resolution backing store, CSS-scaled up
      const resize = () => {
        const w = Math.max(1, Math.round(canvas.clientWidth * 0.5))
        const h = Math.max(1, Math.round(canvas.clientHeight * 0.5))
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
        if (!disposed) setFailed(true) // graceful CSS fallback
      }

      document.addEventListener('visibilitychange', onVisibility)
      canvas.addEventListener('webglcontextlost', onContextLost)

      if (reduced) {
        draw(time) // one static frame, no loop
      } else {
        setRunning(true)
      }

      return () => {
        disposed = true
        running = false
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
      if (!disposed) setFailed(true)
      return () => {
        disposed = true
      }
    }
  }, [])

  if (failed) {
    return <div aria-hidden className={cn('h-full w-full', className)} style={FALLBACK_STYLE} />
  }

  return (
    <canvas
      ref={canvasRef}
      aria-hidden
      className={cn('block h-full w-full', className)}
    />
  )
}
