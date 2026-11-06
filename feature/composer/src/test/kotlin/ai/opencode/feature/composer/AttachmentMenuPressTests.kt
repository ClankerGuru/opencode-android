package ai.opencode.feature.composer

import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.MotionSpring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `AttachmentMenuPressTests.swift`: the + button's touch (hold to open, slide over
 * rows, release to choose) and the haptics each step plays. Points are root dp at density 1, so
 * the slop is [AttachmentMenuPress.BUTTON_SLOP] itself.
 *
 * `attachmentMenuLingerResetsAcrossSessionAndDraftSwitches` keeps its [AttachmentMenuLinger]
 * assertions over stand-in identities; the half that derives `taskIdentity` from
 * `HomeFeature.makeDraft` and a draft becoming its session belongs to `:feature:drawer`.
 */
class AttachmentMenuPressTests {
    private val button = rect(20f, 800f, 44f, 44f)
    private val rows: Map<AttachmentMenuItem, Rect> = mapOf(
        AttachmentMenuItem.Camera to rect(20f, 600f, 200f, 48f),
        AttachmentMenuItem.Photos to rect(20f, 648f, 200f, 48f),
        AttachmentMenuItem.Files to rect(20f, 696f, 200f, 48f),
        AttachmentMenuItem.Paste to rect(20f, 744f, 200f, 48f),
    )
    private val onButton: Offset get() = button.center
    private val slop = AttachmentMenuPress.BUTTON_SLOP

    private fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(Offset(x, y), Size(width, height))

    private fun point(item: AttachmentMenuItem): Offset = rows.getValue(item).center

    private fun press(log: MutableList<String>) = AttachmentMenuPress(HapticsClient(play = { log += it.name }))

    private fun AttachmentMenuPress.moved(to: Offset, rows: Map<AttachmentMenuItem, Rect> = this@AttachmentMenuPressTests.rows) =
        moved(to, button, rows, slop)

    private fun AttachmentMenuPress.ended(at: Offset, rows: Map<AttachmentMenuItem, Rect> = this@AttachmentMenuPressTests.rows) =
        ended(at, button, rows, slop)

    @Test fun theDismissAreaIsMountedOnlyForWhatIsDrawn() {
        assertNull(ComposerDismissTarget.current(attachmentMenuOpen = false, autocompleteOpen = true, autocompleteDrawn = false))
        assertEquals(ComposerDismissTarget.Autocomplete, ComposerDismissTarget.current(attachmentMenuOpen = false, autocompleteOpen = true, autocompleteDrawn = true))
        assertEquals(ComposerDismissTarget.AttachmentMenu, ComposerDismissTarget.current(attachmentMenuOpen = true, autocompleteOpen = true, autocompleteDrawn = false))
        assertNull(ComposerDismissTarget.current(attachmentMenuOpen = false, autocompleteOpen = false, autocompleteDrawn = true))
    }

    @Test fun aTapTogglesAndOpeningPlaysOneLightTap() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        assertEquals(AttachmentMenuPress.Outcome.Toggle, press.ended(onButton))
        press.menuOpened()
        assertEquals(listOf("lightTap"), log)

