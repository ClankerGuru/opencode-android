package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.core.touch.TouchRecovery
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Port of iOS `MarkLoadingTests.swift`. iOS advances a TCA `TestClock` only to read elapsed
 * seconds; [MarkLoadingTiming] takes times in seconds, so the elapsed times are passed directly.
 *
 * Dropped (UIKit scene and cell lifecycle; Android's `MarkLoadingView` takes `isHidden` from the
 * list, and its pause rule is pinned by pulseTimelineRunsOnlyWhileALoadShowsOnScreen):
 * - pulsePausesInBackgroundBehindAnotherScreenAndOffScreen (scene notifications, `viewDidDisappear`,
 *   `willDisplay`/`didEndDisplaying` cell callbacks)
 * - pulsePausesWhenItsRowLeavesTheSnapshotWhileDisplayed (diffable snapshot + cell callbacks)
 * - reusedCellNeverHostsALoadingRow (collection-view cell reuse)
 * - listDetachedWhileBackgroundedPulsesAgainWhenShown (window attach/detach)
 * - cancellingHandoffRestoresInteractionAndRemovesPresentation (`TranscriptMarkHandoff` snapshots
 *   the list into a `UIView` picture and masks its layer; Android crossfades `emptyHandoff` in
 *   composition)
 * - activeHandoffDoesNotCountTowardDisabledInvariant (DEBUG `TranscriptTouchSafety` invariant; n/a,
 *   see docs/parity/devtools.md)
 *
 * activeHandoffOwnsTheListOnlyUntilItsRealTimeDeadline keeps its hand-off half; its tail (a stranded
 * `isUserInteractionEnabled` flag released after a fresh grace) is n/a: Android's list has no such
 * flag. The watch is built from the controller's own fault and release, as `TranscriptList` does.
 *
 * The hand-off tests read "interaction" as [TranscriptListController.emptyHandoff]: while it is set,
 * the leaving mark takes every touch and the list hides from TalkBack. iOS's `TestClock` is the
 * [TestScope] scheduler, and its `AnimationTimeScale` parameter the shared scale, reset at once.
 */
class MarkLoadingTests {
    @Test
    fun fastLoadNeverStartsAHandoff() {
        val timing = MarkLoadingTiming(start = 0.0)
        val elapsed = 0.299
        assertEquals(0.0, timing.opacity(elapsed), 0.0)
        assertNull(timing.handoffDelay(elapsed))
        // Ending before the delay removes the row outright: no leaving copy survives.
        assertNull(timing.handoffDelay(0.3))
    }

    @Test
    fun coldLoadHoldsMinimumThenCrossfades() {
        val timing = MarkLoadingTiming(start = 0.0)
        assertEquals(0.0, timing.opacity(0.3), 0.0)
        val halfway = 0.4
        assertEquals(0.5, timing.opacity(halfway), 0.0001)
        val arrived = 0.5
        val hold = requireNotNull(timing.handoffDelay(arrived))
        assertEquals(0.2, hold, 0.0001)
        assertEquals(1.0, timing.opacity(arrived), 0.0)
        assertTrue(requireNotNull(timing.handoffDelay(0.699)) > 0)
        assertEquals(0.0, requireNotNull(timing.handoffDelay(0.7)), 0.0001)
        assertEquals(0.85, arrived + hold + timing.fadeOut, 0.0001)
    }

    @Test
    fun loadingGlyphPulsesFromFullAndHoldsStillUnderReduceMotion() {
        // The pulse starts at full when the entrance starts, so the fade in is not dimmed.
        assertEquals(1.0, LoadingGlyphPulse.opacity(0.0, reducedMotion = false), 0.0)
        assertEquals(1f, LoadingGlyphPulse.scale(0.0, reducedMotion = false))
        assertEquals(1.0, LoadingGlyphPulse.opacity(null, reducedMotion = false), 0.0)
        assertEquals(1.0, LoadingGlyphPulse.opacity(-0.3, reducedMotion = false), 0.0)
        val trough = 0.85
        assertEquals(0.45, LoadingGlyphPulse.opacity(trough, reducedMotion = false), 0.0001)
        assertEquals(0.94, LoadingGlyphPulse.scale(trough, reducedMotion = false).toDouble(), 0.0001)
        assertEquals(1.0, LoadingGlyphPulse.opacity(1.7, reducedMotion = false), 0.0001)
        var elapsed = 0.0
        while (elapsed <= 4.0 + 1e-9) {
            assertEquals(0.5, LoadingGlyphPulse.opacity(elapsed, reducedMotion = true), 0.0)
            assertEquals(1f, LoadingGlyphPulse.scale(elapsed, reducedMotion = true))
            elapsed += 0.4
        }
    }

    @Test
    fun pulseTimelineRunsOnlyWhileALoadShowsOnScreen() {
        assertFalse(markLoadingIsPaused(loadingSince = 1.0, isHidden = false, settled = true, reducedMotion = false))
        assertTrue(markLoadingIsPaused(loadingSince = 1.0, isHidden = true, settled = false, reducedMotion = false))
        assertTrue(markLoadingIsPaused(loadingSince = null, isHidden = false, settled = false, reducedMotion = false))
        assertTrue(markLoadingIsPaused(loadingSince = 1.0, isHidden = false, settled = true, reducedMotion = true))
    }

    @Test
    fun arrivalDuringFadeInFinishesEntranceWithoutBlinking() {
        val timing = MarkLoadingTiming(start = 10.0)
        val arrival = 10.31
        assertTrue(timing.opacity(arrival) > 0)
        assertEquals(0.39, timing.handoffDelay(arrival) ?: 0.0, 0.0001)
        assertEquals(0.0, requireNotNull(timing.handoffDelay(12.0)), 0.0)
    }

