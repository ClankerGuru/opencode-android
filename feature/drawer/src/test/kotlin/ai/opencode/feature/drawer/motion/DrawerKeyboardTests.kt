package ai.opencode.feature.drawer.motion

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.catchup.CatchUpStackController
import ai.opencode.feature.drawer.container.DrawerKeyboard
import ai.opencode.feature.drawer.container.SessionDrawerController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `DrawerKeyboardTests.swift`: whether closing the drawer gives the keyboard back
 * (`DrawerKeyboard`), and the hold that keeps the card's field focused while the drawer moves
 * (`DrawerKeyboardHold`), against a real [SessionDrawerController] as iOS drives a real
 * `SessionDrawerViewController`. The composer's field is a fake that reports focus the way
 * `Modifier.drawerKeyboardField` does; the soft keyboard is a recording [DrawerKeyboardHold.Ime].
 *
 * The keys can't follow the card here (no [ImeFollower]: driving the IME's insets needs a real
 * window), which is iOS's "keys that cannot follow" path (`Harness()` without `keys`).
 *
 * Deleted:
 * - `theKeysSlideWithTheCardAndRestBesideTheOpenDrawer` and `anotherFieldTakesTheKeysWhereItIs`
 *   assert the keyboard window's transform as it follows the card. Android drives the IME's
 *   insets through `WindowInsetsAnimationController` ([ImeFollower]), which needs a real window
 *   and IME; nothing else in those tests differs from the ported fallback tests.
 * - `suspendedSessionCardTranscriptIgnoresDrawerKeyboardFrameChanges` probes
 *   `TranscriptListViewController`'s UIKit content inset (`:feature:transcript`).
 *
 * Partially ported: in `keysLeavingWithAnotherSessionSlideOffAndReturnOnceTheyAreGone` and
 * `callTrayExperimentWithoutCall…` the keyboard window's transform; `safeAreaRegions` is
 * [DrawerKeyboardHold.excludesKeyboard] (the container's `consumeWindowInsets(ime)`), and the
 * card's `isUserInteractionEnabled` is [DrawerMotionState.isContentSuspended].
 */
class DrawerKeyboardTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun onlyTheSameSessionGetsTheKeyboardBack() {
        assertTrue(DrawerKeyboard.restoresFocus(openedOn = "ses_a", closedOn = "ses_a", sheetPresented = false))
        assertFalse(DrawerKeyboard.restoresFocus(openedOn = "ses_a", closedOn = "ses_b", sheetPresented = false))
        // A sheet over the card (Settings from the drawer) keeps it down.
        assertFalse(DrawerKeyboard.restoresFocus(openedOn = "ses_a", closedOn = "ses_a", sheetPresented = true))
        // New Session's draft keeps its own focus behaviour.
        assertTrue(DrawerKeyboard.restoresFocus(openedOn = "ses_a", closedOn = null, sheetPresented = false))
        // A draft that became a session is another session.
        assertFalse(DrawerKeyboard.restoresFocus(openedOn = null, closedOn = "ses_b", sheetPresented = false))
    }

    @Test
    fun theDrawerClosesOntoTheSessionChosenNotTheOneStillShowing() {
        // An uncached session keeps the old card until it loads.
        val closing = DrawerKeyboard.closingSession(chosen = "ses_b", shown = "ses_a")
        assertEquals("ses_b", closing)
        assertFalse(DrawerKeyboard.restoresFocus(openedOn = "ses_a", closedOn = closing, sheetPresented = false))
        // Nothing chosen: the session on screen.
        assertEquals("ses_a", DrawerKeyboard.closingSession(chosen = null, shown = "ses_a"))
    }
}

class DrawerKeyboardHoldTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A focusable field reporting to the hold as `Modifier.drawerKeyboardField` / drawer search do. */
    private class Field(private val hold: DrawerKeyboardHold, private val key: String, private val inDrawer: Boolean = false) {
        var isFocused = false
            private set

        fun focus() {
            isFocused = true
            report(true)
        }

        fun clearFocus() {
            if (!isFocused) return
            isFocused = false
            report(false)
        }

        private fun report(focused: Boolean) =
            if (inDrawer) hold.drawerFieldFocusChanged(key, focused, ::clearFocus) else hold.cardFieldFocusChanged(key, focused, ::clearFocus)
    }

    private class Harness(val test: TestScope) {
        val scope = test.backgroundScope
        val hold = DrawerKeyboardHold(scope)
        val motion = DrawerMotionState(scope, initiallyPresented = false, drawerWidth = 330f)
        val controller = SessionDrawerController(scope, motion, CatchUpStackController().also { it.scope = scope })
        val field = Field(hold, "composer")
        val fields = mutableListOf(field)
        var shows = 0
        var hides = 0

        init {
            controller.drawerWidth = 330f
            controller.reducedMotion = true
            motion.reducedMotion = true
            hold.ime = object : DrawerKeyboardHold.Ime {
                override fun show() { shows += 1 }
                override fun hide() { hides += 1 }
            }
            // The container's wiring (`SessionDrawerContainer`).
            controller.keyboardHold = hold
            motion.keyboardHold = hold
            controller.clearFocus = { fields.forEach { it.clearFocus() } }
            field.focus()
            hold.imeVisibilityChanged(true)
        }

        val keysAway: Boolean get() = hold.keysAway

        fun beginDrag() {
            controller.panBegan()
            motion.beginDrag()
        }

        /** The container leaving composition (iOS `viewDidDisappear`). */
        fun disappear() = hold.release()

        fun sleep(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
    }

    /**
     * iOS: back from the background UIKit set the keyboard window's frame under the slide, so
     * closing left the keys aside, the keyboard up with none of it on screen. Android: back
     * from the background the system has the IME again; resting open the keys stay down beside
     * the drawer, and closing gives them back on screen.
     */
    @Test
    fun keysReframedUnderTheSlideComeBackToTheScreen() = runTest {
        val harness = Harness(this)
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.keysAway)
        assertEquals(1, harness.hides)

        harness.hold.didBecomeActive()
        assertTrue("Back from the background the keys are still beside the drawer", harness.keysAway)
        assertEquals("and the system doesn't bring them up over it", 2, harness.hides)
        assertTrue(harness.field.isFocused)

        harness.controller.setPresented(false, animated = false)
        assertFalse("Closed, the keys are on screen", harness.keysAway)
        assertEquals(1, harness.shows)
        assertTrue(harness.field.isFocused)
    }

    @Test
    fun keysLeavingWithAnotherSessionSlideOffAndReturnOnceTheyAreGone() = runTest {
        val harness = Harness(this)
        harness.controller.contentSessionID = "ses_a"
        harness.controller.setPresented(true, animated = false)
        harness.controller.contentSessionID = "ses_b"
        assertTrue("The incoming card lays out without the keyboard", harness.hold.excludesKeyboard)
        harness.sleep(50)
        assertFalse(harness.field.isFocused)
        harness.sleep(1_700)
        assertFalse(harness.hold.excludesKeyboard)
    }

    @Test
    fun choosingInTheDrawerLetsTheKeyboardGoInTheTap() = runTest {
        val harness = Harness(this)
        harness.controller.contentSessionID = "ses_a"
        harness.controller.setPresented(true, animated = false)
        harness.hold.cardWillChange()
        assertFalse("The switch's own update has the composer unfocused", harness.field.isFocused)
    }

    @Test
    fun theDrawerLeavingTheScreenPutsTheKeysBack() = runTest {
        val harness = Harness(this)
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.hold.isHeld("composer"))
        // A server switch tears the drawer down with the keys still aside.
        harness.disappear()
        assertFalse("Nothing the hold did to the keyboard outlives the drawer", harness.hold.isHeld("composer"))
        assertFalse(harness.keysAway)
    }

    @Test
    fun keysThatCannotFollowStayWhileTheCardMovesAndStepDownOnceItRestsOpen() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.motion.drag(120f)
        assertTrue("Dragging never resigns the composer", harness.field.isFocused)
        assertFalse("The real keys stay on screen while the card moves", harness.keysAway)
        assertEquals(0, harness.hides)

        // Released short of halfway, nothing about the keyboard ever changed.
        harness.motion.endDrag(-600f)
        assertTrue(harness.field.isFocused)
        assertFalse(harness.keysAway)

        // Resting open, the keys step out of the drawer's way; the field keeps its focus.
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.keysAway)
        assertEquals(1, harness.hides)
        assertTrue(harness.field.isFocused)
        // Dragging the open card again brings them back up with it.
        harness.beginDrag()
        assertFalse(harness.keysAway)
        assertEquals(1, harness.shows)
        assertTrue(harness.field.isFocused)
    }

    @Test
    fun closingOntoTheSameSessionGivesTheKeysBack() = runTest {
        val harness = Harness(this)
        harness.controller.contentSessionID = "ses_a"
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.keysAway)
        harness.controller.setPresented(false, animated = false)
        assertTrue(harness.field.isFocused)
        assertFalse("Closing onto the same session gives the keys back", harness.keysAway)
        // The hold is over: the next drag starts afresh.
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.keysAway)
    }

    @Test
    fun aSessionChosenInTheDrawerLetsTheKeyboardGoWhileTheDrawerCoversTheCard() = runTest {
        val harness = Harness(this)
        harness.controller.contentSessionID = "ses_a"
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.keysAway)

        harness.controller.contentSessionID = "ses_b"
        assertFalse("The composer's next focus shows the keys", harness.keysAway)
        // The focus goes just after, outside the update that chose it.
        harness.sleep(50)
        assertFalse("Another session opens with the keyboard down", harness.field.isFocused)
        assertTrue("The card under the open drawer takes no touches", harness.motion.isContentSuspended)

        harness.controller.setPresented(false, animated = false)
        assertFalse(harness.field.isFocused)
        assertFalse(harness.motion.isContentSuspended)
    }

    @Test
    fun anotherFieldTakingTheKeyboardEndsTheHold() = runTest {
        val harness = Harness(this)
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.keysAway)

        val search = Field(harness.hold, "search", inDrawer = true)
        harness.fields += search
        // Focus moves: the composer loses it as the search field gains it.
        harness.field.clearFocus()
        search.focus()
        assertFalse("The composer's next focus shows the keys", harness.keysAway)
        assertFalse(harness.hold.isHolding)
        search.clearFocus()
    }

    @Test
    fun aHardwareKeyboardCannotTypeIntoTheComposerTheDrawerTakesAway() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.motion.drag(120f)
        assertTrue("Mid-drag, Enter must not send from a card on its way out", harness.hold.isHeld("composer"))
        harness.motion.endDrag(-600f)
        assertFalse("Back in place, the composer types again", harness.hold.isHeld("composer"))
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.hold.isHeld("composer"))
        harness.controller.setPresented(false, animated = false)
        assertFalse(harness.hold.isHeld("composer"))
    }

    @Test
    fun drawerSearchExcludesTheKeyboardFromTheSessionCardUntilItHides() = runTest {
        val harness = Harness(this)
        harness.field.clearFocus()
        harness.hold.imeVisibilityChanged(false)
        harness.controller.setPresented(true, animated = false)
        val search = Field(harness.hold, "search", inDrawer = true)
        harness.fields += search

        harness.hold.setDrawerSearching(true)
        search.focus()
        harness.hold.imeVisibilityChanged(true)
        assertTrue("Drawer search excludes the keyboard from the peeking session card", harness.hold.excludesKeyboard)

        harness.hold.setDrawerSearching(false)
        assertTrue("The exclusion holds while the drawer search keyboard is still dismissing", harness.hold.excludesKeyboard)
        search.clearFocus()
        harness.hold.imeVisibilityChanged(false)
        assertFalse("Once the keyboard has hidden, the session card takes keyboard insets again", harness.hold.excludesKeyboard)
    }

    /** Closing the drawer mid-search clears the exclusion, so the composer is never left under the keyboard. */
    @Test
    fun closingDrawerMidSearchRestoresCardKeyboardSafeArea() = runTest {
        val harness = Harness(this)
        harness.controller.contentSessionID = "ses_a"
        harness.controller.setPresented(true, animated = false)

        // Focus drawer search while the drawer is open.
        harness.hold.setDrawerSearching(true)
        assertTrue(harness.hold.excludesKeyboard)

        // Close the drawer mid-search without first tapping Cancel in the search bar.
        harness.controller.setPresented(false, animated = false)
        assertFalse("Closing the drawer mid-search clears keyboard exclusion", harness.hold.excludesKeyboard)
    }

    @Test
    fun callTrayExperimentWithoutCallKeepsKeyboardHoldAndDismissesIfCallStartsWhileSuspended() = runTest {
        val harness = Harness(this)

        // With Experimental › Call Tray on and no active call, `dismissesKeyboardOnOpen` is false,
        // so opening the drawer holds the keyboard.
        harness.controller.dismissesKeyboardOnOpen = false
        harness.controller.setPresented(true, animated = false)
        assertTrue(harness.field.isFocused)
        assertTrue(harness.hold.isHeld("composer"))

        // If a call starts while the card is suspended under the open drawer,
        // `dismissesKeyboardOnOpen` turns on and immediately lets go of the hold and ends editing.
        harness.controller.dismissesKeyboardOnOpen = true
        assertFalse(harness.hold.isHeld("composer"))
        assertFalse(harness.field.isFocused)
        assertTrue(harness.motion.isContentSuspended)
    }
}
