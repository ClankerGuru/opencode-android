package ai.opencode.feature.system.liveactivity

import java.time.Instant

/**
 * Port of iOS `DictationActivityAttributes`: a hands-free recording reads on the lock screen
 * and in the status bar while the app is away, so it isn't forgotten. Tapping it opens the
 * app, where the recording's bar is on screen.
 */
data class DictationActivityAttributes(val serverID: String) {
    data class ContentState(
        /** The session the words will be sent in; “New session” on a draft. */
        val title: String,
        /** When the recording went hands-free; the system counts the timer from here. */
        val startedAt: Instant,
    )
}

enum class DictationCommand { Stop }

/** Port of iOS `StopDictationIntent`. */
object DictationIntents {
    const val STOP_TITLE = "Stop Recording"
}
