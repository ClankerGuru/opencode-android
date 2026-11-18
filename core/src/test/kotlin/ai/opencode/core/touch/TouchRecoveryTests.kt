package ai.opencode.core.touch

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = {
                inspected += 1
                TouchRecovery.Fault(TouchRecovery.Reason.Closed, holder)
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

    @Test
    fun slowMotionCannotStretchTheBackoffOrTheGrace() {
        val holder = Holder()
        var releases = 0
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = { TouchRecovery.Fault(TouchRecovery.Reason.Closed, holder) },
            release = { releases += 1 },
        )
        // At 0.1× animation time crawls; the backoff still runs in real seconds.
        assertFalse(watch.sample(now = 0.0, wall = 0.0))
        assertTrue(watch.sample(now = 1.01, wall = 1.01))
        assertFalse(watch.sample(now = 1.2, wall = 3.02))
        assertFalse(watch.sample(now = 1.3, wall = 5.0))
        // The grace is one animation second, but never more than four real ones.
        assertTrue(watch.sample(now = 1.45, wall = 7.1))
        assertEquals(2, releases)
    }

    /** A sampler with a hand-driven clock and timer. */
    private class SamplerRig {
        var wall = 0.0
        var starts = 0
        var stops = 0
        var tick: (() -> Unit)? = null
        val sampler = TouchRecoverySampler(
            clock = { TouchRecoverySampler.Time(now = wall, wall = wall) },
            schedule = { tick ->
                starts += 1
                this.tick = tick
                val stop = {
                    stops += 1
                    this.tick = null
                }
                stop
            },
        )

        /** Runs the timer until it stops or [end], as the main looper would. */
        fun run(end: Double) {
            while (true) {
                val tick = tick ?: return
                if (wall >= end) return
                wall += TouchRecoverySampler.INTERVAL
                tick()
            }
        }
    }

    @Test
    fun samplerSleepsOnceItsWindowPassesQuietly() {
        val rig = SamplerRig()
        var inspected = 0
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = {
                inspected += 1
                null
            },
            release = {},
        )
        rig.sampler.add(watch)
        assertTrue(rig.sampler.isRunning)
        rig.run(end = 60.0)
        assertFalse("At rest the sampler stops: 0 Hz", rig.sampler.isRunning)
        assertTrue(rig.wall <= TouchRecoverySampler.WINDOW + TouchRecoverySampler.INTERVAL)
        assertEquals(Math.round(TouchRecoverySampler.WINDOW / TouchRecoverySampler.INTERVAL).toInt(), rig.sampler.ticks)
        assertEquals(1, rig.starts)
        assertEquals(1, rig.stops)
        // A minute at rest costs nothing.
        val quiet = rig.sampler.ticks
        rig.wall += 60
        assertEquals(quiet, rig.sampler.ticks)
        assertEquals(quiet, inspected)
        // A transition arms a fresh window, and arming again only extends it.
        rig.sampler.arm()
        rig.sampler.arm()
        assertEquals(2, rig.starts)
        rig.run(end = rig.wall + 60)
        assertFalse(rig.sampler.isRunning)
        assertEquals(2, rig.stops)
    }

    @Test
    fun samplerStaysAwakeUntilAStandingFaultIsReleasedAndItsBackoffEnds() {
        val rig = SamplerRig()
        val holder = Holder()
        var fault: TouchRecovery.Fault? = null
        var releases = 0
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = { fault },
            release = {
                releases += 1
                fault = null
            },
        )
        rig.sampler.add(watch)
        rig.run(end = 3.0)
        // A fault born late in the window keeps it open past the window's end.
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, holder)
        rig.run(end = 4.9)
        assertEquals("Released a second after it was first seen", 1, releases)
        assertTrue(rig.sampler.isRunning)
        rig.run(end = 6.0)
        assertTrue("Past its window but backing off: a fault that comes straight back is still watched", rig.sampler.isRunning)
        rig.run(end = 60.0)
        assertFalse(rig.sampler.isRunning)
        assertTrue(rig.wall <= 4.5 + TouchRecovery.backoff(1) + TouchRecoverySampler.INTERVAL)
    }

    @Test
    fun aTouchOnAnIdleSamplerSeesAStandingFaultAtOnce() {
        val rig = SamplerRig()
        val holder = Holder()
        var fault: TouchRecovery.Fault? = null
        var releases = 0
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = { fault },
            release = {
                releases += 1
                fault = null
            },
        )
        rig.sampler.add(watch)
        rig.run(end = 60.0)
        assertFalse(rig.sampler.isRunning)
        // Stranded while nothing was sampling; the finger finds it.
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, holder)
        rig.wall = 100.0
        rig.sampler.touched()
        assertTrue(rig.sampler.isRunning)
        rig.run(end = 101.25)
        assertEquals("Seen when the finger came down, so released a second after it", 1, releases)
    }
}
