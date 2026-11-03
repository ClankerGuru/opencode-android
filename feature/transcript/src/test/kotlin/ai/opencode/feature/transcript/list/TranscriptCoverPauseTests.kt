package ai.opencode.feature.transcript.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the list half of iOS `TranscriptCoverPauseTests.swift`: [TranscriptTimelineGate]. Settings'
 * cover test is in `:feature:settings`. `shownOnlyInAWindowWithNoHiddenOrTransparentAncestor`
 * (`ViewVisibilityObserver`, KVO on ancestor layers) has no Compose counterpart, and the drawer
 * stack tests drive UIKit's `SessionDrawerViewController` page stack in a hosted window.
 */
class TranscriptCoverPauseTests {
    @Test
    fun pausesWheneverTheReaderCantSeeTheList() {
        assertFalse(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = false, covered = false, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = true, sceneInBackground = false, covered = false, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = true, covered = false, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = false, covered = true, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = false, covered = false, drawn = false))
    }
}
