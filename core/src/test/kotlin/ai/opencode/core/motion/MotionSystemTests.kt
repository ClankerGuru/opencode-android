package ai.opencode.core.motion

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningStore
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of iOS `MotionSystemTests` (`OpenCodeTests/MotionSystemTests.swift`).
 *
 * Not here:
 * - `emptyStateMotionBreathesContinuouslyAndRevealsWithoutJumping` (`EmptyStateMotion`,
 *   `EmptyStateTunables`) belongs to `:feature:transcript`.
 * - `drawerShortcutSlotCollapsesMonotonicallyAndPreservesVelocityOnMidExitReversal`,
 *   `drawerShortcutMotionDisplayLinkStopsOnDrawerCloseCatchUpOpenAndBackground`,
 *   `drawerCatchUpFinishPlaysOncePerFinishThroughSearchHidingAndUnread`,
 *   `drawerShortcutCloseDelaySurvivesUnrelatedInputChangeAndUnmountsCleanly`
 *   (`DrawerShortcutSlot`, `DrawerShortcutMotion`) belong to `:feature:drawer`.
 * - `returnToDrawerDoesNotMoveLongReturnSession` probes a UIKit `SessionDrawerViewController`
 *   in a `UIWindow` (`:feature:drawer`, UIKit-only layout probe): deleted.
 */
class MotionSystemTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun motionConstantsRegisterAndExposeDefaults() {
        val store = TuningStore(null)
        store.setEnabled(true)
        MotionTunables.register(store)

        assertEquals(0.42, MotionTunables.morphOpen.duration.defaultValue, 0.0)
        assertEquals(0.12, MotionTunables.morphOpen.bounce.defaultValue, 0.0)
        assertEquals(0.34, MotionTunables.morphClose.duration.defaultValue, 0.0)
        assertEquals(0.0, MotionTunables.morphClose.bounce.defaultValue, 0.0)
        assertEquals(0.34, MotionTunables.panelOpen.duration.defaultValue, 0.0)
        assertEquals(0.12, MotionTunables.panelOpen.bounce.defaultValue, 0.0)
        assertEquals(0.40, MotionTunables.settle.duration.defaultValue, 0.0)
        assertEquals(0.30, MotionTunables.disclosureOpen.duration.defaultValue, 0.0)
        assertEquals(0.24, MotionTunables.disclosureClose.duration.defaultValue, 0.0)
        assertEquals(0.26, MotionTunables.microPop.duration.defaultValue, 0.0)
        assertEquals(0.22, MotionTunables.microPop.bounce.defaultValue, 0.0)
        assertEquals(0.18, MotionTunables.microExit.duration.defaultValue, 0.0)
        assertEquals(0.22, MotionTunables.drawerSlide.duration.defaultValue, 0.0)

        assertEquals(0.20, MotionTunables.reducedFade.defaultValue, 0.0)
        assertEquals(0.14, MotionTunables.fadeFast.defaultValue, 0.0)
        assertEquals(0.22, MotionTunables.fadeStandard.defaultValue, 0.0)
        assertEquals(0.32, MotionTunables.fadeSlow.defaultValue, 0.0)
        assertEquals(0.05, MotionTunables.contentDelay.defaultValue, 0.0)
        assertEquals(0.10, MotionTunables.handoffDelay.defaultValue, 0.0)
        assertEquals(0.02, MotionTunables.staggerStep.defaultValue, 0.0)
        assertEquals(0.60, MotionTunables.foldHandoverRatio.defaultValue, 0.0)
        assertEquals(3.dp, MotionTunables.blurSubtle.defaultValue)
        assertEquals(8.dp, MotionTunables.blurPanel.defaultValue)
        assertEquals(14.dp, MotionTunables.blurSheet.defaultValue)
        assertEquals(18.dp, MotionTunables.blurBackdrop.defaultValue)
        assertEquals(6.dp, MotionTunables.entranceRise.defaultValue)
        assertEquals(10.dp, MotionTunables.panelRise.defaultValue)

