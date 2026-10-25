package ai.opencode.feature.composer

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the request panel fits under the header (iOS `RequestPanelFit`): a pure function of the
 * room, never of the panel's own height, so it can't feed back on itself (OC-471). Heights
 * are in whatever unit the caller uses (dp or px), consistently.
 */
object RequestPanelFit {
    /**
     * Short of room, a stack lays out its higher priorities first: the panel's fixed parts
     * (0), then its page, then a question, then its choices or a command, which so give up
     * room first.
     */
    object Priority {
        const val page: Double = -1.0
        const val question: Double = -1.0
        const val command: Double = -2.0
        const val choices: Double = -2.0
    }

    /**
     * The heights the cap is made from: the room between the top safe area and the keyboard,
     * the composer area, and its capsule with and without the panel's room.
     */
    data class Inputs(val room: Float = 0f, val composer: Float = 0f, val capsule: Float = 0f, val bar: Float = 0f)

    /** The latest [Inputs], kept without being observed. */
    class Measures {
        var inputs = Inputs()
    }

    /**
     * The panel's maximum: the room between the top safe area and the keyboard, less the
     * header and the composer's chrome outside the panel. Null before the room is measured.
     */
    fun panelCap(room: Float, header: Float, chrome: Float): Float? {
        if (room <= 0f || chrome <= 0f) return null
        return max(0f, room - header - chrome)
    }

    /**
     * The room the session keeps for the panel: what it measured, never more than the cap
     * it's laid out under now (a measurement lags the layout by a pass, so as the cap shrinks
     * the room shrinks with the panel at once).
     */
    fun room(measured: Float, cap: Float?): Float = cap?.let { min(measured, max(it, 0f)) } ?: measured

    /**
     * Whether the glass eases to a new height: only for changes of the panel's own content
     * (arriving, pages, choices), never for one the cap made, which the room has already
     * followed. The keyboard changes the panel two ways: its cap follows the room, and its
     * scrolling parts take shorter caps while the bar has the keyboard (300 → 180 for a
     * command, never reaching the room's cap). Either way the room follows in the same pass;
     * only the panel's own content eases.
     */
    data class Easing(var wasCapped: Boolean = false, var keyboardUp: Boolean? = null, var cap: Float? = null) {
        /** Whether the glass eases to [height], laid out under [cap] with the keyboard up or down; records them for the next change. */
        fun eases(height: Float, cap: Float?, keyboardUp: Boolean): Boolean {
            val keyboardMoved = this.keyboardUp?.let { it != keyboardUp } ?: false
            val capMoved = this.keyboardUp != null && !same(this.cap, cap)
            val capped = isCapped(height, cap)
            val eases = !keyboardMoved && !capMoved && !wasCapped && !capped
            wasCapped = capped
            this.keyboardUp = keyboardUp
            this.cap = cap
            return eases
        }

        private fun same(a: Float?, b: Float?): Boolean = when {
            a == null && b == null -> true
            a != null && b != null -> abs(a - b) < 0.5f
            else -> false
        }
    }

    /** The panel stands at its cap: the cap, not its content, set its height. */
    fun isCapped(height: Float, cap: Float?): Boolean = cap?.let { height >= it - 0.5f } ?: false

    /**
     * The composer's chrome outside the panel: the composer area less the panel's room (its
     * capsule with the room less without), all laid out in the same frame, so it holds still
     * while the room animates.
     */
    fun chrome(composer: Float, capsule: Float, bar: Float): Float {
        if (composer <= 0f || bar <= 0f) return 0f
        return Math.round(composer - max(0f, capsule - bar)).toFloat()
    }
}

/** A request panel part that scrolls (iOS `requestPanelPart(natural:floor:priority:)`), read by [RequestPanelStack]. */
internal data class RequestPanelPartData(val cap: Dp, val floor: Dp, val priority: Double)

private class RequestPanelPartModifier(val data: RequestPanelPartData) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = data
    override fun equals(other: Any?) = other is RequestPanelPartModifier && other.data == data
    override fun hashCode() = data.hashCode()
}