        press.began(isOpen = true)
        assertEquals("The × closes it", AttachmentMenuPress.Outcome.Toggle, press.ended(onButton))
        assertEquals("Closing plays nothing", listOf("lightTap"), log)
    }

    @Test fun holdingOpensOnceAndSlidingTicksOncePerRow() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        assertNull(press.highlighted)
        // Before the hold elapses, sliding highlights nothing.
        press.moved(point(AttachmentMenuItem.Files))
        assertNull(press.highlighted)
        assertTrue(press.holdElapsed())
        assertFalse("A touch opens the menu once", press.holdElapsed())
        press.menuOpened()

        press.moved(point(AttachmentMenuItem.Files))
        press.moved(Offset(60f, 700f))
        press.moved(point(AttachmentMenuItem.Photos))
        press.moved(Offset(100f, 660f))
        press.moved(point(AttachmentMenuItem.Camera))
        assertEquals(AttachmentMenuItem.Camera, press.highlighted)
        assertEquals(listOf("lightTap", "selection", "selection", "selection"), log)

        assertEquals(AttachmentMenuPress.Outcome.Choose(AttachmentMenuItem.Camera), press.ended(point(AttachmentMenuItem.Camera)))
        assertEquals(listOf("lightTap", "selection", "selection", "selection", "lightTap"), log)
        assertNull(press.highlighted)
    }

    @Test fun releasingOnFilesChoosesIt() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(point(AttachmentMenuItem.Files))
        assertEquals(AttachmentMenuPress.Outcome.Choose(AttachmentMenuItem.Files), press.ended(point(AttachmentMenuItem.Files)))
        assertEquals(listOf("selection", "lightTap"), log)
    }

    @Test fun pasteIsNeverChosenByDrag() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(point(AttachmentMenuItem.Paste))
        assertNull(press.highlighted)
        assertEquals(AttachmentMenuPress.Outcome.None, press.ended(point(AttachmentMenuItem.Paste)))
        assertTrue(log.isEmpty())
    }

    @Test fun releasingBackOnTheCloseButtonCancels() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(point(AttachmentMenuItem.Photos))
        press.moved(onButton)
        assertNull(press.highlighted)
        assertEquals(AttachmentMenuPress.Outcome.Close, press.ended(onButton))
        assertEquals(listOf("selection"), log)
    }

    @Test fun heldOpenAndReleasedInPlaceOrAwayLeavesTheMenuOpen() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(Offset(onButton.x + 3f, onButton.y - 2f))
        assertEquals(AttachmentMenuPress.Outcome.None, press.ended(onButton))

        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(Offset(300f, 300f))
        assertEquals(AttachmentMenuPress.Outcome.None, press.ended(Offset(300f, 300f)))
        assertTrue(log.isEmpty())
    }

    @Test fun slidingFromTheOpenMenusCloseButtonChoosesARow() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = true)
        assertFalse("An open menu doesn't reopen", press.holdElapsed())
        press.moved(point(AttachmentMenuItem.Photos))
        assertEquals(AttachmentMenuPress.Outcome.Choose(AttachmentMenuItem.Photos), press.ended(point(AttachmentMenuItem.Photos)))
        assertEquals(listOf("selection", "lightTap"), log)
    }

    /** With the photo card up, the button passes no rows: sliding from the × across the card ticks nothing and chooses nothing. */
    @Test fun withoutRowsSlidingPlaysAndChoosesNothing() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = true)
        press.moved(point(AttachmentMenuItem.Photos), rows = emptyMap())
        press.moved(point(AttachmentMenuItem.Camera), rows = emptyMap())
        assertNull(press.highlighted)
        assertEquals(AttachmentMenuPress.Outcome.None, press.ended(point(AttachmentMenuItem.Camera), rows = emptyMap()))
        assertTrue(log.isEmpty())
    }

    @Test fun aCancelledTouchChoosesNothing() {
        val log = mutableListOf<String>()
        val press = press(log)
        press.began(isOpen = false)
        press.holdElapsed()
        press.moved(point(AttachmentMenuItem.Files))
        press.cancelled()
        assertNull(press.highlighted)
        assertEquals(AttachmentMenuPress.Outcome.None, press.ended(point(AttachmentMenuItem.Files)))
        assertEquals(listOf("selection"), log)
    }

    /**
     * Switching sessions or drafts immediately drops lingering `+` menu and inline photo card
     * layers for the new identity, while an unchanged identity (a draft becoming its own
     * created session keeps its `taskIdentity`) preserves its menu state.
     */
    @Test fun attachmentMenuLingerResetsAcrossSessionAndDraftSwitches() {
        val firstDraftIdentity: Any = "draft-1"
        var linger = AttachmentMenuLinger()
        linger = linger.phaseChanged(AttachmentMenuPhase.Menu, firstDraftIdentity)
        linger = linger.phaseChanged(AttachmentMenuPhase.Photos, firstDraftIdentity)
        linger = linger.phaseChanged(AttachmentMenuPhase.Closed, firstDraftIdentity)
        assertTrue(linger.glassLingers(firstDraftIdentity))
        assertTrue(linger.rowsLinger(firstDraftIdentity))
        assertTrue(linger.keepsPickerDrawn(firstDraftIdentity))

        // A fresh draft has a new identity and immediately suppresses lingering layers.
        val secondDraftIdentity: Any = "draft-2"
        assertFalse(linger.glassLingers(secondDraftIdentity))
        assertFalse(linger.rowsLinger(secondDraftIdentity))
        assertFalse(linger.keepsPickerDrawn(secondDraftIdentity))
        assertEquals(AttachmentMenuPhase.Closed, linger.shownPhase(secondDraftIdentity))

        linger = linger.syncIdentity(secondDraftIdentity, AttachmentMenuPhase.Closed)
        assertFalse(linger.glassLingers)
        assertFalse(linger.rowsLinger)
        assertFalse(linger.keepsPickerDrawn)
    }

    @Test fun flagOffKeepsDefaultHoldDurationAndMenuSprings() {
        assertEquals(250.milliseconds, AttachmentPickerButton.holdDuration)
        assertEquals(250.milliseconds, AttachmentPickerButton.holdDuration(plusMenuSheet = false))
        assertEquals(PlusMenuTunables.HOLD_DURATION.seconds, AttachmentPickerButton.holdDuration(plusMenuSheet = true))
        assertEquals(MotionSpring(0.4, 0.18).spec<Float>(), AttachmentMenuMotion.open<Float>(reduceMotion = false, plusMenuSheet = false))
        assertEquals(MotionSpring(0.34, 0.0).spec<Float>(), AttachmentMenuMotion.close<Float>(reduceMotion = false, plusMenuSheet = false))
        assertEquals(PlusMenuTunables.open.spec<Float>(), AttachmentMenuMotion.open<Float>(reduceMotion = false, plusMenuSheet = true))
        assertEquals(PlusMenuTunables.close.spec<Float>(), AttachmentMenuMotion.close<Float>(reduceMotion = false, plusMenuSheet = true))
    }
}
