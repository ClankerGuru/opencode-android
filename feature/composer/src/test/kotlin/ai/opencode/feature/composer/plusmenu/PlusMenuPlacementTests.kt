package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.AttachmentMenuAnchorHold
import ai.opencode.feature.composer.AttachmentMenuAnchorSample
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlusMenuPlacementTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(x, y, x + width, y + height)

    @Test fun menuFootTracksComposerAcrossTheKeyboardBoundary() {
        val composer = rect(10f, 480f, 380f, 100f)
        for (drop in listOf(-40f, 0f, 160f)) {
            val foot = PlusMenuPlacement.foot(composer, screenBottom = 900f, keyboardTop = 590f, drop = drop)
            assertEquals(composer.bottom + drop, foot)
            assertEquals(PlusMenuPlacement.foot(composer, screenBottom = 900f, keyboardTop = null, drop = drop), foot)
        }
    }

    @Test fun screenFloorStillBoundsTheMenu() {
        val composer = rect(10f, 800f, 380f, 70f)
        assertEquals(888f, PlusMenuPlacement.foot(composer, screenBottom = 900f, keyboardTop = 590f, drop = 80f))
        assertEquals(870f, PlusMenuPlacement.foot(composer, screenBottom = 900f, keyboardTop = null, drop = 0f))
    }

    @Test fun unavailableKeyboardSurfaceKeepsEveryActionReachable() {
        val menu = rect(20f, 450f, 260f, 240f)
        assertEquals(menu, PlusMenuPlacement.visibleMenu(menu, keyboardTop = 600f, coversKeyboard = true))
        assertEquals(menu, PlusMenuPlacement.visibleMenu(menu, keyboardTop = null, coversKeyboard = false))
        val fallback = PlusMenuPlacement.visibleMenu(menu, keyboardTop = 600f, coversKeyboard = false)
        assertEquals(590f, fallback.bottom)
        assertEquals(Size(260f, 240f), fallback.size)
    }

    @Test fun anchorSamplesWriteOnlyWhatTheHoldAllows() {
        fun sample(hold: AttachmentMenuAnchorHold, width: Float = 400f, height: Float = 800f, keys: Float = 300f, y: Float = 0f) =
            AttachmentMenuAnchorSample(
                hold = hold, viewportWidth = width, viewportHeight = height, keyboardHeight = keys, frame = rect(0f, y, 10f, 10f),
            )
        val resting = AttachmentMenuAnchorHold.Resting
        val follows = AttachmentMenuAnchorHold.Follows
        val folding = AttachmentMenuAnchorHold.Folding
        assertTrue(sample(resting, y = 0f).isEquivalent(sample(resting, y = 40f)))
        assertFalse(sample(resting).isEquivalent(sample(resting, keys = 200f)))
        assertNotEquals(sample(resting), sample(resting, keys = 200f))
        assertTrue(AttachmentMenuAnchorSample.writes(sample(resting), sample(resting, height = 700f)))
        assertFalse(AttachmentMenuAnchorSample.writes(sample(follows), sample(resting)))
        assertTrue(AttachmentMenuAnchorSample.writes(sample(resting), sample(resting, width = 900f)))
        assertTrue(AttachmentMenuAnchorSample.writes(sample(resting), sample(resting, keys = 200f)))
        assertFalse(AttachmentMenuAnchorSample.writes(sample(resting), sample(folding, width = 900f)))
        assertFalse(AttachmentMenuAnchorSample.writes(sample(folding), sample(folding, width = 900f)))
        assertTrue(AttachmentMenuAnchorSample.writes(sample(folding), sample(follows)))
    }
}
