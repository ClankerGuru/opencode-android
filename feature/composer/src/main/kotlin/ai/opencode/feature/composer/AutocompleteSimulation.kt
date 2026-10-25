package ai.opencode.feature.composer

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A point in dp (iOS `CGPoint`). */
data class AutocompletePoint(val x: Double, val y: Double)

/** A size in dp (iOS `CGSize`). */
data class AutocompleteSize(val width: Double, val height: Double)

/** A rect in dp (iOS `CGRect`, never negative here). */
data class AutocompleteRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
    val midY: Double get() = y + height / 2
    val isEmpty: Boolean get() = width <= 0.0 || height <= 0.0

    fun offsetBy(dx: Double, dy: Double): AutocompleteRect = copy(x = x + dx, y = y + dy)

    /** `CGRect.contains(_:)`: inclusive of the min edges, exclusive of the max edges. */
    fun contains(point: AutocompletePoint): Boolean =
        !isEmpty && point.x >= minX && point.x < maxX && point.y >= minY && point.y < maxY

    companion object {
        val Zero = AutocompleteRect(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Second-order spring ported from the Round 2 web study (`model.js`), iOS
 * `AutocompleteSpring`. Keeps position and velocity across retargets so interrupted opens,
 * filter updates and closes never jump. Stepped in closed form with
 * [SpringSolver.evaluate]; `lead` divides the duration so one axis can lead another.
 *
 * Mutable like the Swift value type; [copy] gives an independent value.
 */
data class AutocompleteSpring(
    var x: Double = 0.0,
    var v: Double = 0.0,
    var t: Double = x,
    var duration: Double = 0.3,
    var bounce: Double = 0.0,
    var lead: Double = 1.0,
) {
    constructor(initial: Double) : this(x = initial, t = initial)

    fun to(target: Double, duration: Double, bounce: Double, lead: Double = 1.0) {
        t = target
        this.duration = duration
        this.bounce = bounce
        this.lead = lead
    }

    fun snap(value: Double) {
        x = value
        t = value
        v = 0.0
    }

    fun isStill(eps: Double = 0.002): Boolean = abs(x - t) < eps && abs(v) < eps * 8

    fun step(dt: Double) {
        val d = max(0.001, duration / max(lead, 0.1))
        if (d <= 0.004) {
            x = t
            v = 0.0
            return
        }
        val omega = 2 * PI / d
        val zeta = max(0.01, if (bounce >= 0) 1 - bounce else 1 / (1 + bounce))
        val (nx, nv) = SpringSolver.evaluate(from = x, to = t, initialVelocity = v, omega = omega, zeta = zeta, elapsed = dt)
        x = nx
        v = nv
    }
}

/** Parameters snapshot read by [AutocompleteSimulation] (iOS `AutocompleteParameters`). */
data class AutocompleteParameters(
    val order: AutocompleteOrder = AutocompleteOrder.Bottom,
    val exitMode: AutocompleteExitMode = AutocompleteExitMode.Collapse,
    val keysInstant: Boolean = true,
    val reducedMotion: Boolean = false,

    val openDuration: Double = 0.3,
    val openBounce: Double = 0.0,
    val closeDuration: Double = 0.22,
    val closeBounce: Double = 0.0,
    val contentInDuration: Double = 0.28,
    val contentInBounce: Double = 0.0,
    val contentOutDuration: Double = 0.18,
    val contentOutBounce: Double = 0.0,
    val resizeDuration: Double = 0.14,
    val resizeBounce: Double = 0.0,
    val reflowDuration: Double = 0.14,
    val reflowBounce: Double = 0.0,
    val rowInDuration: Double = 0.1,
    val rowInBounce: Double = 0.0,
    val rowOutDuration: Double = 0.06,
    val rowOutBounce: Double = 0.0,
    val highlightDuration: Double = 0.14,
    val highlightBounce: Double = 0.0,
    val flightDuration: Double = 0.32,
    val flightBounce: Double = 0.0,

    val widthLead: Double = 1.85,
    val contentDelay: Double = 0.03,
    val coalesce: Double = 0.0,
    val blur: Double = 4.0,
    val scale: Double = 0.96,
    val rowBlur: Double = 0.0,
    val rowScale: Double = 1.0,
    val flightArc: Double = 1.4,
    val reduceFade: Double = 0.18,

    val maxRows: Double = 5.5,
    val rowHeight: Double = 40.0,
    val padding: Double = 12.0,
    val rowInset: Double = 7.0,
    val iconSize: Double = 22.0,
    val edgeFade: Double = 24.0,
    val cornerRadius: Double = 28.0,
) {
    companion object {
        /** iOS `AutocompleteParameters.current(reducedMotion:)`: the tunables' values. */
        fun current(reducedMotion: Boolean = false): AutocompleteParameters = with(ComposerAutocompleteTunables) {
            AutocompleteParameters(
                order = order,
                exitMode = exitMode,
                keysInstant = keysInstant,
                reducedMotion = reducedMotion,
                openDuration = open.duration,
                openBounce = open.bounce,
                closeDuration = close.duration,
                closeBounce = close.bounce,
                contentInDuration = contentIn.duration,
                contentInBounce = contentIn.bounce,
                contentOutDuration = contentOut.duration,
                contentOutBounce = contentOut.bounce,
                resizeDuration = resize.duration,
                resizeBounce = resize.bounce,
                reflowDuration = reflow.duration,
                reflowBounce = reflow.bounce,
                rowInDuration = rowIn.duration,
                rowInBounce = rowIn.bounce,
                rowOutDuration = rowOut.duration,
                rowOutBounce = rowOut.bounce,
                highlightDuration = highlight.duration,
                highlightBounce = highlight.bounce,
                flightDuration = flight.duration,
                flightBounce = flight.bounce,
                widthLead = widthLead,
                contentDelay = contentDelay,
                coalesce = coalesce,
                blur = blur,
                scale = scale,
                rowBlur = rowBlur,
                rowScale = rowScale,
                flightArc = flightArc,
                reduceFade = reduceFade,
                maxRows = maxRows,
                rowHeight = rowHeight,
                padding = padding,
                rowInset = rowInset,
                iconSize = iconSize,
                edgeFade = edgeFade,
                cornerRadius = composerCornerRadius,
            )
        }
    }
}

/** Metrics for inline solid chips, matching `CHIP` in `model.js` (iOS `AutocompleteChipMetrics`). Lengths in dp, font in sp. */
object AutocompleteChipMetrics {
    const val height: Double = 22.0
    const val leadingPad: Double = 6.0
    const val iconSize: Double = 14.0
    const val gap: Double = 5.0
    const val trailingPad: Double = 8.0
    const val cornerRadius: Double = 7.0
    const val fontSize: Double = 15.0
    /** Solid opaque dark tone `rgb(56, 56, 59)` and highlight start tone `rgb(48, 48, 51)`. */
    const val highlightToneDark: Double = 48.0
    const val chipToneDark: Double = 56.0
    const val highlightToneLight: Double = 232.0
    const val chipToneLight: Double = 224.0

    /**
     * iOS `scaledHeight(for:)`: `max(height, ceil(lineHeight + 4))` of the medium 15 pt
     * subheadline font, scaled. [fontLineHeight] is the scaled chip font's line height in dp.
     */
    fun scaledHeight(fontLineHeight: Double): Double = max(height, ceil(fontLineHeight + 4))

    /**
     * iOS `width(for:maxWidth:)`: the chip's full width for a title measured at [titleWidth]
     * dp in the chip font, never wider than [maxWidth] (but at least 44 dp).
     */
    fun width(titleWidth: Double, maxWidth: Double = Double.MAX_VALUE): Double {
        val textWidth = ceil(titleWidth) + 3
        val full = leadingPad + iconSize + gap + textWidth + trailingPad
        return min(full, max(44.0, maxWidth))
    }
}

/**
 * Pure, frame-by-frame simulation of the Round 2 Composer Grows autocomplete popover, row
 * slot collapse, concentric highlight, edge fades and chip flights (iOS
 * `AutocompleteSimulation`). All lengths are dp; `displayScale` is the density (px per dp).
 */
class AutocompleteSimulation(var params: AutocompleteParameters = AutocompleteParameters()) {
    data class FrozenRow(val rect: AutocompleteRect, val fullSize: AutocompleteSize, val offsetFromAnchor: AutocompletePoint)

    class RowState(
        val id: String,
        var item: AutocompleteItem,
        var isEntering: Boolean = true,
        var taken: Boolean = false,
        val p: AutocompleteSpring,
        val slot: AutocompleteSpring,
        val dx: AutocompleteSpring = AutocompleteSpring(0.0),
        val dy: AutocompleteSpring = AutocompleteSpring(0.0),
        val w: AutocompleteSpring,
        val h: AutocompleteSpring,
        var frozen: FrozenRow? = null,
        var fresh: Boolean = true,
    )

    data class DrawnRow(
        val id: String,
        val item: AutocompleteItem,
        /** Slot rect in list-local coordinates (0, 0 is top-left of the list window). */
        val rect: AutocompleteRect,
        val fullSize: AutocompleteSize,
        val isLeaving: Boolean,
        val alpha: Double,
        val blur: Double,
        val scale: Double,
        val isHighlighted: Boolean,
        val cornerRadius: Double,
    )

    data class DrawnHighlight(
        /** Highlight rect in list-local coordinates. */
        val rect: AutocompleteRect,
        val alpha: Double,
        val cornerRadius: Double,
    )

    data class EdgeFadeMask(
        val topHidden: Double,
        val bottomHidden: Double,
        val fadeLength: Double,
        /** Opacity at the very top edge (`1` when nothing is hidden above; `0` when ≥ `fadeLength` is hidden). */
        val topEdgeAlpha: Double,
        /** Opacity at the very bottom edge (`1` when nothing is hidden below; `0` when ≥ `fadeLength` is hidden). */
        val bottomEdgeAlpha: Double,
    )

    data class ChipFlight(
        val id: UUID,
        val chipID: UUID,
        val item: AutocompleteItem,
        val fromRadius: Double,
        var targetRect: AutocompleteRect,
        val x: AutocompleteSpring,
        val y: AutocompleteSpring,
        val w: AutocompleteSpring,
        val h: AutocompleteSpring,
        var progress: Double = 0.0,
        var initialDistance: Double? = null,
    ) {
        val currentRect: AutocompleteRect get() = AutocompleteRect(x.x, y.x, max(0.0, w.x), max(0.0, h.x))

        fun rect(scale: Double): AutocompleteRect = snapRectEdges(currentRect, scale)

        val currentRadius: Double get() = fromRadius + (AutocompleteChipMetrics.cornerRadius - fromRadius) * progress

        val smoothProgress: Double get() = smooth(0.0, 1.0, progress)

        val kindAlpha: Double get() = 1 - smooth(0.0, 0.35, progress)

        /** An independent copy (springs included) for a published [Frame]. */
        fun snapshot(): ChipFlight = copy(x = x.copy(), y = y.copy(), w = w.copy(), h = h.copy())
    }

    data class Frame(
        val isOpen: Boolean,
        val isShown: Boolean,
        /**
         * Composer glass rect in composer-local coordinates, with top and bottom edges snapped
         * to the pixel grid (`minY = -glassLift`, `maxY = snapEdge(composerHeight)`).
         */
        val glassRect: AutocompleteRect,
        /** How far the composer glass extends above the composer's top edge (dp). */
        val glassLift: Double,
        /** Resting lift for the current rows (used for Reduce Motion layout). */
        val restingLift: Double,
        /** Height of the list window (`L`). */
        val listHeight: Double,
        val listWidth: Double,
        val padding: Double,
        val displayScale: Double,
        val contentAlpha: Double,
        val contentBlur: Double,
        val contentScale: Double,
        val rows: List<DrawnRow>,
        val highlight: DrawnHighlight?,
        val edgeFade: EdgeFadeMask,
        val flights: List<ChipFlight>,
    )

    var time: Double = 0.0
    var isOpen: Boolean = false
    var mode: AutocompleteTrigger.Mode? = null
    var query: String = ""
    var key: String? = null

    val a = AutocompleteSpring(0.0)
    val g = AutocompleteSpring(0.0)
    val c = AutocompleteSpring(0.0)
    val h = AutocompleteSpring(0.0)

    var rows: MutableList<RowState> = mutableListOf()
    var highlightedID: String? = null
    var userMoved: Boolean = false
    val hx = AutocompleteSpring(0.0)
    val hy = AutocompleteSpring(0.0)
    val hw = AutocompleteSpring(0.0)
    val hh = AutocompleteSpring(0.0)
    val ha = AutocompleteSpring(0.0)

    data class PendingCommit(val items: List<AutocompleteItem>, val highlightedID: String?, val userMoved: Boolean)

    var scrollOffset: Double = 0.0
    var lastCommitTime: Double = -10.0
    var pendingItems: PendingCommit? = null
    var pendingCommitAt: Double? = null
    var contentInScheduledAt: Double? = null

    var flights: MutableList<ChipFlight> = mutableListOf()
    val landedChipIDs: MutableSet<UUID> = mutableSetOf()

    val isCollapsingExit: Boolean get() = params.exitMode == AutocompleteExitMode.Collapse && !params.reducedMotion

    val isBottomOrder: Boolean get() = params.order == AutocompleteOrder.Bottom

    val maxListHeight: Double get() = params.maxRows * params.rowHeight

    val highlightCornerRadius: Double get() = max(0.0, min(params.rowHeight / 2, params.cornerRadius - params.padding))

    val isAnimating: Boolean
        get() = flights.isNotEmpty() ||
            pendingCommitAt != null ||
            contentInScheduledAt != null ||
            !a.isStill() || !g.isStill() || !c.isStill() || !h.isStill() ||
            !hx.isStill() || !hy.isStill() || !hw.isStill() || !hh.isStill() || !ha.isStill() ||
            (!isOpen && rows.isNotEmpty()) ||
            rows.any {
                !it.p.isStill() || !it.slot.isStill() || !it.dx.isStill() || !it.dy.isStill() ||
                    !it.w.isStill() || !it.h.isStill() || !it.isEntering
            }

    /**
     * Under Reduce Motion nothing moves (heights, slots and the highlight snap elsewhere);
     * what remains are cross-fades, never slower than the spring they stand in for.
     */
    private fun springConfig(duration: Double, bounce: Double): Pair<Double, Double> =
        if (params.reducedMotion) min(duration, params.reduceFade) to 0.0 else duration to bounce

    private fun slotFactor(row: RowState): Double {
        if (isCollapsingExit) return clamp(row.slot.x, 0.0, 1.0)
        return if (row.isEntering) 1.0 else 0.0
    }

    private class StackEntry(
        val ox: Double,
        val oy: Double,
        val tw: Double,
        val th: Double,
        val u: Double,
        val uEnd: Double,
        val size: Double,
        val k: Double,
        val span: Double,
    )

    private class StackLayout(val entries: Map<String, StackEntry>, val length: Double, val finalLength: Double)

    private fun layoutRows(listWidth: Double): StackLayout {
        val entries = HashMap<String, StackEntry>()
        var u = 0.0
        var finalLength = 0.0
        for (row in rows) {
            val size = params.rowHeight
            val tw = listWidth
            val th = size
            if (row.isEntering) finalLength += size
            if (row.frozen != null) {
                entries[row.id] = StackEntry(0.0, 0.0, tw, th, u, u, size, 0.0, 0.0)
                continue
            }
            val k = slotFactor(row)
            val span = size * k
            val nextU = u + span
            val oy = if (isBottomOrder) -nextU else u
            entries[row.id] = StackEntry(0.0, oy, tw, th, u, nextU, size, k, span)
            u = nextU
        }
        return StackLayout(entries, u, finalLength)
    }

    private class RowBox(
        val rect: AutocompleteRect,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val fullSize: AutocompleteSize,
    )

    private class RowGeometry(
        val layout: StackLayout,
        val rects: Map<String, RowBox>,
        val anchorY: Double,
        val scrollY: Double,
    )

    /** Computes each row's slot rect in list-local coordinates (`0..<listWidth` × `0..<listHeight` is the list window). */
    private fun rowRects(listWidth: Double, listHeight: Double): RowGeometry {
        val lay = layoutRows(listWidth)
        val anchorY = if (isBottomOrder) listHeight else 0.0
        val sy = if (isBottomOrder) scrollOffset else -scrollOffset
        val rects = HashMap<String, RowBox>()
        for (row in rows) {
            val entry = lay.entries[row.id] ?: continue
            val frozen = row.frozen
            if (frozen != null) {
                val left = frozen.offsetFromAnchor.x
                val top = anchorY + frozen.offsetFromAnchor.y + sy
                val right = left + max(0.0, frozen.rect.width)
                val bottom = top + max(0.0, frozen.rect.height)
                val rect = AutocompleteRect(left, top, max(0.0, right - left), max(0.0, bottom - top))
                rects[row.id] = RowBox(rect, left, top, right, bottom, frozen.fullSize)
            } else {
                val left = entry.ox + row.dx.x
                val right = left + max(0.0, row.w.x)
                val top: Double
                val bottom: Double
                if (isBottomOrder) {
                    top = anchorY - entry.uEnd + row.dy.x + sy
                    bottom = anchorY - entry.u + row.dy.x + sy
                } else {
                    top = anchorY + entry.u + row.dy.x + sy
                    bottom = anchorY + entry.uEnd + row.dy.x + sy
                }
                val rect = AutocompleteRect(left, top, max(0.0, right - left), max(0.0, bottom - top))
                rects[row.id] = RowBox(rect, left, top, right, bottom, AutocompleteSize(row.w.x, row.h.x))
            }
        }
        return RowGeometry(lay, rects, anchorY, sy)
    }

    private fun currentHighlightRect(rects: Map<String, RowBox>): AutocompleteRect? {
        val id = highlightedID ?: return null
        val target = rects[id]?.rect ?: return null
        return AutocompleteRect(
            target.minX + hx.x,
            target.minY + hy.x,
            max(0.0, target.width + hw.x),
            max(0.0, target.height + hh.x),
        )
    }

    private fun syncHeight(listWidth: Double, instant: Boolean) {
        val lay = layoutRows(listWidth)
        val targetLen = min(maxListHeight, if (isCollapsingExit) lay.length else lay.finalLength)
        if (instant || params.reducedMotion || isCollapsingExit) {
            h.snap(targetLen)
        } else if (abs(h.t - targetLen) > 0.01) {
            val (d, b) = springConfig(params.resizeDuration, params.resizeBounce)
            h.to(targetLen, d, b)
        }
        scrollOffset = clamp(scrollOffset, 0.0, max(0.0, lay.length - maxListHeight))
    }

    private fun aimHighlight(id: String?, oldRect: AutocompleteRect?, rects: Map<String, RowBox>, instant: Boolean) {
        highlightedID = id
        if (id == null) {
            val (d, b) = springConfig(params.rowOutDuration, params.rowOutBounce)
            ha.to(0.0, d, b)
            return
        }
        val nextRect = rects[id]?.rect
        val snapNow = instant || params.reducedMotion || ha.x < 0.02 || oldRect == null || nextRect == null
        if (snapNow) {
            hx.snap(0.0)
            hy.snap(0.0)
            hw.snap(0.0)
            hh.snap(0.0)
        } else {
            val (d, b) = springConfig(params.highlightDuration, params.highlightBounce)
            hx.x = oldRect!!.minX - nextRect!!.minX
            hx.t = hx.x
            hx.to(0.0, d, b)
            hy.x = oldRect.minY - nextRect.minY
            hy.t = hy.x
            hy.to(0.0, d, b)
            hw.x = oldRect.width - nextRect.width
            hw.t = hw.x
            hw.to(0.0, d, b)
            hh.x = oldRect.height - nextRect.height
            hh.t = hh.x
            hh.to(0.0, d, b)
        }
        val (inD, inB) = springConfig(params.rowInDuration, params.rowInBounce)
        ha.to(1.0, inD, inB)
    }

    /** Opens or updates the autocomplete popover with [items]. */
    fun sync(
        isOpen: Boolean,
        mode: AutocompleteTrigger.Mode?,
        query: String,
        key: String?,
        items: List<AutocompleteItem>,
        selectedRowID: String?,
        userMoved: Boolean,
        composerWidth: Double,
    ) {
        val listWidth = max(0.0, composerWidth - 2 * params.padding)
        if (!isOpen || mode == null || items.isEmpty()) {
            close()
            return
        }

        val wasClosed = !this.isOpen && a.x < 0.05
        if (wasClosed) {
            rows = mutableListOf()
            scrollOffset = 0.0
            lastCommitTime = -10.0
            pendingItems = null
            pendingCommitAt = null
        }

        val openedNow = !this.isOpen
        this.isOpen = true
        this.mode = mode
        this.query = query
        this.key = key

        if (openedNow) {
            val (od, ob) = springConfig(params.openDuration, params.openBounce)
            a.to(1.0, od, ob)
            g.to(1.0, od, ob)
            if (params.contentDelay > 0 && !params.reducedMotion && wasClosed) {
                contentInScheduledAt = time + params.contentDelay
            } else {
                contentInScheduledAt = null
                val (cd, cb) = springConfig(params.contentInDuration, params.contentInBounce)
                c.to(1.0, cd, cb)
            }
            commit(items, selectedRowID, userMoved, listWidth, fresh = wasClosed)
            return
        }

        // If only selection moved (arrow keys / hover), aim the highlight immediately.
        val currentInIDs = rows.filter { it.isEntering }.map { it.id }
        val nextIDs = items.map { it.id }
        if (currentInIDs == nextIDs) {
            if (selectedRowID != highlightedID) {
                val before = rowRects(listWidth, h.x)
                val oldHi = currentHighlightRect(before.rects)
                this.userMoved = userMoved
                aimHighlight(selectedRowID, oldHi, before.rects, instant = params.keysInstant)
                reveal(selectedRowID, listWidth)
            }
            return
        }

        // Coalesce rapid filter updates to `coalesce`.
        val sinceLastCommit = time - lastCommitTime
        if (params.reducedMotion || params.coalesce <= 0 || sinceLastCommit >= params.coalesce) {
            pendingItems = null
            pendingCommitAt = null
            commit(items, selectedRowID, userMoved, listWidth, fresh = false)
        } else {
            pendingItems = PendingCommit(items, selectedRowID, userMoved)
            if (pendingCommitAt == null) pendingCommitAt = lastCommitTime + params.coalesce
        }
    }

    fun commit(
        items: List<AutocompleteItem>,
        highlightedID: String?,
        userMoved: Boolean,
        listWidth: Double,
        fresh: Boolean,
    ) {
        lastCommitTime = time
        val before = if (fresh) null else rowRects(listWidth, h.x)
        val oldHi = before?.let { currentHighlightRect(it.rects) }

        val byID: Map<String, RowState> = rows.associateBy { it.id }
        val wantedIDs = items.map { it.id }.toSet()
        val next = mutableListOf<RowState>()

        val (rowInD, rowInB) = springConfig(params.rowInDuration, params.rowInBounce)
        val (reflowD, reflowB) = springConfig(params.reflowDuration, params.reflowBounce)
        val (rowOutD, rowOutB) = springConfig(params.rowOutDuration, params.rowOutBounce)

        for (item in items) {
            val existing = byID[item.id]
            val r: RowState
            if (existing != null) {
                existing.item = item
                existing.taken = false
                if (!existing.isEntering) {
                    existing.isEntering = true
                    if (existing.frozen != null) {
                        existing.frozen = null
                        existing.slot.snap(if (isCollapsingExit) 0.0 else 1.0)
                    }
                }
                r = existing
            } else {
                r = RowState(
                    id = item.id,
                    item = item,
                    isEntering = true,
                    p = AutocompleteSpring(if (fresh) 1.0 else 0.0),
                    slot = AutocompleteSpring(if (fresh || !isCollapsingExit) 1.0 else 0.0),
                    w = AutocompleteSpring(listWidth),
                    h = AutocompleteSpring(params.rowHeight),
                    fresh = true,
                )
            }
            if (fresh) {
                r.p.snap(1.0)
                r.slot.snap(1.0)
            } else {
                r.p.to(1.0, rowInD, rowInB)
                if (isCollapsingExit) r.slot.to(1.0, reflowD, reflowB) else r.slot.snap(1.0)
            }
            next.add(r)
        }

        // Leaving rows keep their place in the order, after the row that preceded them.
        val oldRows = rows.toList()
        for ((index, oldRow) in oldRows.withIndex()) {
            if (oldRow.id in wantedIDs) continue
            val r = oldRow
            if (r.isEntering) {
                r.isEntering = false
                r.p.to(0.0, rowOutD, rowOutB)
                if (isCollapsingExit) {
                    r.slot.to(0.0, reflowD, reflowB)
                } else {
                    val b = before?.rects?.get(r.id)
                    if (before != null && b != null) {
                        r.frozen = FrozenRow(
                            rect = b.rect,
                            fullSize = b.fullSize,
                            offsetFromAnchor = AutocompletePoint(b.rect.minX, b.rect.minY - before.anchorY - before.scrollY),
                        )
                    }
                }
            }
            if (fresh) continue
            val precedingKept = oldRows.subList(0, index).asReversed().firstOrNull { prev -> next.any { it.id == prev.id } }
            val insertAfter = precedingKept?.let { kept -> next.indexOfFirst { it.id == kept.id } } ?: -1
            if (insertAfter >= 0) next.add(insertAfter + 1, r) else next.add(0, r)
        }

        rows = next
        val lay = layoutRows(listWidth)
        for (row in rows) {
            val entry = lay.entries[row.id] ?: continue
            if (fresh || row.fresh || params.reducedMotion) {
                row.w.snap(entry.tw)
                row.h.snap(entry.th)
                row.fresh = false
            } else {
                if (abs(row.w.t - entry.tw) > 0.01) row.w.to(entry.tw, reflowD, reflowB)
                if (abs(row.h.t - entry.th) > 0.01) row.h.to(entry.th, reflowD, reflowB)
            }
        }

        syncHeight(listWidth, instant = fresh)

        // FLIP: rows that changed slot position start where they were drawn and glide home.
        if (before != null) {
            val now = rowRects(listWidth, h.x)
            for (row in rows) {
                if (row.frozen != null) continue
                val b = before.rects[row.id]?.rect ?: continue
                val n = now.rects[row.id]?.rect ?: continue
                val ddx = b.minX - n.minX
                val ddy = b.minY - n.minY
                if (params.reducedMotion) {
                    row.dx.snap(0.0)
                    row.dy.snap(0.0)
                } else {
                    if (abs(ddx) > 0.01) {
                        row.dx.x += ddx
                        row.dx.t = row.dx.x
                        row.dx.to(0.0, reflowD, reflowB)
                    }
                    if (abs(ddy) > 0.01) {
                        row.dy.x += ddy
                        row.dy.t = row.dy.x
                        row.dy.to(0.0, reflowD, reflowB)
                    }
                }
            }
        }

        val nowRects = rowRects(listWidth, h.x).rects
        this.userMoved = userMoved
        val targetHi = highlightedID ?: rows.firstOrNull { it.isEntering && it.item.isSelectable }?.id
        if (fresh) {
            aimHighlight(targetHi, null, nowRects, instant = true)
        } else if (targetHi != this.highlightedID || ha.t < 1) {
            aimHighlight(targetHi, oldHi, nowRects, instant = false)
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        pendingItems = null
        pendingCommitAt = null
        contentInScheduledAt = null
        val (cd, cb) = springConfig(params.closeDuration, params.closeBounce)
        val (od, ob) = springConfig(params.contentOutDuration, params.contentOutBounce)
        a.to(0.0, cd, cb)
        g.to(0.0, cd, cb)
        c.to(0.0, od, ob)
    }

    fun reveal(id: String?, listWidth: Double) {
        if (id == null) return
        val entry = layoutRows(listWidth).entries[id] ?: return
        val view = maxListHeight
        if (entry.u < scrollOffset) {
            scrollOffset = entry.u
        } else if (entry.u + entry.size > scrollOffset + view) {
            scrollOffset = entry.u + entry.size - view
        }
    }

    fun scroll(deltaY: Double, listWidth: Double) {
        val lay = layoutRows(listWidth)
        val maxScroll = max(0.0, lay.length - maxListHeight)
        val delta = if (isBottomOrder) deltaY else -deltaY
        scrollOffset = clamp(scrollOffset + delta, 0.0, maxScroll)
    }

    /**
     * Launches a solid opaque chip flight from the chosen row's highlight rect (composer-local
     * coordinates) to [targetRect] (composer-local coordinates).
     */
    fun launchFlight(
        rowID: String,
        chip: ComposerAutocompleteChip,
        composerWidth: Double,
        composerHeight: Double = 52.0,
        displayScale: Double = 3.0,
        targetRect: AutocompleteRect,
    ) {
        val f = frame(composerWidth, composerHeight, displayScale)
        val originX = f.padding
        val originY = -f.listHeight
        val hi = f.highlight
        val drawn = f.rows.firstOrNull { it.id == rowID }
        val fromLocal = when {
            hi != null && highlightedID == rowID && hi.alpha > 0.5 -> hi.rect.offsetBy(originX, originY)
            drawn != null -> drawn.rect.offsetBy(originX, originY)
            else -> AutocompleteRect(f.padding, -params.rowHeight, max(60.0, f.listWidth), params.rowHeight)
        }

        rows.firstOrNull { it.id == rowID }?.taken = true
        ha.snap(0.0)

        if (params.reducedMotion) {
            landedChipIDs.add(chip.id)
            close()
            return
        }

        val flight = ChipFlight(
            id = UUID.randomUUID(),
            chipID = chip.id,
            item = chip.item,
            fromRadius = highlightCornerRadius,
            targetRect = targetRect,
            x = AutocompleteSpring(fromLocal.minX),
            y = AutocompleteSpring(fromLocal.minY),
            w = AutocompleteSpring(fromLocal.width),
            h = AutocompleteSpring(fromLocal.height),
        )
        retargetFlight(flight, targetRect)
        flights.add(flight)
        close()
    }

    /** Updates the destination rect of an in-flight chip as the text field lays it out. */
    fun updateFlightTarget(chipID: UUID, targetRect: AutocompleteRect) {
        for (flight in flights) if (flight.chipID == chipID) retargetFlight(flight, targetRect)
    }

    private fun retargetFlight(flight: ChipFlight, targetRect: AutocompleteRect) {
        val duration = params.flightDuration
        val bounce = params.flightBounce
        flight.targetRect = targetRect
        flight.x.to(targetRect.minX, duration, bounce)
        flight.y.to(targetRect.minY, duration, bounce, lead = params.flightArc)
        flight.w.to(targetRect.width, duration, bounce, lead = params.widthLead)
        flight.h.to(targetRect.height, duration, bounce)
    }

    /** Advances all springs by [dt] seconds. */
    fun step(dt: Double, composerWidth: Double) {
        val clampedDt = min(max(dt, 1.0 / 240.0), 1.0 / 20.0)
        time += clampedDt
        val listWidth = max(0.0, composerWidth - 2 * params.padding)

        val contentAt = contentInScheduledAt
        if (contentAt != null && time >= contentAt) {
            contentInScheduledAt = null
            if (isOpen) {
                val (cd, cb) = springConfig(params.contentInDuration, params.contentInBounce)
                c.to(1.0, cd, cb)
            }
        }

        val commitAt = pendingCommitAt
        if (commitAt != null && time >= commitAt) {
            pendingCommitAt = null
            val pending = pendingItems
            if (pending != null && isOpen) {
                pendingItems = null
                commit(pending.items, pending.highlightedID, pending.userMoved, listWidth, fresh = false)
            }
        }

        for (spring in listOf(a, g, c, h, hx, hy, hw, hh, ha)) spring.step(clampedDt)

        for (row in rows) {
            row.p.step(clampedDt)
            row.slot.step(clampedDt)
            row.dx.step(clampedDt)
            row.dy.step(clampedDt)
            row.w.step(clampedDt)
            row.h.step(clampedDt)
        }

        val collapsingExit = isCollapsingExit
        rows.removeAll { r ->
            !r.isEntering &&
                r.p.x < 0.003 &&
                r.p.isStill() &&
                (r.frozen != null || !collapsingExit || (r.slot.x < 0.003 && r.slot.isStill()))
        }

        if (rows.isNotEmpty()) syncHeight(listWidth, instant = false)

        if (!isOpen && a.x < 0.002 && a.isStill() && g.isStill() && c.x < 0.01) {
            rows = mutableListOf()
            h.snap(0.0)
            ha.snap(0.0)
        }

        // Step chip flights.
        val remaining = mutableListOf<ChipFlight>()
        for (fl in flights) {
            fl.x.step(clampedDt)
            fl.y.step(clampedDt)
            fl.w.step(clampedDt)
            fl.h.step(clampedDt)
            val tx = fl.targetRect.minX
            val ty = fl.targetRect.minY
            val tw = fl.targetRect.width
            val th = fl.targetRect.height
            val left = hypot(fl.x.x - tx, fl.y.x - ty) + abs(fl.w.x - tw) + abs(fl.h.x - th)
            val d0 = fl.initialDistance ?: max(1.0, left)
            fl.initialDistance = d0
            fl.progress = clamp(1 - left / d0, fl.progress, 1.0)
            if (fl.x.isStill(0.3) && fl.y.isStill(0.3) && fl.w.isStill(0.3) && fl.h.isStill(0.3)) {
                landedChipIDs.add(fl.chipID)
            } else {
                remaining.add(fl)
            }
        }
        flights = remaining
    }

    /**
     * Produces the current rendered frame in dp, snapping both edges of every anchored span
     * and row rect to [displayScale] and deriving sizes from `bottom - top` / `right - left`
     * so stationary edges never flicker.
     */
    fun frame(composerWidth: Double, composerHeight: Double = 52.0, displayScale: Double = 3.0): Frame {
        val gridScale = if (displayScale > 0) displayScale else 1.0
        val reduced = params.reducedMotion
        val rawPad = params.padding
        val pad = snapEdge(rawPad, gridScale)
        val listLeft = snapEdge(rawPad, gridScale)
        val listRight = snapEdge(composerWidth - rawPad, gridScale)
        val listWidth = max(0.0, listRight - listLeft)

        val rawL = max(0.0, h.x)
        val listHeight = snapAnchoredSpan(0.0, rawL, gridScale).span
        val rawRestingLift = if (rawL > 0) rawL + rawPad else 0.0
        val restingLift = snapAnchoredSpan(0.0, rawRestingLift, gridScale).span
        val gProgress = if (reduced) (if (isOpen || rows.isNotEmpty()) 1.0 else 0.0) else clamp(g.x, 0.0, 1.1)
        val rawGlassLift = max(0.0, rawRestingLift * gProgress)
        val clampedComposerHeight = max(0.0, composerHeight)
        val glassRect = snapEdges(0.0, -rawGlassLift, max(0.0, composerWidth), clampedComposerHeight, gridScale)
        val glassLift = max(0.0, snapEdge(0.0, gridScale) - glassRect.minY)

        val cIn = clamp(c.x, 0.0, 1.0)
        val entering = c.t > 0.5
        val contentAlpha = if (reduced) cIn else smooth(0.0, 1.0, cIn)
        val contentBlur = if (reduced) 0.0 else (1 - cIn) * (if (entering) params.blur else params.blur * 0.75)
        val contentScale = if (reduced) 1.0 else params.scale + (1 - params.scale) * cIn

        val geo = rowRects(listWidth, listHeight)
        val hiRadius = highlightCornerRadius
        val drawnRows = mutableListOf<DrawnRow>()
        for (r in rows) {
            val pair = geo.rects[r.id] ?: continue
            val snappedRect = snapEdges(pair.left, pair.top, pair.right, pair.bottom, gridScale)
            if (snappedRect.width <= 0 || snappedRect.height <= 0) continue
            val pr = clamp(r.p.x, 0.0, 1.0)
            val alpha = if (r.taken) 0.0 else if (reduced) pr else smooth(0.0, 1.0, pr)
            val blur = if (reduced || !r.isEntering) 0.0 else (1 - pr) * params.rowBlur
            val rowScale = if (reduced) 1.0 else params.rowScale + (1 - params.rowScale) * pr
            val snappedFullSize = AutocompleteSize(
                max(0.0, snapEdge(pair.fullSize.width, gridScale)),
                max(0.0, snapEdge(pair.fullSize.height, gridScale)),
            )
            drawnRows.add(
                DrawnRow(
                    id = r.id,
                    item = r.item,
                    rect = snappedRect,
                    fullSize = snappedFullSize,
                    isLeaving = !r.isEntering,
                    alpha = alpha,
                    blur = blur,
                    scale = rowScale,
                    isHighlighted = r.id == highlightedID,
                    cornerRadius = hiRadius,
                ),
            )
        }

        val hiRect = currentHighlightRect(geo.rects)
        val drawnHighlight = if (hiRect != null && ha.x > 0.002) {
            DrawnHighlight(snapRectEdges(hiRect, gridScale), clamp(ha.x, 0.0, 1.0), hiRadius)
        } else {
            null
        }

        // Edge-fade mask: a fade at top/bottom only when content extends past the visible top
        // or bottom edge (`autocomplete.js` `edgeStops`).
        val visibleTop = max(0.0, listHeight - max(0.0, glassLift - pad))
        val visibleBottom = listHeight
        val shown = drawnRows.filter { it.alpha > 0.01 && it.rect.height > 0.5 }
        val minY = shown.minOfOrNull { it.rect.minY } ?: visibleTop
        val maxY = shown.maxOfOrNull { it.rect.maxY } ?: visibleBottom
        val rawTopHidden = if (shown.isEmpty()) 0.0 else max(0.0, visibleTop - minY)
        val rawBottomHidden = if (shown.isEmpty()) 0.0 else max(0.0, maxY - visibleBottom)
        val topHidden = if (rawTopHidden > 0.5) rawTopHidden else 0.0
        val bottomHidden = if (rawBottomHidden > 0.5) rawBottomHidden else 0.0
        val fade = max(1.0, params.edgeFade)
        val edgeFade = EdgeFadeMask(
            topHidden = topHidden,
            bottomHidden = bottomHidden,
            fadeLength = fade,
            topEdgeAlpha = 1 - clamp(topHidden / fade, 0.0, 1.0),
            bottomEdgeAlpha = 1 - clamp(bottomHidden / fade, 0.0, 1.0),
        )

        val isShown = isOpen || glassLift > 0.5 || contentAlpha > 0.01 || rows.isNotEmpty()
        return Frame(
            isOpen = isOpen,
            isShown = isShown,
            glassRect = glassRect,
            glassLift = glassLift,
            restingLift = restingLift,
            listHeight = listHeight,
            listWidth = listWidth,
            padding = pad,
            displayScale = gridScale,
            contentAlpha = contentAlpha,
            contentBlur = contentBlur,
            contentScale = contentScale,
            rows = drawnRows,
            highlight = drawnHighlight,
            edgeFade = edgeFade,
            flights = flights.map { it.snapshot() },
        )
    }

    /** Result of [snapAnchoredSpan]. */
    data class AnchoredSpan(val start: Double, val end: Double, val span: Double)

    companion object {
        fun clamp(value: Double, low: Double, high: Double): Double = min(max(value, low), high)

        fun smooth(edge0: Double, edge1: Double, x: Double): Double {
            val t = clamp((x - edge0) / max(edge1 - edge0, 1e-6), 0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** Snaps a single coordinate edge to the pixel grid for [scale] (`rounded()`: half away from zero). */
        fun snapEdge(value: Double, scale: Double): Double {
            if (scale <= 0) return value
            val scaled = value * scale
            val rounded = if (scaled >= 0) (scaled).roundToLong().toDouble() else -((-scaled).roundToLong().toDouble())
            return rounded / scale
        }

        /**
         * Snaps a span whose [anchoredEnd] stays fixed while its opposite start edge grows
         * (`start = anchoredEnd - rawSpan`), deriving `span = end - start` from the two
         * pixel-snapped edges so `start + span == end` on every frame.
         */
        fun snapAnchoredSpan(anchoredEnd: Double, rawSpan: Double, scale: Double): AnchoredSpan {
            val clampedSpan = max(0.0, rawSpan)
            val start = snapEdge(anchoredEnd - clampedSpan, scale)
            val end = snapEdge(anchoredEnd, scale)
            return AnchoredSpan(start, end, max(0.0, end - start))
        }

        /** Snaps all four edges to the pixel grid for [scale] and derives width and height from them. */
        fun snapEdges(left: Double, top: Double, right: Double, bottom: Double, scale: Double): AutocompleteRect {
            if (scale <= 0) return AutocompleteRect(left, top, max(0.0, right - left), max(0.0, bottom - top))
            val l = snapEdge(left, scale)
            val t = snapEdge(top, scale)
            val r = snapEdge(right, scale)
            val b = snapEdge(bottom, scale)
            return AutocompleteRect(l, t, max(0.0, r - l), max(0.0, b - t))
        }

        /** Snaps all four edges of [rect] to the pixel grid for [scale]. */
        fun snapRectEdges(rect: AutocompleteRect, scale: Double): AutocompleteRect = snapEdges(
            rect.minX,
            rect.minY,
            rect.minX + max(0.0, rect.width),
            rect.minY + max(0.0, rect.height),
            scale,
        )
    }
}

/**
 * Drives [AutocompleteSimulation] per frame (iOS `AutocompleteMotionController`, which steps it
 * on a 60–120 Hz `CADisplayLink`). A plain object: it publishes [currentFrame] as Compose
 * state and asks for frames through [needsFrames]; [AutocompleteMotionDriver] runs the
 * frame loop ([motionFrameLoop]) while it does. iOS's per-frame glass trace
 * (`-lab-glass-trace`) and Motion X-Ray reporting are lab tooling and not ported.
 */
@Stable
class AutocompleteMotionController {
    var simulation: AutocompleteSimulation = AutocompleteSimulation()
        private set

    var currentFrame: AutocompleteSimulation.Frame by mutableStateOf(simulation.frame(350.0, 100.0, 1.0))
        private set

    /** True while the simulation is animating; [AutocompleteMotionDriver] steps it then. */
    var needsFrames: Boolean by mutableStateOf(false)
        private set

    var composerWidth: Double = 350.0
    var composerHeight: Double = 100.0
    var displayScale: Double = 1.0
        private set
    private var lastChooseGeneration = 0

    /** The landing slot of an inline chip in composer-local dp (iOS `chipSlotProvider`). */
    var chipSlotProvider: ((UUID) -> AutocompleteRect?)? = null

    /** Called when a chip flight lands, so the text field shows the inline chip (iOS `onChipFlightLanded`). */
    var onChipFlightLanded: (() -> Unit)? = null

    val isOpen: Boolean get() = currentFrame.isOpen
    val isShown: Boolean get() = currentFrame.isShown
    val glassLift: Double get() = currentFrame.glassLift

    /** How far the composer's surface grows above the bar (dp); Reduce Motion uses the resting lift at once. */
    val glassExtent: Double
        get() = if (simulation.params.reducedMotion && currentFrame.isShown) currentFrame.restingLift else currentFrame.glassLift

    fun isChipHidden(id: UUID): Boolean = currentFrame.flights.any { it.chipID == id }

    fun updateDisplayScale(scale: Double) {
        val clamped = max(scale, 1.0)
        if (abs(clamped - displayScale) <= 0.01) return
        displayScale = clamped
        updateCurrentFrame()
    }

    /**
     * Mirrors the reducer's [state] into the simulation: launches a chip flight when a row was
     * chosen, then opens, updates or closes the list. [displayScale] is the density (px per dp).
     */
    fun sync(
        state: ComposerAutocompleteState,
        composerWidth: Double,
        composerHeight: Double = 100.0,
        displayScale: Double? = null,
        reducedMotion: Boolean,
    ) {
        if (composerWidth > 40) this.composerWidth = composerWidth
        if (composerHeight > 20) this.composerHeight = composerHeight
        if (displayScale != null && displayScale > 0) this.displayScale = displayScale
        simulation.params = AutocompleteParameters.current(reducedMotion)

        if (state.chooseGeneration != lastChooseGeneration) {
            lastChooseGeneration = state.chooseGeneration
            val chip = state.lastChosenChip
            val rowID = state.lastChosenRowID
            if (chip != null && rowID != null) {
                val slot = chipSlotProvider?.invoke(chip.id)
                    ?: AutocompleteRect(19.0, 16.0, AutocompleteChipMetrics.width(chip.item.title.length * 8.0), AutocompleteChipMetrics.height)
                simulation.launchFlight(
                    rowID = rowID,
                    chip = chip,
                    composerWidth = this.composerWidth,
                    composerHeight = this.composerHeight,
                    displayScale = this.displayScale,
                    targetRect = slot,
                )
            }
        }

        simulation.sync(
            isOpen = state.isOpen,
            mode = state.activeTrigger?.mode,
            query = state.activeTrigger?.query ?: "",
            key = state.activeTrigger?.key,
            items = state.rows,
            selectedRowID = state.selectedRowID,
            userMoved = state.userMovedSelection,
            composerWidth = this.composerWidth,
        )
        updateCurrentFrame()
        needsFrames = simulation.isAnimating
    }

    fun reset() {
        needsFrames = false
        simulation = AutocompleteSimulation()
        updateCurrentFrame()
    }

    fun scroll(deltaY: Double) {
        val listWidth = max(0.0, composerWidth - 2 * simulation.params.padding)
        simulation.scroll(deltaY, listWidth)
        updateCurrentFrame()
    }

    /** Steps one frame of [dt] seconds; returns whether more frames are needed (iOS `tick`). */
    fun tick(dt: Double): Boolean {
        // Keep in-flight chip targets synced with the text field's live layout.
        val provider = chipSlotProvider
        if (provider != null) {
            for (flight in simulation.flights.toList()) {
                provider(flight.chipID)?.let { simulation.updateFlightTarget(flight.chipID, it) }
            }
        }
        val previousFlightCount = simulation.flights.size
        simulation.step(dt, composerWidth)
        updateCurrentFrame()
        if (simulation.flights.size < previousFlightCount) onChipFlightLanded?.invoke()
        val animating = simulation.isAnimating
        if (!animating) needsFrames = false
        return animating
    }

    private fun updateCurrentFrame() {
        currentFrame = simulation.frame(composerWidth, composerHeight, displayScale)
    }
}

/** Remembers one [AutocompleteMotionController] (iOS `@State var autocompleteController`). */
@Composable
fun rememberAutocompleteMotionController(): AutocompleteMotionController = remember { AutocompleteMotionController() }

/**
 * Runs [controller]'s frame loop while it animates (iOS's `CADisplayLink` start/stop). Place
 * it once where the controller lives; leaving composition stops it.
 */
@Composable
fun AutocompleteMotionDriver(controller: AutocompleteMotionController) {
    val frameDuration = rememberFrameDurationSeconds()
    val needsFrames = controller.needsFrames
    LaunchedEffect(controller, needsFrames, frameDuration) {
        if (!needsFrames) return@LaunchedEffect
        // iOS clamps each tick to 1/20 s; let the loop pass that through (the simulation clamps too).
        val maxFrames = max(2.0, (1.0 / 20.0) / frameDuration)
        motionFrameLoop(frameDurationSeconds = frameDuration, maxFrames = maxFrames) { tick ->
            if (controller.tick(tick.dt)) MotionFrameAction.Continue else MotionFrameAction.Stop
        }
    }
}
