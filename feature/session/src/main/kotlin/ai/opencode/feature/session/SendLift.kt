package ai.opencode.feature.session

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * iOS `SendLift` (`TranscriptListViewController+SendFlights.swift`): how far a send's words aim
 * above their bubble while it is still below them (a time stamp laid out above it pushes it
 * there), so they never dip toward the composer. The lift is a smooth function of how far the
 * bubble is below (a softened `max(0, gap)`), so as the list carries the bubble past the words
 * they take up its motion without a corner in their speed. It holds only a bubble that starts
 * below, never follows one back down, and lets go smoothly when its time is up or something
 * else moves the list.
 *
 * Units are the caller's (the flight works in px); [step]'s times are flight-clock seconds.
 */
internal class SendLift(enabled: Boolean = true) {
    sealed interface Phase {
        data object Pending : Phase
        data object Holding : Phase
        data class Releasing(val since: Double) : Phase
        data object Off : Phase
    }

    var phase: Phase = if (enabled) Phase.Pending else Phase.Off
        private set

    /** The furthest the bubble has risen: one moving back down never holds the words again. */
    var lowestGap = 0f
        private set

    var value = 0f
        private set

    /** Whether the words are visibly lifted, which keeps the flight open. */
    val isLifting: Boolean get() = value > 0.5f

    /**
     * The lift for the frame [elapsed] seconds into the flight, given how far the bubble's top is
     * below the words' ([gap], null while it's off screen).
     */
    fun step(gap: Float?, elapsed: Double, interrupted: Boolean, hold: Double, softness: Float): Float {
        if (phase == Phase.Pending) {
            if (gap == null || gap <= 0.5f || hold <= 0) {
                phase = Phase.Off
                value = 0f
                return 0f
            }
            phase = Phase.Holding
            lowestGap = gap
        }
        if (phase == Phase.Off) {
            value = 0f
            return 0f
        }
        val current = gap ?: lowestGap
        if (phase == Phase.Holding && (interrupted || current > lowestGap + 0.5f || elapsed >= hold)) {
            phase = Phase.Releasing(since = elapsed)
        }
        lowestGap = min(lowestGap, current)
        var weight = 1f
        val releasing = phase
        if (releasing is Phase.Releasing) {
            val t = ((elapsed - releasing.since) / (hold / 2)).coerceIn(0.0, 1.0).toFloat()
            weight = 1 - t * t * (3 - 2 * t)
        }
        value = softened(lowestGap, softness) * weight
        // Risen well past the words, or let go: off for good.
        if (value < 0.05f) {
            phase = Phase.Off
            value = 0f
        }
        return value
    }

    companion object {
        /** `max(0, gap)`, rounded off over about [softness] either side. */
        fun softened(gap: Float, softness: Float): Float {
            if (softness <= 0.5f) return max(0f, gap)
            val x = (gap / softness).toDouble()
            return (softness * (max(0.0, x) + ln1p(exp(-abs(x))))).toFloat()
        }
    }
}
