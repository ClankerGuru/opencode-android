package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import android.graphics.BlendMode
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withClip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The floating call's face: what draws inside the orb's surface (and, wider, in its panel's
// strip). Each style keeps the two voices that compete, warm for you and cool for the agent, in
// a shape that suits a round piece of glass. Borrowed from Siri's iOS 18 to 27 orb and glow
// (principles only): layered colour that swirls rather than ticks, a restrained palette, light
// that spills onto what's around it, and motion that answers the voice.

/** Which face the orb wears (iOS `FloatingCallOrbStyle`). */
@Suppress("EnumEntryName")
enum class FloatingCallOrbStyle {
    /** Two gooey blobs of colour, yours and the agent's, that swell, drift and merge as you talk over each other. */
    liquid,

    /** Siri-like: swirling, softly blurred colour with a glowing rim. */
    aurora,

    /** Two concentric rings that breathe with each voice; an arc spins while working. */
    rings,

    /** Round 1's LED dot braid. */
    dots,

    /** The app's GPU activity orb. */
    activity,

    // Dot-matrix takes (round 3): big LED dots lit by two forces with body, yours in warm pearl
    // and the agent's in cool pearl ([FloatingOrbShaders]).

    /** Two counter-rotating spiral vortices circling each other. */
    vortex,

    /** Two masses of dots that swell and push against each other. */
    tides,

    /** The scope's braid with body: two wide bands twisting front over back. */
    helix,

    /** Two sources sending waves across the dots that interfere. */
    ripples,

    /** Two comets on rings, tails growing with each voice. */
    comets,
    ;

    /** The shader's take, for the dot-matrix styles. */
    val dotTake: Int?
        get() = when (this) {
            vortex -> 0
            tides -> 1
            helix -> 2
            ripples -> 3
            comets -> 4
            liquid, aurora, rings, dots, activity -> null
        }

    companion object {
        val dotTakes: List<FloatingCallOrbStyle> = listOf(vortex, tides, helix, ripples, comets)

        fun fromRawValue(rawValue: String): FloatingCallOrbStyle? = entries.firstOrNull { it.name == rawValue }
    }
}

/** What the panel's wide strip shows (iOS `FloatingCallStripStyle`). */
@Suppress("EnumEntryName")
enum class FloatingCallStripStyle {
    /** The call bar's full LED dot scope. */
    scope,

    /** The orb's own face, widened. */
    matching,
}

/** What the call is doing, as the face shows it (iOS `FloatingCallMood`). */
@Suppress("EnumEntryName")
enum class FloatingCallMood { connecting, live, thinking, needsInput, ended }

/** Playground scenes: they stand in for the call's voices and state (iOS `FloatingCallScene`). */
@Suppress("EnumEntryName")
enum class FloatingCallScene { idle, listening, agentSpeaking, bothSpeaking, thinking, needsInput }

/** The two voices' colours (iOS `FloatingCallPalette`). */
object FloatingCallPalette {
    fun warm(dark: Boolean): List<Color> = if (dark) {
        listOf(Color(1.0f, 0.72f, 0.52f), Color(1.0f, 0.42f, 0.56f))
    } else {
        listOf(Color(0.98f, 0.55f, 0.32f), Color(0.93f, 0.26f, 0.45f))
    }

    fun cool(dark: Boolean): List<Color> = if (dark) {
        listOf(Color(0.5f, 0.84f, 1.0f), Color(0.55f, 0.47f, 1.0f))
    } else {
        listOf(Color(0.2f, 0.62f, 0.95f), Color(0.42f, 0.32f, 0.92f))
    }

    /** Siri's restrained four: orange, pink, violet, blue. */
    fun aurora(dark: Boolean): List<Color> = if (dark) {
        listOf(Color(1.0f, 0.62f, 0.35f), Color(1.0f, 0.38f, 0.62f), Color(0.68f, 0.42f, 1.0f), Color(0.32f, 0.66f, 1.0f))
    } else {
        listOf(Color(0.98f, 0.5f, 0.2f), Color(0.95f, 0.26f, 0.52f), Color(0.55f, 0.3f, 0.95f), Color(0.18f, 0.5f, 0.95f))
    }

    /** iOS `Color.orange` (`systemOrange`, light and dark). */
    fun alert(dark: Boolean): Color = if (dark) Color(1f, 0.624f, 0.039f) else Color(1f, 0.584f, 0f)
}

/**
 * The face's motion, stepped each frame: the voices through followers (quick to rise, slow to
 * fall, never jumping with each audio buffer), the call's state eased in and out, and a clock
 * that runs faster as the call gets busier (iOS `FloatingCallMotion`). A plain object; main
 * thread only.
 */
class FloatingCallMotion {
    var you = 0.0; private set
    var agent = 0.0; private set
    var thinking = 0.0; private set
    var alert = 0.0; private set
    var connecting = 0.0; private set
    var ended = 0.0; private set
    var muted = 0.0; private set

    /** Seconds of face time: the swirl's clock. */
    var phase = 0.0; private set

    /** The working arc's angle. */
    var spin = 0.0; private set

    /**
     * How far thinking has turned the figures: it advances only as fast as thinking is shown,
     * so easing into it never whips through old turns.
     */
    var orbit = 0.0; private set
    private var last: Double? = null

    // Hot loop: the meter resolved once.
    private val meter = VoiceAudioLevels.shared

    fun step(time: Double, still: Boolean, mood: FloatingCallMood, isMuted: Boolean, scene: FloatingCallScene?) {
        if (still) {
            you = 0.35; agent = 0.45; phase = 0.0; spin = 0.0; orbit = 0.0; last = null
            thinking = if (mood == FloatingCallMood.thinking) 1.0 else 0.0
            alert = if (mood == FloatingCallMood.needsInput) 1.0 else 0.0
            connecting = if (mood == FloatingCallMood.connecting) 1.0 else 0.0
            ended = if (mood == FloatingCallMood.ended) 1.0 else 0.0
            muted = if (isMuted) 1.0 else 0.0
            return
        }
        // iOS scales by `AnimationTimeScale` (a lab hook); real time here.
        val dt = min(0.1, time - (last ?: time))
        last = time
        val target = levels(time, scene)
        fun follow(current: Double, target: Double): Double = current + (target - current) * min(1.0, (if (target > current) 14.0 else 4.0) * dt)
        fun ease(current: Double, on: Boolean, rate: Double = 5.0): Double = current + ((if (on) 1.0 else 0.0) - current) * min(1.0, rate * dt)
        you = follow(you, if (isMuted) 0.0 else target.first)
        agent = follow(agent, target.second)
        val shown = scene?.let(::mood) ?: mood
        thinking = ease(thinking, shown == FloatingCallMood.thinking)
        alert = ease(alert, shown == FloatingCallMood.needsInput)
        connecting = ease(connecting, shown == FloatingCallMood.connecting, rate = 3.0)
        ended = ease(ended, shown == FloatingCallMood.ended, rate = 6.0)
        muted = ease(muted, isMuted)
        val idle = FloatingCallTunables.idleSpeed
        val gain = FloatingCallTunables.speakingGain
        val work = FloatingCallTunables.thinkingSpeed
        phase += dt * (idle + gain * (you + agent) * 1.4 + thinking * work + connecting * 0.8) * (1 - ended * 0.9)
        spin += dt * (1.6 + 2.4 * thinking) * work
        orbit += dt * 4 * work * thinking
    }

    private fun levels(time: Double, scene: FloatingCallScene?): Pair<Double, Double> {
        if (scene == null) {
            val live = meter.snapshot()
            return live.user.toDouble() to live.assistant.toDouble()
        }
        val you = VoiceScriptedDemo.continuousSpeechLevel(time, rate = 4.5, seed = 0.4).toDouble()
        val agent = VoiceScriptedDemo.continuousSpeechLevel(time, rate = 4.2, seed = 1.9).toDouble()
        return when (scene) {
            FloatingCallScene.listening -> you to 0.02
            FloatingCallScene.agentSpeaking -> 0.02 to agent
            FloatingCallScene.bothSpeaking -> you to agent
            FloatingCallScene.thinking, FloatingCallScene.needsInput -> 0.03 to 0.05
            FloatingCallScene.idle -> 0.0 to 0.0
        }
    }

    companion object {
        fun mood(scene: FloatingCallScene): FloatingCallMood = when (scene) {
            FloatingCallScene.thinking -> FloatingCallMood.thinking
            FloatingCallScene.needsInput -> FloatingCallMood.needsInput
            FloatingCallScene.idle, FloatingCallScene.listening, FloatingCallScene.agentSpeaking, FloatingCallScene.bothSpeaking -> FloatingCallMood.live
        }
    }
}

/**
 * The working state iOS's `.activity` face hands `AgentActivityGlyph` (a `WorkingActivity`):
 * needs input, just started (connecting), or done (ended).
 */
data class FloatingCallActivity(val needsInput: Boolean, val isConnecting: Boolean, val isDone: Boolean)

/**
 * The `.activity` face's drawing: iOS `AgentActivityGlyph(source: .session(activity), size:,
 * isPaused:)` lives in Session (above Voice), so Session provides it here. Without one, the
 * `.activity` face draws round 1's dot glyph instead. Not the default face.
 */
val LocalFloatingCallActivityGlyph = staticCompositionLocalOf<(@Composable (activity: FloatingCallActivity, size: Dp, isPaused: Boolean) -> Unit)?> { null }

/**
 * The orb's (or strip's) face: a circle for the orb, a capsule for the docked orb and the
 * panel's strip, by [cornerFraction] of the shorter side (iOS `FloatingCallFace`). Still under
 * Reduce Motion; nothing draws while [isHidden] or the app isn't resumed.
 *
 * @param scene a playground scene standing in for the call's voices and state.
 * @param isHidden faded out behind the other state: no drawing.
 */
@Composable
fun FloatingCallFace(
    style: FloatingCallOrbStyle,
    mood: FloatingCallMood,
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    scene: FloatingCallScene? = null,
    cornerFraction: Float = 0.5f,
    isHidden: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    Box(modifier.clearAndSetSemantics {}) {
        // A new style crossfades in rather than popping.
        Crossfade(
            targetState = style,
            animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else Motion.easeInOut(0.35),
            label = "floatingCallFace",
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                when (shown) {
                    FloatingCallOrbStyle.dots -> FloatingCallGlyph(
                        phase = when (mood) {
                            FloatingCallMood.connecting -> VoiceFeature.State.Phase.Connecting
                            FloatingCallMood.ended -> VoiceFeature.State.Phase.Ended
                            else -> VoiceFeature.State.Phase.Live
                        },
                        isMuted = isMuted,
                        isReconnecting = false,
                        modifier = Modifier.fillMaxSize(),
                        isHidden = isHidden,
                    )
                    FloatingCallOrbStyle.activity -> FloatingCallActivityFace(mood, isMuted, scene, isHidden)
                    FloatingCallOrbStyle.vortex, FloatingCallOrbStyle.tides, FloatingCallOrbStyle.helix,
                    FloatingCallOrbStyle.ripples, FloatingCallOrbStyle.comets,
                    -> FloatingCallDotFace(
                        config = FloatingOrbConfig(take = shown.dotTake ?: 0, mood = mood, isMuted = isMuted, scene = scene),
                        modifier = Modifier.fillMaxSize(),
                        isHidden = isHidden,
                    )
                    FloatingCallOrbStyle.liquid, FloatingCallOrbStyle.aurora, FloatingCallOrbStyle.rings ->
                        FloatingCallCanvasFace(shown, mood, isMuted, scene, cornerFraction, isHidden)
                }
            }
        }
    }
}