    @Test
    fun cachedSwitchNeverStartsIndicatorEvenDuringRefresh() {
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "cached-message", text = "Cached message"))
        val controller = TranscriptListController(LazyListState(), TestScope())
        controller.update(
            TranscriptConfiguration(sessionID = "cached", entries = TranscriptEntry.build(items), isLoading = true),
        )
        assertNull(controller.emptyLoadingSince)
        // A second later, still nothing shows.
        assertEquals(0.0, MarkLoadingTiming(start = controller.emptyLoadingSince).opacity(1.0), 0.0)
        assertNull(controller.emptyHandoff)
    }

    @Test
    fun backgroundDuringLoadingHandoffRestoresInteraction() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 0.2, fade = MarkLoadingTunables.fadeOut)
        assertNotNull(controller.emptyHandoff)
        controller.isShowingToReader = false
        controller.visibilityChanged()
        controller.isShowingToReader = true
        controller.visibilityChanged()
        assertNull(controller.emptyHandoff)
    }

    @Test
    fun suspendedAnimationCannotKeepTranscriptDisabledPastDeadline() {
        for (scale in AnimationTimeScale.supportedScales) {
            val scope = TestScope()
            // No frame loop runs here: the hand-off can only end by its own deadline.
            val controller = TranscriptListController(LazyListState(), scope)
            try {
                AnimationTimeScale.shared.setScale(scale, bypassAccessCheck = true)
                controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 1.0, fade = MarkLoadingTunables.fadeOut)
            } finally {
                AnimationTimeScale.shared.reset()
            }
            // Maximum supported hold (1 s) and fade (0.5 s) must finish even at 0.1×.
            scope.testScheduler.advanceTimeBy((1500 / scale).toLong())
            scope.testScheduler.runCurrent()
            assertNotNull("scale $scale", controller.emptyHandoff)
            scope.testScheduler.advanceTimeBy(200)
            scope.testScheduler.runCurrent()
            assertNull("scale $scale", controller.emptyHandoff)
        }
    }

    @Test
    fun cancelledHandoffDeadlineCannotUnlockItsReplacement() {
        val scope = TestScope()
        val controller = TranscriptListController(LazyListState(), scope)
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 0.2, fade = MarkLoadingTunables.fadeOut)
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 2.0, fade = MarkLoadingTunables.fadeOut)
        scope.testScheduler.advanceTimeBy(1700)
        scope.testScheduler.runCurrent()
        assertNotNull(controller.emptyHandoff)
        scope.testScheduler.advanceTimeBy(1000)
        scope.testScheduler.runCurrent()
        assertNull(controller.emptyHandoff)
    }

    @Test
    fun activeHandoffOwnsTheListOnlyUntilItsRealTimeDeadline() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.Transcript,
            inspect = { controller.touchRecoveryFault() },
            release = { controller.releaseTouchRecoveryFault() },
        )
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 1.0, fade = MarkLoadingTunables.fadeOut)
        assertNotNull(controller.emptyHandoff)
        assertFalse(watch.sample(now = 0.0))
        // Past the animation grace but inside its longest run: still the hand-off's.
        assertFalse("The hand-off owns the list until it lands", watch.sample(now = 2.0))
        assertTrue(
            "However slow motion runs",
            watch.sample(now = 0.3, wall = MarkLoadingTiming.handoffTouchDeadline + 0.1),
        )
        assertNull(controller.emptyHandoff)
    }

    // From iOS `TranscriptTouchSafetyTests`: its hand-off cases drive the transcript's watch, which
    // Android builds from this controller. Under tests the hand-off starts at 0 real seconds (the
    // test `Clock`), as iOS's `startedAt: 0`.

    @Test
    fun p282HandoffThatNeverLandsIsReleasedOnARealTimeDeadline() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        val watch = transcriptWatch(controller)
        // Neither the frame loop nor the hand-off's own deadline (the TestScope never runs) arrives.
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 1.0, fade = MarkLoadingTunables.fadeOut)
        assertFalse(watch.sample(now = 0.0))
        assertFalse(watch.sample(now = 2.0))
        assertNotNull(controller.emptyHandoff)
        assertTrue(watch.sample(now = 30.0))
        assertNull(controller.emptyHandoff)
    }

    @Test
    fun handoffDeadlineRunsFromItsStartNotFromWhenTheNetFirstSawIt() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        val watch = transcriptWatch(controller)
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 1.0, fade = MarkLoadingTunables.fadeOut)
        // First seen two seconds in (the screen was paused, say).
        assertFalse(watch.sample(now = 2.0, wall = 2.0))
        assertNotNull(controller.emptyHandoff)
        assertTrue(watch.sample(now = 2.1, wall = MarkLoadingTiming.handoffTouchDeadline + 0.1))
        assertNull(controller.emptyHandoff)
    }

    @Test
    fun handoffInsideItsDeadlineIsNotExpedited() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        val watch = transcriptWatch(controller)
        controller.beginHandoff(loading = true, showsEmpty = false, since = 0.0, time = transcriptNow(), hold = 1.0, fade = MarkLoadingTunables.fadeOut)
        assertFalse(watch.sample(now = 0.0))
        assertFalse(watch.expedite(touchBegan = 0.5, now = 1.0, wall = 1.0))
        assertNotNull(controller.emptyHandoff)
        assertTrue(watch.expedite(touchBegan = 0.5, now = 3.0, wall = 3.0))
        assertNull(controller.emptyHandoff)
    }

    private fun transcriptWatch(controller: TranscriptListController): TouchRecovery {
        return TouchRecovery(
            owner = TouchRecovery.Owner.Transcript,
            inspect = { controller.touchRecoveryFault() },
            release = { controller.releaseTouchRecoveryFault() },
        )
    }
}
