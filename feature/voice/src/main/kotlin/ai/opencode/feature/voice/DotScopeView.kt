package ai.opencode.feature.voice

import ai.opencode.core.log.AppLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * iOS `EnvironmentValues.dotScopeCovered`: Session sets it while the session is covered (a
 * browser page, Catch Up), so every scope underneath stops drawing while keeping its
 * simulation. Covered scopes run no frame loop at all.
 */
val LocalDotScopeCovered = staticCompositionLocalOf { false }

/** iOS `DictationLevels.shared.level`: Composer's dictation meter, read once per frame. */
fun interface DotScopeLevelSource {
    fun level(): Float
}

/** iOS `DotScopeView.Mode`. */
sealed interface DotScopeMode {
    data class Call(val stage: DotScopeSimulation.Stage) : DotScopeMode

    /** Both traces render in your warm pearl and follow [level] (Composer's dictation levels). */
    data class Dictation(val leadingInset: Float, val trailingInset: Float, val level: DotScopeLevelSource) : DotScopeMode

    /**
     * Settings → Voice: a simulated conversation, you then the agent, while [active]; at rest
     * otherwise. No microphone or call involved.
     */
    data class Preview(val active: Boolean) : DotScopeMode

    /** The floating call's panel: the call's scope across its full width, with no room kept for buttons at either end. */
    data class Panel(val stage: DotScopeSimulation.Stage) : DotScopeMode

    /** The call tray: the call's scope running from the rounded leading cap all the way to Mute and End on the trailing side. */
    data class Tray(
        val stage: DotScopeSimulation.Stage,
        val leadingInset: Float,
        val trailingInset: Float,
        val visibleWidth: Float = 0f,
        val fade: Float = DotScopeSimulation.windowFade,
        val scanReach: Float = 1f,
        val cornerRadius: Float = 28f,
    ) : DotScopeMode
}

/**
 * The call and dictation bar's visual: an LED dot-matrix oscilloscope where your voice (warm
 * pearl) and the agent's (cool pearl) are two braided traces leaving phosphor trails over a
 * soft glow behind each button. In dictation mode both traces render in your warm pearl and
 * follow the dictation level. Under Reduce Motion it is the quiet state, still; in the
 * background it stops drawing.
 *
 * Android: an AGSL [RuntimeShader] ([DotScopeShaders]) drawn by a Compose [Canvas]; a
 * `withFrameNanos` loop advances the plain-object [DotScopeRenderer] (never reducer actions)
 * only while the scope is visible: not [isHidden], not [LocalDotScopeCovered], and the
 * lifecycle resumed (iOS `scenePhase == .active`). A paused scope keeps its last frame and
 * resumes with a reset clock, so it never fast-forwards. Under Reduce Motion it draws one still
 * frame whenever its inputs change.
 *
 * @param isHidden faded out behind something else: no drawing.
 * @param renderer one call's scope drawn by whichever view shows it (the Call Tray in the
 *   session, then in the drawer), so its trails carry across. `null` makes one.
 */
