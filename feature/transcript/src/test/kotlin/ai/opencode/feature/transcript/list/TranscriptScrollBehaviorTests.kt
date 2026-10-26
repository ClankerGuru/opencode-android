package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.list.TranscriptScrollBehavior.Command
import ai.opencode.feature.transcript.list.TranscriptScrollBehavior.Event
import ai.opencode.feature.transcript.list.TranscriptScrollBehavior.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/*
 * Port of iOS `TranscriptScrollBehaviorTests.swift`.
 *
 * iOS drives most of `TranscriptJumpTests`, `TranscriptAssistiveScrollTests` and
 * `TranscriptRowGrowthTests` through a hosted `TranscriptListViewController` (a real
 * `UICollectionView` in a `UIWindow`, self-sizing cells, `scrollViewDidScroll`). Android's
 * `TranscriptListController` drives a `LazyListState` from LazyColumn layouts, which needs the
 * Compose UI runtime and real text measurement. Where such a test checks a pure behaviour the
 * controller delegates to ([JumpGlide], [BottomSpring], [GrowthRide], [RowGrowth],
 * [TranscriptScrollBehavior]), that behaviour is ported under the same name, stepped frame by
 * frame the way the controller's motion loop steps it. The rest are dropped:
 *
 * Dropped (UIKit collection-view harness: self-sizing estimates, content-offset KVO, layout
 * anchoring and presentation layers that LazyColumn owns on Android):
 * - TranscriptAssistiveScrollTests.listOwnMotionIsNotAnAssistiveScroll (the controller tells its own
 *   offset writes from VoiceOver's by `isAssistiveScrolling`; Android's TalkBack scrolls arrive as
 *   LazyColumn scrolls)
 * - TranscriptAssistiveScrollTests.statusBarScrollToTopLeavesTheBottom (no status-bar tap on
 *   Android; `scrollToTop` sends `UserLeftBottom`, pinned by scrollToTopLeavesTheBottom)
 * - TranscriptRowGrowthTests.openingAndDelayedLoadsKeepTheReaderPinnedToTheBottom
 * - TranscriptRowGrowthTests.delayedLoadPreservesBrowsingWhenTheReaderScrolledUp
 * - TranscriptRowGrowthTests.browsingAnchorHoldsAcrossDelayedLoadsStreamingReconnectPagingAndSelfSizing
 * - TranscriptRowGrowthTests.jumpToLatestWhenWindowTrimsGlidesToBottomWithoutTopGapOrLossOfPosition
 * - TranscriptRowGrowthTests.rowResizingAboveWhileUserScrollsHoldsReadingAnchorWithinOnePointFivePt
 * - TranscriptRowGrowthTests.sendingWhileVisibleRowsAreInEvictedPrefixGlidesToBottomWithoutTeleportOrBlankFrame
 * - TranscriptRowGrowthTests.prependingEarlierHistoryDuringDecelerationHoldsAnchorWithinOnePointFivePt
 * - TranscriptRowGrowthTests.tallRowPeekingMoreThan120PtUnderHeaderGrowsUpwardWithoutMovingVisibleRowsBelow
 * - TranscriptRowGrowthTests.openingShortCachedSessionAndFinishingPrewarmedLoadingRefreshStayAlignedWithinOnePointFivePt
 * - TranscriptRowGrowthTests.liveArrivalGrowingDuringAndAfterJumpGlideNeverStallsFollowVelocity
 * - TranscriptRowGrowthTests.landingOnNewMessagesDividerCapturesBrowsingLayoutAnchorAndHoldsWithinOnePointFivePt
 * - TranscriptScrollBehaviorTests.visiblePreTailRowResizingAfterOpeningSettleDoesNotGrowOrMoveHeldTailRow
 * - TranscriptScrollBehaviorTests.shortTranscriptHoldingTailAnchorAcrossPreTailInsertDoesNotOverscrollBottom
 * - TranscriptScrollBehaviorTests.shortTranscriptHistoryRowLeavingGlidesTopGapClosedOverSeveralFrames
 *
 * Skipped (owner :feature:session):
 * - TranscriptAssistiveScrollTests.projectionCacheIsolatesEntryBuildingAndRowPropsReconfigureOnlyAffectedCells
 *   (`SessionTranscriptView.ProjectionCache`)
 * - TranscriptScrollBehaviorTests.browsingWindowKeepsItsLeadingBoundaryAcrossTailAppends
 *   (`SessionFeature.State.visibleItems`)
 */

private const val FRAME = 1.0 / 60.0

/** iOS `JumpGlideBudget`: the glide (0.5 s, up to 0.2 s longer for an end that grows) and a margin. */
private const val JUMP_GLIDE_BUDGET = 1.0

/** iOS `JumpDistance`. */
private enum class JumpDistance { Far, Near }

/** iOS `MotionFrameSampler`'s offset log: steps between frames. */
private class OffsetLog(start: Float) {
    val offsets = mutableListOf(start)
    private val steps: List<Float> get() = offsets.zipWithNext { a, b -> b - a }
    val largestStepBack: Float get() = steps.maxOfOrNull { -it } ?: 0f
    val largestStep: Float get() = steps.maxOfOrNull { abs(it) } ?: 0f
    val movingFrames: Int get() = steps.count { abs(it) > 0.01f }
}

/**
 * Jump to latest as the controller steps it (`glideToBottom`, `beginCutGlide`,
 * `stepBottomSpring`): far ends are cut to [JumpGlide.LEAD] screens above, near ones glide from
 * where the reader is. Returns when it landed, after the tap.
 */
