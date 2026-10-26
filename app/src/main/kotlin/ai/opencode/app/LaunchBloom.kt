package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of iOS `LaunchBloom.swift`'s cold-launch policy on the AndroidX splash screen: the blank
 * cover stays until content is ready (`AppFeature.State.isColdLaunchContentReady`), a deep link,
 * notification, system action or pairing prompt completes it at once, and it never waits longer
 * than [TIMEOUT_MILLIS] (iOS `launch.timeout`, 1.5 s: an unreachable server never sends a
 * snapshot). The content cut is a hard cut, as iOS's default `launch.fade` of 0. Main thread only.
 * Time is the monotonic [Clock] (iOS `@Dependency(\.continuousClock)`).
 */
object LaunchBloomController {
    /** iOS `LaunchTunables.timeout` default. */
    const val TIMEOUT_MILLIS = 1_500L

    /** iOS `hasCompletedColdLaunch`: once true the cover never shows again in this process. */
    internal var hasCompletedColdLaunch = false

    /** When the cover went up; null until [start]. */
    internal var startedAt: Duration? = null

    /** Called once the activity installs its splash screen. */
    fun start() {
        if (startedAt == null) startedAt = dependency(Clock).now()
    }

    /** iOS `isRunning`: the cover is up. */
    val isRunning: Boolean get() = startedAt != null && !hasCompletedColdLaunch

    /** Ends the cover now: something the person asked for is about to show. */
    fun completeColdLaunch() {
        hasCompletedColdLaunch = true
    }

    /** The splash screen's keep-on-screen condition. */
    fun keepsCover(isContentReady: Boolean): Boolean {
        if (hasCompletedColdLaunch) return false
        val now = dependency(Clock).now()
        if (isContentReady || now - (startedAt ?: now) >= TIMEOUT_MILLIS.milliseconds) {
            hasCompletedColdLaunch = true
            return false
        }
        return true
    }
}
