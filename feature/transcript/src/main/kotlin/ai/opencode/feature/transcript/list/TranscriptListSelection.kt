package ai.opencode.feature.transcript.list

import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.markdown.MarkdownStreamClient
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.ui.InlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.selection.TranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptSelectionAutoscroller
import ai.opencode.feature.transcript.selection.TranscriptSelectionContext
import ai.opencode.feature.transcript.selection.TranscriptSelectionDocument
import ai.opencode.feature.transcript.selection.TranscriptSelectionRow
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Port of iOS `TranscriptListViewController+TextSelection`: the list's side of the reply
 * selection. It builds the document of the reply a touch lands in, tells the selection where the
 * reader can see text (below the header, above the composer and keyboard), autoscrolls while a
 * handle or a hold drags near those edges, and reports selections beginning and ending to the
 * controller, which holds following while one lasts.
 */
internal class TranscriptListSelection(
    private val controller: TranscriptListController,
    private val state: LazyListState,
    private val scope: CoroutineScope,
) : TranscriptReplySelection.Host {
    val selection: TranscriptReplySelection get() = controller.replySelection

    /** Set each composition: what the rows present with, so the document reads as they draw. */
    var configuration: TranscriptConfiguration = TranscriptConfiguration()
    var style: MarkdownStyle? = null
    var density: Density = Density(1f)
    var linkStyle: InlineLinkStyle = InlineLinkStyle.Address

    /** The header's height and the composer's (with the keyboard), in px. */
    var topPadding = 0f
    var bottomPadding = 0f

    private val markdown = dependency(MarkdownStreamClient)
    private var autoscroll: Job? = null

    override val visibleTop: Float get() = topPadding

    override val visibleBottom: Float
        get() = state.layoutInfo.viewportSize.height - bottomPadding

    override val isCoasting: Boolean get() = controller.isCoasting

    override fun document(itemID: String): TranscriptSelectionDocument? {
        val style = style ?: return null
        val items = TranscriptSelectionDocument.replyRows(itemID, configuration.entries)
        if (items.isEmpty()) return null
        val rows = ArrayList<TranscriptSelectionRow>()
        for (item in items) {
            // The same blocks the row draws (`rememberMarkdownBlocks`), from the same cache.
            val growing = controller.isGrowingEntry(item.id)
            val text = if (growing) OpenCodeMarkdownBlock.holdingPendingMarker(item.text) else item.text
            rows += TranscriptSelectionRow(item.id, markdown.blocks(item.id, text, growing))
        }
        val context = TranscriptSelectionContext(
            style = style,
            density = density,
            sessionTargets = configuration.sessionLinks,
            linkStyle = linkStyle,
            // The list keeps a link preview store, so its rows' links get their icon slots.
            decoratesLinks = true,
            isGrowing = { id -> controller.isGrowingEntry(id) },
        )
        return TranscriptSelectionDocument.make(rows, context)
    }

    override fun activeChanged(active: Boolean, byReader: Boolean) {
        if (!active) autoscroll?.cancel()
        controller.replySelectionActiveChanged(active, byReader)
    }

    override fun observeLayout() {
        state.layoutInfo
    }

    /**
     * iOS `beginSelectionScroll`: from a drag's first move until it ends, the list is the
     * reader's (it doesn't follow) and scrolls every frame the finger is near an edge, even
     * held still, faster the closer it is.
     */
    override fun dragMoved() {
        if (autoscroll?.isActive == true) return
        autoscroll = scope.launch { runAutoscroll() }
    }

    private suspend fun runAutoscroll() {
        controller.cancelBorrowedDrag()
        state.scroll(MutatePriority.UserInput) {
            var last = withFrameNanos { it }
            while (selection.isDragging) {
                val now = withFrameNanos { it }
                var dt = (now - last) / 1_000_000_000f
                last = now
                if (dt < 0f) dt = 0f
                if (dt > MAX_FRAME) dt = MAX_FRAME
                val point = selection.dragPoint
                if (point.isSpecified) {
                    val pxPerDp = density.density
                    val velocity = TranscriptSelectionAutoscroller.velocity(point.y / pxPerDp, visibleTop / pxPerDp, visibleBottom / pxPerDp) * pxPerDp
                    if (velocity != 0f) {
                        val step = TranscriptSelectionAutoscroller.step(0f, velocity, dt, scrollBounds())
                        if (step != 0f) {
                            scrollBy(step)
                            controller.replySelectionScrolled()
                        }
                    }
                }
                selection.updateDrag()
            }
        }
    }

    /**
     * iOS `selectionScrollBounds`: how far the list may scroll from here (px, positive down)
     * and stay within the reply, [ROOM] past either end.
     */
    private fun scrollBounds(): ClosedFloatingPointRange<Float> {
        val itemIDs = selection.document?.itemIDs
        if (itemIDs.isNullOrEmpty()) return 0f..0f
        val firstKey = controller.messageItemKey(itemIDs.first())
        val lastKey = controller.messageItemKey(itemIDs.last())
        val info = state.layoutInfo
        val room = ROOM * density.density
        // Item offsets start below the header's padding: 0 is the visible top.
        val bottom = info.viewportEndOffset - bottomPadding
        var lower = Float.NEGATIVE_INFINITY
        var upper = Float.POSITIVE_INFINITY
        for (item in info.visibleItemsInfo) {
            if (item.key == firstKey) lower = minOf(0f, item.offset - room)
            if (item.key == lastKey) upper = maxOf(0f, item.offset + item.size - bottom + room)
        }
        return lower..upper
    }

    companion object {
        /** iOS: an autoscroll keeps this much (dp) of the list past the reply's ends. */
        const val ROOM = 24f

        /** iOS clamps a frame's time to 1/30 s, so a hitch doesn't jump the list. */
        const val MAX_FRAME = 1f / 30f
    }
}