        val keys = store.descriptors.map { it.key }.toSet()
        assertTrue(keys.contains("motion.morphOpen.duration"))
        assertTrue(keys.contains("motion.morphClose.duration"))
        assertTrue(keys.contains("motion.reducedFade"))
        assertTrue(keys.contains("motion.blurPanel"))
        assertTrue(TuningContext.session.groups.contains("motion"))
    }

    /**
     * iOS compares against `FlightSpring` (`Composer/PlusMenu/PhotoFlight.swift`), which Android
     * doesn't have as a type; [FlightSpring] below is that Swift struct verbatim, as the oracle.
     */
    @Test
    fun springSolverMatchesFlightSpringAndIsFrameRateInvariant() {
        for (bounce in listOf(0.0, 0.18, -0.15)) {
            val duration = 0.42
            val params = SpringSolver.parameters(duration, bounce)
            val flightParams = FlightSpring.parameters(duration, bounce)
            assertTrue(abs(params.omega - flightParams.first) < 1e-6)
            assertTrue(abs(params.zeta - flightParams.second) < 1e-6)

            val solver60 = SpringSolver.of(duration = duration, bounce = bounce, value = 0.0, velocity = 0.0, target = 240.0)
            val solver120 = SpringSolver.of(duration = duration, bounce = bounce, value = 0.0, velocity = 0.0, target = 240.0)
            val flight60 = FlightSpring(value = 0.0, velocity = 0.0)

            repeat(18) {
                solver60.step(dt = 1.0 / 60.0, snapWhenSettled = false)
                flight60.step(toward = 240.0, omega = flightParams.first, zeta = flightParams.second, dt = 1.0 / 60.0)
                solver120.step(dt = 1.0 / 120.0, snapWhenSettled = false)
                solver120.step(dt = 1.0 / 120.0, snapWhenSettled = false)

                assertTrue(abs(solver60.value - flight60.value) < 1e-5)
                assertTrue(abs(solver60.velocity - flight60.velocity) < 1e-4)
                assertTrue(abs(solver60.value - solver120.value) < 1e-4)
                assertTrue(abs(solver60.velocity - solver120.velocity) < 1e-3)
            }
        }
    }

    @Test
    fun springSolverRetargetPreservesPositionAndVelocityOnMidFlightReversal() {
        val solver = SpringSolver(Motion.morphOpen, value = 0.0, target = 100.0)
        repeat(8) { solver.step(dt = 1.0 / 120.0, snapWhenSettled = false) }
        val posBeforeReverse = solver.value
        val velBeforeReverse = solver.velocity
        assertTrue(posBeforeReverse > 10 && posBeforeReverse < 90)
        assertTrue(velBeforeReverse > 100)

        solver.retarget(0.0, spring = Motion.morphClose)
        assertEquals(posBeforeReverse, solver.value, 0.0)
        assertEquals(velBeforeReverse, solver.velocity, 0.0)

        // First 120 Hz step after reversing must continue smoothly with positive inertial momentum
        // before decelerating back toward 0, rather than snapping velocity to 0.
        val firstAfter = solver.step(dt = 1.0 / 120.0, snapWhenSettled = false)
        assertTrue(firstAfter.value > posBeforeReverse)
        assertTrue(firstAfter.velocity > 0 && firstAfter.velocity < velBeforeReverse)
    }

    @Test
    fun presenceGateAndMotionBlurRevealRespectGenerationAndBounds() {
        // Android's gate is immutable (reducer state): each call returns the next gate.
        var gate = PresenceGate(isOpen = false)
        fun request(open: Boolean): Int? = gate.request(open).let { (next, token) -> gate = next; token }
        fun beginAnimation(open: Boolean): Int? = gate.beginAnimation(open).let { (next, token) -> gate = next; token }
        fun complete(generation: Int): Boolean = gate.complete(generation).let { (next, unmounted) -> gate = next; unmounted }

        assertTrue(!gate.isMounted && !gate.isPresented)

        // Requesting open while unmounted mounts first and returns null so onAppear starts the animation.
        assertNull(request(true))
        assertTrue(gate.isMounted && !gate.isPresented)

        // Cancelling before onAppear ever started an intro unmounts immediately.
        assertNull(request(false))
        assertTrue(!gate.isMounted && !gate.isPresented)

        // Mount and start intro (gen 1).
        assertNull(request(true))
        val openGen = beginAnimation(true)
        assertEquals(1, openGen)
        assertTrue(gate.isMounted && gate.isPresented)

        // Start closing (gen 2); repeated request(false) during outro must be idempotent
        // and keep the view mounted until complete(generation: 2) runs.
        val closeGen = request(false)
        assertEquals(2, closeGen)
        assertTrue(gate.isMounted && !gate.isPresented)
        assertNull(request(false))
        assertTrue(gate.isMounted && !gate.isPresented && gate.generation == 2)

        // Reopen (gen 3) before gen 2 completes.
        val reopenGen = request(true)
        assertEquals(3, reopenGen)

        // Stale close completion must not unmount the reopened surface.
        assertFalse(complete(2))
        assertTrue(gate.isMounted && gate.isPresented)

        // Final close completion unmounts cleanly.
        val finalCloseGen = request(false)
        assertEquals(4, finalCloseGen)
        assertNull(request(false))
        assertTrue(gate.isMounted)
        assertTrue(complete(4))
        assertTrue(!gate.isMounted && !gate.isPresented)

        // MotionBlurReveal gates blur to 0 at >= 0.98 (resting open) and under Reduce Motion,
        // while keeping blur through 0 so the filter group never tears down mid-collapse.
        assertEquals(14f, MotionBlurReveal.blurRadius(progress = 0f, maxBlur = 14f, reduceMotion = false), 0f)
        assertTrue(abs(MotionBlurReveal.blurRadius(progress = 0.01f, maxBlur = 14f, reduceMotion = false) - 13.86f) < 1e-5f)
        assertEquals(0f, MotionBlurReveal.blurRadius(progress = 0.99f, maxBlur = 14f, reduceMotion = false), 0f)
        assertEquals(0f, MotionBlurReveal.blurRadius(progress = 1f, maxBlur = 14f, reduceMotion = false), 0f)
        assertTrue(abs(MotionBlurReveal.blurRadius(progress = 0.5f, maxBlur = 14f, reduceMotion = false) - 7f) < 1e-5f)
        assertEquals(0f, MotionBlurReveal.blurRadius(progress = 0.5f, maxBlur = 14f, reduceMotion = true), 0f)
    }

    /**
     * iOS `MotionDisplayLink` → Android [motionFrameLoop] on Compose's frame clock, driven here by
     * a 120 Hz test clock. Deleted: the `proMotionRange` (`CAFrameRateRange` 80–120) assertions,
     * since Android has no per-link frame-rate request (the loop runs at the display's refresh
     * rate), and `isRunning` (the loop is a suspend function that runs until it returns).
     */
    @Test
    fun motionDisplayLinkTicksAtProMotionRangeAndStopsOnRequest() = runTest {
        val clock = object : MonotonicFrameClock {
            var nanos = 1_000_000_000L
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                nanos += 8_333_333L
                return onFrame(nanos)
            }
        }
        val ticks = mutableListOf<MotionTick>()
        withContext(clock) {
            motionFrameLoop(frameDurationSeconds = 1.0 / 120.0) { tick ->
                ticks += tick
                if (ticks.size >= 4) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
        }
        assertEquals(4, ticks.size)
        assertTrue(ticks.all { it.dt > 0 && it.rawFrameDuration >= 1.0 / 240.0 })
    }

    /** iOS `FlightSpring` (`Composer/PlusMenu/PhotoFlight.swift`), verbatim, as the reference. */
    private class FlightSpring(var value: Double, var velocity: Double = 0.0) {
        fun step(toward: Double, omega: Double, zeta: Double, dt: Double) {
            val target = toward
            val x0 = value - target
            val v0 = velocity
            if (abs(zeta - 1) < 1e-4) {
                val decay = exp(-omega * dt)
                val a = v0 + omega * x0
                value = target + (x0 + a * dt) * decay
                velocity = (v0 - omega * a * dt) * decay
            } else if (zeta < 1) {
                val wd = omega * sqrt(1 - zeta * zeta)
                val decay = exp(-zeta * omega * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                value = target + decay * (x0 * c + (v0 + zeta * omega * x0) / wd * s)
                velocity = decay * (v0 * c - (zeta * omega * v0 + omega * omega * x0) / wd * s)
            } else {
                val root = sqrt(zeta * zeta - 1)
                val r1 = -omega * (zeta - root)
                val r2 = -omega * (zeta + root)
                val c1 = (v0 - r2 * x0) / (r1 - r2)
                val c2 = x0 - c1
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                value = target + c1 * e1 + c2 * e2
                velocity = c1 * r1 * e1 + c2 * r2 * e2
            }
        }

        companion object {
            fun parameters(duration: Double, bounce: Double): Pair<Double, Double> {
                val omega = 2 * Math.PI / maxOf(duration, 0.01)
                val zeta = if (bounce >= 0) 1 - bounce else 1 / (1 + bounce)
                return omega to maxOf(zeta, 0.01)
            }
        }
    }
}
