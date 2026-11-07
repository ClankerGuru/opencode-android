package ai.opencode.feature.session

import ai.opencode.feature.transcript.list.TranscriptBorrowedDrag
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `SessionScrollEdgeBridge` (`Transcript/TranscriptListView.swift`), which the
 * transcript port leaves to the session screen: what the transcript list and the session chrome
 * share without re-rendering the session. The composer's height (the list's bottom inset), how
 * far content has scrolled under the header (the backdrop's reveal), a jump-to-latest request,
 * and when this screen first drew each Working-line event (so a remount never replays Starting
 * Up or a finale). Plain object, not a reducer: values change per frame.
 */
@Stable
class SessionScrollEdgeBridge {
    /** The composer area's height (dp), the list's bottom content inset. */
    var composerHeight by mutableFloatStateOf(0f)

    /** Content scrolled under the header (dp), capped at the reveal distance and rounded. */
    var scrolledUnderHeader by mutableFloatStateOf(0f)
        private set

    /** Bumped to scroll the list to its latest row. */
    var jumpRequest by mutableIntStateOf(0)

    /** iOS `onBorrowedDrag`: drags on the controls over the list (Jump to latest, queued, capsule) scroll it. */
    val borrowedDrag = TranscriptBorrowedDrag()

    /**
     * iOS `sendTransition`'s list half: the transcript's landing markers and its send hand-off,
     * which `SessionSendFlightOverlay` flies into (the composer's capture is the composer host's).
     */
    val sendLanding = ai.opencode.feature.transcript.user.SendLanding()

    private val workingRunStamps = HashMap<String, Double>()
    private val workingRunOrder = ArrayDeque<String>()

    /** When this screen first drew the event [token] (seconds, `AgentActivityClock`); keeps 64. */
    fun workingRunTime(token: String, now: Double): Double {
        workingRunStamps[token]?.let { return it }
        workingRunStamps[token] = now
        workingRunOrder.addLast(token)
        while (workingRunOrder.size > 64) workingRunStamps.remove(workingRunOrder.removeFirst())
        return now
    }

    /** Whether the transcript has reported where it sits yet; until then the header's bar doesn't animate. */
    var hasPlacedTranscript by mutableStateOf(false)
        private set

    fun scrolled(underHeader: Float, revealDistance: Float) {
        if (!hasPlacedTranscript) hasPlacedTranscript = true
        val capped = min(max(underHeader, 0f), max(revealDistance, MINIMUM_TRACKED_REVEAL)).roundToInt().toFloat()
        if (capped != scrolledUnderHeader) scrolledUnderHeader = capped
    }

    companion object {
        /** Tracked even when nothing reveals with the scroll, so a band that takes over draws at once. */
        const val MINIMUM_TRACKED_REVEAL = 24f
    }
}
