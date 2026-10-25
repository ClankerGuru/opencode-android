package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * Port of iOS `DrawerPinDrag.swift`: continuous 60–120 Hz spring motion across Pinned and Recents
 * while a session row is dragged to pin, unpin, or reorder.
 *
 * Each visible section header and row tracks its visual top in the list's coordinates through a
 * [SpringSolver]. iOS writes each cell's `transform.ty`; here each row reads its entry of
 * [translations] in its own `graphicsLayer`, so a drag never recomposes the list. When the drop
 * lands, `completeDrop` mutates the store and has the list apply it in the same frame
 * ([DrawerPinDragHost.flushNow], iOS `flushNow`), publishing each row's translation against its
 * new place so no row or header jumps on zone entry, slot change, or drop commit.
 *
 * UIKit drag sessions become the row's long-press drag ([DrawerRowInteraction]): the lifted row
 * itself follows the finger (iOS hides it under the system's platter) and lands in its slot on
 * the same spring; a release more than 24 dp beside the list cancels, as iOS's drop proposal does.
 * The collection-wide `UIDropInteraction` is the row's own pointer stream on Android. Positions are
 * dp in the list's own coordinates (its top edge, content padding included), stable during a drag
 * because the drag consumes the finger so the list never scrolls under it (iOS has no auto-scroll).
 * `DrawerPinDragTraceView` and the per-frame trace (XCUITest hooks) are not ported.
 */

/** What [DrawerPinDrag] reads from and asks of the list it moves (iOS: the collection view itself). */
interface DrawerPinDragHost {
    /** The visible headers and rows, untransformed (iOS `visibleKeyedCells`). */
    fun visibleItems(): List<DrawerPinDragItem>

    /** The list's width, dp. */
    val width: Float

    /** The top of the list's visible content (iOS `contentOffset.y + adjustedContentInset.top`), dp. */
    val viewportTop: Float

    /** Applies the store's change now, so this frame lays out the rows in their new places (iOS `flushNow`). */
    fun flushNow()

    /** Keeps the scroll position by index across the drop's reorder (UIKit keeps its content offset). */
    fun preserveScroll()

    /** A scope on the composition's frame clock, and its frame duration. */
    val scope: CoroutineScope
    val frameDurationSeconds: Double
}

/** One visible header or row: its key, untransformed top and height, in dp. */
data class DrawerPinDragItem(val key: DrawerPinDrag.ItemKey, val minY: Float, val height: Float) {
    val maxY: Float get() = minY + height
}

/** The empty-pins drop zone while it shows: where, how open, how hovered. */
data class DrawerPinDropZone(
    val top: Float,
    val height: Float,
    val openProgress: Float,
    val hoverProgress: Float,
    val headerHeight: Float,
    val reducedMotion: Boolean,
)

@Stable
class DrawerPinDrag {
    sealed interface HoverTarget {
        data class Pinned(val index: Int) : HoverTarget
        data object Recent : HoverTarget
    }

    sealed interface ItemKey {
        data object PinnedHeader : ItemKey
        data object RecentsHeader : ItemKey
        data class Row(val id: String) : ItemKey
    }

    companion object {
        /** iOS `-lab-pin-drag-trace`; no launch arguments on Android. */
        const val IS_TRACED = false

        /** A release this far beside the list cancels (iOS's `.cancel` drop proposal), dp. */
        const val CANCEL_MARGIN = 24f

        /** iOS's landing fallback: the system's drop animation, at most 0.55 s. */
        const val LANDING_FALLBACK_MILLIS = 550L
    }

    // ---- Output, read by the rows in their layers ----

    /** Each row's and header's vertical translation in dp (iOS `cell.transform.ty`). */
    val translations = mutableStateMapOf<ItemKey, Float>()

    /** The Pinned header's opacity while its only row is dragged out (iOS `cell.contentView.alpha`). */
    var pinnedHeaderAlpha: Float by mutableFloatStateOf(1f)
        internal set

    /** The empty-pins drop zone, while it shows (iOS `emptyZoneHostingController`). */
    var dropZone: DrawerPinDropZone? by mutableStateOf(null)
        internal set

    /** The dragged row, while a drag or its landing runs. */
    var draggingID: String? by mutableStateOf(null)
        internal set

    fun translation(key: ItemKey): Float = translations[key] ?: 0f

    // ---- Drag state (iOS @ObservationIgnored) ----

    internal var host: DrawerPinDragHost? = null
    private var frameJob: Job? = null
    private var landingFallback: Job? = null

    var activeSessionID: String? = null
        internal set
    internal var startedInPinned = false
    internal var pinnedIDs: List<String> = emptyList()
    internal var otherPinnedIDs: List<String> = emptyList()
    internal var recentIDs: List<String> = emptyList()
    internal var recentInsertionIndex = 0
    internal var showsShortcuts = false
    internal var reducedMotion = false

    internal var hoverTarget: HoverTarget = HoverTarget.Recent
    internal var initialFingerY = 0f
    internal var lastFingerY = 0f
    /**
     * Where the finger holds the lifted row, from its centre. iOS centres its hidden cell on the
     * finger under the system's platter; Android shows the row itself, so it keeps the grip.
     */
    internal var grabOffset = 0f
    internal var hasRevealedEmptyZone = false
    internal var hasCommittedDrop = false
    internal var isLandingPlatter = false
    internal var isCancellingEmptyZone = false
    private var hasNotifiedDropReleased = false
    /** The landing fallback fired: commit in the next frame (see [scheduleLandingFallback]). */
    private var commitRequested = false
    /** The Pinned header's start after the first pin lands, applied once it's laid out. */
    internal var pendingPinnedHeaderOffset: Float? = null

    internal var cachedTopMinY = 0f
    internal var cachedFirstPinnedMinY = 0f
    internal var cachedFirstRecentMinY = 0f
    internal var cachedHeaderHeight = 56f

    internal val cellSprings = HashMap<ItemKey, SpringSolver>()
    internal var emptyZoneSpring = SpringSolver.of(duration = 0.42, bounce = 0.0)
    internal var hoverSpring = SpringSolver.of(duration = 0.26, bounce = 0.0)

    private var onZoneCrossed: ((Boolean) -> Unit)? = null
    private var onSlotChanged: (() -> Unit)? = null
    private var onDropReleased: ((String, Int?) -> Unit)? = null
    private var onDrop: ((String, Int?) -> Unit)? = null
    private var onSetLifted: ((String?) -> Unit)? = null

    val isDisplayLinkRunning: Boolean get() = frameJob?.isActive == true

    val rowStride: Float get() = DrawerMetrics.rowHeight.value + DrawerTunables.rowGap.value

    internal fun makeSpring(value: Float, target: Float): SpringSolver {
        val duration = if (reducedMotion) 0.18 else DrawerTunables.pinDragSpring.duration
        val bounce = if (reducedMotion) 0.0 else DrawerTunables.pinDragSpring.bounce
        return SpringSolver.of(
            duration = max(duration, 0.12),
            bounce = bounce,
            value = value.toDouble(),
            target = target.toDouble(),
            positionEpsilon = 0.08,
            velocityEpsilon = 0.5,
        )
    }

    /** The list this drag moves (iOS `prepareCollectionViewIfNeeded`). */
    fun attach(host: DrawerPinDragHost) {
        this.host = host
    }

    fun detach(host: DrawerPinDragHost) {
        if (this.host !== host) return
        // The list is gone (iOS: the collection view left its window): end the drag and leave
        // no spring, translation or drop zone behind for the next list.
        cancelDrag()
        resetAllCellsAndCleanup()
        this.host = null
    }

    fun beginDrag(
        sessionID: String,
        isPinnedSection: Boolean,
        pinnedIDs: List<String>,
        recentIDs: List<String>,
        unpinnedRecentsIndex: Int,
        showsShortcuts: Boolean,
        reducedMotion: Boolean,
        fingerY: Float,
        onZoneCrossed: (Boolean) -> Unit,
        onSlotChanged: () -> Unit,
        onDropReleased: (String, Int?) -> Unit = { _, _ -> },
        onDrop: (String, Int?) -> Unit,
        onSetLifted: (String?) -> Unit,
    ) {
        resetAllCellsAndCleanup()
        val host = host ?: return

        activeSessionID = sessionID
        draggingID = sessionID
        startedInPinned = isPinnedSection
        this.pinnedIDs = pinnedIDs
        otherPinnedIDs = pinnedIDs.filter { it != sessionID }
        this.recentIDs = recentIDs
        recentInsertionIndex = unpinnedRecentsIndex.coerceIn(0, recentIDs.size)
        this.showsShortcuts = showsShortcuts
        this.reducedMotion = reducedMotion
        this.onZoneCrossed = onZoneCrossed
        this.onSlotChanged = onSlotChanged
        this.onDropReleased = onDropReleased
        this.onDrop = onDrop
        this.onSetLifted = onSetLifted
        onSetLifted(sessionID)

        val initialPinIdx = pinnedIDs.indexOf(sessionID).coerceAtLeast(0)
        hoverTarget = if (isPinnedSection) HoverTarget.Pinned(initialPinIdx) else HoverTarget.Recent
        hasRevealedEmptyZone = false
        hasCommittedDrop = false
        isLandingPlatter = false
        isCancellingEmptyZone = false
        hasNotifiedDropReleased = false
        commitRequested = false
        pendingPinnedHeaderOffset = null

        initialFingerY = fingerY
        lastFingerY = fingerY

        captureInitialGeometry()
        grabOffset = visibleItem(ItemKey.Row(sessionID))?.let { fingerY - (it.minY + it.height / 2) } ?: 0f
        emptyZoneSpring = makeSpring(0f, 0f)
        hoverSpring = SpringSolver.of(
            duration = if (reducedMotion) 0.15 else 0.24,
            bounce = 0.0,
            value = if (isPinnedSection) 1.0 else 0.0,
        )
        startFrameLoop(host)
    }

    private fun startFrameLoop(host: DrawerPinDragHost) {
        if (isDisplayLinkRunning) return
        frameJob = host.scope.launch {
            motionFrameLoop(frameDurationSeconds = host.frameDurationSeconds) { tick -> step(tick.dt) }
        }
    }

    /** The finger moved to [fingerY] (dp, list coordinates). */
    fun updateDrag(fingerY: Float) {
        if (activeSessionID == null || hasCommittedDrop || isLandingPlatter) return
        lastFingerY = fingerY
        positionLiftedRowUnderFinger()
        evaluateHover(fingerY)
        retargetSprings()
    }

    private fun positionLiftedRowUnderFinger() {
        val draggedID = activeSessionID ?: return
        val item = visibleItem(ItemKey.Row(draggedID)) ?: return
        val targetMinY = lastFingerY - grabOffset - item.height / 2
        cellSprings[item.key]?.let {
            it.value = targetMinY.toDouble()
            it.velocity = 0.0
        }
        translations[item.key] = targetMinY - item.minY
    }

    /**
     * The finger lifted at [fingerX]/[fingerY]: drops (lands the row in its slot, then commits), or
     * cancels beside the list (iOS's `.cancel` proposal).
     */
    fun endDrag(fingerX: Float, fingerY: Float) {
        if (activeSessionID == null || hasCommittedDrop) return
        val listWidth = host?.width?.takeIf { it > 0f } ?: Float.MAX_VALUE
        if (fingerX < -CANCEL_MARGIN || fingerX > listWidth + CANCEL_MARGIN) {
            cancelDrag(deferLiftedClear = true)
            return
        }
        updateDrag(fingerY)
        beginLanding()
    }

    /** The slot the lifted row lands in on drop, as its top in dp (iOS `landingTarget`). */
    fun landingSlotMinY(): Float? {
        val draggedID = activeSessionID ?: return null
        val stride = rowStride
        return when (val hover = hoverTarget) {
            is HoverTarget.Pinned ->
                if (pinnedIDs.isEmpty()) cachedTopMinY + cachedHeaderHeight else cachedFirstPinnedMinY + hover.index * stride
            HoverTarget.Recent -> if (!startedInPinned) {
                visibleItem(ItemKey.Row(draggedID))?.minY ?: run {
                    val r = recentIDs.indexOf(draggedID).takeIf { it >= 0 } ?: recentInsertionIndex
                    recentSlotMinY(r, shiftBeforeR = 0f)
                }
            } else {
                val shiftBeforeR = if (otherPinnedIDs.isEmpty()) -(cachedHeaderHeight + stride) else -stride
                recentSlotMinY(recentInsertionIndex, shiftBeforeR)
            }
        }
    }

    /** Where a cancelled drag's row returns: its original resting slot (iOS `cancelTarget`). */
    fun cancelSlotMinY(): Float? {
        val draggedID = activeSessionID ?: return null
        visibleItem(ItemKey.Row(draggedID))?.let { return it.minY }
        val p = pinnedIDs.indexOf(draggedID)
        if (startedInPinned && p >= 0) return cachedFirstPinnedMinY + p * rowStride
        val r = recentIDs.indexOf(draggedID).takeIf { it >= 0 } ?: recentInsertionIndex
        return recentSlotMinY(r, shiftBeforeR = 0f)
    }

    /**
     * Cancels an active or landing drag without mutating pins or playing a commit haptic,
     * springing shifted rows (the lifted one included) and any open empty-pin zone back to rest.
     */
    fun cancelDrag(deferLiftedClear: Boolean = false) {
        landingFallback?.cancel()
        landingFallback = null

        val hadActiveDrag = activeSessionID != null || isDisplayLinkRunning || cellSprings.isNotEmpty()
        if (!hadActiveDrag) return

        val clearLifted = onSetLifted

        onDrop = null
        onDropReleased = null
        onZoneCrossed = null
        onSlotChanged = null
        hasNotifiedDropReleased = true
        activeSessionID = null
        hasCommittedDrop = true
        isLandingPlatter = false
        hasRevealedEmptyZone = false

        val host = host
        if (host == null) {
            clearLifted?.invoke(null)
            resetAllCellsAndCleanup()
            return
        }

        isCancellingEmptyZone = emptyZoneSpring.value > 0.5
        emptyZoneSpring.retarget(0.0)
        hoverSpring.retarget(if (startedInPinned) 1.0 else 0.0)
        retargetSprings()

        if (!deferLiftedClear) {
            onSetLifted = null
            clearLifted?.invoke(null)
        } else {
            scheduleLandingFallback()
        }

        if (!deferLiftedClear && allSpringsSettled()) {
            resetAllCellsAndCleanup()
        } else {
            startFrameLoop(host)
        }
    }

    private val targetPinnedIndex: Int?
        get() = (hoverTarget as? HoverTarget.Pinned)?.index

    private fun willMutateStore(draggedID: String): Boolean = when (val hover = hoverTarget) {
        is HoverTarget.Pinned -> !startedInPinned || pinnedIDs.indexOf(draggedID) != hover.index
        HoverTarget.Recent -> startedInPinned
    }

    private fun notifyDropReleasedIfNeeded(draggedID: String) {
        if (hasNotifiedDropReleased) return
        hasNotifiedDropReleased = true
        onDropReleased?.invoke(draggedID, targetPinnedIndex)
    }

    /**
     * Freezes the hover target when the finger lifts (the reducer plays the commit haptic for a
     * drop that changes pins), and closes the drop zone if the drag returned to Recents with no
     * pins. The store mutation runs in [completeDrop] once the row has landed.
     */
    fun beginLanding() {
        val draggedID = activeSessionID
        if (hasCommittedDrop || draggedID == null) return
        isLandingPlatter = true
        notifyDropReleasedIfNeeded(draggedID)
        if (!willMutateStore(draggedID) && pinnedIDs.isEmpty() && emptyZoneSpring.value > 0.5) {
            isCancellingEmptyZone = true
            emptyZoneSpring.retarget(0.0)
            hoverSpring.retarget(0.0)
        }
        retargetSprings()
        scheduleLandingFallback()
    }

    /**
     * Commits the pin/unpin/reorder once the row has landed, publishing each row's translation
     * against its new place so no row jumps by even a fraction of a dp.
     */
    fun completeDrop() {
        landingFallback?.cancel()
        landingFallback = null

        val draggedID = activeSessionID
        if (hasCommittedDrop || draggedID == null) {
            finishLanding()
            return
        }
        hasCommittedDrop = true
        isLandingPlatter = false
        commitRequested = false

        val host = host
        if (host == null) {
            resetAllCellsAndCleanup()
            return
        }

        val targetPinnedIndex = targetPinnedIndex
        notifyDropReleasedIfNeeded(draggedID)

        if (!willMutateStore(draggedID)) {
            onSetLifted?.invoke(null)
            retargetSprings()
            if (allSpringsSettled()) resetAllCellsAndCleanup()
            return
        }

        val wasEmptyPins = pinnedIDs.isEmpty() && targetPinnedIndex != null
        val openZoneDelta = if (wasEmptyPins) emptyZoneSpring.value.toFloat() - (cachedHeaderHeight + rowStride) else 0f

        // Each row lands where its target was (the targets are its slot after the drop): after the
        // store change this frame lays it out there, so its translation is what's left of its spring.
        // The lifted row included: iOS snaps its hidden cell because the system's platter has
        // already landed; here the row itself is still settling, so it keeps what's left too (a
        // fallback commit just before it settles never jumps it).
        val visible = host.visibleItems()
        for (item in visible) {
            val spring = cellSprings[item.key] ?: continue
            val residual = (spring.value - spring.target).toFloat()
            setTranslation(item.key, residual)
        }
        if (wasEmptyPins) {
            val start = openZoneDelta * (cachedHeaderHeight / max(cachedHeaderHeight + rowStride, 1f))
            pendingPinnedHeaderOffset = start
            setTranslation(ItemKey.PinnedHeader, start)
            dropZone = null
        }
        // The rows' bases change with the drop: start their springs again from where they show.
        val residuals = visible.associate { it.key to (translations[it.key] ?: 0f) }
        cellSprings.clear()
        pendingResiduals = residuals

        host.preserveScroll()
        onDrop?.invoke(draggedID, targetPinnedIndex)
        onSetLifted?.invoke(null)
        startFrameLoop(host)
    }

    /** Translations carried across the drop, until the rows' new places are laid out. */
    private var pendingResiduals: Map<ItemKey, Float>? = null

    fun finishLanding() {
        landingFallback?.cancel()
        landingFallback = null
        isLandingPlatter = false
        onSetLifted?.invoke(null)
        onSetLifted = null
        if (host == null || allSpringsSettled()) resetAllCellsAndCleanup()
    }

    private fun scheduleLandingFallback() {
        landingFallback?.cancel()
        val host = host ?: return
        landingFallback = host.scope.launch {
            // iOS: 0.55 s of the system's drop animation, slowed with the lab time scale.
            delay((LANDING_FALLBACK_MILLIS / max(0.1, AnimationTimeScale.shared.scale)).toLong())
            landingFallback = null
            if (activeSessionID != null && !hasCommittedDrop) {
                // Commit from the frame loop, so the store change is laid out in the frame that
                // publishes the rows' carried-over translations (a commit between frames would
                // let the next step read the old layout as the new one).
                if (isDisplayLinkRunning) commitRequested = true else completeDrop()
            } else {
                finishLanding()
            }
        }
    }

    fun resetAllCellsAndCleanup() {
        landingFallback?.cancel()
        landingFallback = null
        frameJob?.cancel()
        frameJob = null
        translations.clear()
        pinnedHeaderAlpha = 1f
        dropZone = null
        cellSprings.clear()
        pendingResiduals = null
        pendingPinnedHeaderOffset = null
        if (activeSessionID != null || onSetLifted != null) onSetLifted?.invoke(null)
        activeSessionID = null
        draggingID = null
        onDrop = null
        onDropReleased = null
        onZoneCrossed = null
        onSlotChanged = null
        onSetLifted = null
        hasCommittedDrop = false
        isLandingPlatter = false
        isCancellingEmptyZone = false
        hasNotifiedDropReleased = false
        commitRequested = false
    }

    internal fun setTranslation(key: ItemKey, value: Float) {
        val shown = if (kotlin.math.abs(value) > 0.02f) value else 0f
        if (shown == 0f) {
            if (translations.containsKey(key)) translations.remove(key)
        } else if (translations[key] != shown) {
            translations[key] = shown
        }
    }

    // ---- Frame step (iOS `step(tick:)`, in DrawerPinDragGeometry.kt's helpers) ----

    internal fun step(dt: Double): MotionFrameAction {
        val host = host
        if (host == null) {
            resetAllCellsAndCleanup()
            return MotionFrameAction.Stop
        }
        val keyed = host.visibleItems()

        // After a drop: the rows' new places are laid out; carry over what showed.
        pendingResiduals?.let { residuals ->
            pendingResiduals = null
            for (item in keyed) {
                val shown = residuals[item.key]
                    ?: if (item.key == ItemKey.PinnedHeader) pendingPinnedHeaderOffset ?: 0f else 0f
                cellSprings[item.key] = makeSpring(item.minY + shown, item.minY)
            }
            pendingPinnedHeaderOffset = null
        }

        retargetSprings(keyed)
        emptyZoneSpring.step(dt)
        hoverSpring.step(dt)

        val visibleKeys = HashSet<ItemKey>(keyed.size)
        for (item in keyed) {
            visibleKeys += item.key
            val spring = cellSprings.getOrPut(item.key) { makeSpring(item.minY, item.minY) }
            val key = item.key
            // The lifted row follows the finger while dragging.
            if (key is ItemKey.Row && key.id == activeSessionID && !hasCommittedDrop && !isLandingPlatter) {
                spring.value = (lastFingerY - grabOffset - item.height / 2).toDouble()
                spring.velocity = 0.0
            } else {
                spring.step(dt)
            }
            setTranslation(key, (spring.value - item.minY).toFloat())
        }
        // Rows scrolled out of the list keep no translation.
        if (translations.keys.any { it !in visibleKeys }) {
            translations.keys.filter { it !in visibleKeys }.forEach { translations.remove(it) }
        }
        pinnedHeaderAlpha = if (startedInPinned && otherPinnedIDs.isEmpty() && !hasCommittedDrop) {
            hoverSpring.value.toFloat().coerceIn(0f, 1f)
        } else {
            1f
        }

        updateEmptyZone()

        // The lifted row has landed in its slot: commit.
        if (isLandingPlatter && !hasCommittedDrop) {
            val draggedID = activeSessionID
            val dragged = draggedID?.let { cellSprings[ItemKey.Row(it)] }
            if (commitRequested || dragged == null || dragged.isSettled) completeDrop()
            return MotionFrameAction.Continue
        }
        if (hasCommittedDrop && !isLandingPlatter && pendingResiduals == null && allSpringsSettled(keyed)) {
            // A cancelled drag's lifted row has landed back: let it go.
            landingFallback?.cancel()
            landingFallback = null
            frameJob = null
            resetAllCellsAndCleanup()
            return MotionFrameAction.Stop
        }
        return MotionFrameAction.Continue
    }

    internal fun allSpringsSettled(keyed: List<DrawerPinDragItem>? = null): Boolean {
        if (!emptyZoneSpring.isSettled) return false
        for (item in keyed ?: host?.visibleItems().orEmpty()) {
            val spring = cellSprings[item.key] ?: continue
            if (!spring.isSettled) return false
        }
        return true
    }

    private fun updateEmptyZone() {
        val shouldShow = pinnedIDs.isEmpty() && !startedInPinned &&
            (!hasCommittedDrop || isCancellingEmptyZone) &&
            (emptyZoneSpring.value > 0.5 || emptyZoneSpring.target > 0.5)
        if (!shouldShow) {
            if (dropZone != null) dropZone = null
            return
        }
        val fullHeight = max(cachedHeaderHeight + rowStride, 1f)
        dropZone = DrawerPinDropZone(
            top = cachedTopMinY,
            height = max(emptyZoneSpring.value.toFloat(), 1f),
            openProgress = (emptyZoneSpring.value.toFloat() / fullHeight).coerceIn(0f, 1f),
            hoverProgress = hoverSpring.value.toFloat().coerceIn(0f, 1f),
            headerHeight = cachedHeaderHeight,
            reducedMotion = reducedMotion,
        )
    }

    internal fun notifyZoneCrossed(entered: Boolean) {
        onZoneCrossed?.invoke(entered)
    }

    internal fun notifySlotChanged() {
        onSlotChanged?.invoke()
    }
}
