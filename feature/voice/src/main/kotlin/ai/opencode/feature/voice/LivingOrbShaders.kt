package ai.opencode.feature.voice

/**
 * Port of `LivingOrbShaders.metal`'s `livingOrbFragment` as an AGSL `RuntimeShader`.
 *
 * DotScope's LED transfer, adapted to a circular field with tunable dot size and phosphor halo.
 * As in [DotScopeShaders], the lit field ([LivingOrbField.render]) arrives as a
 * `columns × rows` RGBA_F16 bitmap (`x` you, `y` agent, `z` freshness) bound with
 * `RuntimeShader.setInputBuffer`, instead of Metal's `device float4 *` buffer. The uniforms
 * keep Metal's `LivingOrbUniforms` layout, one `float4` each.
 */
object LivingOrbShaders {
    const val livingOrbFragment: String = """
uniform shader field;
uniform float4 grid;     // pitch (px), columns, rows, scale (px per pt)
uniform float4 origin;   // first cell (px), orb size (pt)
uniform float4 look;     // dark, dot size, glow, unlit
uniform float4 colour;   // tint rgb, ended (0.1) or live (1)

half4 main(float2 position) {
  float pitch = grid.x;
  float sc = grid.w;
  float2 cell = floor((position - origin.xy) / pitch);
  if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= grid.y || cell.y >= grid.z) return half4(0.0);
  float2 centre = origin.xy + (cell + 0.5) * pitch;
  float d = length(position - centre);
  float4 f = float4(field.eval(cell + 0.5));
  float r = min(origin.z, origin.w) * 0.5;
  float edge = r - length(centre / sc - origin.zw * 0.5);
  float mask = smoothstep(2.0, 6.5, edge);
  float P = clamp(1.0 - (1.0 - min(f.x, 1.0)) * (1.0 - min(f.y, 1.0)), 0.0, 1.0);
  bool dark = look.x > 0.5;
  float a0 = (dark ? 0.06 : 0.075) * look.w;
  float alpha = a0 + ((dark ? 0.96 : 1.0) - a0) * pow(max(P, 1e-5), (dark ? 0.9 : 0.75) * mix(1.4, 1.0, f.z));
  float rad = (0.25 + 0.17 * pow(max(P, 1e-5), 0.6)) * mix(0.82, 1.0, f.z) * pitch * look.y;
  float coverage = clamp(rad - d + 0.5, 0.0, 1.0);
  float halo = look.z * P * P * exp(-max(d - rad, 0.0) / (0.12 * pitch)) * (1.0 - coverage);
  alpha = clamp((alpha * coverage + halo) * mask * colour.w, 0.0, 1.0);
  // Keep the core tinted: a speaking cyan must not bleach back to white.
  float3 c = dark ? mix(colour.rgb * 0.8, colour.rgb, P) : colour.rgb * 0.45;
  return half4(float4(c * alpha, alpha));
}
"""
}
