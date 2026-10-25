package ai.opencode.feature.voice

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What the Living Orb shows (iOS `LivingOrbState`). */
@Suppress("EnumEntryName")
enum class LivingOrbState {
    idle, listening, thinking, speaking;

    companion object {
        fun resolve(mood: FloatingCallMood, speaking: Boolean, userLevel: Double, muted: Boolean, scene: FloatingCallScene?): LivingOrbState {
            if (scene != null) {
                return when (scene) {
                    FloatingCallScene.idle -> idle
                    FloatingCallScene.listening -> if (muted) idle else listening
                    FloatingCallScene.agentSpeaking, FloatingCallScene.bothSpeaking -> LivingOrbState.speaking
                    FloatingCallScene.thinking, FloatingCallScene.needsInput -> thinking
                }
            }
            if (speaking) return LivingOrbState.speaking
            if (!muted && userLevel > 0.06) return listening
            if (mood == FloatingCallMood.thinking || mood == FloatingCallMood.connecting || mood == FloatingCallMood.needsInput) return thinking
            return idle
        }
    }
}

/** An RGB colour as the field and shader use it (iOS `SIMD3<Float>`). */
data class LivingOrbColour(val r: Float, val g: Float, val b: Float) {
    operator fun plus(o: LivingOrbColour) = LivingOrbColour(r + o.r, g + o.g, b + o.b)
    operator fun minus(o: LivingOrbColour) = LivingOrbColour(r - o.r, g - o.g, b - o.b)
    operator fun times(k: Float) = LivingOrbColour(r * k, g * k, b * k)
}

/**
 * Immutable snapshots: resolve tuning once per draw, never once per LED (iOS `LivingOrbLook`).
 * The tunables are baked ([LivingOrbTunables]), so one look serves every draw.
 */
data class LivingOrbLook(
    val pitch: Double = LivingOrbTunables.pitch,
    val dotSize: Double = LivingOrbTunables.dotSize,
    val glow: Double = LivingOrbTunables.glow,
    val unlit: Double = LivingOrbTunables.unlit,
    val density: Double = LivingOrbTunables.density,
    val brightness: Double = LivingOrbTunables.brightness,
    val contrast: Double = LivingOrbTunables.contrast,
    val drift: Double = LivingOrbTunables.drift,
    val response: Double = LivingOrbTunables.response,
    val idleEnergy: Double = LivingOrbTunables.idleEnergy,
    val floor: Double = LivingOrbTunables.floor,
    val thinkingSpeed: Double = LivingOrbTunables.thinkingSpeed,
    val thinkingLight: Double = LivingOrbTunables.thinkingLight,
    val attack: Double = LivingOrbTunables.attack,
    val release: Double = LivingOrbTunables.release,
    val fastDecay: Double = LivingOrbTunables.fastDecay,
    val afterglow: Double = LivingOrbTunables.afterglow,
    val trailMix: Double = LivingOrbTunables.trailMix,
    val rotationX: Double = LivingOrbTunables.rotationX,
    val rotationY: Double = LivingOrbTunables.rotationY,
    val voiceOffset: Double = LivingOrbTunables.voiceOffset,
    val polarFolds: Int = LivingOrbTunables.polarFolds,
    val polarDepth: Double = LivingOrbTunables.polarDepth,
    val polarSpeed: Double = LivingOrbTunables.polarSpeed,
    val polarCross: Double = LivingOrbTunables.polarCross,
    val cloudWarp: Double = LivingOrbTunables.cloudWarp,
    val cloudScale: Double = LivingOrbTunables.cloudScale,
    val cloudAspect: Double = LivingOrbTunables.cloudAspect,
    val ribbonSlant: Double = LivingOrbTunables.ribbonSlant,
    val ribbonCross: Double = LivingOrbTunables.ribbonCross,
    val ribbonSpeed: Double = LivingOrbTunables.ribbonSpeed,
    val colourFade: Double = LivingOrbTunables.colourFade,
    val palette: List<LivingOrbColour> = defaultPalette(),
) {
    fun colour(state: LivingOrbState): LivingOrbColour = when (state) {
        LivingOrbState.idle -> palette[0]
        LivingOrbState.listening -> palette[1]
        LivingOrbState.thinking -> palette[2]
        LivingOrbState.speaking -> palette[3]
    }

    companion object {
        /** The baked look every orb draws with. */
        val baked = LivingOrbLook()

        private fun defaultPalette(): List<LivingOrbColour> {
            val t = LivingOrbTunables
            val mix = if (t.colours) t.colourMix else 0.0
            return listOf(
                colour(hue = t.idleHue, saturation = t.idleSaturation * mix, intensity = t.idleIntensity),
                colour(hue = t.listeningHue, saturation = t.listeningSaturation * mix, intensity = t.listeningIntensity),
                colour(hue = t.thinkingHue, saturation = t.thinkingSaturation * mix, intensity = t.thinkingIntensity),
                colour(hue = t.speakingHue, saturation = t.speakingSaturation * mix, intensity = t.speakingIntensity),
            )
        }

        private fun colour(hue: Double, saturation: Double, intensity: Double): LivingOrbColour {
            fun channel(offset: Double): Float {
                // Swift's `truncatingRemainder` keeps the dividend's sign, as Kotlin's `%` does.
                val k = (offset + hue * 6) % 6
                return (intensity * (1 - saturation * max(0.0, minOf(k, 4 - k, 1.0)))).toFloat()
            }
            return LivingOrbColour(channel(5.0), channel(3.0), channel(1.0))
        }
    }
}

