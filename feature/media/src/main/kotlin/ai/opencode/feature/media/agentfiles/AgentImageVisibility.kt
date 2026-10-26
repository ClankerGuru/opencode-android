package ai.opencode.feature.media

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * A scrolling list image previews live in (the transcript list; iOS `AgentImageViewport`): what
 * part of it the reader sees, and the rows to ease when an image's shape changes. The list
 * provides itself through [LocalAgentImageViewport] and registers with [AgentImageVisibility].
 */
interface AgentImageViewport {
    /**
     * The part of the list the reader sees (between the header and the composer), in window
     * coordinates; empty while nothing counts as on screen (the session's opening hasn't settled).
     */
    val imageViewportVisibleRect: Rect
    /** Still opening: nothing counts as on screen yet; ask again shortly. */
    val imageViewportIsSettling: Boolean
    /** Covered session roots stay composed: a hidden or covered list doesn't hold previews. */
    val imageViewportIsVisible: Boolean
    /** An image inside [marker]'s row ([AgentImageMarkerView.rowKey]) is about to change the row's height. */
    fun imageContentRevised(marker: AgentImageMarkerView)
}

/** The list the markers below belong to (iOS: the marker's ancestor viewport). */
val LocalAgentImageViewport = staticCompositionLocalOf<AgentImageViewport?> { null }

/** The list row the markers below are in, so [AgentImageViewport.imageContentRevised] can find it (iOS: the cell above the view). */
val LocalAgentImageRow = staticCompositionLocalOf<Any?> { null }

/**
 * Which image previews are on screen (iOS `AgentImageVisibility`). A reply's blocks are laid out
 * eagerly in one row, so being composed says nothing: each preview carries a marker
 * ([AgentImageMarker]), and after every scroll and layout (on the next turn of the main loop,
 * never inside one: previews change state the rows observe) its viewport says which markers lie
 * inside the part the reader sees (with [MARGIN] screens of margin, so a read starts just before
 * it scrolls in). Only those hold a read and show pixels.
 *
 * A plain main-thread object: markers report their window bounds from `onGloballyPositioned`.
 */
class AgentImageVisibility(private val previews: AgentImagePreviews) {
    private val viewports: MutableSet<AgentImageViewport> = Collections.newSetFromMap(WeakHashMap())
    private val markers = LinkedHashSet<AgentImageMarkerView>()
    private var scheduled = false
    private var settleScheduled = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Settle rechecks scheduled, for diagnostics. */
    var settleChecks = 0
        private set

    init {
        previews.onLayoutChange = { key -> revised(key) }
    }

    fun register(viewport: AgentImageViewport) {
        viewports.add(viewport)
        setNeedsUpdate()
    }

    /** The viewport left: its markers go off screen and its opening hold ends. */
    fun unregister(viewport: AgentImageViewport) {
        viewports.remove(viewport)
        previews.holdEntrances(viewport, false)
        update()
    }

    /** Every preview's marker. */
    val markerViews: List<AgentImageMarkerView> get() = markers.toList()

    fun add(marker: AgentImageMarkerView) {
        markers.add(marker)
        setNeedsUpdate()
    }

    fun remove(marker: AgentImageMarkerView) {
        markers.remove(marker)
    }

    /** Updates on the next turn of the main loop, once for many requests. */
    fun setNeedsUpdate() {
        if (scheduled) return
        scheduled = true
        handler.post {
            scheduled = false
            update()
        }
    }

    /** Marks every marker on or off screen now. */
    fun update() {
        // Only an opening viewport with mounted images can hold an entrance. Text-only, covered,
        // and settled viewports release even without a marker asking for the visible rect.
        val currentMarkers = markers.toList()
        var waiting = false
        for (viewport in viewports.toList()) {
            val hold = viewport.imageViewportIsVisible && viewport.imageViewportIsSettling &&
                currentMarkers.any { it.isAttached && it.viewport === viewport }
            previews.holdEntrances(viewport, hold)
            waiting = waiting || hold
        }
        previews.refreshEntranceHolds()
        // Every placement first; then what's in view comes on screen before the margin, so its
        // reads take the free slots first.
        val placed = currentMarkers.map { it to placement(it) }
        for ((marker, place) in placed) marker.isInView = place.second
        for ((marker, place) in placed) if (!place.first) marker.isOnScreen = false
        for ((marker, place) in placed) if (place.second) marker.isOnScreen = true
        for ((marker, place) in placed) if (place.first && !place.second) marker.isOnScreen = true
        // A viewport still opening, with previews in it, is asked again once it may have
        // settled; one without any (an empty draft) is left alone.
        if (waiting && !settleScheduled) {
            settleScheduled = true
            settleChecks += 1
            handler.postDelayed({
                settleScheduled = false
                setNeedsUpdate()
            }, 100)
        }
    }

