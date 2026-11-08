package ai.opencode.core.scene

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Port of iOS `SceneWindow` (`Core/ActiveScene.swift`): the window a store runs in. Whether it
 * is the one in use, so attention haptics and announcements play only there and two windows
 * never buzz twice for one event, and its [sceneID], so a URL opened from it (the in-app
 * browser's Open in Browser and the links it hands to the system) opens beside that window.
 *
 * iOS tracks the iPad window last touched; Android runs one activity per task, so the window is
 * in use while one of the app's activities is resumed and has window focus (not behind another
 * app in split screen, the shade or a system dialog). Before any activity is seen nothing is
 * tracked and, as on iOS, the window counts as in front. A window's [sceneID] is its task's id
 * ([ActiveScene.sceneID]; iOS: the scene session's `persistentIdentifier`). The app-wide value
 * has none, as iOS's untracked `SceneWindow()`; each tablet window sets its own.
 */
class SceneWindowClient(
    private val front: () -> Boolean = { true },
    private val scene: () -> String? = { null },
) {
    /** Whether this window is the one in use. */
    val isFront: Boolean get() = front()

    /** This window's id, or null when the store isn't a window's own. */
    val sceneID: String? get() = scene()

    companion object Key : DependencyKey<SceneWindowClient> {
        override val liveValue: SceneWindowClient by lazy {
            ActiveScene.install(dependency(AppContext))
            SceneWindowClient(front = { ActiveScene.isFront })
        }

        /** Inert: always in front and no scene, like iOS's untracked `SceneWindow()`. */
        override val testValue: SceneWindowClient get() = SceneWindowClient()
    }
}

/**
 * The app's windows (iOS `ActiveScene`): the activities alive now and the ones resumed, so a URL
 * opens from the window that asked ([open]) and [SceneWindowClient.isFront] can tell the window
 * in use. Window focus is read when asked, so it is never stale.
 */
object ActiveScene {
    private val lock = Any()
    private val alive = ArrayList<WeakReference<Activity>>()
    private val resumed = ArrayList<WeakReference<Activity>>()
    private var hasSeenActivity = false
    private var installed = false

    /** Starts tracking the app's activities. Safe to call more than once. */
    fun install(context: Context) {
        val application = context.applicationContext as? Application ?: return
        synchronized(lock) {
            if (installed) return
            installed = true
        }
        application.registerActivityLifecycleCallbacks(Tracker)
    }

    /** A window's id: its task's (iOS: the scene session's `persistentIdentifier`). */
    fun sceneID(activity: Activity): String = activity.taskId.toString()

    /** The id of the window a view's [context] belongs to; null when it isn't an activity's. */
    fun sceneID(context: Context): String? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return sceneID(current)
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** Whether one of the app's activities is resumed and has window focus (in front before any is seen). */
    val isFront: Boolean
        get() = synchronized(lock) {
            if (!hasSeenActivity) return true
            resumed.removeAll { it.get() == null }
            resumed.any { it.get()?.hasWindowFocus() == true }
        }

    /**
     * The window to open from: the one with [sceneID] while it's still alive, else the one in
     * use (iOS `connectedScene(id:) ?? current`). Null when no window is known.
     */
    fun activity(sceneID: String?): Activity? = synchronized(lock) {
        alive.removeAll { it.get() == null }
        resumed.removeAll { it.get() == null }
        if (sceneID != null) {
            for (reference in alive) {
                val activity = reference.get() ?: continue
                if (!activity.isFinishing && sceneID(activity) == sceneID) return activity
            }
        }
        for (reference in resumed) {
            val activity = reference.get() ?: continue
            if (!activity.isFinishing && activity.hasWindowFocus()) return activity
        }
        null
    }

    /**
     * Opens [url] in whatever claims it, from the window with [sceneID] (or the one in use), so
     * the app it opens appears beside that window; with one window, as before. Returns whether
     * something took it.
     */
    suspend fun open(url: String, sceneID: String?): Boolean = withContext(Dispatchers.Main) {
        val origin: Context = activity(sceneID) ?: dependency(AppContext)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            origin.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private object Tracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = synchronized(lock) {
            if (alive.none { it.get() === activity }) alive += WeakReference(activity)
        }

        override fun onActivityResumed(activity: Activity) = synchronized(lock) {
            hasSeenActivity = true
            if (alive.none { it.get() === activity }) alive += WeakReference(activity)
            if (resumed.none { it.get() === activity }) resumed += WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) = synchronized(lock) {
            hasSeenActivity = true
            resumed.removeAll { it.get() == null || it.get() === activity }
            Unit
        }

        override fun onActivityDestroyed(activity: Activity) = synchronized(lock) {
            alive.removeAll { it.get() == null || it.get() === activity }
            resumed.removeAll { it.get() == null || it.get() === activity }
            Unit
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
