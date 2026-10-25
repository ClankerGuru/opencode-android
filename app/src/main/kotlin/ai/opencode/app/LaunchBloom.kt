package ai.opencode.app

import android.os.SystemClock

/**
 * Port of iOS `LaunchBloom.swift`'s cold-launch policy on the AndroidX splash screen: the blank
 * cover stays until content is ready (`AppFeature.State.isColdLaunchContentReady`), a deep link,
 * notification, system action or pairing prompt completes it at once, and it never waits longer
 * than [TIMEOUT_MILLIS] (iOS `launch.timeout`, 1.5 s: an unreachable server never sends a
 * snapshot). The content cut is a hard cut, as iOS's default `launch.fade` of 0. Main thread only.
 */
object LaunchBloomController {
    /** iOS `LaunchTunables.timeout` default. */
    const val TIMEOUT_MILLIS = 1_500L

    private var hasCompletedColdLaunch = false
    private var startedAt = 0L

    /** Called once the activity installs its splash screen. */
    fun start() {
        if (startedAt == 0L) startedAt = SystemClock.uptimeMillis()
    }

    /** Ends the cover now: something the person asked for is about to show. */
    fun completeColdLaunch() {
        hasCompletedColdLaunch = true
    }

    /** The splash screen's keep-on-screen condition. */
    fun keepsCover(isContentReady: Boolean): Boolean {
        if (hasCompletedColdLaunch) return false
        if (isContentReady || SystemClock.uptimeMillis() - startedAt >= TIMEOUT_MILLIS) {
            hasCompletedColdLaunch = true
            return false
        }
        return true
    }
}
