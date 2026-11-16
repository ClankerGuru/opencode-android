package ai.opencode.feature.session

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerTouchStateTests {
    @Test
    fun aBackDragCaughtMidPopLetsThePopLand() = runTest {
        val stack = SessionPageStackMotion(animationScope(), emptyList())
        stack.width = 300f
        stack.reducedMotion = true
        stack.update(listOf(SessionStackPage(id = 1)))
        stack.finishTransition()
        val popped = mutableListOf<Int>()
        stack.onPopped = { popped.add(it) }
        assertTrue(stack.popTop())
        // A finger already down as the pop began is recognized only now.
        assertFalse(stack.dragBegan(leanSign = 1f))
        stack.dragEnded(velocity = 0f, ended = true)
        stack.finishTransition()
        assertTrue("the pop lands; the page never stays on top", stack.pages.isEmpty())
        assertEquals(listOf(0), popped)
        assertFalse(stack.isPopping)
        stack.update(listOf(SessionStackPage(id = 2)))
        stack.finishTransition()
        assertTrue("the next back works", stack.popTop())
        stack.finishTransition()
        assertTrue(stack.pages.isEmpty())
    }

    /** The stack's moves run on Compose's frame clock: a 60 Hz one on virtual time. */
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
