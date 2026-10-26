package ai.opencode.core.ui

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.min

/**
 * Port of iOS `GlassShapeTests`: `GlassShape.clamped` → [OpenCodeShapeGeometry.clamped].
 * `tuningTabsKeepTheirWholeHitTargetOnScreen` is `:devtools`' (`TuningPlacementLayout`).
 */
class GlassShapeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun clampsToTheSmallerDrawnSide() {
        assertEquals(17f, OpenCodeShapeGeometry.clamped(radius = 28f, width = 180f, height = 34f))
        assertEquals(9f, OpenCodeShapeGeometry.clamped(radius = 28f, width = 18f, height = 60f))
        assertEquals(20f, OpenCodeShapeGeometry.clamped(radius = 20f, width = 80f, height = 60f))
        assertEquals(0f, OpenCodeShapeGeometry.clamped(radius = -2f, width = 80f, height = 60f))
        assertEquals(0f, OpenCodeShapeGeometry.clamped(radius = 28f, width = 0f, height = 60f))
        assertEquals(0f, OpenCodeShapeGeometry.clamped(radius = 28f, width = 80f, height = -1f))
    }

    @Test
    fun remainsBoundedThroughInsetAndMorphSizes() {
        for (width in 0..400 step 2) {
            for (height in 0..300 step 2) {
                val radius = OpenCodeShapeGeometry.clamped(radius = 36f, width = width.toFloat(), height = height.toFloat())
                assertTrue(radius >= 0 && radius <= min(width, height) / 2f)
            }
        }
    }
}