private class JumpSimulation(var offset: Float, var end: Float, visible: Float) {
    val glide: JumpGlide
    val log: OffsetLog
    var time = 0.0
    private val deadline: Double

    init {
        val distance = end - offset
        if (distance > visible * JumpGlide.REACH) {
            val lead = visible * JumpGlide.LEAD
            offset = end - lead
            glide = JumpGlide(lead, JumpGlide.Curve.FastStart)
        } else {
            glide = JumpGlide(distance, JumpGlide.Curve.FromRest)
        }
        deadline = BottomSpring.deadline(0f, glide.distance, SpringKind.Jump, 0.0)
        log = OffsetLog(offset)
    }

    /** One motion frame; false once landed. */
    fun stepFrame(): Boolean {
        time += FRAME
        val remaining = end - offset
        val share = glide.step(FRAME, remaining, pixel = 1f / 3, expired = time > deadline)
        if (share == null) {
            offset = end
            log.offsets += offset
            return false
        }
        offset += remaining * share
        log.offsets += offset
        return true
    }

    /** A frame where layout interrupted the motion: time passes, nothing moves. */
    fun interruptedFrame() {
        time += FRAME
        log.offsets += offset
    }

    fun land(timeout: Double = 2.0): Double {
        while (time < timeout) {
            if (!stepFrame()) return time
        }
        return Double.POSITIVE_INFINITY
    }
}

/** A [BottomSpring] stepped to rest the way the controller's motion loop steps it. */
private fun settle(spring: BottomSpring, from: Float, target: Float, log: OffsetLog): Float {
    var current = from
    var now = 0.0
    while (now < 3) {
        now += FRAME
        when (val step = spring.step(FRAME, current, target, pixel = 1.0 / 3, now = now)) {
            is BottomSpring.Step.Move -> current = step.y
            BottomSpring.Step.Settled -> {
                current = target
                log.offsets += current
                return current
            }
            is BottomSpring.Step.ShrunkEnd -> return current
        }
        log.offsets += current
    }
    return current
}

class TranscriptJumpTests {
    /// A far jump glides: it is not on the end at once, reaches the tail within the glide, and
    /// carries on through an interruption.
    @Test
    fun jumpReachesTheMeasuredTailWithinTheGlideAndSurvivesScrollCancellation() {
        val visible = 700f
        val jump = JumpSimulation(offset = 0f, end = 6_000f, visible = visible)
        assertTrue("A jump glides; it doesn't land at once", jump.end - jump.offset > 1)
        assertTrue(jump.stepFrame())
        assertTrue(jump.stepFrame())
        // Layout/snapshot interruptions don't end the glide.
        jump.interruptedFrame()
        // The measured tail turns out taller than estimated on the way.
        jump.end += 180f
        val landed = jump.land()
        assertTrue("Landed after $landed s", landed < JUMP_GLIDE_BUDGET)
        assertEquals(jump.end, jump.offset, 1f)
    }

    /// The end the jump heads for stays put on the way, it only moves down, and it lands on it.
    @Test
    fun jumpOverRowsNeverShownKeepsItsEndAndOnlyMovesDown() {
        for (distance in JumpDistance.entries) {
            val visible = 700f
            val start = if (distance == JumpDistance.Far) 0f else 4_700f
            val jump = JumpSimulation(offset = start, end = 5_000f, visible = visible)
            val end = jump.end
            if (distance == JumpDistance.Near) {
                assertEquals("A near jump glides from where the reader was", start, jump.offset, 1f)
            }
            val landed = jump.land()
            assertTrue("$distance: landed after $landed s", landed < JUMP_GLIDE_BUDGET)
            assertEquals(end, jump.end, 2f)
            assertEquals(jump.end, jump.offset, 1f)
            assertTrue("$distance: stepped back ${jump.log.largestStepBack} pt", jump.log.largestStepBack < 0.5f)
            assertTrue("$distance: glided over ${jump.log.movingFrames} frames", jump.log.movingFrames >= 5)
        }
    }

    /// Rows leaving the end under a following reader mid-turn hold no space: the reader glides
    /// down onto the new end instead of a one-frame clamp.
    @Test
    fun rowsLeavingTheEndMidTurnSettleAFollowingReaderOntoIt() {
        val before = 2_000f
        // Three rows of about 44 pt leave the end.
        val target = before - 3 * 44f
        val log = OffsetLog(before)
        val spring = BottomSpring(SpringKind.Settle, before, target, 0.0)
        spring.omega = BottomSpring.JUMP_OMEGA
        val landed = settle(spring, before, target, log)
        assertTrue("Not clamped in one frame", before - log.offsets[1] < 30)
        assertTrue("A frame moved ${log.largestStep} pt", log.largestStep < 30)
        assertEquals(target, landed, 1f)
    }