    private fun viewport(marker: AgentImageMarkerView): AgentImageViewport? =
        marker.viewport?.takeIf { it in viewports }

    /** On screen (within the margin), and in view (inside what's visible). */
    private fun placement(marker: AgentImageMarkerView): Pair<Boolean, Boolean> {
        if (!marker.isAttached) return false to false
        val viewport = viewport(marker) ?: return false to false
        if (!viewport.imageViewportIsVisible) return false to false
        val bounds = marker.bounds ?: return false to false
        // A held image's marker has no height: it's where its image will be.
        val frame = Rect(bounds.left, bounds.top - 0.5f, bounds.right, bounds.bottom + 0.5f)
        val visible = viewport.imageViewportVisibleRect
        return isOnScreen(frame, visible) to (!visible.isEmpty && frame.overlaps(visible))
    }

    /** An entry is about to change its layout: the rows showing it ease. */
    private fun revised(key: AgentImageKey) {
        for (marker in markers.toList()) {
            if (marker.sighting?.key == key) viewport(marker)?.imageContentRevised(marker)
        }
    }

    fun revised(marker: AgentImageMarkerView) {
        viewport(marker)?.imageContentRevised(marker)
    }

    companion object Key : DependencyKey<AgentImageVisibility> {
        /** Screens beyond the visible part that still count as on screen. */
        const val MARGIN = 0.5f

        override val liveValue: AgentImageVisibility by lazy { AgentImageVisibility(dependency(AgentImagePreviews)) }
        override val testValue: AgentImageVisibility get() = AgentImageVisibility(AgentImagePreviews.testValue)

        /** Whether a marker at [frame] counts as on screen for [visible]. */
        fun isOnScreen(frame: Rect, visible: Rect): Boolean {
            if (visible.isEmpty) return false
            val margin = visible.height * MARGIN
            return frame.overlaps(Rect(visible.left, visible.top - margin, visible.right, visible.bottom + margin))
        }
    }
}

/**
 * What a row's images still have to grow, block by block, while the list eases the row
 * (iOS `AgentImageRowGrowth`, driven by the transcript's `RowGrowth`). Each gallery reports its
 * own height change ([add]); the list scales them all to the growth still to come each frame
 * ([update]). A block below an image is drawn up by what the images above it still have to
 * grow, and an image's block is clipped by its own, so everything below an image holds still on
 * screen while the row's edge eases, as the rows below do. Block IDs run in reading order within
 * a row. Heights in dp.
 */
class AgentImageRowGrowth(private val now: () -> Double = ::agentImageNow) {
    /** Growth still to come per image block, as drawn: the shares following the row's growth, plus changes it hasn't measured yet. */
    var pending: Map<Int, Float> by mutableStateOf(emptyMap())
        private set
    /** Shares following the row's current growth, as they were when it was last rebased, and how much it had still to grow then. */
    private var following: MutableMap<Int, Float> = HashMap()
    private var followingNow: Map<Int, Float> = emptyMap()
    private var baseRemaining: Float? = null
    /** Changes reported that no growth of the row has taken up yet: held whole. */
    private val unmeasured = HashMap<Int, Float>()
    private var unmeasuredSince: Double? = null
    private var growthBegan = Double.NEGATIVE_INFINITY
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    val isAwaitingGrowth: Boolean get() = unmeasured.isNotEmpty()

    /** A block's layout grew (or shrank) by [delta] just now. */
    fun add(block: Int, delta: Float) {
        val now = now()
        if (now - growthBegan <= JOIN_WINDOW) {
            // The list measured first: it's part of the growth already under way.
            following = HashMap(followingNow)
            val total = (following[block] ?: 0f) + delta
            if (abs(total) < 0.5f) following.remove(block) else following[block] = total
            followingNow = HashMap(following)
            baseRemaining = null
        } else {
            val total = (unmeasured[block] ?: 0f) + delta
            if (abs(total) < 0.5f) unmeasured.remove(block) else unmeasured[block] = total
            if (unmeasuredSince == null) unmeasuredSince = now
            val since = unmeasuredSince
            handler.postDelayed({
                if (unmeasuredSince == since && since != null) clearUnmeasured()
            }, (UNMEASURED_LIFETIME * 1000).toLong())
        }
        publish()
    }

