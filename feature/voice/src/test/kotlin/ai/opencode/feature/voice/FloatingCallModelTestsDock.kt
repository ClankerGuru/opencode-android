package ai.opencode.feature.voice

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** iOS `CGRect(x:y:width:height:)`. */
private fun cgRect(x: Float, y: Float, width: Float, height: Float) = Rect(x, y, x + width, y + height)

/** The session's tray noted with no anchor mounted (iOS: no `sessionAnchor` set). */
private fun CallTrayDock.noteSessionTrayWithoutAnchor(frame: Rect) {
    noteSessionTray(frame)
    noteSessionAnchorRemoved()
}

/** iOS `FloatingCallModelTests+Dock.swift` (an extension of `FloatingCallModelTests`). */
class FloatingCallModelTestsDock {
    @Test
    fun drawerReturnFollowsTheKeyboardAnchorWithoutBlendingItsLift() {
        // iOS hangs a `UIView` anchor in a card in a 440 × 956 window; Android's anchor reports
        // its window frame through `noteSessionTray`, so each step feeds the card-translated frame.
        val window = cgRect(0f, 0f, 440f, 956f)
        var cardX = 0f
        var anchor = cgRect(20f, 866f, 400f, 56f)
        fun noteAnchor() = anchor.translate(cardX, 0f)
        val dock = CallTrayDock()
        // A frame clock that never ticks while the test runs, as iOS's `MotionDisplayLink`.
        val frameClock = CoroutineScope(StandardTestDispatcher())
        dock.attach(frameClock)
        try {
            dock.noteSessionTray(noteAnchor())
            dock.setDrawer(CallTrayDockGeometry(slot = cgRect(10f, 866f, 310f, 56f), bounds = window, recedes = true, screen = window))
            dock.setArmed(true)
            dock.drawerMoved(1f)
            // Include reversal while the keyboard is rising. The translated card's
            // offscreen x must not discard its new return height or pull the tray aside.
            for (step in (25..100).reversed() + (25..55) + (0..55).reversed()) {
                val p = step.toFloat() / 100
                cardX = 330 * p
                anchor = cgRect(anchor.left, 530 + 336 * p, anchor.width, anchor.height)
                dock.noteSessionTray(noteAnchor())
                dock.drawerMoved(p)
                val frame = dock.frame(0.0)
                assertTrue("step $step: ${frame.bottom} vs ${anchor.bottom}", abs(frame.bottom - anchor.bottom) < 0.001f)
                assertTrue("step $step: ${frame.left}", frame.left >= 10 && frame.left <= 20)
                assertTrue("step $step", dock.isTravelling)
                assertTrue("step $step", dock.hidesSessionTray)
            }
            assertFalse(dock.finishReturnIfSettled())
            assertTrue(dock.finishReturnIfSettled())
            assertFalse(dock.isTravelling)
            assertFalse(dock.hidesSessionTray)
            dock.drawerMoved(1f)
            dock.drawerMoved(0f)
            anchor = cgRect(anchor.left, 1000f, anchor.width, anchor.height)
            dock.noteSessionTray(noteAnchor())
            assertFalse(dock.finishReturnIfSettled())
            assertTrue(dock.frame(0.0).bottom <= 922)
            dock.setArmed(false)
            assertFalse(dock.isTravelling)
            assertFalse(dock.hidesSessionTray)
        } finally {
            frameClock.cancel()
        }
    }

    @Test
    fun reversingDrawerKeepsTheSameTripUntilTheCardIsShut() {
        val dock = CallTrayDock()
        val session = cgRect(20f, 866f, 400f, 56f)
        dock.noteSessionTrayWithoutAnchor(session)
        dock.setDrawer(CallTrayDockGeometry(slot = cgRect(10f, 866f, 310f, 56f), bounds = cgRect(0f, 0f, 330f, 956f), recedes = true))
        dock.setArmed(true)
        val outward = mutableListOf<Rect>()
        for (step in 0..120) {
            dock.drawerMoved(step.toFloat() / 120)
            outward += dock.frame(0.0)
        }
        // Close partway, reverse, then close all the way. Ownership must never
        // hand back early, even when the finger crosses the settlement threshold.
        for (step in (30..120).reversed() + (30..90) + (0..90).reversed()) {
            dock.drawerMoved(step.toFloat() / 120)
            assertEquals("step $step", outward[step], dock.frame(0.0))
            assertEquals("step $step", step > 0, dock.isTravelling)
            assertEquals("step $step", step > 0, dock.hidesSessionTray)
        }
        assertEquals(session, dock.frame(0.0))
    }

    @Test
    fun dockPreservesAnAlreadyAlignedVerticalAnchor() {
        val dock = CallTrayDock()
        val session = cgRect(20f, 866f, 400f, 56f)
        dock.noteSessionTrayWithoutAnchor(session)
        dock.setDrawer(CallTrayDockGeometry(slot = cgRect(10f, 866f, 310f, 56f), bounds = cgRect(0f, 0f, 330f, 956f), recedes = true))
        dock.setArmed(true)
        for (step in 0..120) {
            dock.drawerMoved(step.toFloat() / 120)
            val frame = dock.frame(0.0)
            assertTrue("The drawer receding must not move the call's vertical anchor", abs(frame.center.y - session.center.y) < 0.001f)
            assertEquals(session.height, frame.height)
        }
    }
}