/**
 * A request panel part that scrolls: its natural height (its content, at most [cap]) when
 * there's room, less (down to [floor]) when its stack is short of room, giving up height in
 * [priority] order.
 */
internal fun Modifier.requestPanelPart(cap: Dp, floor: Dp, priority: Double): Modifier =
    this.then(RequestPanelPartModifier(RequestPanelPartData(cap, floor, priority)))

/**
 * Proposes the request panel [cap] (or unbounded room without one), so its scrolling parts
 * take their natural heights where they fit and give up the difference in priority order
 * where they don't, and past their floors it scrolls ([RequestPanelFallback]). All in
 * layout: nothing is measured and fed back (iOS `RequestPanelCap`).
 */
fun Modifier.requestPanelCap(cap: Dp?): Modifier = layout { measurable, constraints ->
    val capPx = cap?.let { max(it.roundToPx(), 0) }
    val proposed = capPx ?: Constraints.Infinity
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = proposed))
    val height = capPx?.let { min(placeable.height, it) } ?: placeable.height
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * A request panel that fits its room (iOS `RequestPanelFallback`): [column]'s parts squeezed
 * down to their floors ([requestPanelPart]) or, when even that's too tall, the whole column
 * scrolling with the [footer] (Allow Once and Always Allow) pinned below it, so the actions are
 * always on screen. [column]'s children must be direct children here (header, parts, lines):
 * the parts are laid out by [RequestPanelStack].
 */
@Composable
internal fun RequestPanelFallback(
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
    column: @Composable () -> Unit,
) {
    Layout(
        contents = listOf({ RequestPanelStack(content = column) }, footer),
        modifier = modifier,
    ) { (stackMeasurables, footerMeasurables), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(minWidth = 0, maxWidth = width)
        val footers = footerMeasurables.map { it.measure(loose) }
        val footerHeight = footers.sumOf { it.height }
        val room = if (constraints.hasBoundedHeight) max(constraints.maxHeight - footerHeight, 0) else Constraints.Infinity
        val stacks = stackMeasurables.map { it.measure(Constraints(maxWidth = width, maxHeight = room)) }
        val stackHeight = stacks.sumOf { it.height }
        layout(width, stackHeight + footerHeight) {
            var y = 0
            for (placeable in stacks) { placeable.place(0, y); y += placeable.height }
            for (placeable in footers) { placeable.place(0, y); y += placeable.height }
        }
    }
}

/**
 * Lays out the panel's column under its room: fixed children at their own heights, parts
 * ([requestPanelPart]) at their natural heights where they fit, else squeezed in priority
 * order down to their floors; past the floors the column keeps the parts whole and scrolls
 * (iOS's scrolling fallback, where `requestPanelScrolls` shows the parts whole).
 */
