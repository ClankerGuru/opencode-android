package ai.opencode.feature.voice

/**
 * Port of `FloatingOrbShaders.metal`'s `floatingOrbFragment` as an AGSL `RuntimeShader`.
 *
 * The floating call's dot-matrix faces ([FloatingOrbRenderer]): big LED dots across the whole
 * orb, lit by two forces with body, yours (warm pearl) and the agent's (cool pearl), the way
 * the call bar's scope braids two traces. Each take is a different pair of forces; all share
 * the moods: thinking, needs input (an amber chase round the rim), connecting (a scanning
 * beam), muted and ended.
 *
 * The uniforms keep Metal's `FloatingOrbUniforms` layout, one `float4` each. AGSL notes: Metal's
 * `fmod` + wrap is GLSL `mod`, `atan2` is `atan(y, x)`, `saturate` is `clamp(…, 0, 1)`, `round`
 * is `floor(x + 0.5)`, and the take `switch` is an `if` chain.
 */
object FloatingOrbShaders {
    const val floatingOrbFragment: String = """
uniform float4 size;      // width px, height px, pitch px, points to pixels
uniform float4 levels;    // you, agent, thinking, needs input
uniform float4 clock;     // phase (s), orbit (rad: turns only while thinking), take, dark
uniform float4 mood;      // muted, connecting, ended, body
uniform float4 look;      // dot fill, glow, base, unused
uniform float4 you;
uniform float4 youCore;
uniform float4 agent;
uniform float4 agentCore;

const float fcoTau = 6.2831853;

struct FcoForces {
  float you;
  float agent;
  float seam;  // where the two meet: lit in both colours
};

float fcoSq(float x) { return x * x; }

float fcoSat(float x) { return clamp(x, 0.0, 1.0); }

float fcoBehind(float head, float angle) {
  return mod(head - angle, fcoTau);
}

// Twin Vortex: two counter-rotating spiral vortices circling each other.
float fcoVortex(float2 d, float sigma, float spinRate, float t, float dir) {
  float r = length(d);
  float a = atan(d.y, d.x);
  float arms = 0.5 + 0.5 * cos(2.0 * a * dir + 4.5 * r - spinRate * t);
  float falloff = exp(-(r * r) / (sigma * sigma));
  float core = exp(-(r * r) / (sigma * sigma * 0.25));
  return falloff * (0.45 + 0.75 * smoothstep(0.15, 0.75, arms)) + 0.8 * core;
}

FcoForces fcoTwinVortex(float2 uv, float ly, float la, float th, float t, float orbit, float body, float spread) {
  float both = fcoSat(min(ly, la) * 1.6);
  float sep = mix(0.46, 0.22, both) * mix(1.0, 0.25, th);
  float theta = t * 0.35 + orbit;
  float2 dir = float2(cos(theta) * spread, sin(theta));
  float2 cy = -sep * dir;
  float2 ca = sep * dir;
  float sy = (0.46 + 0.36 * ly) * body * mix(1.0, 1.2, th);
  float sa = (0.46 + 0.36 * la) * body * mix(1.0, 1.2, th);
  FcoForces f;
  f.you = fcoVortex(uv - cy, sy, 1.0 + 5.0 * ly + 3.0 * th, t, 1.0);
  f.agent = fcoVortex(uv - ca, sa, 1.0 + 5.0 * la + 3.0 * th, t, -1.0);
  f.seam = 0.0;
  return f;
}

// Tides: two masses of dots, three lobes each, that swell, lean in and push
// against each other with a bright seam; thinking sets them chasing as comets.
FcoForces fcoTides(float2 uv, float ly, float la, float th, float t, float orbit, float body, float spread) {
  float fy = 0.0;
  float fa = 0.0;
  for (int i = 0; i < 3; i++) {
    float k = float(i);
    float wobble = t * (0.7 + 0.3 * k) + k * 2.1;
    float2 homeY = float2((-0.5 + 0.42 * ly - 0.18 * la) * spread, 0.0);
    float2 homeA = float2((0.5 - 0.42 * la + 0.18 * ly) * spread, 0.0);
    float2 cy = homeY + 0.2 * float2(cos(wobble) * spread, sin(wobble * 1.3));
    float2 ca = homeA + 0.2 * float2(cos(wobble + 2.3) * spread, sin(wobble * 1.17 + 1.0));
    float lag = k * 0.42;
    float2 cometY = 0.52 * float2(cos(orbit - lag) * spread, sin(orbit - lag));
    float2 cometA = 0.52 * float2(cos(orbit + 3.14159 - lag) * spread, sin(orbit + 3.14159 - lag));
    cy = mix(cy, cometY, th);
    ca = mix(ca, cometA, th);
    float taper = mix(1.0, 1.0 - 0.28 * k, th);
    float ry = (0.2 + 0.13 * ly) * body * taper;
    float ra = (0.2 + 0.13 * la) * body * taper;
    float2 dy = uv - cy;
    float2 da = uv - ca;
    fy += ry * ry / max(dot(dy, dy), 1e-4);
    fa += ra * ra / max(dot(da, da), 1e-4);
  }
  float total = fy + fa;
  float share = fy / max(total, 1e-4);
  float mass = smoothstep(0.75, 1.5, total) + 0.3 * smoothstep(0.3, 0.75, total);
  FcoForces f;
  f.you = mass * smoothstep(0.35, 0.65, share);
  f.agent = mass * smoothstep(0.35, 0.65, 1.0 - share);
  float meet = smoothstep(0.55, 1.0, min(fy, fa));
  f.seam = meet * (0.55 + 0.45 * sin(t * 7.0 + uv.y * 10.0));
  return f;
}

// Thick Helix: the scope's braid with body, two wide bands twisting front
// over back across the orb.
FcoForces fcoHelix(float2 uv, float ly, float la, float th, float t, float orbit, float body, float spread) {
  float both = fcoSat(min(ly, la) * 1.6);
  float x = uv.x / spread;
  float k = 3.0 + 1.4 * both + 1.0 * th;
  float ph = t * 2.2 + orbit * 1.4;
  float ay = mix(0.14 + 0.5 * ly, 0.42, th);
  float aa = mix(0.14 + 0.5 * la, 0.42, th);
  float wy = (0.26 + 0.16 * ly + 0.06 * th) * body;
  float wa = (0.26 + 0.16 * la + 0.06 * th) * body;
  float yy = ay * sin(k * x - ph);
  float ya = -aa * sin(k * x - ph + 0.5 * (1.0 - th));
  float zy = cos(k * x - ph);
  float by = exp(-fcoSq(uv.y - yy) / (wy * wy));
  float ba = exp(-fcoSq(uv.y - ya) / (wa * wa));
  // Where they cross, the band in front hides the one behind.
  float overlap = by * ba;
  FcoForces f;
  f.you = 1.25 * (by * (0.65 + 0.35 * zy) - overlap * step(zy, 0.0) * 0.6);
  f.agent = 1.25 * (ba * (0.65 - 0.35 * zy) - overlap * step(0.0, zy) * 0.6);
  f.seam = 0.0;
  return f;
}

// Ripples: two sources sending waves across the dots, interfering where both
// talk; thinking sets the sources circling so the waves curl into a spiral.
FcoForces fcoRipples(float2 uv, float ly, float la, float th, float t, float orbit, float body, float spread) {
  float a = orbit;
  float2 sy = mix(0.58, 0.32, th) * float2(-cos(a) * spread, -sin(a));
  float2 sa = -sy;
  float dy = length(uv - sy);
  float da = length(uv - sa);
  float k = 5.5 / body;
  float ey = 0.35 + 0.75 * max(ly, th * 0.8);
  float ea = 0.35 + 0.75 * max(la, th * 0.8);
  float wy = sin(k * dy - t * 5.0) * exp(-dy * 0.45) * ey;
  float wa = sin(k * da - t * 5.0 + 1.3) * exp(-da * 0.45) * ea;
  float total = wy + wa;
  float lit = 1.3 * smoothstep(-0.05, 0.6, total);
  float py = max(wy, 0.0);
  float pa = max(wa, 0.0);
  float share = py / max(py + pa, 1e-3);
  FcoForces f;
  f.you = lit * share + 1.1 * exp(-dy * dy / (0.07 * body)) * ey;
  f.agent = lit * (1.0 - share) + 1.1 * exp(-da * da / (0.07 * body)) * ea;
  f.seam = 0.0;
  return f;
}

// Comets: yours on an inner ring, the agent's on the outer, tails growing
// with each voice; talking together the rings close in and weave; thinking
// sends both racing round the rim.
float fcoComet(float2 uv, float ring, float head, float tail, float width) {
  float r = length(uv);
  float a = atan(uv.y, uv.x);
  float band = exp(-fcoSq(r - ring) / (width * width));
  float behind = fcoBehind(head, a);
  return band * (0.3 + 1.0 * exp(-behind / tail));
}

FcoForces fcoComets(float2 uv, float ly, float la, float th, float t, float orbit, float body, float spread) {
  float2 e = float2(uv.x / spread, uv.y);
  float both = fcoSat(min(ly, la) * 1.6);
  float ry = mix(mix(0.42, 0.56, both), 0.74, th);
  float ra = mix(mix(0.84, 0.7, both), 0.74, th);
  float hy = t * 1.3 + orbit * 1.6;
  float ha = -t * 1.05 + orbit * 1.6 + 3.14159 * th;
  float wy = (0.15 + 0.08 * ly) * body;
  float wa = (0.15 + 0.08 * la) * body;
  FcoForces f;
  f.you = fcoComet(e, ry, hy, 0.8 + 3.0 * ly + 1.2 * th, wy);
  f.agent = fcoComet(e, ra, ha, 0.8 + 3.0 * la + 1.2 * th, wa);
  f.seam = 0.0;
  return f;
}

FcoForces fcoForces(float2 uv, float spread) {
  float ly = levels.x;
  float la = levels.y;
  float th = levels.z;
  float t = clock.x;
  float orbit = clock.y;
  float body = mood.w;
  int take = int(clock.z + 0.5);
  FcoForces f;
  if (take == 1) { f = fcoTides(uv, ly, la, th, t, orbit, body, spread); }
  else if (take == 2) { f = fcoHelix(uv, ly, la, th, t, orbit, body, spread); }
  else if (take == 3) { f = fcoRipples(uv, ly, la, th, t, orbit, body, spread); }
  else if (take == 4) { f = fcoComets(uv, ly, la, th, t, orbit, body, spread); }
  else { f = fcoTwinVortex(uv, ly, la, th, t, orbit, body, spread); }
  // Quiet voices still show, dimmer; your muted force sinks further.
  float presenceY = (0.5 + 0.5 * fcoSat(ly * 1.4 + th * 0.7)) * (1.0 - 0.6 * mood.x);
  float presenceA = 0.5 + 0.5 * fcoSat(la * 1.4 + th * 0.7);
  float calm = (1.0 - 0.45 * levels.w) * (1.0 - 0.6 * mood.y) * (1.0 - 0.8 * mood.z);
  f.you = max(f.you, 0.0) * presenceY * calm;
  f.agent = max(f.agent, 0.0) * presenceA * calm;
  f.seam *= calm;
  return f;
}

// Needs input: an amber comet chasing round the rim, the whole rim breathing.
float fcoAlert(float2 uv, float spread) {
  float al = levels.w;
  if (al < 0.01) return 0.0;
  float2 e = float2(uv.x / spread, uv.y);
  float r = length(e);
  float a = atan(e.y, e.x);
  float rim = smoothstep(0.6, 0.92, r);
  float head = clock.x * 2.2;
  float chase = exp(-fcoBehind(head, a) / 1.4);
  return al * rim * (0.3 + 0.25 * sin(clock.x * 4.0) + 0.75 * chase);
}

// Connecting: a pearl beam scanning across, as the call bar's does.
float fcoBeam(float2 uv, float spread) {
  float co = mood.y;
  if (co < 0.01) return 0.0;
  float bx = 0.9 * spread * sin(clock.x * 2.6);
  return co * exp(-fcoSq(uv.x - bx) / 0.03);
}

float4 fcoOver(float4 src, float4 dst) {
  return src + dst * (1.0 - src.a);
}

float3 fcoColor(FcoForces f, float alert, float beam, float intensity, bool dark) {
  float shareY = f.you / max(f.you + f.agent, 1e-4);
  float3 base = mix(agent.rgb, you.rgb, shareY);
  float3 core = mix(agentCore.rgb, youCore.rgb, shareY);
  float3 c = mix(base, core, smoothstep(0.6, 1.4, intensity));
  float3 pearl = dark ? float3(0.96, 0.97, 1.0) : float3(0.22, 0.24, 0.3);
  float3 amber = dark ? float3(1.0, 0.72, 0.36) : float3(0.86, 0.45, 0.08);
  float total = max(intensity, 1e-4);
  c = mix(c, pearl, fcoSat((f.seam * 0.8 + beam) / total));
  c = mix(c, amber, fcoSat(alert / total));
  return c;
}

half4 main(float2 p) {
  float W = size.x;
  float H = size.y;
  float pitch = size.z;
  float sc = size.w;
  bool dark = clock.w > 0.5;
  float2 C = float2(W, H) * 0.5;
  float R = min(W, H) * 0.5;
  float spread = max(1.0, (W / H) * 0.8);

  // The base fills the whole glass, edge to edge.
  float3 baseTint = dark ? float3(0.05, 0.055, 0.07) : float3(0.97, 0.97, 0.98);
  float4 outColor = float4(baseTint * look.z, look.z);

  // A soft glow of the same forces under the dots.
  float2 uvp = (p - C) / R;
  FcoForces g = fcoForces(uvp, spread);
  float ga = fcoAlert(uvp, spread);
  float gb = fcoBeam(uvp, spread);
  float gi = g.you + g.agent + g.seam + ga + gb;
  float glowA = look.y * 0.75 * smoothstep(0.0, 1.2, gi);
  outColor = fcoOver(float4(fcoColor(g, ga, gb, gi, dark) * glowA, glowA), outColor);

  // The dot this pixel belongs to, on a grid centred in the box.
  float2 cell = floor((p - C) / pitch + 0.5);
  float2 cc = C + cell * pitch;
  float2 uv = (cc - C) / R;
  FcoForces f = fcoForces(uv, spread);
  float alert = fcoAlert(uv, spread);
  float beam = fcoBeam(uv, spread);
  float intensity = f.you + f.agent + f.seam + alert + beam;
  float lit = smoothstep(0.06, 0.85, intensity);
  float fill = look.x;
  float radius = pitch * 0.5 * fill * mix(0.55, 0.62 + 0.38 * fcoSat(intensity), lit);
  float d = length(p - cc);
  float coverage = 1.0 - smoothstep(radius - sc * 0.75, radius + sc * 0.5, d);
  float3 off = dark ? float3(1.0) : float3(0.0);
  float offA = dark ? 0.1 : 0.08;
  float3 color = mix(off, fcoColor(f, alert, beam, intensity, dark), lit);
  float a = coverage * mix(offA, 1.0, lit);
  outColor = fcoOver(float4(color * a, a), outColor);
  return half4(outColor);
}
"""
}
