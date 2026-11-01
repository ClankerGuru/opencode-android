package ai.opencode.feature.transcript

import ai.opencode.feature.transcript.list.TranscriptListController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Port of iOS `NewMessagesListTests.swift`.
 *
 * Dropped (hosted `TranscriptListViewController` harness: whether arrivals fit is judged from
 * `UICollectionView` self-sized cell heights versus its 44 pt estimates, window attach/detach and
 * `scrollViewDidScroll` callbacks; Android's controller judges it from LazyColumn layouts, which
 * need the Compose UI runtime):
 * - aTallArrivalAtAnEstimatedHeightIsntJudgedToFit
 * - rowsThatOnlyFitAtTheirEstimateKeepTheDivider (on screen and off)
 * - aShortArrivalFitsAndShowsAtTheEndWithoutTheDivider
 * - withTheExperimentOffEvenATallArrivalEndsAtTheBottom
 * - aWatchedReadSlidesToTheDividerOnlyWhenItDoesntFit (tall and short)
 * - rowsShrinkingBelowTheirEstimateDontRestartTheFollowerOnLanding
 * - rowsShrinkingBelowTheirEstimateDontRestartTheFollowerDuringASlide
 * - shrinkingRowsThatFitEndTheSlideAtTheEnd
 * - oneToolCallUnderWorkingFitsWithoutTheDivider
 * - whatLandedAwayIsDecidedOnceTheReaderIsBack
 * - whatLandedAwayAndDoesntFitShowsTheDividerOnReturn
 * - aDividerThatFitDoesntComeBackWhileStreaming
 * - reachingTheEndPastTheDividerClearsIt
 * - aLongArrivalMeasuresOnlyTheRowsThatProveItDoesntFit (short and long)
 * - withTheExperimentOffAWatchedReadSlidesToTheEnd (tall and short)
 * - dividerWithShortContentBelowClearsWhenReaderScrollsToEnd
 * - aFadedDividerClosesWithoutMovingTheRowsUnderIt
 * - aClearedDividerOnScreenClosesWithTheEndHeld
 * - leavingMidCollapseLandsTheDividerClosed
 * - leavingMidTurnBackLandsTheDividerOpen
 * - scrollingDuringTheFadeKeepsTheDivider
 * - browsingFarUpKeepsTheDividerOnAShortReplyScreensBelow
 * - browsingFromPreviousMarkReevaluatesFitWhenMarkMovesToShortReplyAtEnd
 * - initialOffscreenSnapshotBeforeFirstLayoutStillDecidesFitWhenShown
 * - replyThatFitsWithoutDividerHeightDoesNotShowDividerOrStopShortOfBottom
 */
class NewMessagesListTests {
    /// The visible top is under the header: a divider still behind the header counts as read,
    /// one below it doesn't.
    @Test
    fun theDividerIsPassedOnceItsAboveTheHeadersEdge() {
        assertTrue(NewMessagesPlacement.isPassed(dividerMaxY = 130f, offsetY = 40f, topInset = 100f))
        assertFalse(NewMessagesPlacement.isPassed(dividerMaxY = 150f, offsetY = 40f, topInset = 100f))
    }

    /// With the divider gone the end comes up by its height; a little overflow still reads from the end.
    @Test
    fun whatsNewFitsWithTheDividerGoneAndALittleOverflow() {
        assertTrue(TranscriptListController.newMessagesFit(dividerOffset = 1000f, end = 1014f, dividerHeight = 34f, overflow = 0f))
        assertFalse(TranscriptListController.newMessagesFit(dividerOffset = 1000f, end = 1040f, dividerHeight = 34f, overflow = 0f))
        assertTrue(TranscriptListController.newMessagesFit(dividerOffset = 1000f, end = 1140f, dividerHeight = 34f, overflow = 120f))
        assertFalse(TranscriptListController.newMessagesFit(dividerOffset = 1000f, end = 1160f, dividerHeight = 34f, overflow = 120f))
    }

    /// At the end, the divider still below the header is on the last screen; behind it, it's passed.
    @Test
    fun theDividerIsOnTheLastScreenAtTheEndBelowTheHeader() {
        assertTrue(NewMessagesPlacement.isOnLastScreen(dividerMaxY = 534f, offsetY = 300f, topInset = 100f, end = 300f))
        assertFalse(NewMessagesPlacement.isOnLastScreen(dividerMaxY = 534f, offsetY = 200f, topInset = 100f, end = 300f))
        assertFalse(NewMessagesPlacement.isOnLastScreen(dividerMaxY = 384f, offsetY = 300f, topInset = 100f, end = 300f))
        assertTrue(NewMessagesPlacement.isOnLastScreen(dividerMaxY = 414f, offsetY = 300f, topInset = 100f, end = 300f))
    }
}
