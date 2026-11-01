package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Debug-only trace of the full-screen image in flight (iOS `MediaTransitionTrace`,
 * `-lab-media-trace`): frame by frame, its on-screen frame and corner radius, the backdrop,
 * and whether the thumbnail is out of the transcript, so the open, the drag, the spring back
 * and the flight home can be checked. One `AppLog.perf` line per phase:
 * `media-trace name|source x,y,w,h,r|rest x,y,w,h|page x,y,w,h|` then `x,y,w,h,r,backdrop,sourceAlpha,t`
 * samples separated by spaces, in dp (iOS points), window coordinates (`t`: seconds since the
 * phase began, so steps compare per 120 Hz frame).
 *
 * Enabled only in debuggable builds, when [isEnabled] is set (developer tools) or the app was
 * launched with `--ez lab-media-trace true` (read by [MediaView]). Geometry only, no content.
 */
class MediaTransitionTrace {
    /** One frame: the flight's frame (px, window), corner radius (px), backdrop alpha and the thumbnail's alpha (−1 when none). */
    data class Sample(val frame: Rect, val radius: Float, val backdrop: Float, val sourceAlpha: Float)

    private class Phase(val name: String, val source: Pair<Rect, Float>?, val rest: Rect) {
        val start = System.nanoTime()
        val samples = ArrayList<String>()
    }

    private var phase: Phase? = null
    private var state: MediaViewerState? = null
    private var density = 1f
    private var sampler: (() -> Sample?)? = null
    private var link: Job? = null

    fun begin(
        name: String,
        scope: CoroutineScope,
        density: Density,
        source: Pair<Rect, Float>?,
        rest: Rect,
        state: MediaViewerState,
        sample: () -> Sample?,
    ) {
        if (!enabled) return
        phase?.name?.let(::end)
        this.state = state
        this.density = density.density
        sampler = sample
        phase = Phase(name, source, rest)
        // Where it leaves from, before the frame clock's first tick.
        sample()
        link = scope.launch {
            while (isActive) withFrameNanos { sample() }
        }
    }

    fun end(name: String) {
        if (!enabled || phase?.name != name) return
        sample()
        val phase = phase ?: return
        link?.cancel()
        link = null
        this.phase = null
        val source = phase.source?.let { format(it.first) + "," + number(it.second) } ?: "-"
        val page = state?.mediaFrame?.let(::format) ?: "-"
        val line = listOf(phase.name, "source $source", "rest " + format(phase.rest), "page $page", phase.samples.joinToString(" "))
            .joinToString("|")
        synchronized(recorded) { recorded.add(line) }
        AppLog.perf.info { "media-trace $line" }
    }

    /** The viewer went away mid-phase. */
    fun cancel() {
        link?.cancel()
        link = null
        phase = null
    }

    private fun sample() {
        val phase = phase ?: return
        val sample = sampler?.invoke() ?: return
        val time = (System.nanoTime() - phase.start) / 1e9
        phase.samples.add(
            listOf(
                format(sample.frame),
                number(sample.radius),
                plain(sample.backdrop),
                plain(sample.sourceAlpha),
                String.format(Locale.US, "%.4f", time),
            ).joinToString(","),
        )
    }

    private fun number(px: Float): String = plain(px / density)

    private fun plain(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun format(rect: Rect): String =
        listOf(rect.left, rect.top, rect.width, rect.height).joinToString(",") { number(it) }

    companion object {
        /** Developer tools or the `lab-media-trace` launch extra turn it on; only debuggable builds honour it. */
        @Volatile
        var isEnabled: Boolean = false

        private val debuggable: Boolean by lazy {
            runCatching { dependency(AppContext).applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }.getOrDefault(false)
        }

        private val enabled: Boolean get() = isEnabled && debuggable

        /** Every phase traced in this process, oldest first (iOS's `media-trace` element value). */
        val lines: List<String> get() = synchronized(recorded) { recorded.toList() }
        private val recorded = ArrayList<String>()
    }
}
