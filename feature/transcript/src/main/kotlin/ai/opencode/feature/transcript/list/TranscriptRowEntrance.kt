package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.TranscriptTunables
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * How a new row arrives (iOS `RowEntrance.Style`). `Settling`: a quiet row landing among rows
 * already read (a model switch) takes a slower, even fade. `WithFlight`: a sent message's time
 * stamp, held back until the flight's last stretch.
 */
enum class RowEntranceStyle { Arrival, Settling, WithFlight }

/**
 * A new row at the end fades and rises a few points into place (iOS `RowEntrance`).
 * Presentation-only (a graphics layer): layout, reuse and self-sizing are unaffected.
 */
object RowEntrance {
    val DURATION: Double get() = TranscriptTunables.entranceDuration
    val SETTLING_DURATION: Double get() = TranscriptTunables.settlingDuration
    /** About as long as a send's flight. */
    const val WITH_FLIGHT_DURATION = 0.45
    /** A row leaving the screen without a motion of its own fades out this fast. */
    const val EXIT_DURATION = 0.2
    val RISE: Float get() = TranscriptTunables.entranceRise
    /** Larger insertions are a refresh or a page, not arrivals. */
    const val MAXIMUM_ROWS = 4
    /** How long a row inserted off screen may still fade in when it appears. */
    const val WINDOW = 0.5

    private val arrivalEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    private val settlingEasing = FastOutSlowInEasing.let { CubicBezierEasing(0.42f, 0f, 0.58f, 1f) }
    private val withFlightEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** Opacity and rise (dp, positive = below) at [progress] `0..1` of [style]'s duration. */
    fun pose(progress: Float, style: RowEntranceStyle): Pair<Float, Float> = when (style) {
        RowEntranceStyle.Arrival -> {
            val t = arrivalEasing.transform(progress)
            t to RISE * (1 - t)
        }
        RowEntranceStyle.Settling -> {
            val t = settlingEasing.transform(progress)
            t to RISE * (1 - t)
        }
        RowEntranceStyle.WithFlight -> {
            // Held until 35 %, then settles down 4 pt into place.
            val t = withFlightEasing.transform(progress)
            val k = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
            k to -4f * (1 - k)
        }
    }
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