@Composable
private fun FloatingCallActivityFace(mood: FloatingCallMood, isMuted: Boolean, scene: FloatingCallScene?, isHidden: Boolean) {
    val glyph = LocalFloatingCallActivityGlyph.current
    if (glyph == null) {
        FloatingCallGlyph(
            phase = if (mood == FloatingCallMood.connecting) VoiceFeature.State.Phase.Connecting else if (mood == FloatingCallMood.ended) VoiceFeature.State.Phase.Ended else VoiceFeature.State.Phase.Live,
            isMuted = isMuted,
            isReconnecting = false,
            modifier = Modifier.fillMaxSize(),
            isHidden = isHidden,
        )
        return
    }
    val shown = scene?.let(FloatingCallMotion::mood) ?: mood
    val activity = FloatingCallActivity(
        needsInput = shown == FloatingCallMood.needsInput,
        isConnecting = shown == FloatingCallMood.connecting,
        isDone = shown == FloatingCallMood.ended,
    )
    // A touch larger than the glass, so its dots reach the edge.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        glyph(activity, min(maxWidth.value, maxHeight.value).dp * 1.12f, isHidden)
    }
}

/** The liquid, aurora and rings faces: drawn each frame by [FloatingCallRender]. */
@Composable
private fun FloatingCallCanvasFace(
    style: FloatingCallOrbStyle,
    mood: FloatingCallMood,
    isMuted: Boolean,
    scene: FloatingCallScene?,
    cornerFraction: Float,
    isHidden: Boolean,
) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val dark = OpenCodeTheme.colors.isDark
    val density = LocalDensity.current.density
    val motion = remember { FloatingCallMotion() }
    val render = remember { FloatingCallRender() }
    var frame by remember { mutableIntStateOf(0) }
    val still = reduceMotion || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val paused = still || isHidden
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (true) withFrameNanos { frame++ }
    }
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") frame
        motion.step(System.nanoTime() / 1e9, still, mood, isMuted, scene)
        when (style) {
            FloatingCallOrbStyle.liquid -> render.liquid(this, motion, dark, cornerFraction, density)
            FloatingCallOrbStyle.aurora -> render.aurora(this, motion, dark, cornerFraction, density)
            else -> render.rings(this, motion, dark, cornerFraction, density)
        }
    }
}

