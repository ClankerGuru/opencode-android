package ai.opencode.feature.system

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Port of iOS `SceneWindow` (`Core/ActiveScene.swift`): whether the app's window is the one in
 * use, so attention haptics and announcements play only there and two windows never buzz twice
 * for one event.
 *
 * iOS tracks the iPad window last touched; Android runs one activity per task, so the window is
 * in use while one of the app's activities is resumed and has window focus (not behind another
 * app in split screen, the shade or a system dialog). Before any activity is seen nothing is
 * tracked and, as on iOS, the window counts as in front.
 */
class SceneWindowClient(private val front: () -> Boolean = { true }) {
    /** Whether this window is the one in use. */
    val isFront: Boolean get() = front()

    companion object Key : DependencyKey<SceneWindowClient> {
        override val liveValue: SceneWindowClient by lazy {
            val tracker = FrontWindowTracker()
            (dependency(AppContext).applicationContext as? Application)?.registerActivityLifecycleCallbacks(tracker)
            SceneWindowClient { tracker.isFront }
        }

        /** Inert: always in front, like iOS's untracked `SceneWindow()`. */
        override val testValue: SceneWindowClient get() = SceneWindowClient()
    }
}

/** The app's resumed activities; window focus is read when asked, so it is never stale. */
private class FrontWindowTracker : Application.ActivityLifecycleCallbacks {
    private val lock = Any()
    private val resumed = ArrayList<WeakReference<Activity>>()
    private var hasSeenActivity = false

    val isFront: Boolean
        get() = synchronized(lock) {
            if (!hasSeenActivity) return true
            resumed.removeAll { it.get() == null }
            resumed.any { it.get()?.hasWindowFocus() == true }
        }

    override fun onActivityResumed(activity: Activity) = synchronized(lock) {
        hasSeenActivity = true
        if (resumed.none { it.get() === activity }) resumed += WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = synchronized(lock) {
        hasSeenActivity = true
        resumed.removeAll { it.get() == null || it.get() === activity }
        Unit
    }

    override fun onActivityDestroyed(activity: Activity) = synchronized(lock) {
        resumed.removeAll { it.get() == null || it.get() === activity }
        Unit
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
