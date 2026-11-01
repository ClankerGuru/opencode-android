package ai.opencode.feature.transcript.list

import ai.opencode.core.diagnostics.MotionFrame
import ai.opencode.core.diagnostics.MotionWatch
import ai.opencode.core.diagnostics.PerfDisplay
import ai.opencode.core.tuning.AnimationTimeScale
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `TranscriptMotionTrace` in its Motion Watch mode (Developer Mode › Motion Watch):
 * what the reader sees of the transcript every display frame, fed to [MotionWatch]. Each frame
 * records the visible rows as laid out (their top and height on the list's screen), the scroll
 * offset and insets, whether a finger or a fling moves the list, which of the list's motions run,
 * and the card's place (the drawer). Snapshot applies and the session's first content frame ride
 * on the frame after them as events.
 *
 * Only frames that differ from the last one recorded are kept; `elapsed` counts the display
 * frames since the previous callback, so a hitch isn't read as a jump. The frame loop pauses once
 * the transcript and drawer have been still for 1.2 s and wakes before the next snapshot apply,
 * scroll, drawer move, or sheet. Zero cost while Motion Watch is off: every entry point returns after
 * one volatile read.
 *
 * Android mapping: `CADisplayLink` → `Choreographer.FrameCallback`; presentation layers →
 * `LazyListLayoutInfo` (rows as laid out this frame; Compose draws what it lays out, so there is
 * no uncommitted-update skip) and the list's window position (the drawer moves the card with a
 * graphics layer, which `positionInWindow` includes). Row entrances, growth and disclosures are
 * marked `animated` instead of read from drawn opacity. iOS's `-lab-motion-trace` posting and
 * Mac playground capture have no Android counterpart (no XCUITest motion suite or Mac page).
 */
object TranscriptMotionTrace {
    /** The list on screen: its controller and where it sits in the window. */
    class Source(
        internal val controller: TranscriptListController,
        internal val coordinates: () -> LayoutCoordinates?,
    )

    val isActive: Boolean get() = MotionWatch.shared.isActive

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val launch = UUID.randomUUID().toString().take(8).uppercase()

    private var source: WeakReference<Source>? = null
    private var running = false
    private var callback = 0
    private var sequence = 0
    private var lastFrameNanos = 0L
    private var lastActivityNanos = 0L
    private var lastRecordedWallMs = 0L
    private var lastCardX: Double? = null
    private var isDrawerMoving = false
    private var lastContent: MotionFrame? = null
    private val events = ArrayList<MotionFrame.Event>()

