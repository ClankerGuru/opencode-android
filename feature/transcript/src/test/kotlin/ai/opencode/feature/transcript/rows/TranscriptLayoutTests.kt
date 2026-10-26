package ai.opencode.feature.transcript.rows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Port of iOS `TranscriptLayoutTests.swift`.
 *
 * Dropped (`TranscriptLayoutTests`, UIKit layout probes):
 * - layoutDuringScrollCommandDoesNotRecursivelyScroll (content-offset KVO re-entering
 *   `viewDidLayoutSubviews`; Compose layout passes aren't re-entrant)
 * - reconfiguredTableSettlesAtFractionalOffsets (`UIHostingConfiguration` cell self-sizing depth)
 * - growthSoftEdgeNeverDimsWhatTheRowAlreadyShowed (`TranscriptHostingCell`'s `CAGradientLayer` mask)
 * - growthSoftEdgeWidensWithTheRowAndEasesAway (same mask and its `CABasicAnimation` leave)
 * - hairlineSnapsWithoutMovingItsMeasurement (`HairlineRule.pixelOffset`; Compose places the
 *   one-pixel rule on whole pixels, so Android has no offset to compute)
 */
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
}
