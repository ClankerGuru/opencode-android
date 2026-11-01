package ai.opencode.feature.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS `OpenCodeTests/ComposerTextSelectionSwipeTests.swift`. Kit: “Trying to select text in the
 * composer always actually brings it up or down because of our swipe action.” A drag the text
 * field is selecting with is never the composer's swipe.
 *
 * `onlyAFocusedNonEmptySelectionClaims` hosts a `UITextView` in a window; the field's half of it
 * (`selectionClaims` on the focused field's selection) lives in the composable, so it isn't
 * ported. iOS `CGRect.null`/`.infinite` → non-finite [Rect]s.
 */
class ComposerTextSelectionSwipeTests {
    @Test
    fun aSelectingDragBelongsToTheText() {
        assertFalse("A quick swipe is the composer's", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.05, holdBegan = false, textGestureBegan = false))
        assertTrue("On the selection or a handle", ComposerSwipeDown.textOwnsDrag(startsOnSelection = true, heldFor = 0.05, holdBegan = false, textGestureBegan = false))
        assertTrue("Rested first: the loupe", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.6, holdBegan = false, textGestureBegan = false))
        assertTrue("A long press began", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.1, holdBegan = true, textGestureBegan = false))
        assertTrue("A handle or a double-tap drag took it", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.05, holdBegan = false, textGestureBegan = true))
    }

    @Test
    fun theSelectionClaimsTouchesOnAndNearIt() {
        val rects = listOf(Rect(40f, 22f, 240f, 44f), Rect(0f, 44f, 80f, 66f))
        assertTrue("On it", ComposerSwipeDown.selectionClaims(Offset(100f, 30f), selectionRects = rects))
        assertTrue("By its end handle", ComposerSwipeDown.selectionClaims(Offset(250f, 30f), selectionRects = rects))
        assertTrue("Over its start handle's knob", ComposerSwipeDown.selectionClaims(Offset(40f, 5f), selectionRects = rects))
        assertFalse("Well away from it", ComposerSwipeDown.selectionClaims(Offset(300f, 100f), selectionRects = rects))
        assertFalse("No selection", ComposerSwipeDown.selectionClaims(Offset(100f, 30f), selectionRects = emptyList()))
        val inf = Float.POSITIVE_INFINITY
        assertFalse(
            "Degenerate rects claim nothing",
            ComposerSwipeDown.selectionClaims(Offset.Zero, selectionRects = listOf(Rect(inf, inf, inf, inf), Rect(-inf, -inf, inf, inf))),
        )
    }

    /**
     * Once the text field claims a drag, the composer's swipe stays out of it to its last
     * callback, even after the field's flag resets.
     */
    @Test
    fun aClaimedDragStaysTheTexts() {
        val ownership = ComposerSwipeOwnership()
        val start = Offset(40f, 10f)
        assertFalse(ownership.yields(startLocation = start) { false })
        assertTrue("A handle began", ownership.yields(startLocation = start, textClaims = true) { false })
        assertTrue("Still the text's", ownership.yields(startLocation = start, textClaims = false) { false })
        ownership.ended()
        assertFalse("The next drag decides afresh", ownership.yields(startLocation = start) { false })
    }
}
