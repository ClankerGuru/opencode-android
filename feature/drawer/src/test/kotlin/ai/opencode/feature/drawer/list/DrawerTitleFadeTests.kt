package ai.opencode.feature.drawer.list

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Port of iOS `DrawerTitleFadeTests.swift`: a drawer title that overruns its row fades at its own
 * end and keeps its own start in view, in either layout direction (OC-527); while the time reveal
 * slides a row under the card, the whole row fades at the card's edge, growing in without a step
 * (R-174).
 *
 * iOS lays out UIKit views and reads their frames and `CAGradientLayer` masks; here the same
 * decisions are the pure rules [DrawerListTitleView] and [DrawerListSessionCell] draw with
 * ([DrawerListTitle.placement], [DrawerRevealFade]), in dp. A title's natural width (iOS measures
 * the label) is given: wider than the 200 dp row for a long title, narrower for a short one. The
 * card-edge mask is sampled with the reveal's ease-in (`revealFadeIn`) finished, as iOS samples it.
 *
 * Deleted:
 * - `aRelayoutMidRevealKeepsTheFadeAtTheEdge`: a UIKit cell laid out again keeps its mask layer;
 *   Compose computes the mask in the draw phase from the live reveal each frame, so there is no
 *   state a relayout could lose.
 * - `catchUpFocusFindsItsElement`: walks UIKit views for an accessibility element; Android moves
 *   TalkBack focus with a `FocusRequester` on the Catch Up row.
 */
class DrawerTitleFadeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val english = "Streaming Markdown Profiling with Instruments and signposts"
    private val hebrew = "שלום עולם זוהי כותרת ארוכה מאוד של שיחה שלא נכנסת בשורה"

    @Test
    fun writingDirectionFollowsTheFirstStrongCharacter() {
        assertEquals(LayoutDirection.Ltr, DrawerListTitle.writingDirection(english))
        assertEquals(LayoutDirection.Rtl, DrawerListTitle.writingDirection(hebrew))
        assertEquals(LayoutDirection.Rtl, DrawerListTitle.writingDirection("42 — $hebrew"))
        assertEquals(LayoutDirection.Rtl, DrawerListTitle.writingDirection("مرحبا"))
        assertNull(DrawerListTitle.writingDirection("123 …"))
    }

    @Test
    fun englishShowsItsStartAndFadesOnTheRight() {
        for (layoutRTL in listOf(false, true)) {
            val (x, clearEnd) = laidOut(english, naturalWidth = LONG, layoutRTL = layoutRTL)
            assertEquals("The title's first words are on the left (rtl=$layoutRTL)", 0, x)
            assertEquals("It fades out at the right edge (rtl=$layoutRTL)", DrawerListTitle.ClearEnd.Right, clearEnd)
        }
    }

    @Test
    fun hebrewShowsItsStartAndFadesOnTheLeft() {
        for (layoutRTL in listOf(false, true)) {
            val (x, clearEnd) = laidOut(hebrew, naturalWidth = LONG, layoutRTL = layoutRTL)
            assertEquals("The title's first words are on the right (rtl=$layoutRTL)", WIDTH, x + LONG)
            assertEquals("It fades out at the left edge (rtl=$layoutRTL)", DrawerListTitle.ClearEnd.Left, clearEnd)
        }
    }

    @Test
    fun aShortTitleIsUnfaded() {
        val (x, clearEnd) = laidOut("Short title", naturalWidth = 80, layoutRTL = false)
        assertEquals(0, x)
        assertNull(clearEnd)
    }

    /**
     * While the time reveal slides a row under the card, the whole row fades out over the last
     * `titleFade` dp before the row's resting trailing edge (the card's edge), wherever the slide
     * has carried it. A row at rest, or barely moved, is untouched.
     */
    @Test
    fun theRevealFadesTheWholeRowAtTheCardEdge() {
        val edge = CELL_WIDTH - DrawerMetrics.rowEdge.value
        val fade = DrawerTunables.titleFade.value
        assertEquals("At rest the row runs to its edge", 1f, maskAlpha(offset = 0f, x = edge - 1))
        assertTrue("The first point of slide changes little", maskAlpha(offset = 1f, x = edge - 3) > 0.9f)
        assertEquals("Short of the card it's opaque", 1f, maskAlpha(offset = 150f, x = edge - fade - 10))
        assertTrue("At the card's edge it's faded out", maskAlpha(offset = 150f, x = edge - 0.5f) < 0.05f)
        val middle = maskAlpha(offset = 150f, x = edge - fade / 2)
        assertTrue("It fades over the last $fade dp", middle > 0.3f && middle < 0.7f)
        assertEquals("The revealed time in the gutter isn't masked off", 1f, maskAlpha(offset = 150f, x = -40f))
        assertFalse("Back at rest the mask is gone", DrawerRevealFade.masks(reducedMotion = false, offset = 0f))
    }

    /** No point of slide steps any part of the row's alpha: the fade moves onto the edge over `revealFadeRamp` dp, a little at a time. */
    @Test
    fun theFadeGrowsInWithoutAStep() {
        val edge = CELL_WIDTH - DrawerMetrics.rowEdge.value
        var previous: List<Float>? = null
        var offset = 0f
        while (offset <= 48f) {
            val samples = generateSequence(edge - 24) { it + 2 }.takeWhile { it < edge }.map { maskAlpha(offset, it) }.toList()
            previous?.let { before ->
                val step = before.zip(samples).maxOf { (a, b) -> abs(a - b) }
                assertTrue("2 dp more slide changed alpha by $step at $offset dp", step < 0.1f)
            }
            previous = samples
            offset += 2f
        }
    }

    @Test
    fun reduceMotionLeavesTheRowAlone() {
        assertFalse(DrawerRevealFade.masks(reducedMotion = true, offset = 150f))
    }

    // Helpers

    private fun laidOut(text: String, naturalWidth: Int, layoutRTL: Boolean): Pair<Int, DrawerListTitle.ClearEnd?> {
        val titleRTL = DrawerListTitle.isRightToLeft(text, layoutRTL)
        return DrawerListTitle.placement(naturalWidth, WIDTH, overflows = naturalWidth > WIDTH, titleRTL = titleRTL, layoutRTL = layoutRTL)
    }

    /**
     * The card-edge mask's alpha at [x] dp in a [CELL_WIDTH] dp row slid [offset] dp: 1 without a
     * mask, else its solid part, its linear ramp to clear, or clear (the row's `horizontalGradient`).
     */
    private fun maskAlpha(offset: Float, x: Float): Float {
        if (!DrawerRevealFade.masks(reducedMotion = false, offset = offset)) return 1f
        val (solidEnd, rampEnd) = DrawerRevealFade.stops(
            rowMaxX = CELL_WIDTH - DrawerMetrics.rowEdge.value,
            offset = offset,
            fade = maxOf(DrawerTunables.titleFade.value, 0.5f),
            ramp = DrawerTunables.revealFadeRamp.value,
        )
        if (x <= solidEnd) return 1f
        if (x >= rampEnd) return 0f
        return 1f - (x - solidEnd) / (rampEnd - solidEnd)
    }

    private companion object {
        /** iOS's 200 × 24 pt title. */
        const val WIDTH = 200
        /** A title line far wider than its row. */
        const val LONG = 480
        /** iOS `makeDrawerTestCell`'s 300 pt drawer. */
        const val CELL_WIDTH = 300f
    }
}