@Composable
private fun RequestPanelStack(content: @Composable () -> Unit) {
    val holder = remember { RequestPanelScroll() }
    val scrollState = rememberScrollableState { delta ->
        val old = holder.offset
        val next = (old - delta).coerceIn(0f, holder.overflow.toFloat())
        holder.offset = next
        old - next
    }
    Layout(
        content = content,
        modifier = Modifier
            // Clipped top and bottom only: the header's × sits out past the panel's side padding,
            // on the surface's corner arc (`outset`), which a full `clipToBounds` cut in half.
            .clip(VerticalOnlyClip)
            .scrollable(scrollState, Orientation.Vertical, enabled = true)
            .testTag("request-panel-scroll"),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val room = constraints.maxHeight
        val loose = Constraints(minWidth = 0, maxWidth = width)
        val parts = measurables.map { it.parentData as? RequestPanelPartData }
        val fixed = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        var fixedHeight = 0
        for ((index, measurable) in measurables.withIndex()) {
            if (parts[index] == null) {
                fixed[index] = measurable.measure(loose).also { fixedHeight += it.height }
            }
        }
        val natural = IntArray(measurables.size)
        val least = IntArray(measurables.size)
        for ((index, measurable) in measurables.withIndex()) {
            val part = parts[index] ?: continue
            natural[index] = min(naturalHeight(measurable, width), part.cap.roundToPx())
            least[index] = min(natural[index], part.floor.roundToPx())
        }
        val needed = fixedHeight + least.sum()
        val scrolls = room != Constraints.Infinity && needed > room
        val heights = IntArray(measurables.size)
        if (scrolls || room == Constraints.Infinity) {
            for (index in measurables.indices) if (parts[index] != null) heights[index] = natural[index]
        } else {
            for (index in measurables.indices) if (parts[index] != null) heights[index] = least[index]
            var extra = room - needed
            // Higher priorities take room first; within one, the least flexible first, evenly.
            val groups = measurables.indices.filter { parts[it] != null }.groupBy { parts[it]!!.priority }
            for (priority in groups.keys.sortedDescending()) {
                val members = groups.getValue(priority).sortedBy { natural[it] - least[it] }
                var left = members.size
                for (index in members) {
                    val share = if (left > 0) extra / left else 0
                    val grant = min(share, natural[index] - least[index]).coerceAtLeast(0)
                    heights[index] += grant
                    extra -= grant
                    left -= 1
                }
            }
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            fixed[index] ?: measurable.measure(Constraints(minWidth = 0, maxWidth = width, minHeight = heights[index], maxHeight = heights[index]))
        }
        val total = placeables.sumOf { it.height }
        val height = if (room == Constraints.Infinity) total else min(total, room)
        holder.overflow = max(total - height, 0)
        if (holder.offset > holder.overflow) holder.offset = holder.overflow.toFloat()
        layout(width, height) {
            val offset = if (scrolls) holder.offset.roundToInt() else 0
            var y = -offset
            for (placeable in placeables) { placeable.place(0, y); y += placeable.height }
        }
    }
}

private fun naturalHeight(measurable: Measurable, width: Int): Int =
    runCatching { measurable.maxIntrinsicHeight(width) }.getOrDefault(0)

/** The fallback's scroll position, read only while placing (so scrolling re-places, never re-measures). */
private class RequestPanelScroll {
    var offset by mutableFloatStateOf(0f)
    var overflow = 0
}

/**
 * Scrolls what's taller than [cap], sized to what it holds; its edges fade only where there's
 * more, so nothing slides under a hard edge. In a panel short of room it gives up height,
 * down to [floor], in [priority] order (iOS `FadingScroll`). It reaches the panel's edges,
 * so bands inside run full width; content pads itself to [RequestDockMetrics.edge].
 */
@Composable
internal fun FadingScroll(
    cap: Dp,
    floor: Dp,
    priority: Double,
    identifier: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val fade = 20.dp
    Box(
        modifier
            .requestPanelPart(cap, floor, priority)
            .heightIn(max = cap)
            .bleedHorizontally(RequestDockMetrics.edge)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // A question that fits never fades, even mid-transition.
                val scrolls = scroll.maxValue > 1
                val top = scrolls && scroll.value > 1
                val bottom = scrolls && scroll.maxValue - scroll.value > 1
                if (!top && !bottom || size.height <= 0f) return@drawWithContent
                val edge = min(fade.toPx() / size.height, 0.5f)
                drawRect(
                    Brush.verticalGradient(
                        0f to (if (top) Color.Transparent else Color.Black),
                        edge to Color.Black,
                        1f - edge to Color.Black,
                        1f to (if (bottom) Color.Transparent else Color.Black),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .verticalScroll(scroll)
            .testTag(identifier),
    ) { content() }
}

/** iOS `.padding(.horizontal, -edge)`: reaches [edge] past each side of what it's given. */
internal fun Modifier.bleedHorizontally(edge: Dp): Modifier = layout { measurable, constraints ->
    val extra = (edge * 2).roundToPx()
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    } else constraints
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-edge.roundToPx(), 0) }
}

/** Bounds only the vertical extent; the sides stay open for content placed past them. */
private val VerticalOnlyClip = androidx.compose.foundation.shape.GenericShape { size, _ ->
    addRect(androidx.compose.ui.geometry.Rect(-size.width, 0f, size.width * 2, size.height))
}