/**
 * The Canvas styles (iOS `FloatingCallRender`). Each lays its figures out in a box of any
 * aspect: round in a circle, spread along the length of a capsule, yours toward the leading end
 * and the agent's toward the trailing end.
 *
 * Android: SwiftUI's layer filters (blur, alpha threshold, saturation) become [RenderEffect]s on
 * [RenderNode]s drawn into the hardware canvas; without hardware acceleration the layers draw
 * unfiltered. Sizes are pixels; point constants (blur radii, line widths) scale by `density`.
 * One per mounted face; it reuses its paints and nodes across frames.
 */
class FloatingCallRender {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint()
    private val nodes = HashMap<String, RenderNode>()

    private fun node(name: String, w: Float, h: Float): RenderNode =
        nodes.getOrPut(name) { RenderNode(name) }.also { it.setPosition(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)) }

    private fun Color.withAlpha(alpha: Double): Int = copy(alpha = (this.alpha * alpha).toFloat().coerceIn(0f, 1f)).toArgb()

    private fun roundRect(canvas: android.graphics.Canvas, w: Float, h: Float, radius: Float, p: Paint) =
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, p)

    /** Draws [body] into a node with [effect] (or directly, without hardware acceleration). */
    private fun filtered(canvas: android.graphics.Canvas, name: String, w: Float, h: Float, effect: RenderEffect?, body: (android.graphics.Canvas) -> Unit) {
        if (!canvas.isHardwareAccelerated) { body(canvas); return }
        val node = node(name, w, h)
        node.setRenderEffect(effect)
        val recording = node.beginRecording()
        try { body(recording) } finally { node.endRecording() }
        canvas.drawRenderNode(node)
    }

    private fun blur(radius: Float): RenderEffect = RenderEffect.createBlurEffect(max(0.01f, radius), max(0.01f, radius), Shader.TileMode.DECAL)

    fun liquid(scope: DrawScope, motion: FloatingCallMotion, dark: Boolean, corner: Float, density: Float) = scope.drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        val w = scope.size.width
        val h = scope.size.height
        val r0 = min(w, h)
        val wide = w > h * 1.5f
        val t = motion.phase
        val warmMix = FloatingCallTunables.warm
        val coolMix = FloatingCallTunables.cool
        val goo = r0 * FloatingCallTunables.goo.toFloat() * (if (wide) 0.6f else 1f)
        val radius = min(w, h) * corner
        val clip = android.graphics.Path().apply { addRoundRect(0f, 0f, w, h, radius, radius, android.graphics.Path.Direction.CW) }
        canvas.withClip(clip) {
        // Both colours wash the whole glass, edge to edge, under the blobs.
        val warmWash = FloatingCallPalette.warm(dark)
        val coolWash = FloatingCallPalette.cool(dark)
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, 0f, w, h, warmWash[1].withAlpha(0.42 * warmMix), coolWash[1].withAlpha(0.42 * coolMix), Shader.TileMode.CLAMP)
        roundRect(canvas, w, h, radius, paint)

        fun blobs(warm: Boolean): List<Triple<Float, Float, Float>> {
            val level = if (warm) motion.you else motion.agent
            val rival = if (warm) motion.agent else motion.you
            val seed = if (warm) 0.0 else 2.3
            val home = if (warm) -1.0 else 1.0
            // Speaking pulls a voice's blobs toward the middle (they compete for it); thinking
            // sets them orbiting; idle, they drift at home.
            val pull = min(1.0, level * 1.6) * 0.55
            val push = min(1.0, rival * 1.2) * 0.2
            return (0 until 3).map { i ->
                val k = i.toDouble()
                val a = t * (0.9 + 0.35 * k) + seed + k * 2.1 + motion.orbit
                val orbit = 0.18 + 0.08 * sin(t * 0.7 + k)
                var nx = home * (0.32 - pull + push) + cos(a) * orbit
                var ny = sin(a * 1.13) * orbit * 1.2
                if (motion.thinking > 0.01) {
                    val ring = motion.spin + (if (warm) 0.0 else PI) + k * 2.09
                    nx = nx * (1 - motion.thinking) + cos(ring) * 0.34 * motion.thinking
                    ny = ny * (1 - motion.thinking) + sin(ring) * 0.34 * motion.thinking
                }
                val cx = if (wide) w / 2 + (nx * w * 0.42).toFloat() else w / 2 + (nx * r0 * 0.5).toFloat()
                val cy = if (wide) h / 2 + (ny * h * 0.5).toFloat() else h / 2 + (ny * r0 * 0.5).toFloat()
                val mutedScale = if (warm) 1 - 0.55 * motion.muted else 1.0
                val r = r0 * (0.22 + 0.06 * (if (i == 0) 1.0 else 0.6) + 0.14 * min(1.0, level * 1.4)) * mutedScale * (1 - 0.6 * motion.ended)
                Triple(cx, cy, r.toFloat())
            }
        }

        fun family(warm: Boolean, mix: Double) {
            val colors = if (warm) FloatingCallPalette.warm(dark) else FloatingCallPalette.cool(dark)
            val figures = blobs(warm)
            val level = if (warm) motion.you else motion.agent
            val opacity = min(1.0, (0.55 + 0.45 * min(1.0, level * 1.5)) * mix) * (1 - 0.5 * motion.ended)
            layerPaint.reset()
            layerPaint.alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
            layerPaint.blendMode = if (warm) null else if (dark) BlendMode.SCREEN else BlendMode.MULTIPLY
            canvas.saveLayer(0f, 0f, w, h, layerPaint)
            // The blobs' gooey union (blurred, then thresholded) masks the colour.
            filtered(canvas, if (warm) "warmMask" else "coolMask", w, h, RenderEffect.createColorFilterEffect(alphaThreshold, blur(max(1f, goo)))) { mask ->
                paint.reset(); paint.isAntiAlias = true; paint.color = android.graphics.Color.WHITE
                for ((x, y, r) in figures) mask.drawCircle(x, y, r, paint)
            }
            paint.reset(); paint.isAntiAlias = true
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val (cx, cy, _) = figures[0]
            paint.shader = RadialGradient(cx, cy, max(0.01f, r0 * 0.75f), colors.map { it.toArgb() }.toIntArray(), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            canvas.restore()
        }
        family(warm = true, mix = warmMix * (1 - 0.6 * motion.alert))
        family(warm = false, mix = coolMix * (1 - 0.6 * motion.alert))
        if (motion.alert > 0.01) {
            val pulse = 0.5 + 0.5 * sin(motion.phase * 4)
            val alert = FloatingCallPalette.alert(dark)
            paint.reset(); paint.isAntiAlias = true
            paint.shader = RadialGradient(w / 2, h / 2, max(0.01f, r0 * 0.55f), alert.withAlpha(0.75 * motion.alert), alert.withAlpha(0.2 * motion.alert * pulse), Shader.TileMode.CLAMP)
            roundRect(canvas, w, h, radius, paint)
        }
        if (motion.connecting > 0.01) sweep(canvas, w, h, motion, dark)
        // A soft specular top-leading highlight, like a lit sphere.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(w * 0.32f, h * 0.26f, max(0.01f, r0 * 0.45f), Color.White.withAlpha(if (dark) 0.16 else 0.3), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        roundRect(canvas, w, h, radius, paint)
        }
    }

    fun aurora(scope: DrawScope, motion: FloatingCallMotion, dark: Boolean, corner: Float, density: Float) = scope.drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        val w = scope.size.width
        val h = scope.size.height
        val r0 = min(w, h)
        val wide = w > h * 1.5f
        val t = motion.phase
        val colors = FloatingCallPalette.aurora(dark)
        val radius = min(w, h) * corner
        val saturation = FloatingCallTunables.saturation.toFloat()
        val clip = android.graphics.Path().apply { addRoundRect(0f, 0f, w, h, radius, radius, android.graphics.Path.Direction.CW) }
        canvas.withClip(clip) {
        // A base of all four, so the blur never fades to an empty edge.
        paint.reset(); paint.isAntiAlias = true
        paint.shader = conic(w / 2, h / 2, (colors + colors[0]).map { it.withAlpha(0.55) }.toIntArray(), -t * 0.4)
        roundRect(canvas, w, h, radius, paint)
        // Orange and pink are yours, violet and blue the agent's: each swells and brightens
        // with its voice and swirls on its own clock.
        val saturate = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
        filtered(canvas, "aurora", w, h, RenderEffect.createColorFilterEffect(saturate, blur(r0 * 0.2f))) { layer ->
            for ((i, color) in colors.withIndex()) {
                val warm = i < 2
                val level = if (warm) motion.you else motion.agent
                val k = i.toDouble()
                val a = t * (0.6 + 0.22 * k) * (if (k % 2 == 0.0) 1 else -1) + k * 1.57 + motion.orbit * 0.6
                val orbit = (if (wide) 0.32 else 0.22) + 0.06 * sin(t + k)
                val cx = if (wide) w / 2 + (cos(a) * orbit).toFloat() * w + (if (warm) -w * 0.18f else w * 0.18f) * (1 - motion.thinking).toFloat()
                else w / 2 + (cos(a) * orbit).toFloat() * r0
                val cy = h / 2 + (sin(a * 1.2) * orbit).toFloat() * r0
                val r = r0 * (0.42f + 0.26f * min(1.0, level * 1.5).toFloat()) * (1 - 0.5f * motion.ended.toFloat())
                val alpha = (0.55 + 0.45 * min(1.0, level * 1.6)) * (if (warm) 1 - 0.5 * motion.muted else 1.0) * (1 - 0.6 * motion.alert)
                paint.reset(); paint.isAntiAlias = true; paint.color = color.withAlpha(alpha)
                layer.drawCircle(cx, cy, r, paint)
            }
            if (motion.alert > 0.01) {
                paint.reset(); paint.isAntiAlias = true; paint.color = FloatingCallPalette.alert(dark).withAlpha(0.8 * motion.alert)
                layer.drawCircle(w / 2, h / 2, r0 * 0.45f, paint)
            }
        }
        // The rim glows brighter, and thicker, as either voice rises.
        val energy = min(1.0, motion.you + motion.agent).toFloat()
        filtered(canvas, "rim", w, h, blur((1.2f + 2.5f * energy) * density)) { layer ->
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
            paint.strokeWidth = (1.5f + 3f * energy) * density
            paint.shader = conic(w / 2, h / 2, (colors + colors[0]).map { it.toArgb() }.toIntArray(), motion.phase * 1.3)
            roundRect(layer, w, h, radius, paint)
        }
        if (motion.connecting > 0.01) sweep(canvas, w, h, motion, dark)
        }
    }

    fun rings(scope: DrawScope, motion: FloatingCallMotion, dark: Boolean, corner: Float, density: Float) = scope.drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        val w = scope.size.width
        val h = scope.size.height
        val r0 = min(w, h) / 2
        val cx = w / 2
        val cy = h / 2
        val warm = FloatingCallPalette.warm(dark)
        val cool = FloatingCallPalette.cool(dark)
        val warmMix = FloatingCallTunables.warm
        val coolMix = FloatingCallTunables.cool
        // The agent's colour fills the glass out to its edge, yours glows in the middle.
        val radius = min(w, h) * corner
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, max(0.01f, max(w, h) / 2), warm[1].withAlpha(0.3 * warmMix), cool[1].withAlpha(0.45 * coolMix), Shader.TileMode.CLAMP)
        roundRect(canvas, w, h, radius, paint)
        fun ring(r: Float, color: Color, width: Float, alpha: Double) {
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
            paint.strokeWidth = width; paint.color = color.withAlpha(alpha)
            canvas.drawCircle(cx, cy, r, paint)
        }
        val breathe = 0.5 + 0.5 * sin(motion.phase * 2)
        // The agent's ring outside, yours inside: each grows and brightens with its voice.
        val outer = r0 * (0.74f + 0.16f * min(1.0, motion.agent * 1.4).toFloat() + 0.03f * breathe.toFloat())
        val inner = r0 * (0.36f + 0.2f * min(1.0, motion.you * 1.4).toFloat()) * (1 - 0.4f * motion.muted.toFloat())
        filtered(canvas, "inner", w, h, blur(3f * density)) { layer ->
            paint.reset(); paint.isAntiAlias = true
            paint.color = warm[0].withAlpha(0.35 * min(1.0, motion.you * 2) * warmMix)
            layer.drawCircle(cx, cy, inner, paint)
        }
        ring(outer, cool[1], (2 + 2.5f * motion.agent.toFloat()) * density, (0.45 + 0.55 * min(1.0, motion.agent * 1.6)) * coolMix * (1 - motion.ended * 0.7))
        ring(inner, warm[1], (2 + 2.5f * motion.you.toFloat()) * density, (0.45 + 0.55 * min(1.0, motion.you * 1.6)) * warmMix * (1 - motion.ended * 0.7))
        if (motion.thinking > 0.01 || motion.connecting > 0.01) {
            val start = Math.toDegrees(motion.spin).toFloat()
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density; paint.strokeCap = Paint.Cap.ROUND
            paint.color = (if (dark) Color.White else Color.Black).withAlpha(0.85 * max(motion.thinking, motion.connecting))
            canvas.drawArc(cx - outer, cy - outer, cx + outer, cy + outer, start, 80f, false, paint)
        }
        if (motion.alert > 0.01) {
            ring(r0 * 0.86f, FloatingCallPalette.alert(dark), 2.5f * density, motion.alert * (0.5 + 0.5 * breathe))
        }
    }

    /** Connecting: a soft beam scans across, as the call bar's does. */
    private fun sweep(canvas: android.graphics.Canvas, w: Float, h: Float, motion: FloatingCallMotion, dark: Boolean) {
        val x = w / 2 + w * 0.4f * sin(motion.phase * 2.6).toFloat()
        val left = x - w * 0.12f
        val right = left + w * 0.24f
        val beam = (if (dark) Color.White else Color.Black).withAlpha(0.5 * motion.connecting)
        paint.reset()
        paint.shader = LinearGradient(left, 0f, right, 0f, intArrayOf(android.graphics.Color.TRANSPARENT, beam, android.graphics.Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(left, 0f, right, h, paint)
    }

    /** SwiftUI's `conicGradient(…, angle:)`: a sweep starting at [angle] radians, clockwise on screen. */
    private fun conic(cx: Float, cy: Float, colors: IntArray, angle: Double): SweepGradient =
        SweepGradient(cx, cy, colors, null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(Math.toDegrees(angle).toFloat(), cx, cy) })
        }

    private companion object {
        /** SwiftUI `.alphaThreshold(min: 0.5, color: .white)`: white where alpha ≥ 0.5, a steep ramp for antialiasing. */
        val alphaThreshold = ColorMatrixColorFilter(
            floatArrayOf(
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 24f, -24f * 127.5f,
            ),
        )
    }
}

