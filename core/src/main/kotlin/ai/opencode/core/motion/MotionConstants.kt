package ai.opencode.core.motion

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A spring in SwiftUI's `(duration, bounce)` vocabulary (iOS `TunableSpring` defaults).
 *
 * SwiftUI's mapping (`Spring(duration:bounce:)`, ported in [SpringSolver.parameters]):
 * ω = 2π / duration, ζ = 1 − bounce (or 1 / (1 + bounce) for negative bounce). A Compose
 * spring with mass 1 has ω = √stiffness, so the exact equivalent is
 * `spring(dampingRatio = ζ, stiffness = ω²)`: [spec].
 *
 * Legacy iOS `.spring(response:dampingFraction:)` is the same curve with
 * `response = duration`, `dampingFraction = ζ`: use [fromResponse].
 */
data class MotionSpring(val duration: Double, val bounce: Double = 0.0) {
    /** Angular frequency ω (rad/s). */
    val omega: Double get() = SpringSolver.parameters(duration, bounce).omega

    /** Damping ratio ζ. */
    val dampingRatio: Float get() = SpringSolver.parameters(duration, bounce).zeta.toFloat()

    /** Compose stiffness (mass 1) = ω². */
    val stiffness: Float get() = (omega * omega).toFloat()

    /** The exact Compose spring. Pass a [visibilityThreshold] for Dp/Offset/Int values. */
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        SpringSpec(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

    /**
     * iOS `TunableSpring.animation(reduceMotion:delay:)`: the spring, or [Motion.reducedFadeSpec]
     * under Reduce Motion (Android: animations removed / animator duration scale 0), optionally
     * delayed by [delaySeconds].
     */
    fun <T> animation(reduceMotion: Boolean, delaySeconds: Double = 0.0, visibilityThreshold: T? = null): FiniteAnimationSpec<T> {
        if (reduceMotion) return Motion.reducedFadeSpec(delaySeconds)
        val base = spec(visibilityThreshold)
        return if (delaySeconds > 0) delayed(base, delaySeconds) else base
    }

    companion object {
        /** iOS `.spring(response:dampingFraction:)`. */
        fun fromResponse(response: Double, dampingFraction: Double): MotionSpring =
            MotionSpring(response, if (dampingFraction <= 1) 1 - dampingFraction else 1 / dampingFraction - 1)
    }
}

/**
 * The shared motion vocabulary (iOS `Motion`, `OpenCode/Core/Motion/MotionConstants.swift`,
 * docs/motion.md). Every spring, fade, delay, stagger, blur radius and rise comes from here
 * or a feature's own constants, never a bare literal at a call site.
 *
 * iOS backs these with live `Tunable`s (Developer Mode → Tuning Panel); here they are the
 * baked defaults.
 */
object Motion {
    // Canonical springs (duration, bounce)

    /** Primary composer / chip / sheet expansion (0.42 s, 0.12 bounce). */
    val morphOpen = MotionSpring(0.42, 0.12)
    /** Primary sheet / morph fold (0.34 s, critically damped). */
    val morphClose = MotionSpring(0.34, 0.0)
    /** Compact bar / capsule / pull-out expansion (0.34 s, 0.12 bounce). */
    val panelOpen = MotionSpring(0.34, 0.12)
    /** Critically damped layout resize, row promotion, or card settle (0.40 s). */
    val settle = MotionSpring(0.40, 0.0)
    /** Disclosure / accordion / inline band expand (0.30 s). */
    val disclosureOpen = MotionSpring(0.30, 0.0)
    /** Disclosure / accordion / inline band collapse (0.24 s). */
    val disclosureClose = MotionSpring(0.24, 0.0)
    /** Small badge, pill, or glyph entrance pop (0.26 s, 0.22 bounce). */
    val microPop = MotionSpring(0.26, 0.22)
    /** Small badge, pill, or glyph exit (0.18 s). */
    val microExit = MotionSpring(0.18, 0.0)
    /** Drawer / back-swipe critically damped slide (0.22 s; ω ≈ 28.5). */
    val drawerSlide = MotionSpring(0.22, 0.0)

    // Fade durations (seconds)

