package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
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
}
