package ai.opencode.feature.transcript.rows

import ai.opencode.feature.media.MermaidDiagramMetrics
import ai.opencode.feature.transcript.list.GrowthSoftEdge
import ai.opencode.feature.transcript.markdown.ui.MarkdownImage
import ai.opencode.server.model.OpenCodeImageAttachment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Port of iOS `TranscriptLayoutTests.swift`.
 *
 * The two growth soft-edge tests drive iOS `TranscriptHostingCell`'s mask; here they drive the
 * same rules on the list's [GrowthSoftEdge] (the row's height and measured content each frame).
 *
 * Dropped (`TranscriptLayoutTests`, UIKit layout probes):
 * - layoutDuringScrollCommandDoesNotRecursivelyScroll (content-offset KVO re-entering
 *   `viewDidLayoutSubviews`; Compose layout passes aren't re-entrant)
 * - reconfiguredTableSettlesAtFractionalOffsets (`UIHostingConfiguration` cell self-sizing depth)
 * - hairlineSnapsWithoutMovingItsMeasurement (`HairlineRule.pixelOffset`; Compose places the
 *   one-pixel rule on whole pixels, so Android has no offset to compute)
 */
class TranscriptLayoutTests {
    /**
     * A row growing by a line (streamed text wrapping) fades in only what the growth brings: the
     * soft edge never reaches above the height the row already showed.
     */
    @Test
    fun growthSoftEdgeNeverDimsWhatTheRowAlreadyShowed() {
        val edge = GrowthSoftEdge()
        // Not on screen (iOS: a cell outside a window), so the last edge goes at once.
        fun grow(height: Float): Float {
            edge.update(height, measured = 45f, now = 0.0, maxFade = 28f, onScreen = false)
            return edge.drawn(0.0)
        }
        // The growth starts where the row stood: nothing new yet, a hard edge.
        assertEquals(0f, grow(28f))
        for (height in listOf(30f, 34f, 38f, 42f)) {
            val fade = grow(height)
            assertTrue("the line already shown keeps full strength at $height dp", fade > 0.5f && height - fade >= 28 - 0.01f)
        }
        // Grown to its full height: no edge.
        assertEquals(0f, grow(45f))
    }

    /**
     * On screen, a new line arriving mid-growth never widens the soft edge faster than the row
     * grows, and a growth that lands with its edge still soft eases it away rather than dropping
     * it in one frame.
     */
    @Test
    fun growthSoftEdgeWidensWithTheRowAndEasesAway() {
        val edge = GrowthSoftEdge()
        var content = 34f
        fun grow(height: Float): Float {
            edge.update(height, measured = content, now = 0.0, maxFade = 28f)
            return edge.drawn(0.0)
        }
        assertEquals(0f, grow(28f))
        assertEquals(3f, grow(31f), 0.01f)
        // Nearly there, the edge narrows to what is left.
        assertEquals(1f, grow(33f), 0.01f)
        // A new line arrives: a point of growth widens the edge by a point, not to everything
        // since the growth began (which would dim what was shown).
        content = 200f
        assertEquals(2f, grow(34f), 0.01f)
        // Landed (the row reached its content) with the edge still soft: it stays, easing away.
        edge.update(200f, measured = 200f, now = 1.0, maxFade = 28f)
        assertTrue(edge.isLeaving)
        assertEquals(2f, edge.drawn(1.0), 0.01f)
        assertTrue(edge.drawn(1.0 + GrowthSoftEdge.LEAVE_DURATION / 2) in 0.01f..1.99f)
        assertEquals(0f, edge.drawn(1.0 + GrowthSoftEdge.LEAVE_DURATION + 0.001), 0.001f)
        assertTrue(edge.isIdle(1.0 + GrowthSoftEdge.LEAVE_DURATION + 0.001))
    }
}

class JustifiedImageRowsTests {
    private val width = 402f

    @Test
    fun aLoneImageFillsTheWidthAtItsOwnShape() {
        val rows = JustifiedImageRows(listOf(16f / 9), width)
        assertEquals(1, rows.frames.size)
        assertEquals(width, rows.frames[0].width, 0.01f)
        assertEquals(width * 9 / 16, rows.frames[0].height, 0.01f)
    }

    @Test
    fun aLoneTallScreenshotStopsAtTheCapWithoutCropping() {
        val aspect = 1179f / 2556
        val rows = JustifiedImageRows(listOf(aspect), width)
        val frame = rows.frames[0]
        assertEquals(JustifiedImageRows.singleMaxHeight, frame.height)
        assertEquals("same shape, just smaller", aspect, frame.width / frame.height, 0.001f)
        assertEquals(0f, frame.left)
    }

    @Test
    fun everyImageKeepsItsShapeAndFullRowsFillTheWidth() {
        val aspects = listOf(1f, 1179f / 2556, 4f / 3, 4f, 1f, 0.75f, 16f / 9)
        val rows = JustifiedImageRows(aspects, width)
        assertEquals("nothing hidden", aspects.size, rows.frames.size)
        for ((frame, aspect) in rows.frames.zip(aspects)) {
            assertEquals(aspect, frame.width / frame.height, 0.001f)
            assertTrue(frame.right <= width + 0.01f)
        }
        // Rows: frames sharing a y. Each fills the width, unless capped.
        val byRow = rows.frames.groupBy { (it.top * 100).roundToInt() }
        for (row in byRow.values) {
            val right = row.maxOf { it.right }
            val height = row[0].height
            assertTrue("a row shares one height", row.all { abs(it.height - height) < 0.01f })
            assertTrue(abs(right - width) < 0.01f || height >= width * 0.9f - 0.01f)
        }
        assertEquals(rows.frames.maxOf { it.bottom }, rows.height, 0.01f)
    }

    @Test
    fun aPanoramaTakesARowToItself() {
        // The four images one step read in the image test session.
        val rows = JustifiedImageRows(listOf(4f / 3, 4f, 1f, 0.75f), width)
        val panorama = rows.frames[1]
        assertEquals(width, panorama.width, 0.01f)
        assertEquals("icon and portrait share a row", rows.frames[2].top, rows.frames[3].top)
    }

    @Test
    fun aMediaTileWithoutDimensionsTakesItsShapeOnceLoaded() {
        val hash = "a1".repeat(32)
        val images = listOf(
            OpenCodeImageAttachment(id = "inline"),
            OpenCodeImageAttachment(id = "sized", mediaHash = hash, pixelWidth = 400, pixelHeight = 300),
            OpenCodeImageAttachment(id = "unsized", mediaHash = hash),
        )
        val decoded = listOf(IntSize(1179, 2556), null, null)
        val phone = 1179f / 2556
        val sized = 4f / 3
        val wide = 16f / 9
        val before = ToolImageMosaic.aspects(images, decoded, emptyMap())
        assertEquals(listOf(phone, sized, 1f), before)
        val after = ToolImageMosaic.aspects(images, decoded, mapOf("unsized" to wide, "inline" to 3f))
        assertEquals(listOf(phone, sized, wide), after)
    }

    @Test
    fun aTilesNewShapeRevisesItsRowOnlyWhenTheLayoutChanges() {
        val size = IntSize(1600, 900)
        val wide = 16f / 9
        // Loaded cold, laid out square: remember it and revise the row.
        val cold = ToolImageMosaic.change(size, known = null, laidOutAt = 1f)
        assertEquals(wide, cold.record)
        assertTrue(cold.revisesRow)
        // Shown warm at its shape: remember it for after a purge, nothing moves.
        val warm = ToolImageMosaic.change(size, known = null, laidOutAt = wide)
        assertEquals(wide, warm.record)
        assertFalse(warm.revisesRow)
        // Reloaded after a purge, laid out at the remembered shape: nothing at all.
        val reloaded = ToolImageMosaic.change(size, known = wide, laidOutAt = wide)
        assertNull(reloaded.record)
        assertFalse(reloaded.revisesRow)
        // Reported again before the mosaic re-laid out: already known, nothing moves.
        val again = ToolImageMosaic.change(size, known = wide, laidOutAt = 1f)
        assertNull(again.record)
        assertFalse(again.revisesRow)
        assertNull(ToolImageMosaic.change(IntSize.Zero, known = null, laidOutAt = 1f).record)
    }

    @Test
    fun markdownImageAndMermaidDiagramReviseRowsOnAsyncHeightChanges() {
        // Cached Markdown images laid out on Frame 0 do not revise the row when the load runs.
        assertFalse(MarkdownImage.revisesRow(hadImage = true, wasFailed = false, nextImage = true, nextFailed = false))
        // Cold placeholder (120 dp) -> loaded image or failed card (48 dp) revises the row.
        assertTrue(MarkdownImage.revisesRow(hadImage = false, wasFailed = false, nextImage = true, nextFailed = false))
        assertTrue(MarkdownImage.revisesRow(hadImage = false, wasFailed = false, nextImage = false, nextFailed = true))
        // Retrying from failed card -> loading placeholder revises the row.
        assertTrue(MarkdownImage.revisesRow(hadImage = false, wasFailed = true, nextImage = false, nextFailed = false))

        // Mermaid cards sized from a prepared diagram do not revise when the render confirms the same size.
        val preloaded = Size(320f, 180f)
        assertFalse(MermaidDiagramMetrics.revisesRow(laidOutSize = preloaded, renderedSize = preloaded))
        // Uncached placeholder or changed diagram dimensions revise the row.
        assertTrue(MermaidDiagramMetrics.revisesRow(laidOutSize = null, renderedSize = preloaded))
        assertTrue(MermaidDiagramMetrics.revisesRow(laidOutSize = preloaded, renderedSize = Size(320f, 240f)))
    }
}