    /** The list started (or retargeted) the row's growth to its new height: everything held now follows it. */
    fun beginGrowth() {
        growthBegan = now()
        following = HashMap(pending)
        followingNow = HashMap(following)
        unmeasured.clear()
        unmeasuredSince = null
        baseRemaining = null
    }

    /** The row has [remaining] still to grow: every following share eases by the same fraction of the way. Changes it hasn't measured stay whole. */
    fun update(remaining: Float) {
        if (following.isEmpty()) return
        val start = baseRemaining ?: remaining
        baseRemaining = start
        if (abs(start) < 0.5f || abs(remaining) < 0.5f || Math.signum(remaining) != Math.signum(start)) return endFollowing()
        val fraction = minOf(1f, remaining / start)
        // Swift `.rounded()`: to the nearest half, ties away from zero (Math.round ties up).
        followingNow = following.mapValues { entry ->
            val doubled = entry.value * fraction * 2
            Math.copySign(kotlin.math.floor(abs(doubled) + 0.5f), doubled) / 2f
        }
        publish()
    }

    /** The row's growth ended: its shares are done; changes it never measured still wait for their own. */
    fun growthFinished() = endFollowing()

    /**
     * The row was measured without a new height (at rest, or confirming the target of the growth
     * under way). Changes that add up to nothing (one image growing as another shrinks) never get
     * a growth: they let go.
     */
    fun measuredUnchanged() {
        if (abs(unmeasured.values.sum()) < 0.5f) clearUnmeasured()
    }

    /** The row landed at its new height: nothing is held. */
    fun settle() {
        growthBegan = Double.NEGATIVE_INFINITY
        following = HashMap()
        followingNow = emptyMap()
        baseRemaining = null
        unmeasured.clear()
        unmeasuredSince = null
        publish()
    }

    private fun endFollowing() {
        // A report after this belongs to no growth: it waits for its own.
        growthBegan = Double.NEGATIVE_INFINITY
        following = HashMap()
        followingNow = emptyMap()
        baseRemaining = null
        publish()
    }

    private fun clearUnmeasured() {
        unmeasured.clear()
        unmeasuredSince = null
        publish()
    }

    private fun publish() {
        val drawn = HashMap(followingNow)
        for ((block, value) in unmeasured) drawn[block] = (drawn[block] ?: 0f) + value
        val filtered = drawn.filterValues { abs(it) >= 0.5f }
        if (filtered != pending) pending = filtered
    }

    /** How far a block (or bubble) starting at [block] is drawn up. */
    fun lift(before: Int): Float = pending.entries.sumOf { if (it.key < before) it.value.toDouble() else 0.0 }.toFloat()

    /** How much of the bottom of the blocks [blocks] is cut off. */
    fun cut(blocks: Iterable<Int>): Float = maxOf(0f, blocks.sumOf { (pending[it] ?: 0f).toDouble() }.toFloat())

    companion object {
        /** A report this soon after a growth began belongs to it (seconds). */
        const val JOIN_WINDOW = 0.1
        /** A change no growth takes up in this long lets go (seconds). */
        const val UNMEASURED_LIFETIME = 1.0
    }
}

/** The row's [AgentImageRowGrowth], from the list (iOS `agentImageRowGrowth` environment). */
val LocalAgentImageRowGrowth = staticCompositionLocalOf<AgentImageRowGrowth?> { null }

/** The block a gallery is in, so it can report its growth (iOS `agentImageBlock`). */
val LocalAgentImageBlock = staticCompositionLocalOf<Int?> { null }

/**
 * Holds a preview's read while it's on screen (iOS `AgentImageMarkerView`). Lives behind the
 * preview, invisible to touches and accessibility; its window bounds come from the composable.
 */