    init {
        MotionWatch.activeListeners += { onMain(::activeChanged) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** The list on screen now; a newer one replaces it. */
    fun attach(source: Source) {
        this.source = WeakReference(source)
        if (!isActive) return
        note(MotionFrame.Event("attach"))
    }

    fun detach(source: Source) {
        if (this.source?.get() !== source) return
        isDrawerMoving = false
        if (isActive) {
            note(MotionFrame.Event("detach"))
            MotionWatch.shared.settle((System.currentTimeMillis() + MotionWatch.POST_WINDOW_MILLISECONDS).toInt())
        }
        this.source = null
        running = false
    }

    /**
     * Right before a snapshot or disclosure changes the list: if the loop paused while idle,
     * samples the resting state first so the detector compares the post-change frame against the
     * exact pre-change pose.
     */
    fun willChange() {
        if (!isActive) return
        if (!running) sampleRestingBaseline()
        wake()
    }

    /**
     * iOS `sheetWillMove()`: a sheet starts arriving or leaving over the transcript. Nothing in the
     * list moves, so this is what wakes a paused loop to watch it.
     */
    fun sheetWillMove() {
        if (!isActive) return
        willChange()
    }

    /** Wakes the frame loop when scrolling, a motion, or a disclosure starts. */
    fun wake() {
        if (!isActive) return
        lastActivityNanos = System.nanoTime()
        if (running || source?.get() == null) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Tracks when the session drawer starts or finishes moving the card. */
    fun noteDrawerMoving(moving: Boolean) {
        if (!isActive) return
        if (moving && !isDrawerMoving) {
            if (!running) sampleRestingBaseline()
            isDrawerMoving = true
            wake()
        } else if (!moving && isDrawerMoving) {
            isDrawerMoving = false
            lastActivityNanos = System.nanoTime()
        }
    }

    /** An event (an apply, the first content frame) for the next frame. */
    fun note(event: MotionFrame.Event) {
        if (!isActive) return
        events += event
        wake()
    }

    private fun activeChanged() {
        if (isActive) {
            if (source?.get() == null) return
            lastContent = null
            note(MotionFrame.Event("attach"))
        } else {
            isDrawerMoving = false
            running = false
            events.clear()
            lastContent = null
        }
    }

    private val frame = Choreographer.FrameCallback { frameTimeNanos -> tick(frameTimeNanos) }

    private fun tick(frameTimeNanos: Long) {
        if (!running) return
        if (!isActive) {
            running = false
            return
        }
        callback += 1
        val period = PerfDisplay.periodSeconds()
        val frames = if (lastFrameNanos > 0) max(1, ((frameTimeNanos - lastFrameNanos) / 1e9 / period).roundToInt()) else 1
        lastFrameNanos = frameTimeNanos
        val current = source?.get()
        val content = current?.let(::capture)
        if (content == null) {
            pauseIfIdle(frameTimeNanos, activelyMoving = false)
            if (running) Choreographer.getInstance().postFrameCallback(frame)
            return
        }
        val cardMoved = lastCardX?.let { abs(it - content.cardX) > 0.1 } ?: false
        lastCardX = content.cardX
        val activelyMoving = content.motions.isNotEmpty() || content.gesture || isDrawerMoving || cardMoved || content.phase == "settle"
        if (activelyMoving) lastActivityNanos = frameTimeNanos
        if (content == lastContent && events.isEmpty()) {
            pauseIfIdle(frameTimeNanos, activelyMoving)
            if (running) Choreographer.getInstance().postFrameCallback(frame)
            return
        }
        lastContent = content
        lastActivityNanos = frameTimeNanos
        ingest(content, elapsed = frames, periodMs = period * 1_000)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun ingest(content: MotionFrame, elapsed: Int, periodMs: Double) {
        sequence += 1
        val wall = System.currentTimeMillis()
        lastRecordedWallMs = wall
        val frame = content.copy(events = events.toList()).also {
            it.index = callback
            it.sequence = sequence
            it.elapsed = max(1, elapsed)
            it.periodMs = if (periodMs > 0) periodMs else MotionFrame.DEFAULT_PERIOD_MS
            it.wall = wall.toInt()
        }
        events.clear()
        if (MotionWatch.shared.isActive) MotionWatch.shared.ingest(frame)
    }

    private fun sampleRestingBaseline() {
        val now = System.currentTimeMillis()
        if (now - lastRecordedWallMs <= 80) return
        val content = source?.get()?.let(::capture) ?: return
        callback += 1
        lastContent = content
        ingest(content, elapsed = 1, periodMs = PerfDisplay.periodSeconds() * 1_000)
    }

    private fun pauseIfIdle(frameTimeNanos: Long, activelyMoving: Boolean) {
        val idleWindow = 1.2 / max(0.1, min(1.0, AnimationTimeScale.shared.scale))
        if (activelyMoving || (frameTimeNanos - lastActivityNanos) / 1e9 <= idleWindow) return
        running = false
        MotionWatch.shared.settle(System.currentTimeMillis().toInt())
    }

    /** What one frame shows: rows as laid out, chrome boxes, the list's geometry (dp). */
    private fun capture(source: Source): MotionFrame? {
        val coordinates = source.coordinates()?.takeIf { it.isAttached } ?: return null
        val probe = source.controller.motionProbe() ?: return null
        val density = source.controller.density
        val cardX = round((coordinates.positionInWindow().x / density).toDouble())
        val motions = if (isDrawerMoving && "drawer" !in probe.motions) probe.motions + "drawer" else probe.motions
        val boxes = ArrayList<MotionFrame.Box>()
        if (probe.insetTop > 0) boxes += MotionFrame.Box("session-header", "header", cardX, 0.0, probe.width, probe.insetTop, 1.0)
        if (probe.insetBottom > 0) {
            boxes += MotionFrame.Box("session-composer", "composer", cardX, max(0.0, probe.height - probe.insetBottom), probe.width, probe.insetBottom, 1.0)
        }
        if (cardX > 0.5) boxes += MotionFrame.Box("session-drawer", "drawer", 0.0, 0.0, cardX, probe.height, 1.0)
        val scale = AnimationTimeScale.shared.scale
        return MotionFrame(
            launch = launch, index = 0, sequence = 0, elapsed = 1, wall = 0,
            offset = probe.offset, insetTop = probe.insetTop, insetBottom = probe.insetBottom,
            contentHeight = probe.contentHeight, height = probe.height, cardX = cardX, width = probe.width,
            gesture = probe.gesture, motions = motions, phase = probe.phase, reduceMotion = probe.reduceMotion,
            browsing = probe.browsing, trailingSpace = 0.0,
            timeScale = if (abs(scale - 1.0) > 1e-4) round(scale, 100.0).takeIf { it > 0 } ?: 1.0 else 1.0,
            session = probe.sessionID.orEmpty(), rows = probe.rows, composerTop = probe.composerTop, boxes = boxes,
        )
    }

    /** What the controller knows about one frame (iOS `TranscriptMotionTrace.Probe`). */
    class Probe(
        val sessionID: String?,
        val rows: List<MotionFrame.Row>,
        /** The list's running motions: the follower's kind, growth, a disclosure, a jump. */
        val motions: List<String>,
        /** `opening` before the first content frame, `settle` while the opening settle lands arrivals, else `live`. */
        val phase: String,
        val reduceMotion: Boolean,
        val browsing: Boolean,
        val gesture: Boolean,
        val offset: Double,
        val contentHeight: Double,
        val insetTop: Double,
        val insetBottom: Double,
        val height: Double,
        val width: Double,
        /**
         * iOS `composerTop` (`ComposerTopEdge`): the composer's drawn top on the list's screen, so
         * rows riding the bottom chrome in the same frame aren't a jump. Android's composer moves
         * the rows through the list's bottom content padding, so its top is where that begins.
         */
        val composerTop: Double? = null,
    )

    /** Never NaN or infinite, which JSON can't carry. */
    internal fun round(value: Double, scale: Double = 10.0): Double =
        if (!value.isFinite()) -1.0 else Math.round(value * scale) / scale
}
