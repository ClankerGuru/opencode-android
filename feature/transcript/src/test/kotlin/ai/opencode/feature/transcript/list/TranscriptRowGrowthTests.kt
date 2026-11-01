package ai.opencode.feature.transcript.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Port of iOS `TranscriptRowGrowthTests.swift` (split out of `TranscriptScrollBehaviorTests.swift`
 * in iOS 0eb46803, unchanged). The pure growth and ride behaviours ([RowGrowth], [GrowthRide],
 * [BottomSpring]) are stepped frame by frame the way the controller's motion loop steps them.
 *
 * Dropped (UIKit collection-view harness: a hosted `TranscriptListViewController` with
 * self-sizing estimates, content-offset KVO, layout anchoring and presentation layers that
 * LazyColumn owns on Android):
 * - openingAndDelayedLoadsKeepTheReaderPinnedToTheBottom
 * - delayedLoadPreservesBrowsingWhenTheReaderScrolledUp
 * - browsingAnchorHoldsAcrossDelayedLoadsStreamingReconnectPagingAndSelfSizing
 * - jumpToLatestWhenWindowTrimsGlidesToBottomWithoutTopGapOrLossOfPosition
 * - rowResizingAboveWhileUserScrollsHoldsReadingAnchorWithinOnePointFivePt
 * - sendingWhileVisibleRowsAreInEvictedPrefixGlidesToBottomWithoutTeleportOrBlankFrame
 * - prependingEarlierHistoryDuringDecelerationHoldsAnchorWithinOnePointFivePt
 * - tallRowPeekingMoreThan120PtUnderHeaderGrowsUpwardWithoutMovingVisibleRowsBelow
 * - openingShortCachedSessionAndFinishingPrewarmedLoadingRefreshStayAlignedWithinOnePointFivePt
 * - liveArrivalGrowingDuringAndAfterJumpGlideNeverStallsFollowVelocity
 * - landingOnNewMessagesDividerCapturesBrowsingLayoutAnchorAndHoldsWithinOnePointFivePt
 */

private const val FRAME = 1.0 / 60.0

class TranscriptRowGrowthTests {
    /// Rows arriving together grow faster than arrivals glide: the view rides them at that speed
    /// and no faster, keeps that speed while the growth still lies ahead (no stall back to rest),
    /// and lands on the end.
    @Test
    fun rowsGrowingTogetherRideAtTheGlideSpeedWithoutStalling() {
        val base = 1_000f
        val rows = (0 until 4).map { RowGrowth(0f, 110f, 0.0, 440f) }
        val ride = GrowthRide()
        val step = (BottomSpring.ARRIVAL_SPEED * FRAME).toFloat()
        var time = 0.0
        var offset = base
        var end = base
        val samples = mutableListOf(offset to end)
        // The growth frames, ridden (the controller's `GrowthRide` pass).
        while (rows.any { !it.isFinished }) {
            time += FRAME
            rows.forEach { it.advance(FRAME, time) }
            val endBefore = end
            end = base + rows.sumOf { it.height.toDouble() }.toFloat()
            offset = ride.offset(offset, endBefore, end, step)
            samples += offset to end
        }
        // What the ride left over goes to the follower at the same speed.
        if (end - offset > 0.5f) {
            val spring = BottomSpring(SpringKind.Arrival, offset, end, time)
            if (ride.deficit > 0.5f) spring.velocity = BottomSpring.ARRIVAL_SPEED
            ride.reset()
            while (time < 3) {
                time += FRAME
                when (val result = spring.step(FRAME, offset, end, 1.0 / 3, time)) {
                    is BottomSpring.Step.Move -> offset = result.y
                    BottomSpring.Step.Settled -> offset = end
                    is BottomSpring.Step.ShrunkEnd -> {}
                }
                samples += offset to end
                if (offset == end) break
            }
        }
        val grown = end - base
        assertTrue("The rows grew $grown pt; too little to need riding", grown > 300)
        assertEquals(end, offset, 1f)
        val steps = samples.zipWithNext { previous, now -> (now.first - previous.first) to (now.second - now.first) }
        val largest = steps.maxOf { it.first }
        assertTrue("A frame moved $largest pt", largest <= 18.5f)
        // Once at full speed, the view keeps it while the end is still far.
        val stalls = steps.zipWithNext().filter { (previous, now) -> previous.first >= 15 && now.first < previous.first / 2 && now.second > 40 }
        assertTrue("Slowed from full speed short of the end: $stalls", stalls.isEmpty())
    }

