package ai.opencode.core.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Generation-guarded mount/unmount gate for animated surfaces (iOS `PresenceGate`,
 * `OpenCode/Core/Motion/PresenceGate.swift`): mounts immediately on open, runs the
 * presentation animation, and unmounts only when the latest generation's outro completes
 * while still closed, so rapid reversals never unmount a reopened surface and no timer
 * (`delay`) guesses when an animation ends.
 *
 * Immutable for reducer state: each call returns the next gate. Compose's own
 * `AnimatedVisibility` already implements this contract for plain enter/exit; use the gate
 * for custom drivers (an `Animatable` or [SpringSolver] whose completion you observe):
 *
 * ```kotlin
 * val (next, token) = gate.request(open)        // token == null: just mounted, start in onAppear
 * // ... animate; when that animation finishes:
 * val (settled, unmounted) = next.complete(token)
 * ```
 */
data class PresenceGate(
    val wantsOpen: Boolean,
    val isPresented: Boolean,
    val isMounted: Boolean,
    val generation: Int = 0,
    /** True while an outro is in flight waiting for [complete]. */
    private val isClosing: Boolean = false,
) {
    constructor(isOpen: Boolean = false) : this(wantsOpen = isOpen, isPresented = isOpen, isMounted = isOpen)

    /**
     * Prepares the gate for [open]. The token is `null` when the surface just mounted and
     * should wait for its first frame to call [beginAnimation], otherwise the generation to
     * pass to [complete] (or `null` if no transition is needed).
     */
    fun request(open: Boolean): Pair<PresenceGate, Int?> {
        if (open && !isMounted) {
            return copy(wantsOpen = true, isMounted = true, isClosing = false) to null
        }
        return copy(wantsOpen = open).beginAnimation(open)
    }

    /**
     * Starts or retargets the presentation toward [open], returning the new generation if a
     * transition is needed. Repeated `false` calls during an outro are no-ops.
     */
    fun beginAnimation(open: Boolean): Pair<PresenceGate, Int?> {
        val wanting = copy(wantsOpen = open)
        if (isPresented == open) {
            // Unmount at once only if mounted by request(true) and cancelled before any intro began.
            val next = if (!open && !isClosing) wanting.copy(isMounted = false) else wanting
            return next to null
        }
        val nextGeneration = generation + 1
        return wanting.copy(generation = nextGeneration, isPresented = open, isClosing = !open) to nextGeneration
    }

    /**
     * Call when the animation started with [completedGeneration] finishes. Unmounts only if no
     * newer transition started and the surface still wants to be closed; the Boolean says
     * whether it unmounted.
     */
    fun complete(completedGeneration: Int?): Pair<PresenceGate, Boolean> {
        if (completedGeneration != generation || wantsOpen) return this to false
        return copy(isMounted = false, isClosing = false) to true
    }
}

/**
 * Gated blur + opacity + optional rise for content entering or leaving a morphing surface
 * (iOS `MotionBlurReveal` / `.motionBlurReveal`). Blur only runs strictly between
 * `0.02 < progress < 0.98` so resting views pay no offscreen pass; Reduce Motion is
 * opacity-only. [progress] is `0..1` (animate it with [Motion] specs).
 */
fun Modifier.motionBlurReveal(
    progress: Float,
    maxBlur: Dp = Motion.blurPanel,
    rise: Dp = 0.dp,
    reduceMotion: Boolean = false,
): Modifier {
    val clamped = progress.coerceIn(0f, 1f)
    val radius = MotionBlurReveal.blurRadius(clamped, maxBlur.value, reduceMotion)
    val offset = MotionBlurReveal.verticalOffset(clamped, rise.value, reduceMotion)
    val opacity = if (clamped <= 0.02f) 0f else clamped
    val blurred = if (radius > 0f) this.blur(radius.dp) else this
    return blurred.graphicsLayer {
        alpha = opacity
        translationY = offset.dp.toPx()
    }
}

/** The pure maths behind [motionBlurReveal] (iOS's testable statics). */
object MotionBlurReveal {
    fun blurRadius(progress: Float, maxBlur: Float, reduceMotion: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (reduceMotion || clamped >= 0.98f) return 0f
        return (1 - clamped) * maxBlur
    }

    fun verticalOffset(progress: Float, rise: Float, reduceMotion: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (reduceMotion) return 0f
        return (1 - clamped) * rise
    }
}