/**
 * A renderer-owned simulation (iOS `LivingOrbField`). No stores, tasks, audio I/O or per-frame
 * actions: [LivingOrbRenderer] steps it each frame and reads the lit field.
 */
class LivingOrbField {
    var phase = 0.0
        private set
    var you = 0.0
        private set
    var agent = 0.0
        private set
    var thinking = 0.0
        private set
    var tint = LivingOrbColour(0.8f, 0.8f, 0.8f)
        private set
    private var fast = FloatArray(0)
    private var slow = FloatArray(0)
    private var initialized = false

    /** [levels] is (you, agent). */
    fun step(dt: Double, levels: Pair<Double, Double>, state: LivingOrbState, still: Boolean, look: LivingOrbLook) {
        val dt = max(0.0, min(0.1, dt))
        val target = look.colour(state)
        if (still) {
            // No audio-dependent redraw: each semantic state has a stable still pose.
            phase = 0.0; you = if (state == LivingOrbState.listening) 0.4 else 0.0; agent = if (state == LivingOrbState.speaking) 0.5 else 0.0
            thinking = if (state == LivingOrbState.thinking) 1.0 else 0.0; tint = target
            initialized = true
            return
        }
        fun follow(a: Double, b: Double): Double = a + (b - a) * (1 - exp(-dt / (if (b > a) look.attack else look.release)))
        you = follow(you, levels.first.coerceIn(0.0, 1.0))
        agent = follow(agent, levels.second.coerceIn(0.0, 1.0))
        thinking += ((if (state == LivingOrbState.thinking) 1.0 else 0.0) - thinking) * (1 - exp(-dt / look.colourFade))
        phase += dt * (look.drift + (you + agent) * look.response + thinking * look.thinkingSpeed)
        tint = if (initialized) tint + (target - tint) * (1 - exp(-dt / look.colourFade)).toFloat() else target
        initialized = true
    }

    /** Lights [field] (`x` you, `y` agent, `z` freshness, `w` 0 per dot, row-major) for [grid]. */
    fun render(grid: DotScopeGrid, style: LivingOrbStyle, dt: Double, still: Boolean, look: LivingOrbLook, field: FloatArray) {
        val count = grid.count
        if (fast.size != count * 2) { fast = FloatArray(count * 2); slow = FloatArray(count * 2) }
        val fastFall = exp(-dt / look.fastDecay).toFloat()
        val slowFall = exp(-dt / look.afterglow).toFloat()
        val radius = min(grid.width, grid.height).toDouble() * 0.5
        val trail = look.trailMix.toFloat()
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.columns) {
                val i = row * grid.columns + col
                val x = (grid.columnX(col.toFloat()).toDouble() - grid.width.toDouble() * 0.5) / radius
                val y = (((grid.originY + (row + 0.5f) * grid.pitch) / grid.scale).toDouble() - grid.height.toDouble() * 0.5) / radius
                val inX = excitation(x, y, you, 0.0, style, look).toFloat()
                val inY = excitation(x, y, agent, look.voiceOffset, style, look).toFloat()
                val f = i * 2
                fast[f] = if (still) inX else max(inX, fast[f] * fastFall)
                fast[f + 1] = if (still) inY else max(inY, fast[f + 1] * fastFall)
                slow[f] = if (still) inX else max(inX, slow[f] * slowFall)
                slow[f + 1] = if (still) inY else max(inY, slow[f + 1] * slowFall)
                val ex = fast[f] * (1 - trail) + slow[f] * trail
                val ey = fast[f + 1] * (1 - trail) + slow[f + 1] * trail
                val fresh = min(1f, (inX + inY) / max(0.001f, ex + ey))
                val o = i * 4
                field[o] = ex; field[o + 1] = ey; field[o + 2] = fresh; field[o + 3] = 0f
            }
        }
    }

    private fun excitation(x: Double, y: Double, voice: Double, offset: Double, style: LivingOrbStyle, look: LivingOrbLook): Double {
        val r = hypot(x, y)
        if (r >= 1) return 0.0
        val t = phase + offset
        val z = sqrt(max(0.0, 1 - r * r))
        val u = x * cos(t * look.rotationX) + z * sin(t * look.rotationX)
        val v = y * cos(t * look.rotationY) - z * sin(t * look.rotationY)
        val wave = pattern(style, x = x, y = y, r = r, u = u, v = v, t = t, p = look)
        val body = max(0.0, 0.5 + 0.5 * wave).pow(look.contrast)
        val work = thinking * (0.5 + 0.5 * cos(atan2(y, x) - phase)) * r
        return min(1.0, (look.floor + body * (look.idleEnergy + voice * look.response) + work * look.thinkingLight) * look.brightness)
    }

    companion object {
        fun pattern(style: LivingOrbStyle, x: Double, y: Double, r: Double, u: Double, v: Double, t: Double, p: LivingOrbLook): Double = when (style) {
            // Integral angular harmonics close the seam; radial attenuation removes the angular
            // singularity at the centre.
            LivingOrbStyle.polarBloom ->
                sin(p.density * r - t * p.polarSpeed + p.polarDepth * r * sin(p.polarFolds.toDouble() * atan2(y, x) + t)) * cos(p.polarCross * u - t * 0.6)
            LivingOrbStyle.livingClouds ->
                sin(p.density * u + p.cloudWarp * sin(p.cloudScale * v + t)) * cos(p.density * v * p.cloudAspect - t)
            LivingOrbStyle.silkRibbons ->
                sin(p.density * (u + v * p.ribbonSlant) + t) * cos(p.density * (v - u * p.ribbonCross) - t * p.ribbonSpeed)
        }
    }
}