    /// A composer shrinking under a browsing reader near the end leaves them past it: they glide
    /// down onto the end and follow again, rather than being clamped in one frame.
    @Test
    fun aComposerShrinkingUnderABrowsingReaderNearTheEndSettlesThemOntoIt() {
        val behavior = TranscriptScrollBehavior()
        // Following at the end with a 400 pt composer, then 150 pt up, browsing.
        behavior.handle(Event.UserScrolled(viewport(offset = 850f, height = 1_500f, visible = 500f, canRequestEarlierHistory = false)))
        assertTrue(behavior.showsJumpButton)
        // The composer shrinks by 320 pt: the reader stands 170 pt past the end.
        assertEquals(
            listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 850f, height = 1_500f, visible = 820f, canRequestEarlierHistory = false))),
        )
        assertEquals("At the end, following again", Mode.Following, behavior.mode)
        // The follower settles them onto it.
        val before = 850f
        val target = 1_500f - 820f
        val log = OffsetLog(before)
        val spring = BottomSpring(SpringKind.Settle, before, target, 0.0)
        spring.omega = BottomSpring.JUMP_OMEGA
        val landed = settle(spring, before, target, log)
        assertTrue("Not clamped in one frame", abs(log.offsets[1] - before) < 60)
        assertTrue("Glided over ${log.movingFrames} frames", log.movingFrames >= 5)
        assertTrue("A frame moved ${log.largestStep} pt", log.largestStep < 60)
        assertTrue("Down onto the end", log.largestStepBack > 0)
        assertEquals(target, landed, 1f)
    }
}

class TranscriptAssistiveScrollTests {
    /// TalkBack's scroll moves the list without a drag; the reader is browsing from then on, so
    /// arrivals and layout no longer pull them back.
    @Test
    fun assistiveScrollUpLeavesTheBottom() {
        val behavior = TranscriptScrollBehavior()
        val bottom = 1_500f
        val up = viewport(offset = bottom - 400, height = 2_000f, visible = 500f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserScrolled(up)))
        // Not pulled back by a later layout pass.
        assertTrue(behavior.handle(Event.LayoutChanged(up)).isEmpty())
        assertEquals(Mode.Browsing, behavior.mode)
    }
}

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

