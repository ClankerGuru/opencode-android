package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerMorphGeometry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The photo sheet's dismissal seed and drag geometry. */
class PhotoSheetInteractionTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun reopeningMenuDoesNotReuseThePreviousDismissalSeed() {
        val menu = rect(12f, 620f, 220f, 156f)
        val oldMenu = rect(12f, 400f, 220f, 156f)
        val seed = rect(24f, 810f, 34f, 34f)
        assertEquals(menu, ComposerMorphGeometry.resolvedSource(shown = false, sheetActive = false, source = menu, openedFrom = oldMenu, landsOn = seed))
        assertEquals(seed, ComposerMorphGeometry.resolvedSource(shown = true, sheetActive = false, source = menu, openedFrom = oldMenu, landsOn = seed))
        assertEquals(oldMenu, ComposerMorphGeometry.resolvedSource(shown = true, sheetActive = true, source = menu, openedFrom = oldMenu, landsOn = seed))
    }

    @Test fun compactDownwardDragTracksFingerEvenBeyondTheOldMinimumHeightClamp() {
        val target = rect(8f, 500f, 386f, 366f)
        val detent = PlusPhotoSheetDetent(compactTop = 500f, ceiling = 120f)
        for (frame in 0..300) {
            val translation = frame * 1.5f
            val drag = detent.dragChanged(translation)
            val (rect, downward) = ComposerMorphGeometry.stretched(
                target, pull = drag, stretchesUpward = true, minHeightPx = MIN_HEIGHT,
                translatesDownward = detent.dragOriginExpanded == false,
            )
            assertEquals(target.size, rect.size)
            assertEquals(target.top + translation, rect.top + downward, 0.001f)
            assertEquals(target.bottom + translation, rect.bottom + downward, 0.001f)
        }
        assertEquals(PlusPhotoSheetDetent.Settlement.Close, detent.settle(predicted = 600f, velocity = 800f))
        assertEquals("Release retains its drag geometry for the fold", false, detent.dragOriginExpanded)
    }

    @Test fun expandedCollapseKeepsItsDragGeometryUntilTheNextTouch() {
        val detent = PlusPhotoSheetDetent(isExpanded = true, compactTop = 500f, ceiling = 120f)
        detent.dragChanged(100f)
        detent.settle(predicted = 200f, velocity = 400f)
        assertFalse(detent.isExpanded)
        assertTrue(detent.dragOriginExpanded == true)
        detent.dragChanged(5f)
        assertTrue(detent.dragOriginExpanded == false)
    }

    private companion object {
        const val MIN_HEIGHT = 120f

        fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(Offset(x, y), Size(width, height))
    }
}