class AgentImageMarkerView(
    private val previews: AgentImagePreviews,
    private val visibility: AgentImageVisibility,
) {
    /** Other inline media reuse the transcript's actual viewport test. */
    var onVisibilityChange: ((Boolean) -> Unit)? = null
    var sighting: AgentImageSighting? = null
        private set
    private var request: AgentImageRequest? = null
    private var shown: AgentImageSighting? = null
    /** The list it's in, and its row there. */
    var viewport: AgentImageViewport? = null
        internal set
    var rowKey: Any? = null
        internal set
    /** Composed and placed (iOS `window != nil`). */
    var isAttached = false
        private set
    /** Its bounds in window coordinates, unclipped by the list. */
    var bounds: Rect? = null
        private set

    var isOnScreen = false
        set(value) {
            if (value == field) return
            field = value
            sync()
            onVisibilityChange?.invoke(value)
        }

    /** Inside what the reader sees, not just the margin: its read goes first. */
    var isInView = false
        set(value) {
            if (value == field) return
            field = value
            syncPriority()
        }

    /** The key this marker counts as in view for. */
    private var countedInView: AgentImageKey? = null

    fun contentRevised() = visibility.revised(this)

    fun configure(sighting: AgentImageSighting, request: AgentImageRequest) {
        this.request = request
        if (sighting == this.sighting) return
        this.sighting = sighting
        sync()
        visibility.setNeedsUpdate()
    }

    /** Shows the current sighting while on screen, and nothing otherwise. */
    private fun sync() {
        val wanted = if (isOnScreen && isAttached) sighting else null
        if (wanted == shown) return
        shown?.let(previews::hide)
        shown = wanted
        // Its priority before its read is asked for, so the queue sees it.
        syncPriority()
        val request = request
        if (wanted != null && request != null) previews.show(wanted, request)
    }

    /** Counts this marker in view for its shown key, both ways. */
    private fun syncPriority() {
        val wanted = if (isInView) shown?.key else null
        if (wanted == countedInView) return
        countedInView?.let(previews::leaveView)
        countedInView = wanted
        wanted?.let(previews::enterView)
    }

    internal fun attach() {
        isAttached = true
        visibility.add(this)
    }

    internal fun positioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val origin = coordinates.positionInWindow()
        val next = Rect(origin, coordinates.size.toSize())
        if (next == bounds) return
        bounds = next
        visibility.setNeedsUpdate()
    }

    /** Leaves the screen for good (the composable left the composition). */
    fun dismantle() {
        isOnScreen = false
        isAttached = false
        sighting = null
        sync()
        visibility.remove(this)
    }
}

/**
 * A preview's marker (iOS `AgentImageMarker`): sized by [modifier] (usually `matchParentSize()`
 * behind the preview), hidden from accessibility, reading [sighting] while it's on screen.
 */
@Composable
fun AgentImageMarker(
    sighting: AgentImageSighting,
    request: AgentImageRequest,
    modifier: Modifier = Modifier,
    onVisibilityChange: ((Boolean) -> Unit)? = null,
) {
    val viewport = LocalAgentImageViewport.current
    val row = LocalAgentImageRow.current
    val marker = remember { AgentImageMarkerView(dependency(AgentImagePreviews), dependency(AgentImageVisibility)) }
    DisposableEffect(marker) {
        marker.attach()
        onDispose { marker.dismantle() }
    }
    SideEffect {
        marker.viewport = viewport
        marker.rowKey = row
        marker.onVisibilityChange = onVisibilityChange
        marker.configure(sighting, request)
    }
    Spacer(modifier.onGloballyPositioned(marker::positioned).clearAndSetSemantics {})
}

/**
 * A marker that only reports whether it's on screen, for other inline media that reuse the
 * transcript's viewport test (iOS `ImageLinkVisibilityMarker`: an `AgentImageMarkerView` without a
 * sighting). [changed] gets the visibility and the marker, whose [AgentImageMarkerView.contentRevised]
 * tells the list its row is about to change height.
 */
@Composable
fun AgentImageVisibilityMarker(modifier: Modifier = Modifier, changed: (Boolean, AgentImageMarkerView) -> Unit) {
    val viewport = LocalAgentImageViewport.current
    val row = LocalAgentImageRow.current
    val marker = remember { AgentImageMarkerView(dependency(AgentImagePreviews), dependency(AgentImageVisibility)) }
    val current by androidx.compose.runtime.rememberUpdatedState(changed)
    DisposableEffect(marker) {
        marker.attach()
        onDispose {
            marker.onVisibilityChange = null
            marker.dismantle()
        }
    }
    SideEffect {
        marker.viewport = viewport
        marker.rowKey = row
        if (marker.onVisibilityChange == null) marker.onVisibilityChange = { visible -> current(visible, marker) }
    }
    Spacer(modifier.onGloballyPositioned(marker::positioned).clearAndSetSemantics {})
}
