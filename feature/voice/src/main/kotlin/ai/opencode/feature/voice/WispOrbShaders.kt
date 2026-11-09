package ai.opencode.feature.voice

/**
 * Port of `WispOrb.metal`'s raster, `wispOrbFragment` (the lens, its shadow, every lit LED, the
 * rim, mute ring, the bloom's feather and fade, and the dither), as an AGSL `RuntimeShader`.
 *
 * Android: the `wispOrbCells` kernel runs on the CPU ([WispOrbCells.evaluate]); its cells arrive
 * here as a `2·columns × columns` RGBA_F16 bitmap bound with `RuntimeShader.setInputBuffer`
 * instead of Metal's `device WispOrbCell *`: pixel `2i` holds (dot radius, dot alpha, halo alpha,
 * 1) and pixel `2i + 1` the colour. The uniforms keep [WispOrbUniforms]' slots, one `float4` each.
 * AGSL loops need constant bounds, so the dot search walks a fixed 7 × 7 window and stops at its
 * reach (one or two cells at every tuning).
 *
 * Visual divergence (desktop design system, not iOS glass): the lens is a flat plate in the
 * theme's colours ([WispOrbPalette]) with a 1 dp hairline instead of the Fresnel rim and specular
 * line, the LEDs are the desktop spinner's rounded squares without halos, and the shadow is the
 * desktop's small raised one. In light themes the LEDs print as ink: each colour keeps its hue at a
 * darker, more saturated tone. The simulation, the kernel's per-LED values and every timing are
 * iOS's, unchanged.
 */
object WispOrbShaders {
    /** The uniform slots the raster reads, by name. */
    val rasterSlots: List<Pair<String, Int>> = listOf(
        "grid" to WispOrbUniforms.grid,
        "mixB" to WispOrbUniforms.mixB,
        "tintBase" to WispOrbUniforms.tintBase,
        "tintCore" to WispOrbUniforms.tintCore,
        "rim" to WispOrbUniforms.rim,
        "view" to WispOrbUniforms.view,
        "glow" to WispOrbUniforms.glow,
        "clipRect" to WispOrbUniforms.clipRect,
        "clipShape" to WispOrbUniforms.clipShape,
    )