@Composable
fun DotScopeView(
    mode: DotScopeMode,
    modifier: Modifier = Modifier,
    isHidden: Boolean = false,
    renderer: DotScopeRenderer? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isCovered = LocalDotScopeCovered.current
    val ownRenderer = remember { if (renderer == null) DotScopeRenderer.make() else null }
    val scope = renderer ?: ownRenderer ?: return
    val shared = renderer != null
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentMode by rememberUpdatedState(mode)
    val currentHidden by rememberUpdatedState(isHidden)

    // Each mounted surface owns its configuration even when the simulation is shared: apply it
    // at draw time, and a hidden view never overwrites the visible one's mode.
    fun configure() {
        scope.isDark = isDark
        scope.isStill = reduceMotion
        if (!shared || !currentHidden) scope.mode = currentMode
    }

    val isPaused = isHidden || isCovered || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(scope, isPaused, reduceMotion, size, density, if (reduceMotion) mode else Unit, isDark) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        scope.resetClock()
        if (isPaused) return@LaunchedEffect
        if (reduceMotion) {
            // Still: draw only when something changes.
            configure()
            if (scope.render(nowSeconds = System.nanoTime() / 1e9, pixelWidth = size.width, pixelHeight = size.height, scale = density)) frame++
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { nanos ->
                configure()
                if (scope.render(nowSeconds = nanos / 1e9, pixelWidth = size.width, pixelHeight = size.height, scale = density)) frame++
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .clearAndSetSemantics {},
    ) {
        @Suppress("UNUSED_EXPRESSION") frame
        val brush = scope.brush ?: return@Canvas
        drawRect(brush)
    }
}

/** The pearl palette shared by the dot scopes, `AgentActivityOrb` and Catch Up's drift field (iOS `DotScopeMetalShared`). */
object DotScopePalette {
    /** Dark-mode cool pearl base and core shared by the dot scopes and `AgentActivityOrb`. */
    val coolPearlDarkBase = floatArrayOf(0.70f, 0.75f, 0.84f)
    val coolPearlDarkCore = floatArrayOf(0.92f, 0.95f, 1.0f)

    data class Pearl(val you: FloatArray, val youCore: FloatArray, val agent: FloatArray, val agentCore: FloatArray)

    /** The pearl palette: warm for you, cool for the agent; cores are where a trace burns brightest. */
    fun pearlPalette(dark: Boolean): Pearl = if (dark) {
        Pearl(
            floatArrayOf(0.86f, 0.83f, 0.77f, 1f),
            floatArrayOf(0.98f, 0.96f, 0.92f, 1f),
            floatArrayOf(coolPearlDarkBase[0], coolPearlDarkBase[1], coolPearlDarkBase[2], 1f),
            floatArrayOf(coolPearlDarkCore[0], coolPearlDarkCore[1], coolPearlDarkCore[2], 1f),
        )
    } else {
        Pearl(
            floatArrayOf(0.62f, 0.53f, 0.40f, 1f),
            floatArrayOf(0.47f, 0.38f, 0.26f, 1f),
            floatArrayOf(0.40f, 0.47f, 0.58f, 1f),
            floatArrayOf(0.24f, 0.31f, 0.43f, 1f),
        )
    }
}

/**
 * Steps the scope's simulation at a fixed rate and draws the lit grid (iOS `DotScopeRenderer`).
 * A plain object: one per mounted scope, or one per call shared by the tray in the session and
 * in the drawer ([CallTrayController.scopeRenderer]). Main thread only.
 */
class DotScopeRenderer private constructor() {
    private var simulation = DotScopeSimulation()
    private var lastFrame: Double? = null

    /** Dictation's window eases to new insets as the bar's buttons come and go. */
    private var insets: ScopeVector2? = null

    var mode: DotScopeMode = DotScopeMode.Call(DotScopeSimulation.Stage.live)
    var isDark = true
    var isStill = false

    // Hot loop: clients resolved once.
    private val sounds = VoiceCallSounds.shared
    private val levels = VoiceAudioLevels.shared

    private val shader = RuntimeShader(DotScopeShaders.dotScopeFragment)
    private var grid: DotScopeGrid? = null
    private var field = FloatArray(0)
    private var bitmap: Bitmap? = null
    private var pixels: ByteBuffer? = null

    /** The shader brush once a frame has been drawn into it. */
    var brush: ShaderBrush? = null
        private set

    /** After a pause the scope resumes where it was instead of fast-forwarding. */
    fun resetClock() {
        lastFrame = null
    }

    private fun prepareGrid(pixelWidth: Int, pixelHeight: Int, scale: Float): DotScopeGrid? {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return null
        val next = DotScopeGrid(pixelWidth = pixelWidth, pixelHeight = pixelHeight, scale = scale, requestedPitch = 3.5f)
        if (next != grid) {
            grid = next
            field = FloatArray(max(1, next.count) * 4)
            val texture = createBitmap(next.columns, next.rows, Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            bitmap = texture
            pixels = ByteBuffer.allocateDirect(next.columns * next.rows * 8).order(ByteOrder.nativeOrder())
            val input = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
            shader.setInputBuffer("field", input)
            brush = null
            // The floating call's panel is not the bar the call's sounds are timed to.
            if (mode !is DotScopeMode.Panel) sounds.noteScopeWidth(next.width)
        }
        return grid
    }

    /** Advances (or stills) the simulation for the frame at [nowSeconds] and uploads it. */
    fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        val grid = prepareGrid(pixelWidth, pixelHeight, scale) ?: return false
        val mode = mode
        val target = when (mode) {
            is DotScopeMode.Call, is DotScopeMode.Preview -> ScopeVector2(DotScopeSimulation.windowInset, DotScopeSimulation.windowInset)
            is DotScopeMode.Panel -> ScopeVector2(0f, 0f)
            is DotScopeMode.Tray -> ScopeVector2(mode.leadingInset, mode.trailingInset)
            is DotScopeMode.Dictation -> ScopeVector2(mode.leadingInset, mode.trailingInset)
        }
        if (isStill) {
            val connected = when (mode) {
                is DotScopeMode.Call -> mode.stage == DotScopeSimulation.Stage.live
                is DotScopeMode.Panel -> mode.stage == DotScopeSimulation.Stage.live
                is DotScopeMode.Tray -> mode.stage == DotScopeSimulation.Stage.live
                is DotScopeMode.Preview -> mode.active
                is DotScopeMode.Dictation -> true
            }
            simulation = DotScopeSimulation.still(connected)
            insets = target
            simulation.windowInsets = target
            applyTrayMetrics(grid)
        } else {
            val rawElapsed = min(0.1, nowSeconds - (lastFrame ?: nowSeconds))
            // iOS `AnimationTimeScale` is a lab hook; real time here.
            val elapsed = rawElapsed
            lastFrame = nowSeconds
            val eased = if (mode is DotScopeMode.Tray) target else insets?.let {
                val k = min(1f, elapsed.toFloat() * 12)
                ScopeVector2(it.x + (target.x - it.x) * k, it.y + (target.y - it.y) * k)
            } ?: target
            insets = eased
            simulation.windowInsets = eased
            applyTrayMetrics(grid)
            when (mode) {
                is DotScopeMode.Call, is DotScopeMode.Panel -> {
                    val stage = if (mode is DotScopeMode.Call) mode.stage else (mode as DotScopeMode.Panel).stage
                    // Where the shared clock was when this frame's step begins.
                    val stageElapsed = sounds.stageElapsed(stage)?.let { max(0.0, it - elapsed) }
                    simulation.setStage(stage, stageElapsed)
                    val snapshot = levels.snapshot()
                    simulation.advance(elapsed, you = snapshot.user, agent = snapshot.assistant, connected = stage == DotScopeSimulation.Stage.live)
                }
                is DotScopeMode.Tray -> {
                    simulation.setStage(mode.stage)
                    val snapshot = levels.snapshot()
                    simulation.advance(elapsed, you = snapshot.user, agent = snapshot.assistant, connected = mode.stage == DotScopeSimulation.Stage.live)
                }
                is DotScopeMode.Dictation -> {
                    val level = mode.level.level()
                    simulation.advance(elapsed, you = level, agent = level * 0.85f, connected = true)
                }
                is DotScopeMode.Preview -> {
                    val preview = if (mode.active) previewLevels(nowSeconds) else 0f to 0f
                    simulation.advance(elapsed, you = preview.first, agent = preview.second, connected = mode.active)
                }
            }
        }
        simulation.render(grid, field)
        upload(grid)
        setUniforms(grid)
        if (brush == null) brush = ShaderBrush(shader)
        return true
    }

    private fun applyTrayMetrics(grid: DotScopeGrid) {
        val mode = mode
        if (mode is DotScopeMode.Tray) {
            val w = if (mode.visibleWidth > 0) min(grid.width, mode.visibleWidth) else grid.width
            simulation.reachesLeadingEdge = true
            simulation.trayFade = mode.fade
            simulation.trayScanReach = mode.scanReach
            simulation.visibleBar = ScopeVector2(0f, w)
        } else {
            simulation.reachesLeadingEdge = false
            simulation.visibleBar = ScopeVector2()
        }
    }

    // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data.
    @SuppressLint("HalfFloat")
    private fun upload(grid: DotScopeGrid) {
        val buffer = pixels ?: return
        val texture = bitmap ?: return
        buffer.clear()
        val one = Half.toHalf(1f)
        for (n in 0 until grid.count) {
            val i = n * 4
            buffer.putShort(Half.toHalf(field[i]))
            buffer.putShort(Half.toHalf(field[i + 1]))
            buffer.putShort(Half.toHalf(field[i + 2]))
            buffer.putShort(one)
        }
        buffer.rewind()
        texture.copyPixelsFromBuffer(buffer)
    }

    private fun setUniforms(grid: DotScopeGrid) {
        val scan = simulation.scan(grid.width)
        val palette = DotScopePalette.pearlPalette(isDark)
        val isDictation: Boolean
        val window: FloatArray
        val bar = floatArrayOf(0f, grid.width, 0f, 0f)
        val shape = floatArrayOf(grid.height / 2, 0f, 0f, 0f)
        when (val mode = mode) {
            is DotScopeMode.Call, is DotScopeMode.Preview -> {
                isDictation = false
                window = floatArrayOf(DotScopeSimulation.windowInset, DotScopeSimulation.windowFade, 0f, 0f)
            }
            is DotScopeMode.Panel -> {
                isDictation = false
                window = floatArrayOf(0f, 1f, 0f, 1f)
            }
            is DotScopeMode.Tray -> {
                isDictation = false
                val eased = insets ?: ScopeVector2(mode.leadingInset, mode.trailingInset)
                val w = if (mode.visibleWidth > 0) min(grid.width, mode.visibleWidth) else grid.width
                val leftFade = max(6f, mode.fade)
                window = floatArrayOf(eased.x, mode.fade, eased.y, 0f)
                // The fixed-size canvas is pinned to the glass's leading edge. A stale frame may
                // carry an older trailing width, but cannot detach its leading mask from the cap.
                bar[1] = w; bar[2] = leftFade; bar[3] = CallTrayTunables.scopeEdgeCurve
                shape[0] = min(mode.cornerRadius, min(w / 2, grid.height / 2))
            }
            is DotScopeMode.Dictation -> {
                isDictation = true
                val eased = insets ?: ScopeVector2(mode.leadingInset, mode.trailingInset)
                window = floatArrayOf(eased.x, 24f, eased.y, 0f)
            }
        }
        val glow = simulation.glow
        shader.setFloatUniform("grid", grid.pitch, grid.columns.toFloat(), grid.rows.toFloat(), grid.scale)
        shader.setFloatUniform("origin", grid.originX, grid.originY, grid.width, grid.height)
        shader.setFloatUniform("state", simulation.live, if (isDark) 1f else 0f, scan.grid, scan.presence)
        shader.setFloatUniform("window", window)
        shader.setFloatUniform("glow", glow.x, glow.y, simulation.breath, simulation.live)
        shader.setFloatUniform("you", palette.you)
        shader.setFloatUniform("youCore", palette.youCore)
        shader.setFloatUniform("agent", if (isDictation) palette.you else palette.agent)
        shader.setFloatUniform("agentCore", if (isDictation) palette.youCore else palette.agentCore)
        shader.setFloatUniform("bar", bar)
        shader.setFloatUniform("shape", shape)
    }

    companion object {
        /**
         * Builds the shader once off the main thread, so the first scope (a held mic's bar, a
         * call) appears at once. Android compiles AGSL lazily per program; this warms the
         * process's shader cache like iOS `DotScopeRenderer.prepare()`.
         */
        fun prepare() {
            Thread { runCatching { RuntimeShader(DotScopeShaders.dotScopeFragment) } }.start()
        }

        fun make(): DotScopeRenderer? = runCatching { DotScopeRenderer() }
            .onFailure { AppLog.voice.error("dot_scope_shader_failed type=${it.javaClass.simpleName}") }
            .getOrNull()

        /**
         * A 6.4 s exchange: you speak, a beat, the agent answers, a beat. Each voice is a
         * syllable-paced envelope, like speech rather than a tone.
         */
        fun previewLevels(time: Double): Pair<Float, Float> {
            val cycle = time % 6.4
            fun turn(start: Double, length: Double, seed: Double): Float {
                val t = cycle - start
                if (t <= 0 || t >= length) return 0f
                val edge = min(1.0, min(t / 0.25, (length - t) / 0.35))
                val syllables = abs(sin(time * 8.3 + seed)) * (0.55 + 0.45 * abs(sin(time * 3.1 + seed * 2)))
                return (edge * (0.18 + 0.62 * syllables)).toFloat()
            }
            return turn(0.2, 2.3, 0.7) to turn(2.9, 3.0, 2.1)
        }
    }
}