    /** Cross-fade used under Reduce Motion (0.20 s, ease-in-out). */
    const val reducedFade = 0.20
    /** Fast outgoing content / press / chrome fade (0.14 s, ease-out). */
    const val fadeFast = 0.14
    /** Standard incoming content / row fade (0.22 s, ease-out). */
    const val fadeStandard = 0.22
    /** Backdrop / full-bleed dim fade (0.32 s, ease-in-out). */
    const val fadeSlow = 0.32

    // Delays, staggers & handover ratios

    /** One beat after a surface begins expanding before interior content reveals (0.05 s). */
    const val contentDelay = 0.05
    /** Outgoing content clears before incoming bar/panel content arrives (0.10 s). */
    const val handoffDelay = 0.10
    /** Per-item cascade delay across a staggered group (0.02 s). */
    const val staggerStep = 0.02
    /** Fraction of close duration before the source surface retakes opacity (0.60). */
    const val foldHandoverRatio = 0.60

    // Blur radii & entrance rises (iOS points → dp)

    /** Subtle blur for compact capsules (3). */
    val blurSubtle: Dp = 3.dp
    /** Standard blur for morphing panels and docks (8). */
    val blurPanel: Dp = 8.dp
    /** Deep arrival blur for full sheets (14). */
    val blurSheet: Dp = 14.dp
    /** Progressive backdrop blur behind expanded sheets (18). Android: use a scrim, not a blur. */
    val blurBackdrop: Dp = 18.dp
    /** Subtle vertical rise for arriving rows and labels (6). */
    val entranceRise: Dp = 6.dp
    /** Vertical rise for expanding panel content (10). */
    val panelRise: Dp = 10.dp

    /** SwiftUI `.easeInOut` (CSS ease-in-out). */
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** SwiftUI `.easeOut` (CSS ease-out). */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** iOS `Motion.reducedFadeAnimation`: `.easeInOut(duration: reducedFade)`. */
    fun <T> reducedFadeSpec(delaySeconds: Double = 0.0): FiniteAnimationSpec<T> =
        tween(durationMillis = millis(reducedFade), delayMillis = millis(delaySeconds), easing = EaseInOut)

    /** iOS `Tunable<Double>.easeOut(delay:reduceMotion:)` for a fade of [seconds]. */
    fun <T> easeOut(seconds: Double, delaySeconds: Double = 0.0, reduceMotion: Boolean = false): FiniteAnimationSpec<T> =
        if (reduceMotion) reducedFadeSpec(delaySeconds)
        else tween(durationMillis = millis(seconds), delayMillis = millis(delaySeconds), easing = EaseOut)

    /** iOS `Tunable<Double>.easeInOut(delay:reduceMotion:)` for a fade of [seconds]. */
    fun <T> easeInOut(seconds: Double, delaySeconds: Double = 0.0, reduceMotion: Boolean = false): FiniteAnimationSpec<T> =
        if (reduceMotion) reducedFadeSpec(delaySeconds)
        else tween(durationMillis = millis(seconds), delayMillis = millis(delaySeconds), easing = EaseInOut)

    internal fun millis(seconds: Double): Int = (seconds * 1000).roundToInt()
}

/** Delays any finite spec (Compose's `spring` has no delay parameter; SwiftUI's `.delay`). */
internal fun <T> delayed(spec: FiniteAnimationSpec<T>, delaySeconds: Double): FiniteAnimationSpec<T> =
    DelayedSpec(spec, (delaySeconds * 1_000_000_000L).toLong())

private class DelayedSpec<T>(private val base: FiniteAnimationSpec<T>, private val delayNanos: Long) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        DelayedVectorizedSpec(base.vectorize(converter), delayNanos)

    override fun equals(other: Any?) = other is DelayedSpec<*> && other.base == base && other.delayNanos == delayNanos
    override fun hashCode() = base.hashCode() * 31 + delayNanos.hashCode()
}

private class DelayedVectorizedSpec<V : AnimationVector>(
    private val base: VectorizedFiniteAnimationSpec<V>,
    private val delayNanos: Long,
) : VectorizedFiniteAnimationSpec<V> {
    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        if (playTimeNanos < delayNanos) initialValue
        else base.getValueFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        if (playTimeNanos < delayNanos) initialVelocity
        else base.getVelocityFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        delayNanos + base.getDurationNanos(initialValue, targetValue, initialVelocity)
}
