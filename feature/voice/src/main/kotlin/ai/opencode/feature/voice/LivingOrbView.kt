package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** What a Living Orb draws (iOS `LivingOrbConfig`). */
data class LivingOrbConfig(
    val style: LivingOrbStyle,
    val mood: FloatingCallMood,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val scene: FloatingCallScene? = null,
)

/**
 * The Living Orb (Experimental `livingOrb`): a voice-reactive phosphor field in the floating
 * call's orb, mint idle, amber while you talk, violet thinking and cyan while the agent speaks
 * (iOS `LivingOrbView`).
 *
 * Android: an AGSL [RuntimeShader] ([LivingOrbShaders]) drawn by a Compose [Canvas]; a
 * `withFrameNanos` loop steps the plain-object [LivingOrbRenderer] only while it shows (not
 * [isHidden], lifecycle resumed: iOS `scenePhase == .active`). Under Reduce Motion it draws a
 * fixed pose per semantic state, once whenever its inputs change.
 *
 * @param isHidden faded out behind the panel: no drawing.
 */
@Composable
fun LivingOrbView(config: LivingOrbConfig, modifier: Modifier = Modifier, isHidden: Boolean = false) {
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val renderer = remember { LivingOrbRenderer.make() } ?: return
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentConfig by rememberUpdatedState(config)

    fun configure() {
        renderer.config = currentConfig
        renderer.look = LivingOrbLook.baked
        renderer.isDark = isDark
        renderer.isStill = reduceMotion
    }

    val isPaused = isHidden || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(renderer, isPaused, reduceMotion, size, density, if (reduceMotion) config else Unit, isDark) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        renderer.resetClock()
        if (isPaused) return@LaunchedEffect
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
        drawRect(brush)
    }
}

/**
 * Steps the [LivingOrbField] each frame and draws it through [LivingOrbShaders] (iOS
 * `LivingOrbRenderer`). A plain object, one per mounted orb; main thread only.
 */
class LivingOrbRenderer private constructor() {
    private val shader = RuntimeShader(LivingOrbShaders.livingOrbFragment)
    private val field = LivingOrbField()
    private var last: Double? = null

    // Hot loop: the meter resolved once.
    private val levels = VoiceAudioLevels.shared

    var config = LivingOrbConfig(style = LivingOrbStyle.polarBloom, mood = FloatingCallMood.live, isMuted = false, isSpeaking = false)
    var look = LivingOrbLook.baked
    var isDark = true
    var isStill = false

    private var grid: DotScopeGrid? = null
    private var lit = FloatArray(0)
    private var bitmap: Bitmap? = null
    private var pixels: ByteBuffer? = null

    /** The shader brush once a frame has been drawn into it. */
    var brush: ShaderBrush? = null
        private set

    fun resetClock() {
        last = null
    }

    private fun prepareGrid(pixelWidth: Int, pixelHeight: Int, scale: Float): DotScopeGrid? {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return null
        val next = DotScopeGrid(pixelWidth = pixelWidth, pixelHeight = pixelHeight, scale = scale, requestedPitch = look.pitch.toFloat())
        if (next != grid) {
            grid = next
            lit = FloatArray(max(1, next.count) * 4)
            val texture = createBitmap(next.columns, next.rows, Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            bitmap = texture
            pixels = ByteBuffer.allocateDirect(next.columns * next.rows * 8).order(ByteOrder.nativeOrder())
            val input = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
            shader.setInputBuffer("field", input)
            brush = null
        }
        return grid
    }

    /** Steps (or stills) the field for the frame at [nowSeconds] and uploads it. */
    fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        val grid = prepareGrid(pixelWidth, pixelHeight, scale) ?: return false
        // iOS scales by `AnimationTimeScale` (a lab hook); real time here.
        val dt = max(0.0, min(0.05, nowSeconds - (last ?: nowSeconds)))
        last = if (isStill) null else nowSeconds
        val levels = levels(nowSeconds)
        val state = LivingOrbState.resolve(config.mood, config.isSpeaking, levels.first, config.isMuted, config.scene)
        field.step(dt, levels, state, isStill, look)
        field.render(grid, config.style, dt, isStill, look, lit)
        upload(grid)
        val tint = field.tint
        val ended = if (config.mood == FloatingCallMood.ended) 0.1f else 1f
        shader.setFloatUniform("grid", grid.pitch, grid.columns.toFloat(), grid.rows.toFloat(), grid.scale)
        shader.setFloatUniform("origin", grid.originX, grid.originY, grid.width, grid.height)
        shader.setFloatUniform("look", if (isDark) 1f else 0f, look.dotSize.toFloat(), look.glow.toFloat(), look.unlit.toFloat())
        shader.setFloatUniform("colour", tint.r, tint.g, tint.b, ended)
        if (brush == null) brush = ShaderBrush(shader)
        return true
    }

    private fun levels(time: Double): Pair<Double, Double> {
        val scene = config.scene
        var levels: Pair<Double, Double> = if (scene != null) {
            val you = VoiceScriptedDemo.continuousSpeechLevel(time, rate = 4.5, seed = 0.4).toDouble()
            val agent = VoiceScriptedDemo.continuousSpeechLevel(time, rate = 4.2, seed = 1.9).toDouble()
            when (scene) {
                FloatingCallScene.listening -> you to 0.0
                FloatingCallScene.agentSpeaking -> 0.0 to agent
                FloatingCallScene.bothSpeaking -> you to agent
                FloatingCallScene.idle, FloatingCallScene.thinking, FloatingCallScene.needsInput -> 0.0 to 0.0
            }
        } else {
            val live = levels.snapshot()
            live.user.toDouble() to live.assistant.toDouble()
        }
        if (config.isMuted) levels = 0.0 to levels.second
        return levels
    }

    // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data.
    @SuppressLint("HalfFloat")
    private fun upload(grid: DotScopeGrid) {
        val buffer = pixels ?: return
        val texture = bitmap ?: return
        buffer.clear()
        for (n in 0 until grid.count) {
            val i = n * 4
            buffer.putShort(Half.toHalf(lit[i]))
            buffer.putShort(Half.toHalf(lit[i + 1]))
            buffer.putShort(Half.toHalf(lit[i + 2]))
            buffer.putShort(Half.toHalf(1f))
        }
        buffer.rewind()
        texture.copyPixelsFromBuffer(buffer)
    }

    companion object {
        fun make(): LivingOrbRenderer? = runCatching { LivingOrbRenderer() }.getOrNull()
    }
}