/**
 * Round 1's face: an LED dot field in a circle where your voice (warm pearl) and the agent's
 * (cool pearl) braid, like the call bar's scope (iOS `FloatingCallGlyph`).
 *
 * @param isHidden faded out behind the panel: no drawing.
 */
@Composable
fun FloatingCallGlyph(
    phase: VoiceFeature.State.Phase,
    isMuted: Boolean,
    isReconnecting: Boolean,
    modifier: Modifier = Modifier,
    isHidden: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val dark = OpenCodeTheme.colors.isDark
    val model = remember { FloatingCallGlyphModel() }
    var frame by remember { mutableIntStateOf(0) }
    val still = reduceMotion || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(still || isHidden) {
        if (still || isHidden) return@LaunchedEffect
        while (true) withFrameNanos { frame++ }
    }
    val density = LocalDensity.current.density
    Canvas(modifier.clearAndSetSemantics {}) {
        @Suppress("UNUSED_EXPRESSION") frame
        val now = System.nanoTime() / 1e9
        model.step(now, still, isMuted)
        drawGlyph(model, phase, isReconnecting, dark, if (still) 0.0 else now, density)
    }
}

private fun DrawScope.drawGlyph(model: FloatingCallGlyphModel, phase: VoiceFeature.State.Phase, isReconnecting: Boolean, dark: Boolean, time: Double, density: Float) {
    // Laid out in points, as iOS, then scaled to pixels.
    val width = size.width / density
    val height = size.height / density
    val palette = DotScopePalette.pearlPalette(dark)
    val you = palette.you
    val agentColor = palette.agent
    val pitch = max(3.2f, width / 13)
    val dot = pitch * 0.36f
    // Dots run out to the glass's edge; its shape clips them.
    val radius = min(width, height) / 2 + pitch * 0.5f
    val midX = width / 2
    val midY = height / 2
    val columns = (width / pitch).toInt()
    val rows = (height / pitch).toInt()
    val originX = (width - (columns - 1) * pitch) / 2
    val originY = (height - (rows - 1) * pitch) / 2
    val connecting = phase == VoiceFeature.State.Phase.Connecting || isReconnecting
    val ended = phase.isTerminal
    val amplitude = height * 0.34
    val k = 2 * PI / (width * 0.9)
    val levelsYou = model.you.toDouble()
    val levelsAgent = model.agent.toDouble()
    val beam = if (connecting) midX + (radius * 0.9f) * sin(time * 2.6).toFloat() else null
    val thickness = pitch * 0.85
    val unlit = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f)
    val beamColor = if (dark) Triple(0.93, 0.94, 0.97) else Triple(0.28, 0.3, 0.36)
    for (j in 0 until rows) {
        for (i in 0 until columns) {
            val x = originX + i * pitch
            val y = originY + j * pitch
            if (hypot(x - midX, y - midY) > radius) continue
            val u = (x / width).toDouble()
            val wy = 0.35 + 0.65 * (1 - u)
            val wa = 0.35 + 0.65 * u
            val ay = (0.2 + 0.8 * levelsYou) * amplitude * wy
            val aa = (0.2 + 0.8 * levelsAgent) * amplitude * wa
            val yy = midY + ay * sin(k * x - time * 5.2) * cos(k * 0.37 * x + time * 1.3)
            val ya = midY - aa * sin(k * 1.15 * x - time * 4.4 + 1.7) * cos(k * 0.29 * x - time * 0.9)
            var iy = exp(-(y - yy).pow(2) / (thickness * thickness)) * (0.35 + 0.65 * max(levelsYou, 0.2) * wy)
            var ia = exp(-(y - ya).pow(2) / (thickness * thickness)) * (0.35 + 0.65 * max(levelsAgent, 0.2) * wa)
            var ib = 0.0
            if (beam != null) {
                val dx = (x - beam).toDouble()
                ib = exp(-(dx * dx) / (pitch * pitch * 2).toDouble())
                iy *= 0.25; ia *= 0.25
            }
            if (ended) { iy *= 0.2; ia *= 0.2 }
            val total = iy + ia + ib
            val center = Offset(x * density, y * density)
            drawCircle(unlit, radius = dot * density, center = center)
            if (total <= 0.04) continue
            val r = (you[0] * iy + agentColor[0] * ia + beamColor.first * ib) / total
            val g = (you[1] * iy + agentColor[1] * ia + beamColor.second * ib) / total
            val b = (you[2] * iy + agentColor[2] * ia + beamColor.third * ib) / total
            val lit = dot + dot * 0.25f * min(1.0, total).toFloat()
            drawCircle(Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f), min(1.0, total).toFloat()), radius = lit * density, center = center)
        }
    }
}

