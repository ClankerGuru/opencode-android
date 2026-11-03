package ai.opencode.feature.session

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * iOS `ComposerSendTransitionTests` (in `DirectionalPanGestureRecognizerTests.swift`): the send
 * lift (`SendLift`) and the flight's clock (`SendFlight`).
 *
 * Not ported: `interruptedSendRestoresTheBubbleAndRemovesBothSnapshots` (UIKit snapshot views
 * and subview counts) and `liftedWordsAttachmentsAndLabelMoveAsOne` (UIKit frames of the
 * snapshot views; on Android the overlay draws the words, bubble, attachments and label from one
 * lifted aim, `SessionSendFlights.draw`).
 */
class ComposerSendTransitionTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun sendLiftCarriesTheWordsSpeedAcrossTheCrossing() {
        val smooth = wordsPath(start = 33f, travel = 200f) { state, gap, t ->
            state.step(gap = gap, elapsed = t, interrupted = false, hold = 0.3, softness = 16f)
        }
        // Unheld, the spring and the list alone change speed this much a frame (and dip). A hard
        // `max(0, gap)` adds a corner: still to the bubble's speed in a frame. The lift adds nothing.
        val unheld = largestSpeedChange(wordsPath(start = 33f, travel = 200f) { _, _, _ -> 0f })
        val hard = wordsPath(start = 33f, travel = 200f) { _, gap, _ -> maxOf(0f, gap) }
        assertTrue(largestSpeedChange(hard) > unheld + 1)
        assertTrue(largestSpeedChange(smooth) < unheld + 0.3f)
        // Never below where they left, and landed on the bubble.
        assertTrue(smooth.all { it <= 0.01f })
        assertTrue(abs(smooth.last() - (33f - 200f)) < 2)
    }

    @Test
    fun sendLiftArmsOnlyForABubbleBelowAndNeverUnderReduceMotion() {
        val above = SendLift()
        assertEquals(0f, above.step(gap = -4f, elapsed = 0.0, interrupted = false, hold = 0.3, softness = 16f))
        assertEquals(SendLift.Phase.Off, above.phase)
        assertEquals(0f, above.step(gap = 40f, elapsed = 0.02, interrupted = false, hold = 0.3, softness = 16f))
        val reduced = SendLift(enabled = false)
        assertEquals(0f, reduced.step(gap = 40f, elapsed = 0.0, interrupted = false, hold = 0.3, softness = 16f))
        val below = SendLift()
        assertTrue(below.step(gap = 40f, elapsed = 0.0, interrupted = false, hold = 0.3, softness = 16f) >= 40f)
        assertTrue(below.isLifting)
    }

    @Test
    fun sendLiftLetsGoSmoothlyWhenTheBubbleMovesBackDown() {
        // The keyboard goes down mid-flight: the bubble rises a little, then falls away. The
        // words stop holding at once and never hold again.
        val state = SendLift()
        val lifts = mutableListOf<Float>()
        for (frame in 0..30) {
            val t = frame / 60.0
            val gap = if (frame < 6) 30f - frame * 2f else 20f + (frame - 6) * 12f
            lifts += state.step(gap = gap, elapsed = t, interrupted = false, hold = 0.3, softness = 16f)
        }
        assertTrue("The hold never re-engages", lifts.zipWithNext().all { (a, b) -> b <= a + 0.01f })
        assertEquals(0f, lifts.last())
        assertEquals(SendLift.Phase.Off, state.phase)
        // Let go smoothly: no step bigger than a few points.
        assertTrue(lifts.zipWithNext().maxOf { (a, b) -> abs(b - a) } < 6)
        // A drag or a keyboard move lets go the same way.
        val dragged = SendLift()
        val held = dragged.step(gap = 30f, elapsed = 0.0, interrupted = false, hold = 0.3, softness = 16f)
        val first = dragged.step(gap = 30f, elapsed = 1.0 / 60, interrupted = true, hold = 0.3, softness = 16f)
        assertTrue(abs(first - held) < 0.5f)
        assertEquals(0f, dragged.step(gap = 30f, elapsed = 0.2, interrupted = false, hold = 0.3, softness = 16f))
    }

    @Test
    fun sendLiftLetsGoAfterItsTime() {
        val state = SendLift()
        for (frame in 0..17) state.step(gap = 30f, elapsed = frame / 60.0, interrupted = false, hold = 0.3, softness = 16f)
        assertTrue(state.isLifting)
        // Its time is up at 0.3 s; it lets go over half as long again.
        assertTrue(state.step(gap = 30f, elapsed = 0.3, interrupted = false, hold = 0.3, softness = 16f) > 30f)
        val midway = state.step(gap = 30f, elapsed = 0.375, interrupted = false, hold = 0.3, softness = 16f)
        assertTrue(midway > 10f && midway < 25f)
        assertEquals(0f, state.step(gap = 30f, elapsed = 0.45, interrupted = false, hold = 0.3, softness = 16f))
    }

    /** iOS steps by timestamps; the Android flight advances by frame deltas on its own clock. */
    @Test
    fun sendFlightEndsOnTimeWhenNothingIsLifted() {
        // Under Reduce Motion, or with the bubble above the words, the flight lands when its spring does.
        for (lift in listOf(SendLift(enabled = false), SendLift())) {
            val flight = SendFlight(itemID = "m", source = null, awaitsImages = false, lift = lift)
            flight.omega = 14.0
            assertNotNull(flight.advance(0.0, gap = -10f))
            assertEquals(0f, flight.raised)
            assertNotNull(flight.advance(0.3, gap = -10f))
            assertNull(flight.advance(0.3, gap = -10f))
        }
    }

    /**
     * The words' height above where they left, per 60 Hz frame, as a bubble that starts [start]
     * points below them is carried [travel] points up.
     */
    private fun wordsPath(start: Float, travel: Float, lift: (SendLift, Float, Double) -> Float): List<Float> {
        val state = SendLift()
        val path = mutableListOf<Float>()
        for (frame in 0..45) {
            val t = frame / 60.0
            val carried = travel * (1 - (1 + 12 * t) * exp(-12 * t)).toFloat()
            val gap = start - carried
            val progress = (1 - (1 + 14 * t) * exp(-14 * t)).toFloat()
            path += (gap - lift(state, gap, t)) * progress
        }
        return path
    }

    private fun largestSpeedChange(path: List<Float>): Float {
        val steps = path.zipWithNext { a, b -> b - a }
        return steps.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
    }
}
