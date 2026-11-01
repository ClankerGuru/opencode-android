package ai.opencode.feature.drawer.list

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS `DrawerLiftedRowTests` (OC-600): where a cancelled release still lands. iOS's
 * `theShadowFollowsItsTunables` is n/a: Android has no Tuning Panel, and the dragged row's shadow
 * is a Material elevation.
 */
class DrawerLiftedRowTests {
    private val window = Rect(0f, 0f, 440f, 956f)
    private val list = Rect(0f, 0f, 300f, 956f)

    private fun lands(x: Float, y: Float): Boolean =
        DrawerPinDrag.landsOnCancel(Offset(x, y), list, headerBottom = 120f, footerTop = 870f, window = window)

    @Test
    fun aCancelledReleaseOverTheHeaderOrFooterLands() {
        assertTrue(lands(150f, 52f))
        assertTrue(lands(150f, 119f))
        assertTrue(lands(150f, 900f))
        assertTrue("A little past the column's edge still counts", lands(320f, 52f))
    }

    @Test
    fun anyOtherCancelSpringsBack() {
        // Escape with the finger over the list's body.
        assertFalse(lands(150f, 400f))
        assertFalse(lands(150f, 120f))
        assertFalse(lands(150f, 870f))
        // Off the window, above or below.
        assertFalse(lands(150f, -10f))
        assertFalse(lands(150f, 970f))
        // Beside the list's column.
        assertFalse(lands(380f, 52f))
    }
}
