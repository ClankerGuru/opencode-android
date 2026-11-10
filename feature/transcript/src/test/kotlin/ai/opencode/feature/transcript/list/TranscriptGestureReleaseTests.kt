package ai.opencode.feature.transcript.list

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `TranscriptGestureReleaseTests` (`OpenCodeTests/TranscriptGestureReleaseTests.swift`):
 * the reader's hold on the list (`isUserGestureActive`) always lets go once nothing holds it.
 *
 * Android's list returns to the reader in one place, [TranscriptListController.visibilityChanged]
 * (the lifecycle reaching RESUMED covers both the screen and the app coming back), so both
 * return tests drive it. The drag is begun as `TranscriptList` reports it
 * ([TranscriptListController.scrollInProgressChanged]); the unattached [LazyListState] is not
 * scrolling, as a list whose drag never reported its end.
 *
 * Not here:
 * - aSelectionDragLetGoUnderTheFingerHandsTheListBack: the selection's autoscroll runs inside the
 *   list's own `scroll` mutation, so clearing the selection cancels it and the scroll's end settles
 *   the hold; no separate selection hold exists to strand.
 * - aScrollToTopCutShortStillLetsTheEndBeFollowed, aSessionChangeForgetsAScrollToTop: Android has
 *   no `isScrollingToTop` flag; scroll to top is `animateScrollToItem(0)`, which a finger cancels
 *   and whose end settles like any scroll.
 * - aGrowthFinishingUnderTheFingerLeavesTheListToIt: the follower already settles only when no
 *   gesture holds the list (`isUserGestureActive` covers drags and flings); the iOS test needs a
 *   laid-out list and motion clock.
 */
class TranscriptGestureReleaseTests {
    @Test
    fun aDragTheScreenTookIsLetGoWhenTheListReturns() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        controller.scrollInProgressChanged(true)
        controller.isShowingToReader = false
        controller.visibilityChanged()
        assertTrue("A finger still on the list keeps it", controller.isUserGestureActive)
        controller.isShowingToReader = true
        controller.visibilityChanged()
        assertFalse(controller.isUserGestureActive)
    }

    @Test
    fun aDragTheAppTookIsLetGoWhenItReturns() {
        val controller = TranscriptListController(LazyListState(), TestScope())
        controller.scrollInProgressChanged(true)
        // Backgrounding pauses the screen: the same lifecycle change as leaving it.
        controller.isShowingToReader = false
        controller.visibilityChanged()
        assertTrue("A finger still on the list keeps it", controller.isUserGestureActive)
        controller.isShowingToReader = true
        controller.visibilityChanged()
        assertFalse(controller.isUserGestureActive)
    }
}
