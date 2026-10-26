package ai.opencode.feature.drawer.motion

import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Port of iOS `MotionSystemTests`' drawer shortcut tests (`DrawerShortcutSlot`,
 * `DrawerShortcutMotion`). The rest of that file is `:core`'s (`MotionSystemTests` there) and
 * `:feature:transcript`'s; `returnToDrawerDoesNotMoveLongReturnSession` is deleted: it probes a
 * hosted `UIScrollView`'s content offset through UIKit layout.
 */
class MotionSystemTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS's display link: a frame clock that never ticks, so a started loop stays running until stopped. */
    private object StillFrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = awaitCancellation()
    }

    private fun CoroutineScope.motion() = DrawerShortcutMotion(CoroutineScope(coroutineContext + StillFrameClock))

    @Test
    fun drawerShortcutSlotCollapsesMonotonicallyAndPreservesVelocityOnMidExitReversal() {
        val slot = DrawerShortcutSlot(isOpen = true)
        assertTrue(slot.isMounted)
        assertTrue(slot.wantsOpen)
        assertEquals(1f, slot.progress)
        assertTrue(slot.isSettled)

        // Exit: stays mounted while collapsing monotonically from 1 -> 0, then unmounts on settle.
        slot.retarget(false, reducedMotion = false)
        assertTrue(slot.isMounted)
        assertFalse(slot.wantsOpen)
        assertFalse(slot.isSettled)

        val dt = 1.0 / 120.0
        var previous = slot.progress
        var steps = 0
        while (!slot.isSettled && steps < 240) {
            slot.step(dt)
            assertTrue("progress must decrease monotonically on exit", slot.progress <= previous + 1e-6)
            if (!slot.isSettled) assertTrue("row stays mounted until the exit spring settles", slot.isMounted)
            previous = slot.progress
            steps += 1
        }
        assertTrue(slot.isSettled)
        assertFalse(slot.isMounted)
        assertEquals(0f, slot.progress)
        assertTrue(steps > 10)

        // Re-enter from unmounted (0 -> 1): mounts immediately and expands monotonically to 1.
        slot.retarget(true, reducedMotion = false)
        assertTrue(slot.isMounted)
        assertTrue(slot.wantsOpen)
        assertFalse(slot.isSettled)
        var enterPrevious = slot.progress
        var enterSteps = 0
        while (!slot.isSettled && enterSteps < 240) {
            slot.step(dt)
            assertTrue("progress must increase monotonically on enter", slot.progress >= enterPrevious - 1e-6)
            enterPrevious = slot.progress
            enterSteps += 1
        }
        assertTrue(slot.isSettled)
        assertTrue(slot.isMounted)
        assertEquals(1f, slot.progress)

        // Mid-exit reversal (1 -> 0 -> 1): preserves position and negative velocity, then recovers to 1.
        slot.retarget(false, reducedMotion = false)
        repeat(12) { slot.step(dt) }
        val midProgress = slot.progress
        val midVelocity = slot.solver.velocity
        assertTrue(midProgress > 0.1f && midProgress < 0.9f)
        assertTrue(midVelocity < 0)

        slot.retarget(true, reducedMotion = false)
        assertTrue(slot.isMounted)
        assertTrue(slot.wantsOpen)
        assertTrue(abs(slot.progress - midProgress) < 1e-6)
        assertTrue(abs(slot.solver.velocity - midVelocity) < 1e-6)

        var recoverySteps = 0
        while (!slot.isSettled && recoverySteps < 240) {
            slot.step(dt)
            recoverySteps += 1
        }
        assertTrue(slot.isSettled)
        assertTrue(slot.isMounted)
        assertEquals(1f, slot.progress)
    }

    @Test
    fun drawerShortcutMotionDisplayLinkStopsOnDrawerCloseCatchUpOpenAndBackground() = runTest {
        val motion = backgroundScope.motion()
        try {
            val base = DrawerShortcutInputs(
                showsCatchUp = true,
                catchUpCount = 2,
                catchUpFinish = null,
                canMarkAllRead = true,
                showsWhatsNew = true,
                searchIsEmpty = true,
                serverID = "srv_1",
                isSettingsShown = false,
                isDrawerVisible = true,
                reducedMotion = false,
            )
            motion.sync(base)
            assertFalse(motion.isRunning)
            assertTrue(motion.catchUp.isMounted)

            // 1. Drawer close stops the display link.
            val closing = base.copy(showsCatchUp = false, catchUpCount = 0)
            motion.sync(closing)
            assertTrue(motion.isRunning)
            motion.drawerDidHide()
            assertFalse(motion.isRunning)
            assertFalse(motion.catchUp.isMounted)

            // 2. Catch Up opening (isDrawerVisible becomes false) stops the display link.
            motion.sync(base)
            motion.sync(closing)
            assertTrue(motion.isRunning)
            motion.sync(closing.copy(isDrawerVisible = false))
            motion.drawerDidHide()
            assertFalse(motion.isRunning)

            // 3. Entering background (isDrawerVisible becomes false) stops the display link.
            motion.sync(base)
            motion.sync(closing)
            assertTrue(motion.isRunning)
            motion.sync(closing.copy(isDrawerVisible = false))
            motion.drawerDidHide()
            assertFalse(motion.isRunning)
        } finally {
            motion.drawerDidHide()
        }
    }

    @Test
    fun drawerCatchUpFinishPlaysOncePerFinishThroughSearchHidingAndUnread() = runTest {
        val motion = backgroundScope.motion()
        try {
            val finished = DrawerShortcutInputs(
                showsCatchUp = true,
                catchUpCount = 0,
                catchUpFinish = 1,
                canMarkAllRead = false,
                showsWhatsNew = false,
                searchIsEmpty = true,
                serverID = "srv_1",
                isSettingsShown = false,
                isDrawerVisible = true,
                reducedMotion = false,
            )
            val hidden = finished.copy(isDrawerVisible = false)
            motion.sync(hidden)
            assertNull("waits for the drawer", motion.finishStart)
            motion.sync(finished)
            val start = motion.finishStart
            assertNotNull(start)
            assertTrue(motion.catchUp.isMounted)

            // Search folds the row away and brings it back: same clock.
            motion.sync(finished.copy(searchIsEmpty = false))
            assertFalse(motion.catchUp.isMounted)
            motion.sync(finished)
            assertEquals(start, motion.finishStart)

            // The drawer hides and shows again: same clock.
            motion.sync(hidden)
            motion.drawerDidHide()
            motion.sync(finished)
            assertEquals(start, motion.finishStart)

            // Unread puts the count back; clearing it never replays this finish.
            motion.sync(finished.copy(catchUpFinish = null, catchUpCount = 1))
            assertNull(motion.finishStart)
            motion.sync(finished)
            assertNull("a finish already played never starts again", motion.finishStart)

            // The next real finish plays.
            motion.sync(finished.copy(catchUpFinish = 2))
            assertNotNull(motion.finishStart)
        } finally {
            motion.drawerDidHide()
        }
    }

    @Test
    fun drawerShortcutCloseDelaySurvivesUnrelatedInputChangeAndUnmountsCleanly() = runTest {
        val motion = backgroundScope.motion()
        try {
            val inWhatsNew = DrawerShortcutInputs(
                showsCatchUp = true,
                catchUpCount = 2,
                catchUpFinish = null,
                canMarkAllRead = true,
                showsWhatsNew = true,
                searchIsEmpty = true,
                serverID = "srv_1",
                isSettingsShown = true,
                isDrawerVisible = true,
                reducedMotion = false,
            )
            motion.sync(inWhatsNew)
            assertTrue(motion.whatsNew.isMounted)

            // Returning from What's New starts the close delay.
            val returned = inWhatsNew.copy(isSettingsShown = false, showsWhatsNew = false)
            motion.sync(returned)
            val initialDelay = DrawerMotionTunables.SHORTCUT_CLOSE_DELAY
            assertTrue(abs(motion.whatsNew.delayRemaining - initialDelay) < 1e-5)

            // Step partway through the delay, then send an unrelated input change.
            motion.whatsNew.step(0.05)
            val remainingBefore = motion.whatsNew.delayRemaining
            assertTrue(abs(remainingBefore - (initialDelay - 0.05)) < 1e-5)
            assertEquals(1f, motion.whatsNew.progress)

            motion.sync(returned.copy(catchUpCount = 3))
            assertTrue(
                "unrelated input must not reset the close delay",
                abs(motion.whatsNew.delayRemaining - remainingBefore) < 1e-5,
            )

            // Even if generation is nil on a closing slot, settling still unmounts it.
            motion.whatsNew.generation = null
            var steps = 0
            while (!motion.whatsNew.isSettled && steps < 240) {
                motion.whatsNew.step(1.0 / 120.0)
                steps += 1
            }
            assertTrue(motion.whatsNew.isSettled)
            assertFalse(motion.whatsNew.isMounted)
            assertEquals(0f, motion.whatsNew.progress)
        } finally {
            motion.drawerDidHide()
        }
    }
}
