package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.SpringSolver
import ai.opencode.feature.transcript.TranscriptTunables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.max

/**
 * How a new row arrives (iOS `RowEntrance.Style`). `Arrival`: fades in on `Motion.swap` while it
 * rises into place on `Motion.layout`. `Settling`: a quiet row landing among rows already read (a
 * model switch) fades evenly over the whole rise, so it's seen arriving rather than popping in.
 * `WithFlight`: a sent message's time stamp, held back until the flight's last stretch, then
 * settling down into place as its bubble lands, on the send flight's own clock.
 */
enum class RowEntranceStyle { Arrival, Settling, WithFlight }

/**
 * A new row at the end fades and rises a few points into place (iOS `RowEntrance`).
 * Presentation-only (a graphics layer): layout, reuse and self-sizing are unaffected.
 */
object RowEntrance {
    val RISE: Float get() = Motion.entranceRise.value
    /** Larger insertions are a refresh or a page, not arrivals. */
    const val MAXIMUM_ROWS = 4
    /** How long a row inserted off screen may still fade in when it appears. */
    const val WINDOW = 0.5

    /** The send flight's length as a spring's perceptual duration (`2π / ω`), iOS `SendFlight.duration`. */
    val withFlightDuration: Double get() = 2 * PI / max(TranscriptTunables.sendFlightOmega, 0.01)

    /** How long [style]'s entrance runs: its fade or its rise, whichever is longer. */
    fun duration(style: RowEntranceStyle): Double = when (style) {
        RowEntranceStyle.Arrival, RowEntranceStyle.Settling -> max(MotionTunables.swapInDuration.value, Motion.layout.curve().duration)
        RowEntranceStyle.WithFlight -> withFlightDuration
    }

    /** The longest any entrance runs. */
    val maximumDuration: Double get() = max(duration(RowEntranceStyle.Arrival), withFlightDuration)

    /** Opacity and rise (dp, positive = below) at [progress] `0..1` of [style]'s [duration]. */
    fun pose(progress: Float, style: RowEntranceStyle): Pair<Float, Float> {
        val elapsed = progress * duration(style)
        return when (style) {
            RowEntranceStyle.Arrival -> {
                val fade = Motion.EaseOut.transform(fraction(elapsed, MotionTunables.swapInDuration.value))
                fade to RISE * lift(elapsed)
            }
            RowEntranceStyle.Settling -> {
                // An even fade across the rise's beat.
                val fade = Motion.EaseInOut.transform(fraction(elapsed, Motion.layout.curve().duration))
                fade to RISE * lift(elapsed)
            }
            RowEntranceStyle.WithFlight -> {
                // Held until 35 %, then settles down 4 pt into place.
                val t = Motion.EaseOut.transform(progress.coerceIn(0f, 1f))
                val k = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                k to -4f * (1 - k)
            }
        }
    }

    /** What's left of the rise (`1 → 0`) [elapsed] seconds in, on `Motion.layout`. */
    private fun lift(elapsed: Double): Float {
        val parameters = Motion.layout.curve(MotionPhase.Enter).springParameters
        val (value, _) = SpringSolver.evaluate(from = 1.0, to = 0.0, omega = parameters.omega, zeta = parameters.zeta, elapsed = elapsed)
        return value.toFloat().coerceIn(0f, 1f)
    }

    private fun fraction(elapsed: Double, duration: Double): Float = (elapsed / max(duration, 0.01)).toFloat().coerceIn(0f, 1f)
}

/** Applies a row's entrance pose while [entrance] is non-null (read in the draw phase only). */
fun Modifier.rowEntrance(entrance: () -> Pair<Float, RowEntranceStyle>?): Modifier = graphicsLayer {
    val current = entrance()
    if (current == null) {
        alpha = 1f
        translationY = 0f
    } else {
        val (opacity, rise) = RowEntrance.pose(current.first, current.second)
        alpha = opacity
        translationY = rise * density
    }
}
