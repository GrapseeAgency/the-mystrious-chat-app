// ─────────────────────────────────────────────────────────────
// Pulse ambient FX — Metal kernels for the SwiftUI shader system.
// Faithful ports of the web app's WebGL fragment shaders
// (src/components/fx/webgl-glow.tsx): aurora, mesh and stars.
// caustics and liquid render as Canvas-gradient approximations
// (AmbientFieldView) and have no kernel here.
//
// Contract with AmbientFieldView:
//   uniform order  = (float2 size, float time, float intensity, float dark)
//   uv             = position / size, with uv.x pre-multiplied by aspect
//   output         = opaque half4, alpha forced to 1
// Marked [[ stitchable ]] so ShaderLibrary can bind them on iOS 17.
// ─────────────────────────────────────────────────────────────
#include <metal_stdlib>
using namespace metal;

// ── aurora — drifting soft color curtains ────────────────────

static float pulseCurtain(float2 uv, float aspect, float t,
                          float yBase, float amp, float freq,
                          float speed, float thick) {
  float x = uv.x * aspect;
  float c = yBase
    + amp * sin(x * freq + t * speed)
    + amp * 0.5 * sin(x * freq * 2.3 - t * speed * 0.6 + 1.7);
  float d = uv.y - c;
  return exp(-(d * d) / (thick * thick));
}

[[ stitchable ]] half4 auroraField(float2 position, half4 source,
                                   float2 size, float time,
                                   float intensity, float dark) {
  float aspect = size.x / max(size.y, 1.0);
  float2 uv = position / max(size, float2(1.0));
  float t = time * 0.55;

  float b1 = pulseCurtain(uv, aspect, t, 0.62, 0.10, 2.0, 0.45, 0.16);
  float b2 = pulseCurtain(uv, aspect, t * 1.25, 0.47, 0.13, 2.9, -0.32, 0.20);
  float b3 = pulseCurtain(uv, aspect, t * 0.8, 0.33, 0.08, 3.7, 0.24, 0.12);

  float3 emerald = float3(0.063, 0.725, 0.506);
  float3 teal    = float3(0.078, 0.722, 0.651);
  float3 violet  = float3(0.545, 0.365, 0.965);

  float3 darkCol = float3(0.012, 0.045, 0.038);
  darkCol += emerald * b1 * 0.85 + teal * b2 * 0.70 + violet * b3 * 0.45;
  darkCol = 1.0 - exp(-darkCol * intensity * 1.7);

  float3 lightCol = float3(0.965, 0.976, 0.972);
  lightCol -= (emerald * b1 * 0.16 + teal * b2 * 0.12 + violet * b3 * 0.08) * intensity;

  float3 col = mix(lightCol, darkCol, dark);
  float2 q = uv - 0.5;
  col *= mix(1.0, 1.0 - 0.5 * dot(q, q), dark);
  return half4(half(col.r), half(col.g), half(col.b), half(1.0));
}

// ── mesh — slowly morphing gradient blobs ────────────────────

static float pulseBlob(float2 p, float2 c, float r) {
  return 1.0 - smoothstep(0.0, r, distance(p, c));
}

[[ stitchable ]] half4 meshField(float2 position, half4 source,
                                 float2 size, float time,
                                 float intensity, float dark) {
  float aspect = size.x / max(size.y, 1.0);
  float2 uv = float2((position.x / max(size.x, 1.0)) * aspect,
                     position.y / max(size.y, 1.0));
  float t = time;

  float2 c1 = float2(aspect * 0.50 + 0.24 * sin(t * 0.31), 0.60 + 0.16 * cos(t * 0.23));
  float2 c2 = float2(aspect * 0.30 + 0.18 * cos(t * 0.21 + 1.7), 0.28 + 0.18 * sin(t * 0.27 + 0.6));
  float2 c3 = float2(aspect * 0.72 + 0.20 * sin(t * 0.17 + 3.9), 0.44 + 0.22 * cos(t * 0.19 + 2.4));

  float2 wuv = uv + 0.07 * float2(sin(uv.y * 3.3 + t * 0.45), cos(uv.x * 2.9 - t * 0.38));

  float b1 = max(pulseBlob(uv, c1, 0.62), pulseBlob(wuv, c1, 0.55) * 0.85);
  float b2 = max(pulseBlob(uv, c2, 0.55), pulseBlob(wuv, c2, 0.48) * 0.85);
  float b3 = max(pulseBlob(uv, c3, 0.58), pulseBlob(wuv, c3, 0.50) * 0.85);

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
  return half4(half(col.r), half(col.g), half(col.b), half(1.0));
}

// ── stars — parallax starfield with twinkle ──────────────────

static float pulseHash21(float2 p) {
  p = fract(p * float2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

static float pulseStarLayer(float2 uv, float scale, float t) {
  float2 g = uv * scale;
  float2 id = floor(g);
  float2 f = fract(g) - 0.5;
  float h = pulseHash21(id);
  float2 off = float2(pulseHash21(id + 7.13), pulseHash21(id + 3.71)) - 0.5;
  float d = length(f - off * 0.8);
  float core = smoothstep(0.10, 0.0, d);
  float tw = 0.35 + 0.65 * (0.5 + 0.5 * sin(t * (0.6 + h * 2.4) + h * 6.2831));
  return core * step(0.82, h) * tw;
}

[[ stitchable ]] half4 starsField(float2 position, half4 source,
                                  float2 size, float time,
                                  float intensity, float dark) {
  float aspect = size.x / max(size.y, 1.0);
  float2 uv = position / max(size, float2(1.0));
  float t = time;

  float2 s1 = uv + float2(t * 0.006, t * 0.002);
  float2 s2 = uv + float2(t * 0.012, -t * 0.004) + 0.37;
  float2 s3 = uv + float2(t * 0.021, t * 0.006) + 0.71;

  float l1 = pulseStarLayer(float2(s1.x * aspect, s1.y), 26.0, t);
  float l2 = pulseStarLayer(float2(s2.x * aspect, s2.y), 44.0, t * 1.3);
  float l3 = pulseStarLayer(float2(s3.x * aspect, s3.y), 70.0, t * 1.7);

  float3 darkCol = float3(0.015, 0.030, 0.045);
  darkCol += float3(0.92, 0.98, 1.0) * (l1 * 0.9 + l2 * 0.6 + l3 * 0.4) * intensity;
  darkCol += float3(0.063, 0.500, 0.420) * 0.05 * (0.5 + 0.5 * sin(uv.y * 3.0 + t * 0.1)) * dark;

  float3 lightCol = float3(0.955, 0.965, 0.975);
  lightCol -= float3(0.25, 0.30, 0.38) * (l1 * 0.35 + l2 * 0.25 + l3 * 0.15) * intensity;

  float3 col = mix(lightCol, darkCol, dark);
  float2 q = uv - 0.5;
  col *= mix(1.0, 1.0 - 0.55 * dot(q, q), dark);
  return half4(half(col.r), half(col.g), half(col.b), half(1.0));
}
