package ai.opencode.feature.composer

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of iOS `ComposerMorphPhaseTests.swift`. */
class ComposerMorphPhaseTests {
    /**
     * p282: a fold waiting for its next turn is dropped when the morph opens or closes another
     * way. Its flag must go with it: left set, the morph stays open but never `isOpening`, so
     * Back and every close guard bail.
     */
    @Test fun openingOrClosingAnotherWayClearsAPendingFold() = runTest {
        val morph = ComposerMorph(animationScope())
        morph.open()
        morph.launchThenFold(ComposerMorphTunables.close.spec(), reduceMotion = true, launch = {}, edge = { null })
        assertTrue(morph.foldPending)
        morph.open()
        assertFalse(morph.foldPending)
        assertTrue(morph.isOpening)

        morph.launchThenFold(ComposerMorphTunables.close.spec(), reduceMotion = true, launch = {}, edge = { null })
        morph.close(reduceMotion = true)
        assertFalse(morph.foldPending)
        assertEquals(ComposerMorph.Phase.Closing, morph.phase)
    }

    /** The morph's springs run on `Animatable`s, which need a frame clock: a 60 Hz one on virtual time. */
    private fun TestScope.animationScope(): CoroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return CoroutineScope(backgroundScope.coroutineContext + clock)
    }
}
