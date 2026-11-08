package ai.opencode.core.touch

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `TouchRecoveryTests` (`OpenCodeTests/TouchRecoveryTests.swift`).
 *
 * Not here (UIKit-only fixtures for owners Android doesn't watch, see `docs/parity/core.md`):
 * `closedDrawerRecoversAncestorAndScrimButOpenDrawerKeepsOwnership`,
 * `catchUpLeftoverChromeAndCardGateRecoverAfterExit`,
 * `pushedPageTouchesBelongToTheDrawerNotTheBrowserStack`,
 * `browserStackLandsAPopWhosePageHasAlreadyLeft`, `browserPopStillOnScreenKeepsItsTransition`
 * (a `UINavigationController`-style stack in a `UIWindow`),
 * `sheetRecoveryRecognizesOnlyAnInvisibleUncoveredPresentation`,
 * `recoveredSwiftUISheetClearsItsBindingAndOpensAgain`.
 */
class TouchRecoveryTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Holder

    @Test
    fun graceBelongsToTheSameOwnerFaultAndResetsOnLegitimateBlocking() {
        val first = Holder()
        val second = Holder()
        var fault: TouchRecovery.Fault? = TouchRecovery.Fault(TouchRecovery.Reason.Closed, first)
        var releases = 0
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = { fault },
            release = {
                releases += 1
                fault = null
            },
        )
        assertFalse(watch.sample(now = 0.0))
        assertFalse(watch.sample(now = 1.0))
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, second)
        assertFalse(watch.sample(now = 1.1))
        fault = null // a newly opened menu legitimately owns touches
        assertFalse(watch.sample(now = 2.0))
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, second)
        assertFalse(watch.sample(now = 3.0))
        assertTrue(watch.sample(now = 4.01))
        assertFalse(watch.sample(now = 10.0))
        assertEquals(1, releases)
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, first)
        assertFalse(watch.sample(now = 11.0))
        watch.reset() // background/foreground starts a fresh grace period
        assertFalse(watch.sample(now = 100.0))
        assertFalse(watch.sample(now = 101.0))
        assertTrue(watch.sample(now = 101.01))
    }

    @Test
    fun aFaultThatComesStraightBackBacksOffInsteadOfLooping() {
        val holder = Holder()
        var inspected = 0
        var releases = 0
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.Drawer,
            inspect = {
                inspected += 1
                TouchRecovery.Fault(TouchRecovery.Reason.Disabled, holder)
            },
            release = { releases += 1 },
        )
        assertFalse(watch.sample(now = 0.0))
        assertTrue(watch.sample(now = 1.01))
        // The first release waits 2 s, untouched, before the grace starts again.
        inspected = 0
        assertFalse(watch.sample(now = 2.0))
        assertFalse(watch.sample(now = 3.0))
        assertEquals(0, inspected)
        assertFalse(watch.sample(now = 3.02))
        assertTrue(watch.sample(now = 4.03))
        // The second waits 4 s, the third 8 s, up to a minute.
        assertFalse(watch.sample(now = 8.02))
        assertEquals(2, inspected)
        assertFalse(watch.sample(now = 8.04))
        assertTrue(watch.sample(now = 9.05))
        assertFalse(watch.sample(now = 17.04))
        assertFalse(watch.sample(now = 17.06))
        assertTrue(watch.sample(now = 18.07))
        assertEquals(4, releases)
        assertEquals(60.0, TouchRecovery.backoff(10), 0.0)
        // Five quiet minutes forgive the streak.
        assertFalse(watch.sample(now = 400.0))
        assertTrue(watch.sample(now = 401.01))
        assertFalse(watch.sample(now = 403.03))
        assertTrue(watch.sample(now = 404.04))
    }
}