    @Test
    fun rideCarriesWhatOneFrameLeftOverAtTheSameSpeed() {
        val ride = GrowthRide()
        // Three rows grow 30 pt together at the end in one frame.
        val first = ride.offset(from = 100f, endBefore = 100f, end = 130f, step = 18f)
        assertEquals(118f, first)
        assertEquals(12f, ride.deficit)
        // The next frame, short of the end only by what the ride left, it rides on.
        val second = ride.offset(from = first, endBefore = 130f, end = 135f, step = 18f)
        assertEquals(135f, second)
        assertEquals(0f, ride.deficit)
        // Short of the end for another reason (a follower on its way), it waits.
        val third = ride.offset(from = 100f, endBefore = 160f, end = 170f, step = 18f)
        assertEquals(100f, third)
        // Past the end (settling onto it), growth fills it before the view moves.
        val held = GrowthRide()
        assertEquals(200f, held.offset(from = 200f, endBefore = 180f, end = 190f, step = 18f))
        assertEquals(210f, held.offset(from = 200f, endBefore = 190f, end = 210f, step = 18f))
    }

    /// Content that shrinks while its row is still growing never carries the row past it: it
    /// eases back from where it stands, and a nearer target ahead is reached without passing it.
    @Test
    fun growthNeverPassesItsTarget() {
        var time = 0.0
        fun run(growth: RowGrowth, frames: Int): List<Float> = (0 until frames).map {
            time += 1.0 / 60
            growth.advance(1.0 / 60, time)
            growth.height
        }
        val growth = RowGrowth(226f, 287f, 0.0, 440f)
        val rising = run(growth, 12)
        assertTrue("$rising", rising.last() < 287 && rising.last() > 250)
        growth.retarget(rising.last() - 2)
        val back = run(growth, 40)
        assertTrue("Grew past its content: $back", back.max() <= rising.last() + 0.01f)
        assertEquals(rising.last() - 2, back.last(), 0.01f)

        val ahead = RowGrowth(0f, 200f, 0.0, 440f)
        time = 0.0
        run(ahead, 8)
        ahead.retarget(ahead.height + 0.5f)
        val near = run(ahead, 20)
        assertTrue("Passed a nearer target: $near", near.max() <= near.last() + 0.01f)
    }

    @Test
    fun anImageRowsSpringNeverPassesItsTarget() {
        var time = 0.0
        fun run(growth: RowGrowth, frames: Int): List<Float> = (0 until frames).map {
            time += 1.0 / 60
            growth.advance(1.0 / 60, time)
            growth.height
        }
        val growth = RowGrowth(100f, 420f, 0.0, 440f, springDuration = 0.5)
        val opening = run(growth, 90)
        assertTrue("Reversed: $opening", opening.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(opening.last() == 420f && growth.isFinished)

        // Retargeted just ahead while moving fast, it lands there and stays.
        val ahead = RowGrowth(0f, 400f, 0.0, 440f, springDuration = 0.5)
        time = 0.0
        run(ahead, 10)
        ahead.retarget(ahead.height + 1)
        val near = run(ahead, 30)
        assertTrue("Passed a nearer target: $near", near.max() <= near.last() + 0.01f)
        assertTrue("Reversed: $near", near.zipWithNext().all { (a, b) -> a <= b + 0.01f })
    }

    @Test
    fun growthLandsAtAnotherWidth() {
        val growth = RowGrowth(0f, 120f, 0.0, 440f)
        assertTrue(growth.continues(atWidth = 440f))
        assertFalse(growth.continues(atWidth = 956f))
    }

    /// A streamed reply wrapping onto new lines grows continuously: the follower rides each line
    /// over many frames, never snapping a whole line in one.
    @Test
    fun streamedReplyGrowsContinuouslyWithoutStallingOrSnapping() {
        val line = 22f
        val base = 1_000f
        val step = (BottomSpring.ARRIVAL_SPEED * FRAME).toFloat()
        val ride = GrowthRide()
        var time = 0.0
        // The reply arrives at one pixel and grows to its first line (the controller's entrance).
        val growth = RowGrowth(1f / 3, line, time, 440f)
        var offset = base
        var end = base + growth.height
        val before = offset
        val deltas = mutableListOf<Float>()
        for (chunk in 1..3) {
            if (chunk > 1) growth.retarget(line * chunk)
            repeat(12) {
                time += FRAME
                growth.advance(FRAME, time)
                val endBefore = end
                end = base + growth.height
                val previous = offset
                offset = ride.offset(offset, endBefore, end, step)
                deltas += offset - previous
            }
        }
        val totalGrown = offset - before
        val movingFrames = deltas.count { it > 0.1f }
        val maxStep = deltas.max()
        assertTrue("Expected 3-line growth, got $totalGrown pt", totalGrown > 55)
        assertTrue("Expected continuous multi-frame growth, got $movingFrames moving frames: $deltas", movingFrames >= 15)
        assertTrue("Expected smooth per-frame steps without snapping, max step was $maxStep pt", maxStep <= 18.5f)
    }
}
