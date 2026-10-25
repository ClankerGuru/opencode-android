package ai.opencode.feature.transcript.rows

import ai.opencode.core.motion.MotionSpring
import ai.opencode.feature.transcript.TranscriptTunables
import androidx.compose.animation.core.FiniteAnimationSpec

/**
 * A row opening or closing (iOS `DisclosureMotion`): critically damped, so nothing passes its
 * place and settles back. The disclosure's one curve, shared with bands that draw themselves
 * with the row's edge (`BandMotion`).
 */
object DisclosureMotion {
    /** iOS `-lab-disclosure-slowmo` (DEBUG captures); Android has no lab flag, so always 1. */
    const val slowmo: Double = 1.0

    /** Closing is quicker than opening. */
    fun duration(opening: Boolean): Double =
        (if (opening) TranscriptTunables.disclosureOpen else TranscriptTunables.disclosureClose) * slowmo

    /** `spring(duration:bounce: 0)` for [opening]. */
    fun spring(opening: Boolean): MotionSpring = MotionSpring(duration(opening))

    /** The rows moving inside the row, on the same curve (iOS `animation(opening:)`). */
    fun <T> animation(opening: Boolean): FiniteAnimationSpec<T> = spring(opening).spec()
}