/** Followers for the dot glyph's voices (iOS `FloatingCallGlyphModel`). A plain object; main thread only. */
class FloatingCallGlyphModel {
    var you = 0f; private set
    var agent = 0f; private set
    private var last: Double? = null
    private val meter = VoiceAudioLevels.shared

    fun step(time: Double, still: Boolean, isMuted: Boolean) {
        if (still) {
            you = 0.35f; agent = 0.4f
            last = null
            return
        }
        val dt = min(0.1, time - (last ?: time)).toFloat()
        last = time
        val target = meter.snapshot()
        fun follow(current: Float, target: Float): Float {
            val rate = if (target > current) 14f else 4f
            return current + (target - current) * min(1f, rate * dt)
        }
        you = if (isMuted) follow(you, 0f) else follow(you, target.user)
        agent = follow(agent, target.assistant)
    }
}

/** What a dot-matrix face draws: its take and the call's state (iOS `FloatingOrbConfig`). */
data class FloatingOrbConfig(
    val take: Int,
    val mood: FloatingCallMood,
    val isMuted: Boolean,
    val scene: FloatingCallScene? = null,
)

/**
 * The dot-matrix takes, drawn by AGSL ([FloatingOrbShaders]) at whatever size and shape the
 * surface is: the dots fill it to its edge (iOS `FloatingCallDotFace`). Still under Reduce
 * Motion; no GPU work while hidden or the app isn't resumed.
 */