    const val wispOrbFragment: String = """
uniform shader cells;
uniform float4 grid;
uniform float4 mixB;
uniform float4 tintBase;
uniform float4 tintCore;
uniform float4 rim;
uniform float4 view;
uniform float4 glow;
uniform float4 clipRect;
uniform float4 clipShape;
// WispOrbPalette: plate (rgb, 1 in dark themes), hairline, alert.
uniform float4 plate;
uniform float4 hair;
uniform float4 alert;

const float WISP_BLEED = 10.0;
const float3 USER_BASE = float3(0.60, 0.90, 0.98);
const float3 AGENT_BASE = float3(0.44, 0.91, 0.68);
const float3 MUTED_PLATE = float3(26.0 / 255.0, 14.0 / 255.0, 16.0 / 255.0);
const float3 CONFIRM_PLATE = float3(44.0 / 255.0, 16.0 / 255.0, 18.0 / 255.0);

float ws(float e0, float e1, float x) {
  float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}

float3 mix3(float3 a, float3 b, float t) { return a + (b - a) * t; }

float4 composite(float4 dst, float3 rgb, float alpha) {
  float a = clamp(alpha, 0.0, 1.0);
  return float4(dst.rgb * (1.0 - a) + rgb * a, dst.a * (1.0 - a) + a);
}

float coverage(float d, float aa) { return clamp(0.5 - d / max(aa, 1e-4), 0.0, 1.0); }

float fastTanh(float x) {
  float c = clamp(x, -3.0, 3.0);
  float c2 = c * c;
  return c * (27.0 + c2) / (27.0 + 9.0 * c2);
}

float wispBox(float2 p, float4 box, float corner) {
  float2 hs = 0.5 * (box.zw - box.xy);
  float r = min(corner, min(hs.x, hs.y));
  float2 q = abs(p - 0.5 * (box.xy + box.zw)) - hs + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// The desktop spinner's LED: a square of the dot's area with 24 % corners.
float ledDistance(float2 d, float dotR) {
  float h = 0.886 * dotR;
  float r = 0.425 * dotR;
  float2 q = abs(d) - h + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

bool darkPlate() { return plate.w > 0.5; }

float luma(float3 rgb) { return dot(rgb, float3(0.2126, 0.7152, 0.0722)); }

// A light theme prints the LEDs as ink: brighter light is darker ink, in the same hue with more
// chroma. Near-white cores take the mode's hue, so a hot spot deepens instead of turning grey.
float3 printed(float3 rgb) {
  if (darkPlate()) return rgb;
  float l = luma(rgb);
  float3 chroma = mix3(rgb - l, tintBase.rgb - luma(tintBase.rgb), ws(0.86, 0.97, l));
  return clamp(1.0 - 0.75 * l + 2.2 * chroma, 0.0, 1.0);
}

// (dot radius, dot alpha, halo alpha, 1)
float4 cellGeo(float i, float j) { return float4(cells.eval(float2(2.0 * i + 0.5, j + 0.5))); }
float3 cellColour(float i, float j) { return float3(cells.eval(float2(2.0 * i + 1.5, j + 0.5)).rgb); }

// The hairline, leaning toward whoever speaks on their side (`RimLight`'s voice tint).
float4 rimShade(float4 res, float2 pt, float rPt, float dist, float aa) {
  float live = 1.0 - mixB.w;
  if (live <= 1e-3) return res;
  float hairCov = coverage(abs(dist + 0.5) - 0.5, aa);
  if (hairCov <= 0.0) return res;
  float2 un = pt / max(rPt, 1e-3);
  float3 base = hair.rgb;
  float3 left = mix3(base, printed(USER_BASE), 0.75 * clamp(rim.x, 0.0, 1.0));
  float3 right = mix3(base, printed(AGENT_BASE), 0.75 * clamp(rim.y, 0.0, 1.0));
  float3 side = mix3(left, right, ws(-0.55, 0.55, un.x));
  return composite(res, side, hairCov * live);
}

half4 main(float2 position) {
  float ppp = view.z;
  float aa = view.w;
  float2 pt = (position - view.xy) / ppp;
  float radius = grid.z;
  float pitch = grid.y;
  float cols = grid.x;
  float off = (cols - 1.0) * 0.5;
  bool dark = darkPlate();

  float orbOuter = radius + WISP_BLEED;
  float shadowOuter = radius + WISP_BLEED - 0.2;
  float shadowInner = max(radius - 6.0, 0.0);
  float shadowAlpha = plate.w > 0.5 ? 0.32 : 0.12;
  float r2 = dot(pt, pt);
  float shDy = pt.y - 0.4 * glow.w;
  float shR2 = pt.x * pt.x + shDy * shDy;
  if (r2 > orbOuter * orbOuter && shR2 > shadowOuter * shadowOuter) return half4(0.0);

  float lensInner = max(radius - 1.0 - aa, 0.0);
  bool deepInside = r2 <= lensInner * lensInner;
  float4 res = float4(0.0);
  if (shR2 < shadowInner * shadowInner) {
    res.a = shadowAlpha;
  } else if (shR2 < shadowOuter * shadowOuter) {
    float shadowD = sqrt(shR2) - radius;
    float edgeFade = ws(WISP_BLEED - 0.2, WISP_BLEED - 4.2, shadowD);
    res.a = shadowAlpha * (0.5 - 0.5 * fastTanh(shadowD / 1.6)) * edgeFade;
  }
  float rPt = sqrt(r2);
  float dist = deepInside ? -3.0 : rPt - radius;
  float shape = deepInside ? 1.0 : coverage(dist, aa);
  float3 base = plate.rgb;
  float3 confirmPlate = dark ? CONFIRM_PLATE : mix3(base, alert.rgb, 0.10);
  float3 mutedPlate = dark ? MUTED_PLATE : mix3(base, alert.rgb, 0.08);
  float3 lens = mix3(mix3(base, confirmPlate, mixB.y * 0.65), mutedPlate, mixB.w);
  if (shape > 0.0) res = composite(res, lens, shape);

  if (shape > 0.0) {
    float glowStrength = glow.x * (dark ? 0.6 : 0.35);
    float glowReach = glow.y;
    if (glowStrength > 0.004 && r2 < glowReach * glowReach) {
      float gt = min(rPt / glowReach, 1.0);
      float3 glowMid = mix3(tintBase.rgb, tintCore.rgb, 0.5);
      res = composite(res, printed(mix3(glowMid, tintBase.rgb, gt)), glowStrength * (1.0 - gt) * (1.0 - gt) * shape);
    }
    float reach = glow.z;
    float2 f = pt / pitch + off;
    float iLo = max(ceil(f.x - reach), 0.0);
    float iHi = min(floor(f.x + reach), cols - 1.0);
    float jLo = max(ceil(f.y - reach), 0.0);
    float jHi = min(floor(f.y + reach), cols - 1.0);
    for (int dj = 0; dj < 7; dj++) {
      float j = jLo + float(dj);
      if (j > jHi) break;
      float dy = pt.y - (j - off) * pitch;
      for (int di = 0; di < 7; di++) {
        float i = iLo + float(di);
        if (i > iHi) break;
        float4 geo = cellGeo(i, j);
        float dotR = geo.x;
        float dotA = geo.y;
        if (dotA <= 0.0) continue;
        float dx = pt.x - (i - off) * pitch;
        float maxR = 1.26 * dotR + aa;
        if (dx * dx + dy * dy >= maxR * maxR) continue;
        float3 colour = printed(cellColour(i, j));
        float dc = coverage(ledDistance(float2(dx, dy), dotR), aa);
        if (dc > 0.0) res = composite(res, colour, dotA * shape * dc);
      }
    }
  }

  if (!deepInside && dist <= 0.45 + aa) res = rimShade(res, pt, rPt, dist, aa);
  if (!deepInside && mixB.w > 1e-3) {
    float muteCov = coverage(abs(dist + 0.75) - 0.75, aa);
    if (muteCov > 0.0) res = composite(res, alert.rgb, 0.9 * mixB.w * muteCov);
  }

  // A grown orb feathers into the surface around it and fades above its bottom.
  if (clipShape.x >= 0.0) {
    float inside = -wispBox(position, clipRect, clipShape.x);
    res *= ws(0.0, max(clipShape.y, 1e-3), inside);
  }
  res *= clamp((clipShape.z - position.y) / max(clipShape.w, 1e-3), 0.0, 1.0);

  float alpha = clamp(res.a, 0.0, 1.0);
  if (alpha <= 1e-4) return half4(0.0);
  // `ign_dither`, in premultiplied space.
  float2 px = floor(position);
  float n1 = fract(52.982918 * fract(px.x * 0.06711056 + px.y * 0.00583715));
  float n2 = fract(52.982918 * fract((px.x + 17.0) * 0.06711056 + (px.y + 31.0) * 0.00583715));
  float3 rgb = clamp(res.rgb + (n1 - n2) / 255.0 * alpha, 0.0, alpha);
  return half4(float4(rgb, alpha));
}
"""
}
