package ai.opencode.core.motion

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableSpring
import ai.opencode.core.tuning.TuningStore
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
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> {
        // Slow motion scales time: ω × s, so stiffness × s² (damping ratio is unchanged).
        val scale = AnimationTimeScale.shared.observedScale.toFloat()
        return SpringSpec(dampingRatio = dampingRatio, stiffness = stiffness * scale * scale, visibilityThreshold = visibilityThreshold)
    }

    /**
     * iOS `TunableSpring.animation(reduceMotion:delay:)`: the spring, or [Motion.reducedFadeSpec]
     * under Reduce Motion (Android: animations removed / animator duration scale 0), optionally
     * delayed by [delaySeconds].
     */
    fun <T> animation(reduceMotion: Boolean, delaySeconds: Double = 0.0, visibilityThreshold: T? = null): FiniteAnimationSpec<T> {
        if (reduceMotion) return Motion.reducedFadeSpec(delaySeconds)
        val base = spec(visibilityThreshold)
        return if (delaySeconds > 0) delayed(base, delaySeconds / AnimationTimeScale.shared.observedScale) else base
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
 * Backed by live [MotionTunables] (iOS `Tunable`s, same keys and defaults): the Tuning Panel
 * (Developer Mode) changes them while you watch; off, each read is the default. Springs and
 * tweens built here also follow Developer Mode slow motion ([AnimationTimeScale]).
 */
object Motion {
    // Canonical springs (duration, bounce), live from [MotionTunables]

    /** Primary composer / chip / sheet expansion (0.42 s, 0.12 bounce). */
    val morphOpen: MotionSpring get() = MotionTunables.morphOpen.spring
    /** Primary sheet / morph fold (0.34 s, critically damped). */
    val morphClose: MotionSpring get() = MotionTunables.morphClose.spring
    /** Compact bar / capsule / pull-out expansion (0.34 s, 0.12 bounce). */
    val panelOpen: MotionSpring get() = MotionTunables.panelOpen.spring
    /** Critically damped layout resize, row promotion, or card settle (0.40 s). */
    val settle: MotionSpring get() = MotionTunables.settle.spring
    /** Disclosure / accordion / inline band expand (0.30 s). */
    val disclosureOpen: MotionSpring get() = MotionTunables.disclosureOpen.spring
    /** Disclosure / accordion / inline band collapse (0.24 s). */
    val disclosureClose: MotionSpring get() = MotionTunables.disclosureClose.spring
    /** Small badge, pill, or glyph entrance pop (0.26 s, 0.22 bounce). */
    val microPop: MotionSpring get() = MotionTunables.microPop.spring
    /** Small badge, pill, or glyph exit (0.18 s). */
    val microExit: MotionSpring get() = MotionTunables.microExit.spring
    /** Drawer / back-swipe critically damped slide (0.22 s; ω ≈ 28.5). */
    val drawerSlide: MotionSpring get() = MotionTunables.drawerSlide.spring

    // Fade durations (seconds)

    /** Cross-fade used under Reduce Motion (0.20 s, ease-in-out). */
    val reducedFade: Double get() = MotionTunables.reducedFade.value
    /** Fast outgoing content / press / chrome fade (0.14 s, ease-out). */
    val fadeFast: Double get() = MotionTunables.fadeFast.value
    /** Standard incoming content / row fade (0.22 s, ease-out). */
    val fadeStandard: Double get() = MotionTunables.fadeStandard.value
    /** Backdrop / full-bleed dim fade (0.32 s, ease-in-out). */
    val fadeSlow: Double get() = MotionTunables.fadeSlow.value

    // Delays, staggers & handover ratios

    /** One beat after a surface begins expanding before interior content reveals (0.05 s). */
    val contentDelay: Double get() = MotionTunables.contentDelay.value
    /** Outgoing content clears before incoming bar/panel content arrives (0.10 s). */
    val handoffDelay: Double get() = MotionTunables.handoffDelay.value
    /** Per-item cascade delay across a staggered group (0.02 s). */
    val staggerStep: Double get() = MotionTunables.staggerStep.value
    /** Fraction of close duration before the source surface retakes opacity (0.60). */
    val foldHandoverRatio: Double get() = MotionTunables.foldHandoverRatio.value

    // Blur radii & entrance rises (iOS points → dp)

    /** Subtle blur for compact capsules (3). */
    val blurSubtle: Dp get() = MotionTunables.blurSubtle.value
    /** Standard blur for morphing panels and docks (8). */
    val blurPanel: Dp get() = MotionTunables.blurPanel.value
    /** Deep arrival blur for full sheets (14). */
    val blurSheet: Dp get() = MotionTunables.blurSheet.value
    /** Progressive backdrop blur behind expanded sheets (18). Android: use a scrim, not a blur. */
    val blurBackdrop: Dp get() = MotionTunables.blurBackdrop.value
    /** Subtle vertical rise for arriving rows and labels (6). */
    val entranceRise: Dp get() = MotionTunables.entranceRise.value
    /** Vertical rise for expanding panel content (10). */
    val panelRise: Dp get() = MotionTunables.panelRise.value

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

    /** Seconds → milliseconds, stretched by Developer Mode slow motion ([AnimationTimeScale]). */
    fun millis(seconds: Double): Int = (seconds * 1000 / AnimationTimeScale.shared.observedScale).roundToInt()
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

/**
 * The [Motion] vocabulary as live tunables: iOS `Motion` in `Core/Motion/MotionConstants.swift`,
 * same keys, labels, defaults, ranges and steps (group `motion` in the Tuning Panel). Keep each
 * declaration on one line: `scripts/tune.py bake` rewrites its default.
 */
object MotionTunables {
    val morphOpen = TunableSpring("motion.morphOpen", "Morph open", duration = 0.42, bounce = 0.12)
    val morphClose = TunableSpring("motion.morphClose", "Morph close", duration = 0.34, bounce = 0.0)
    val panelOpen = TunableSpring("motion.panelOpen", "Panel open", duration = 0.34, bounce = 0.12)
    val settle = TunableSpring("motion.settle", "Layout settle", duration = 0.40, bounce = 0.0)
    val disclosureOpen = TunableSpring("motion.disclosureOpen", "Disclosure open", duration = 0.30, bounce = 0.0)
    val disclosureClose = TunableSpring("motion.disclosureClose", "Disclosure close", duration = 0.24, bounce = 0.0)
    val microPop = TunableSpring("motion.microPop", "Micro pop", duration = 0.26, bounce = 0.22)
    val microExit = TunableSpring("motion.microExit", "Micro exit", duration = 0.18, bounce = 0.0)
    val drawerSlide = TunableSpring("motion.drawerSlide", "Drawer slide", duration = 0.22, bounce = 0.0)

    val reducedFade = Tunable("motion.reducedFade", "Reduce Motion fade (s)", default = 0.20, range = 0.05..0.6, step = 0.01)
    val fadeFast = Tunable("motion.fadeFast", "Fast fade (s)", default = 0.14, range = 0.05..0.5, step = 0.01)
    val fadeStandard = Tunable("motion.fadeStandard", "Standard fade (s)", default = 0.22, range = 0.05..0.6, step = 0.01)
    val fadeSlow = Tunable("motion.fadeSlow", "Slow backdrop fade (s)", default = 0.32, range = 0.1..0.8, step = 0.01)

    val contentDelay = Tunable("motion.contentDelay", "Content arrival delay (s)", default = 0.05, range = 0.0..0.3, step = 0.01)
    val handoffDelay = Tunable("motion.handoffDelay", "Handoff delay (s)", default = 0.10, range = 0.0..0.4, step = 0.01)
    val staggerStep = Tunable("motion.staggerStep", "Stagger step (s)", default = 0.02, range = 0.0..0.15, step = 0.005)
    val foldHandoverRatio = Tunable("motion.foldHandoverRatio", "Fold handover ratio", default = 0.60, range = 0.2..0.95, step = 0.05)

    val blurSubtle = Tunable("motion.blurSubtle", "Subtle blur (pt)", default = 3.dp, range = 0.0..20.0, step = 0.5)
    val blurPanel = Tunable("motion.blurPanel", "Panel blur (pt)", default = 8.dp, range = 0.0..30.0, step = 0.5)
    val blurSheet = Tunable("motion.blurSheet", "Sheet arrival blur (pt)", default = 14.dp, range = 0.0..30.0, step = 0.5)
    val blurBackdrop = Tunable("motion.blurBackdrop", "Backdrop blur (pt)", default = 18.dp, range = 0.0..40.0, step = 0.5)
    val entranceRise = Tunable("motion.entranceRise", "Entrance rise (pt)", default = 6.dp, range = 0.0..24.0, step = 1.0)
    val panelRise = Tunable("motion.panelRise", "Panel content rise (pt)", default = 10.dp, range = 0.0..32.0, step = 1.0)

    /** iOS `Motion.register(in:)`: lists every motion knob before its first read. */
    fun register(store: TuningStore = TuningStore.shared) {
        listOf(morphOpen, morphClose, panelOpen, settle, disclosureOpen, disclosureClose, microPop, microExit, drawerSlide).forEach(store::register)
        store.register(reducedFade, fadeFast, fadeStandard, fadeSlow)
        store.register(contentDelay, handoffDelay, staggerStep, foldHandoverRatio)
        store.register(blurSubtle, blurPanel, blurSheet, blurBackdrop, entranceRise, panelRise)
    }
}
