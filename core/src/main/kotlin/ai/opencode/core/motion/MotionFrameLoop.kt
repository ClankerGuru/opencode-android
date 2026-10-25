package ai.opencode.core.motion

import ai.opencode.core.tuning.AnimationTimeScale
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.min

/** What a frame callback wants next (iOS `MotionDisplayLink.Action`). */
enum class MotionFrameAction { Continue, Stop }

/**
 * One frame of a [motionFrameLoop] (iOS `MotionDisplayLink.Tick`).
 *
 * @property dt seconds to advance this frame, already capped at `maxFrames` frame durations
 *   (so a hitch never teleports a spring) and multiplied by the time scale.
 * @property now the loop's virtual clock in seconds (scaled).
 * @property rawFrameDuration the display's frame duration in seconds.
 * @property scale the time scale (1 = real time; Developer Mode slow motion would lower it).
 */
data class MotionTick(val dt: Double, val now: Double, val rawFrameDuration: Double, val scale: Double)

/**
 * Port of iOS `MotionDisplayLink`: a per-frame loop for custom motion (springs stepped by
 * [SpringSolver], gesture followers) on Compose's frame clock (`withFrameNanos`), which runs
 * at the display's refresh rate (up to 120 Hz on capable phones). Suspends until [onTick]
 * returns [MotionFrameAction.Stop] or the calling coroutine is cancelled (leaving
 * composition stops it, like iOS's weak target).
 *
 * Mirrors `AnimationTimeScale.sample`: `dt = min(frameDelta, maxFrames × frameDuration) × scale`;
 * the first frame advances one frame duration. Never read wall-clock time inside a step.
 *
 * ```kotlin
 * LaunchedEffect(target) {
 *     val spring = SpringSolver(Motion.drawerSlide, value = offset, target = target)
 *     motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
 *         offset = spring.step(tick.dt).value
 *         if (spring.isSettled) MotionFrameAction.Stop else MotionFrameAction.Continue
 *     }
 * }
 * ```
 */
suspend fun motionFrameLoop(
    frameDurationSeconds: Double = 1.0 / 60.0,
    maxFrames: Double = 2.0,
    timeScale: Double? = null,
    onTick: (MotionTick) -> MotionFrameAction,
) {
    val rawFrame = max(1.0 / 240.0, frameDurationSeconds)
    var previousNanos = 0L
    var virtualNow = 0.0
    while (true) {
        val action = withFrameNanos { frameNanos ->
            val rawDt = if (previousNanos == 0L) rawFrame else min(max((frameNanos - previousNanos) / 1e9, 0.0), maxFrames * rawFrame)
            previousNanos = frameNanos
            // iOS samples `AnimationTimeScale.shared.scale` every frame, so slow motion engaged mid-flight applies at once.
            val scale = timeScale ?: AnimationTimeScale.shared.scale
            val dt = rawDt * scale
            virtualNow += dt
            onTick(MotionTick(dt = dt, now = virtualNow, rawFrameDuration = rawFrame, scale = scale))
        }
        if (action == MotionFrameAction.Stop) return
    }
}

/** The current display's frame duration in seconds (1/120 on a 120 Hz panel), for [motionFrameLoop]. */
@Composable
fun rememberFrameDurationSeconds(): Double {
    val view = LocalView.current
    val rate = view.display?.refreshRate?.takeIf { it > 0f } ?: 60f
    return remember(rate) { 1.0 / rate }
}

/**
 * Android's Reduce Motion: Settings → Accessibility → Remove animations (which sets the
 * animator duration scale to 0). Live-updating. iOS reads `accessibilityReduceMotion`.
 * Pass it to [MotionSpring.animation] / [Motion.easeOut] so springs become the 0.2 s fade.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduce by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}