@Composable
fun FloatingCallDotFace(config: FloatingOrbConfig, modifier: Modifier = Modifier, isHidden: Boolean = false) {
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val renderer = remember { FloatingOrbRenderer.make() } ?: return
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentConfig by rememberUpdatedState(config)

    fun configure() {
        renderer.config = currentConfig
        renderer.isDark = isDark
        renderer.isStill = reduceMotion
    }

    val isPaused = isHidden || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(renderer, isPaused, reduceMotion, size, density, if (reduceMotion) config else Unit, isDark) {
        if (size.width <= 0 || size.height <= 0 || isPaused) return@LaunchedEffect
        if (reduceMotion) {
            configure()
            if (renderer.render(System.nanoTime() / 1e9, size.width, size.height, density)) frame++
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { nanos ->
                configure()
                if (renderer.render(nanos / 1e9, size.width, size.height, density)) frame++
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .clearAndSetSemantics {},
    ) {
        @Suppress("UNUSED_EXPRESSION") frame
        val brush = renderer.brush ?: return@Canvas
        drawRect(brush, size = Size(size.width.toFloat(), size.height.toFloat()))
    }
}

/**
 * Steps the face's motion ([FloatingCallMotion]) each frame and hands the shader the two
 * voices, the call's state and the look's tunables (iOS `FloatingOrbRenderer`). A plain object,
 * one per mounted face; main thread only.
 */
class FloatingOrbRenderer private constructor() {
    private val shader = RuntimeShader(FloatingOrbShaders.floatingOrbFragment)
    private val motion = FloatingCallMotion()
    var isDark = true
    var isStill = false
    var config = FloatingOrbConfig(take = 0, mood = FloatingCallMood.live, isMuted = false, scene = null)

    /** The shader brush once a frame has been drawn into it. */
    var brush: ShaderBrush? = null
        private set

    fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return false
        motion.step(nowSeconds, isStill, config.mood, config.isMuted, config.scene)
        val short = min(pixelWidth, pixelHeight).toFloat()
        // Dots across the orb's diameter; a wide strip keeps the same pitch.
        val across = FloatingCallTunables.dotsAcross
        val pitch = short / max(4f, across)
        val palette = DotScopePalette.pearlPalette(isDark)
        shader.setFloatUniform("size", pixelWidth.toFloat(), pixelHeight.toFloat(), pitch, scale)
        shader.setFloatUniform("levels", motion.you.toFloat(), motion.agent.toFloat(), motion.thinking.toFloat(), motion.alert.toFloat())
        shader.setFloatUniform(
            "clock",
            (motion.phase % 10_000).toFloat(),
            (motion.orbit % (2 * PI * 1000)).toFloat(),
            config.take.toFloat(),
            if (isDark) 1f else 0f,
        )
        shader.setFloatUniform("mood", motion.muted.toFloat(), motion.connecting.toFloat(), motion.ended.toFloat(), FloatingCallTunables.dotBody)
        shader.setFloatUniform("look", FloatingCallTunables.dotFill, FloatingCallTunables.glow.toFloat(), FloatingCallTunables.dotBase, 0f)
        shader.setFloatUniform("you", palette.you)
        shader.setFloatUniform("youCore", palette.youCore)
        shader.setFloatUniform("agent", palette.agent)
        shader.setFloatUniform("agentCore", palette.agentCore)
        if (brush == null) brush = ShaderBrush(shader)
        return true
    }

    companion object {
        fun make(): FloatingOrbRenderer? = runCatching { FloatingOrbRenderer() }.getOrNull()
    }
}