/** iOS `TranscriptScrollBehaviorTests`: the pure state machine. */
class TranscriptScrollBehaviorTests {
    @Test
    fun followingAppendsAndKeyboardLayoutStayAtBottom() {
        val behavior = TranscriptScrollBehavior()
        val viewport = viewport(offset = 700f, height = 1_200f, visible = 500f)

        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport, layoutReady = true)),
        )
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.LayoutChanged(viewport)))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun bottomHysteresisTransitionsBetweenFollowingAndBrowsing() {
        val behavior = TranscriptScrollBehavior()

        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 900f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals(Mode.Following, behavior.mode)
        // A deliberate scroll of more than a few lines leaves the bottom.
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserScrolled(viewport(offset = 870f, height = 1_500f, visible = 500f))))
        assertEquals(Mode.Browsing, behavior.mode)
        // Reaching the bottom mid-drag is not a return, but Jump to latest leaves at once;
        // coming to rest there is the return.
        assertEquals(listOf(Command.ShowJumpButton(false)), behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))))
        assertEquals(Mode.Browsing, behavior.mode)
        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.UserSettled(viewport(offset = 1_000f, height = 1_500f, visible = 500f), contentArrived = false)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun jumpToLatestLeavesTheMomentAnOverscrollingDragReachesTheEnd() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserLeftBottom)
        assertTrue(behavior.showsJumpButton)
        behavior.handle(Event.DragBegan(viewport(offset = 600f, height = 1_500f, visible = 500f)))
        assertTrue("Still 100 pt short", behavior.handle(Event.UserScrolled(viewport(offset = 900f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals(
            "Within the 1 pt of the end",
            listOf(Command.ShowJumpButton(false)),
            behavior.handle(Event.UserScrolled(viewport(offset = 999.5f, height = 1_500f, visible = 500f))),
        )
        // Rubber-banding past the end and springing back changes nothing more.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_080f, height = 1_500f, visible = 500f))).isEmpty())
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_020f, height = 1_500f, visible = 500f))).isEmpty())
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals("Resting decides following", Mode.Browsing, behavior.mode)
        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.UserSettled(viewport(offset = 1_000f, height = 1_500f, visible = 500f), contentArrived = false)),
        )
        assertEquals(Mode.Following, behavior.mode)
        assertFalse(behavior.showsJumpButton)
    }

    @Test
    fun aDecelerationThatPassesTheEndHidesJumpToLatestInFlight() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserLeftBottom)
        behavior.handle(Event.DragBegan(viewport(offset = 200f, height = 1_500f, visible = 500f)))
        behavior.handle(Event.UserScrolled(viewport(offset = 400f, height = 1_500f, visible = 500f)))
        // Flung: one frame 40 pt short, the next already past the end.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 960f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals(listOf(Command.ShowJumpButton(false)), behavior.handle(Event.UserScrolled(viewport(offset = 1_030f, height = 1_500f, visible = 500f))))
    }

    @Test
    fun jumpToLatestReturnsOnlyWellClearOfTheEnd() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserLeftBottom)
        behavior.handle(Event.DragBegan(viewport(offset = 600f, height = 1_500f, visible = 500f)))
        assertEquals(listOf(Command.ShowJumpButton(false)), behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))))
        // Hovering near the edge never flickers it.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 990f, height = 1_500f, visible = 500f))).isEmpty())
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))).isEmpty())
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 940f, height = 1_500f, visible = 500f))).isEmpty())
        assertTrue("Exactly 120 pt away", behavior.handle(Event.UserScrolled(viewport(offset = 880f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserScrolled(viewport(offset = 870f, height = 1_500f, visible = 500f))))
        // Coming back down, it stays until the end itself.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 990f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals(listOf(Command.ShowJumpButton(false)), behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))))
    }

    @Test
    fun heldSpaceIsNotTheEndForJumpToLatestUntilItDrains() {
        val behavior = TranscriptScrollBehavior()
        // The drag dismisses the keyboard (280 pt held) and carries the reader 140 pt up: they
        // leave, still inside the held space.
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        behavior.handle(Event.LayoutChanged(viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true)))
        assertEquals(
            listOf(Command.SetTrailingSpace(140f), Command.ShowJumpButton(true)),
            behavior.handle(Event.UserScrolled(viewport(offset = 1_040f, height = 1_500f, visible = 600f, trailing = 280f, canRequestEarlierHistory = false))),
        )
        // A pause or a wiggle back down there keeps it.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_041f, height = 1_500f, visible = 600f, trailing = 140f, canRequestEarlierHistory = false))).isEmpty())
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_041f, height = 1_500f, visible = 600f, trailing = 140f, canRequestEarlierHistory = false))).isEmpty())
        // Drained and above the content's end, then back down onto it: it leaves mid-drag, and
        // resting there follows again.
        assertEquals(
            listOf(Command.SetTrailingSpace(0f)),
            behavior.handle(Event.UserScrolled(viewport(offset = 800f, height = 1_500f, visible = 600f, trailing = 140f, canRequestEarlierHistory = false))),
        )
        assertEquals(
            listOf(Command.ShowJumpButton(false)),
            behavior.handle(Event.UserScrolled(viewport(offset = 900f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false))),
        )
        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.UserSettled(viewport(offset = 900f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false), contentArrived = false)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun leavingThroughHeldSpacePastTheContentsEndKeepsJumpToLatest() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        behavior.handle(Event.LayoutChanged(viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true)))
        assertEquals(
            listOf(Command.SetTrailingSpace(140f), Command.ShowJumpButton(true)),
            behavior.handle(Event.UserScrolled(viewport(offset = 1_040f, height = 1_500f, visible = 600f, trailing = 280f, canRequestEarlierHistory = false))),
        )
        // The held space runs out exactly at the content's end on the way up.
        assertEquals(
            listOf(Command.SetTrailingSpace(0f)),
            behavior.handle(Event.UserScrolled(viewport(offset = 900f, height = 1_500f, visible = 600f, trailing = 140f, canRequestEarlierHistory = false))),
        )
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 700f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false))).isEmpty())
        assertTrue(behavior.showsJumpButton)
    }

    @Test
    fun aSlowCreepOntoTheEndStillHidesJumpToLatest() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserLeftBottom)
        behavior.handle(Event.DragBegan(viewport(offset = 990f, height = 1_500f, visible = 500f)))
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 998.8f, height = 1_500f, visible = 500f))).isEmpty())
        assertEquals(listOf(Command.ShowJumpButton(false)), behavior.handle(Event.UserScrolled(viewport(offset = 999.2f, height = 1_500f, visible = 500f))))
    }

    @Test
    fun touchingTheEndMidDragThenRestingAboveItBrowsesWithJumpToLatest() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserLeftBottom)
        behavior.handle(Event.DragBegan(viewport(offset = 600f, height = 1_500f, visible = 500f)))
        behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 500f)))
        assertFalse(behavior.showsJumpButton)
        behavior.handle(Event.UserScrolled(viewport(offset = 950f, height = 1_500f, visible = 500f)))
        assertEquals(
            listOf(Command.ShowJumpButton(true)),
            behavior.handle(Event.UserSettled(viewport(offset = 950f, height = 1_500f, visible = 500f), contentArrived = false)),
        )
        assertEquals(Mode.Browsing, behavior.mode)
    }

    @Test
    fun readerMayRestJustAboveTheBottom() {
        val behavior = TranscriptScrollBehavior()
        val resting = viewport(offset = 1_400f, height = 2_000f, visible = 500f, canRequestEarlierHistory = false)
        behavior.handle(Event.DragBegan(viewport(offset = 1_500f, height = 2_000f, visible = 500f)))
        assertTrue("Within the band mid-drag", behavior.handle(Event.UserScrolled(resting)).isEmpty())
        // 100 pt up at rest: not pulled back, and arrivals no longer follow.
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserSettled(resting, contentArrived = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        assertTrue(
            behavior.handle(
                Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport(offset = 1_400f, height = 2_200f, visible = 500f), layoutReady = true),
            ).isEmpty(),
        )
    }

    @Test
    fun contentArrivingDuringAShortDragKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_500f, height = 2_000f, visible = 500f)))
        val resting = viewport(offset = 1_440f, height = 2_100f, visible = 500f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.UserSettled(resting, contentArrived = true)))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun keyboardLeavingUnderAFollowingDragHoldsAndTheDragDrainsIt() {
        val behavior = TranscriptScrollBehavior()
        // Following at the bottom with the keyboard up (a 320 pt viewport).
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        // The drag dismisses the keyboard: the viewport grows by 280 pt under the finger. The
        // space is held, not pinned (and not rubber-banded back).
        assertEquals(
            listOf(Command.SetTrailingSpace(280f)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true))),
        )
        assertEquals(Mode.Following, behavior.mode)
        // The drag drains the held space as it goes, and 120 pt of it leaves the bottom even
        // though the held space keeps the distance to it at zero.
        assertEquals(
            listOf(Command.SetTrailingSpace(200f)),
            behavior.handle(Event.UserScrolled(viewport(offset = 1_100f, height = 1_500f, visible = 600f, trailing = 280f, canRequestEarlierHistory = false))),
        )
        assertEquals(Mode.Following, behavior.mode)
        assertEquals(
            listOf(Command.SetTrailingSpace(140f), Command.ShowJumpButton(true)),
            behavior.handle(Event.UserScrolled(viewport(offset = 1_040f, height = 1_500f, visible = 600f, trailing = 200f, canRequestEarlierHistory = false))),
        )
        assertEquals(Mode.Browsing, behavior.mode)
    }

    /// The keyboard's space draining under the finger moves the reader with the viewport: that
    /// isn't their travel, so it doesn't leave the bottom.
    @Test
    fun theViewportRidingDownUnderAFingerIsNotTheReadersTravel() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        assertEquals(
            listOf(Command.SetTrailingSpace(280f)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true))),
        )
        // The space drains as the keyboard goes, the view riding down with it.
        assertTrue(behavior.handle(Event.ViewportRode(by = -280f)).isEmpty())
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 900f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false))).isEmpty())
        assertEquals(Mode.Following, behavior.mode)
        // The finger's own 130 pt still leaves.
        assertEquals(
            listOf(Command.ShowJumpButton(true)),
            behavior.handle(Event.UserScrolled(viewport(offset = 770f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false))),
        )
    }

    /// Held space drains on the keyboard's spring: gone in about the keyboard's time, only ever
    /// shrinking, never past nothing.
    @Test
    fun heldSpaceDrainsOnTheKeyboardsSpring() {
        val drain = HeldSpaceDrain()
        var held = 300f
        var elapsed = 0.0
        while (held > 0 && elapsed < 2) {
            val next = drain.step(held, dt = 1.0 / 60, pixel = 1f / 3)
            assertTrue(next <= held && next >= 0)
            held = next
            elapsed += 1.0 / 60
        }
        assertEquals(0f, held)
        assertTrue("Drained in $elapsed s", elapsed > 0.25 && elapsed < 0.6)
    }

    /// The list lets go of held space before the drag settles: 20 pt up, still inside it, the
    /// reader rests past the content's end, so they are at the bottom and settle onto it.
    @Test
    fun aDragThatRestsInsideHeldSpaceSettlesOntoTheEnd() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        behavior.handle(Event.LayoutChanged(viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true)))
        behavior.handle(Event.UserScrolled(viewport(offset = 1_160f, height = 1_500f, visible = 600f, trailing = 280f, canRequestEarlierHistory = false)))
        val resting = viewport(offset = 1_160f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.UserSettled(resting, contentArrived = false)))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun aDragTowardTheEndKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        val resting = viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.UserSettled(resting, contentArrived = false)))
        assertEquals(Mode.Following, behavior.mode)
    }

    /// A drag that left (140 pt up) but came to rest inside the held space, with a reply growing
    /// meanwhile: once the space lets go they stand past the end, at the bottom, and follow again.
    @Test
    fun aDragThatLeftButRestsInsideHeldSpaceFollowsAgain() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        behavior.handle(Event.LayoutChanged(viewport(offset = 1_180f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true)))
        behavior.handle(Event.UserScrolled(viewport(offset = 1_040f, height = 1_500f, visible = 600f, trailing = 280f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        val resting = viewport(offset = 1_040f, height = 1_560f, visible = 600f, canRequestEarlierHistory = false)
        assertEquals(
            listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.UserSettled(resting, contentArrived = true)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun rowsArrivingUnderAFollowingFingerDoNotLeaveTheBottom() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        // More than the leave distance arrives below while the finger barely moves: the reader
        // stays following, and no jump button flashes.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_170f, height = 1_640f, visible = 320f))).isEmpty())
        assertEquals(Mode.Following, behavior.mode)
        val resting = viewport(offset = 1_150f, height = 1_640f, visible = 600f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.UserSettled(resting, contentArrived = true)))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun aFollowerFarBehindAStreamIsNotLeftByALandingFinger() {
        val behavior = TranscriptScrollBehavior()
        // The finger lands while the end is 300 pt ahead of the follower.
        behavior.handle(Event.DragBegan(viewport(offset = 1_200f, height = 2_000f, visible = 500f)))
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_195f, height = 2_000f, visible = 500f))).isEmpty())
        assertEquals(Mode.Following, behavior.mode)
        // Carried 150 pt up by the finger itself, it leaves.
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserScrolled(viewport(offset = 1_050f, height = 2_000f, visible = 500f))))
    }

    @Test
    fun aDragDuringTheFollowersGlideKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        // The follower trails a streaming end by 30 pt when the finger lands.
        behavior.handle(Event.DragBegan(viewport(offset = 1_470f, height = 2_000f, visible = 500f)))
        val resting = viewport(offset = 1_472f, height = 2_000f, visible = 500f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.UserSettled(resting, contentArrived = false)))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun aPillRowGrowingUnderAFingerAtTheEndKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 1_500f, height = 2_000f, visible = 500f)))
        // The capsule's row joins the composer while the finger holds the list: the end now lies
        // 54 pt below, but the reader went nowhere.
        val shrunk = viewport(offset = 1_500f, height = 2_000f, visible = 446f, canRequestEarlierHistory = false, isUserScrolling = true)
        assertTrue(behavior.handle(Event.LayoutChanged(shrunk)).isEmpty())
        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.UserSettled(shrunk.copy(isUserScrolling = false), contentArrived = false)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun scrollToTopLeavesTheBottom() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserLeftBottom))
        assertEquals(Mode.Browsing, behavior.mode)
    }

    @Test
    fun earlierHistoryRequestsOncePerTopApproach() {
        val behavior = TranscriptScrollBehavior()
        val top = viewport(offset = 300f, height = 2_000f, visible = 500f)

        assertTrue(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(top)))
        assertFalse(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(top)))
        assertEquals(
            listOf(Command.AdjustOffset(800f)),
            behavior.handle(Event.ContentChanged(anchorDelta = 800f, appended = false, viewport = top, layoutReady = true)),
        )
        assertTrue(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(top)))
    }

    @Test
    fun rowsArrivingBelowABrowsingReaderNeitherMoveThemNorRearmPaging() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserLeftBottom)
        val top = viewport(offset = 300f, height = 2_000f, visible = 500f)
        assertTrue(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(top)))
        // A catch-up lands below; the reader's row did not move (the list passes only the
        // anchor's own delta, not the offset shift).
        val grown = viewport(offset = 300f, height = 4_000f, visible = 500f)
        assertTrue(behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = grown, layoutReady = true)).isEmpty())
        assertFalse("One request per approach", Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(grown)))
    }

    @Test
    fun anAssistiveScrollJustAboveTheBottomLeavesIt() {
        val behavior = TranscriptScrollBehavior()
        // TalkBack scrolls 80 pt up: no drag, so no drag start.
        val up = viewport(offset = 1_420f, height = 2_000f, visible = 500f, canRequestEarlierHistory = false)
        assertTrue(behavior.handle(Event.UserScrolled(up)).isEmpty())
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.UserSettled(up, contentArrived = false)))
        assertEquals(Mode.Browsing, behavior.mode)
    }

    @Test
    fun aFollowingReaderResolvesHeldKeyboardSpaceMidRun() {
        val behavior = TranscriptScrollBehavior()
        // The drag that dismisses the keyboard holds 280 pt under the reader; it rests at the end
        // of that space, still following. The list lets the space go first (running or not), and
        // the end comes back up.
        behavior.handle(Event.DragBegan(viewport(offset = 1_180f, height = 1_500f, visible = 320f)))
        assertEquals(
            listOf(Command.SetTrailingSpace(282f)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 1_182f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true))),
        )
        val resting = viewport(offset = 1_182f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.UserSettled(resting, contentArrived = false)))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun completedOverlappingHistoryPageRearmsWithoutChainingFromStaleGeometry() {
        val behavior = TranscriptScrollBehavior()
        val top = viewport(offset = 300f, height = 2_000f, visible = 500f)

        assertTrue(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(top)))
        assertTrue(behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = false, viewport = top, layoutReady = true)).isEmpty())
        assertTrue(behavior.handle(Event.EarlierHistoryRequestCompleted).isEmpty())
        assertEquals(listOf(Command.RequestEarlierHistory), behavior.handle(Event.UserScrolled(top)))
    }

    @Test
    fun ineligibleViewportDoesNotConsumeRequestLatch() {
        val behavior = TranscriptScrollBehavior()
        val ineligible = viewport(offset = 300f, height = 2_000f, visible = 500f, canRequestEarlierHistory = false)
        val eligible = viewport(offset = 300f, height = 2_000f, visible = 500f)

        assertFalse(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(ineligible)))
        assertEquals(listOf(Command.RequestEarlierHistory), behavior.handle(Event.UserScrolled(eligible)))
    }

    @Test
    fun completionDuringPrependDoesNotStartNextPageBeforeCompensation() {
        val behavior = TranscriptScrollBehavior()
        val top = viewport(offset = 300f, height = 2_000f, visible = 500f)

        assertEquals(listOf(Command.ShowJumpButton(true), Command.RequestEarlierHistory), behavior.handle(Event.UserScrolled(top)))
        assertTrue(behavior.handle(Event.EarlierHistoryRequestCompleted).isEmpty())
        assertEquals(
            listOf(Command.AdjustOffset(800f)),
            behavior.handle(Event.ContentChanged(anchorDelta = 800f, appended = false, viewport = top, layoutReady = true)),
        )

        val compensated = viewport(offset = 1_100f, height = 2_800f, visible = 500f)
        assertTrue(behavior.handle(Event.LayoutChanged(compensated)).isEmpty())
        assertEquals(listOf(Command.RequestEarlierHistory), behavior.handle(Event.UserScrolled(top)))
    }

    @Test
    fun prependAlwaysPreservesReadingPositionWithoutFollowingBottom() {
        val behavior = TranscriptScrollBehavior()
        val commands = behavior.handle(
            Event.ContentChanged(anchorDelta = 640f, appended = true, viewport = viewport(offset = 900f, height = 2_000f, visible = 500f), layoutReady = true),
        )
        assertEquals(listOf(Command.AdjustOffset(640f)), commands)
    }

    @Test
    fun browsingAnchorSurvivesGrowthAndShrinkAboveIt() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserScrolled(viewport(offset = 500f, height = 1_600f, visible = 500f)))

        assertEquals(
            listOf(Command.AdjustOffset(37f)),
            behavior.handle(Event.ContentChanged(anchorDelta = 37f, appended = true, viewport = viewport(offset = 500f, height = 1_637f, visible = 500f), layoutReady = true)),
        )
        assertEquals(
            listOf(Command.AdjustOffset(-19f)),
            behavior.handle(Event.ContentChanged(anchorDelta = -19f, appended = false, viewport = viewport(offset = 537f, height = 1_618f, visible = 500f), layoutReady = true)),
        )
        assertEquals(Mode.Browsing, behavior.mode)
    }

    @Test
    fun browsingReaderIsNeverMovedByArrivals() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserScrolled(viewport(offset = 400f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        // A delegation, a reply, or another client's message joins the end below the reader.
        // (The reader's own send returns them to the end: `MessageSent`.)
        for (height in 1_600..2_400 step 200) {
            val commands = behavior.handle(
                Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport(offset = 400f, height = height.toFloat(), visible = 600f), layoutReady = true),
            )
            assertTrue(commands.isEmpty())
        }
        assertEquals(Mode.Browsing, behavior.mode)
    }

    @Test
    fun sendingReturnsABrowsingReaderToTheEnd() {
        val behavior = TranscriptScrollBehavior()
        assertTrue("A following reader's send arrives like any other row", behavior.handle(Event.MessageSent).isEmpty())
        behavior.handle(Event.UserScrolled(viewport(offset = 400f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        assertEquals(listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = true)), behavior.handle(Event.MessageSent))
        assertEquals(Mode.Following, behavior.mode)
    }

    /// The end shrinking under a following reader holds nothing: running or idle, the follower
    /// settles them onto the new end.
    @Test
    fun aFollowingReaderWhoseEndShrankSettlesOntoIt() {
        val behavior = TranscriptScrollBehavior()
        // At the bottom: offset + visible == content. The tail shrinks by 40 pt.
        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport(offset = 900f, height = 1_460f, visible = 600f), layoutReady = true)),
        )
        assertEquals(
            listOf(Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 900f, height = 1_460f, visible = 600f))),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    /// Rows shrinking under a following finger are held like the viewport changing under it; rows
    /// arriving fill that space first.
    @Test
    fun rowsShrinkingUnderAFollowingFingerAreHeld() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.DragBegan(viewport(offset = 900f, height = 1_500f, visible = 600f)))
        val shrunk = viewport(offset = 900f, height = 1_460f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true)
        assertEquals(
            listOf(Command.SetTrailingSpace(40f)),
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = false, viewport = shrunk, layoutReady = true)),
        )
        val refilled = viewport(offset = 900f, height = 1_485f, visible = 600f, trailing = 40f, canRequestEarlierHistory = false, isUserScrolling = true)
        assertEquals(
            listOf(Command.SetTrailingSpace(15f)),
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = refilled, layoutReady = true)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    /// A row above a browsing reader near the end shrinking in the same update as rows below: the
    /// anchor moves them, and past the end they follow.
    @Test
    fun anAnchorMoveThatLeavesABrowsingReaderPastTheEndFollows() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserScrolled(viewport(offset = 1_370f, height = 1_900f, visible = 500f, canRequestEarlierHistory = false)))
        behavior.handle(Event.UserLeftBottom)
        assertEquals(
            listOf(Command.AdjustOffset(-10f), Command.ShowJumpButton(false), Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.ContentChanged(anchorDelta = -10f, appended = false, viewport = viewport(offset = 1_370f, height = 1_820f, visible = 500f), layoutReady = true)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    /// Rows below a browsing reader shrinking or leaving bring the end up to them: past it,
    /// they're at the bottom and follow again. Farther up, nothing changes.
    @Test
    fun theEndComingUpToABrowsingReaderFollowsAgain() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserScrolled(viewport(offset = 200f, height = 2_000f, visible = 500f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        assertTrue(
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport(offset = 200f, height = 1_900f, visible = 500f), layoutReady = true)).isEmpty(),
        )
        behavior.handle(Event.UserScrolled(viewport(offset = 1_370f, height = 1_900f, visible = 500f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        assertEquals(
            listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport(offset = 1_370f, height = 1_820f, visible = 500f), layoutReady = true)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun pagingThreeTimesThenJumpLandsAtBottom() {
        val behavior = TranscriptScrollBehavior()
        for (page in 0 until 3) {
            val top = viewport(offset = 100f, height = (1_700 + page * 700).toFloat(), visible = 500f)
            assertTrue(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(top)))
            assertEquals(
                listOf(Command.AdjustOffset(700f)),
                behavior.handle(Event.ContentChanged(anchorDelta = 700f, appended = false, viewport = top, layoutReady = true)),
            )
        }

        assertEquals(listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = true)), behavior.handle(Event.JumpToLatestTapped))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun twoWindowsBeforeFirstLayoutDeferScrollingUntilLayoutIsReady() {
        val behavior = TranscriptScrollBehavior()
        val unlaidOut = viewport(offset = 2_000f, height = 0f, visible = 0f)

        assertTrue(behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = unlaidOut, layoutReady = false)).isEmpty())
        assertTrue(behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = unlaidOut, layoutReady = false)).isEmpty())

        val laidOut = viewport(offset = 0f, height = 1_200f, visible = 500f)
        assertEquals(listOf(Command.ScrollToBottom(animated = false)), behavior.handle(Event.LayoutChanged(laidOut)))
    }

    @Test
    fun jumpRequestsMotionAndSessionChangeResetsState() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(Command.ScrollToBottom(animated = true), behavior.handle(Event.JumpToLatestTapped).last())
        behavior.handle(Event.UserScrolled(viewport(offset = 0f, height = 2_000f, visible = 500f)))
        assertEquals(listOf(Command.SetTrailingSpace(0f), Command.ShowJumpButton(false)), behavior.handle(Event.SessionChanged))
        assertEquals(Mode.Following, behavior.mode)
        assertTrue(Command.RequestEarlierHistory in behavior.handle(Event.UserScrolled(viewport(offset = 0f, height = 2_000f, visible = 500f))))
    }

    @Test
    fun keyboardDismissingUnderABrowsingFingerHoldsSpace() {
        val behavior = TranscriptScrollBehavior()
        // Browsing within reach of the bottom, the keyboard up (320 pt viewport).
        behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 320f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        // The drag dismisses the keyboard: the viewport grows by 280 pt under the finger. The
        // range must grow too, or the list clamps the view down by 280.
        assertEquals(
            listOf(Command.SetTrailingSpace(100f)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 1_000f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true))),
        )
        // At the end of that space the reader is still browsing, not following.
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 600f, trailing = 100f, canRequestEarlierHistory = false))).isEmpty())
        assertEquals(Mode.Browsing, behavior.mode)
    }

    /// Without a finger on the list nothing is held: a browsing reader the viewport grew past is
    /// at the bottom and follows again.
    @Test
    fun aViewportGrowingPastABrowsingReaderAtRestFollowsAgain() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 320f, canRequestEarlierHistory = false)))
        assertEquals(Mode.Browsing, behavior.mode)
        assertEquals(
            listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 1_000f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false))),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun positionSpaceDrainsAsTheReaderScrollsUp() {
        val behavior = TranscriptScrollBehavior()
        // Browsing just above the bottom, then the keyboard dismisses under the reader.
        behavior.handle(Event.UserScrolled(viewport(offset = 500f, height = 1_500f, visible = 320f, canRequestEarlierHistory = false)))
        assertTrue(behavior.handle(Event.UserScrolled(viewport(offset = 1_080f, height = 1_500f, visible = 320f, canRequestEarlierHistory = false))).isEmpty())
        assertEquals(Mode.Browsing, behavior.mode)
        assertEquals(
            listOf(Command.SetTrailingSpace(180f)),
            behavior.handle(Event.LayoutChanged(viewport(offset = 1_080f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false, isUserScrolling = true))),
        )
        // Scrolling up leaves the reserved space behind; it shrinks as the reader leaves it.
        assertEquals(
            listOf(Command.SetTrailingSpace(100f)),
            behavior.handle(Event.UserScrolled(viewport(offset = 1_000f, height = 1_500f, visible = 600f, trailing = 180f, canRequestEarlierHistory = false))),
        )
        assertEquals(
            listOf(Command.SetTrailingSpace(0f)),
            behavior.handle(Event.UserScrolled(viewport(offset = 500f, height = 1_500f, visible = 600f, trailing = 100f, canRequestEarlierHistory = false))),
        )
        // So coming back to rest at the bottom resumes following instead of browsing forever.
        assertEquals(
            listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = false)),
            behavior.handle(Event.UserSettled(viewport(offset = 900f, height = 1_500f, visible = 600f, canRequestEarlierHistory = false), contentArrived = false)),
        )
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun openingADisclosureAtTheBottomLeavesItAndClosingItReturns() {
        val behavior = TranscriptScrollBehavior()
        // The tapped row holds its place: the new end lies 360 pt below.
        assertEquals(listOf(Command.ShowJumpButton(true)), behavior.handle(Event.DisclosureToggled(viewport(offset = 1_000f, height = 1_860f, visible = 500f))))
        assertEquals(Mode.Browsing, behavior.mode)
        // Arrivals no longer chase a reader who opened something to read it.
        assertTrue(
            behavior.handle(Event.ContentChanged(anchorDelta = 0f, appended = true, viewport = viewport(offset = 1_000f, height = 1_900f, visible = 500f), layoutReady = true)).isEmpty(),
        )
        assertEquals(listOf(Command.ShowJumpButton(false)), behavior.handle(Event.DisclosureToggled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))))
        assertEquals(Mode.Following, behavior.mode)
    }

    @Test
    fun aDisclosureThatLeavesTheEndInViewKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        assertTrue(behavior.handle(Event.DisclosureToggled(viewport(offset = 1_000f, height = 1_500f, visible = 500f))).isEmpty())
        assertTrue(behavior.handle(Event.DisclosureToggled(viewport(offset = 0f, height = 300f, visible = 500f))).isEmpty())
        assertEquals(Mode.Following, behavior.mode)
        // A browsing reader opening something mid-history keeps browsing.
        behavior.handle(Event.UserScrolled(viewport(offset = 200f, height = 2_000f, visible = 500f)))
        assertTrue(behavior.handle(Event.DisclosureToggled(viewport(offset = 200f, height = 2_400f, visible = 500f))).isEmpty())
        assertEquals(Mode.Browsing, behavior.mode)
    }

    @Test
    fun browsingAboveTheBottomNeedsNoPositionSpace() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(Event.UserScrolled(viewport(offset = 200f, height = 2_000f, visible = 500f)))
        assertEquals(Mode.Browsing, behavior.mode)
        assertTrue(behavior.handle(Event.LayoutChanged(viewport(offset = 200f, height = 2_000f, visible = 800f))).isEmpty())
    }
}

private fun viewport(
    offset: Float,
    height: Float,
    visible: Float,
    trailing: Float = 0f,
    canRequestEarlierHistory: Boolean = true,
    isUserScrolling: Boolean = false,
) = TranscriptScrollBehavior.Viewport(
    offsetY = offset,
    contentHeight = height,
    visibleHeight = visible,
    trailingSpace = trailing,
    canRequestEarlierHistory = canRequestEarlierHistory,
    isUserScrolling = isUserScrolling,
)
