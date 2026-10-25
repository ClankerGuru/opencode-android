package ai.opencode.devtools.traces

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.diagnostics.CredentialSurface
import ai.opencode.core.diagnostics.ProblemReportBuilder
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `WindowSnapshot` (`TraceTriggers.swift`): the app's window as it looks now, as a JPEG
 * within the report's budget. `drawHierarchy` → `PixelCopy` of the resumed activity's window.
 *
 * Never in the background (no resumed activity), never while a credential surface is composed
 * ([CredentialSurface], iOS's Settings), and never from a `FLAG_SECURE` window. iOS also skips
 * while a secure text field is first responder; Compose has no responder chain to ask, and the
 * app's password fields all live inside Settings, which is a credential surface.
 */
object WindowSnapshot {
    @Volatile private var resumed: WeakReference<Activity>? = null

    /** Follows the resumed activity. Called once from `DevTools.install`. */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** iOS `isAllowed(applicationState:credentialSurfaces:firstResponder:)`. */
    fun isAllowed(isActive: Boolean, credentialSurfaces: Int, isSecureWindow: Boolean): Boolean =
        isActive && credentialSurfaces == 0 && !isSecureWindow

    suspend fun jpeg(maxBytes: Int = ProblemReportBuilder.SCREENSHOT_LIMIT): ByteArray? {
        val bitmap = withContext(Dispatchers.Main) { capture() } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                for (quality in listOf(70, 50, 30)) {
                    val out = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) && out.size() <= maxBytes) return@withContext out.toByteArray()
                }
                null
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun capture(): Bitmap? {
        val activity = resumed?.get() ?: return null
        val window = activity.window ?: return null
        val view = window.decorView
        val secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (view.width <= 0 || view.height <= 0 || !isAllowed(!activity.isFinishing, CredentialSurface.visible, secure)) return null
        // iOS renders at no more than 2× points.
        val density = activity.resources.displayMetrics.density
        val scale = min(1f, 2f / density)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { continuation ->
            PixelCopy.request(window, full, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        if (!copied) {
            full.recycle()
            return null
        }
        if (scale >= 1f) return full
        val scaled = Bitmap.createScaledBitmap(full, (view.width * scale).roundToInt().coerceAtLeast(1), (view.height * scale).roundToInt().coerceAtLeast(1), true)
        full.recycle()
        return scaled
    }
}

/** iOS `ProblemReportCapture`: the app window at the moment of a trace, as a JPEG. */
class ProblemReportCapture(val screenshot: suspend () -> ByteArray? = { null }) {
    companion object Key : DependencyKey<ProblemReportCapture> {
        override val liveValue: ProblemReportCapture = ProblemReportCapture { WindowSnapshot.jpeg() }
        override val testValue: ProblemReportCapture = ProblemReportCapture()
    }
}
