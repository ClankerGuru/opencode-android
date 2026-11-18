package ai.opencode.core.touch

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** See the row in `docs/parity/devtools.md`. */
class TranscriptTouchSafetyTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Holder

    private class SecretLabel(val text: String)

    @Test
    fun recoveryNeedsOneContinuousSecondOfTheSameFault() {
        var fault: TouchRecovery.Fault? = null
        var releases = 0
        val first = Holder()
        val second = Holder()
        val watch = TouchRecovery(
            owner = TouchRecovery.Owner.PlusMenu,
            inspect = { fault },
            release = {
                releases += 1
                fault = null
            },
        )
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, first)
        assertFalse(watch.sample(now = 10.0))
        assertFalse("Exactly one second is still a transition", watch.sample(now = 11.0))
        fault = null
        assertFalse("Healthy resets the grace period", watch.sample(now = 11.5))
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, first)
        assertFalse(watch.sample(now = 12.0))
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, second)
        assertFalse("A different holder starts over", watch.sample(now = 13.5))
        assertEquals(0, releases)
        assertTrue(watch.sample(now = 14.51))
        assertEquals(1, releases)
        assertFalse(watch.sample(now = 20.0))
        fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, first)
        assertFalse("A clock that went backwards starts over", watch.sample(now = 30.0))
        assertFalse(watch.sample(now = 5.0))
        assertTrue(watch.sample(now = 6.1))
    }

    @Test
    fun faultsNameTheHolderTypeNotItsContent() {
        val label = SecretLabel("secret message")
        val fault = TouchRecovery.Fault(TouchRecovery.Reason.Closed, label)
        assertEquals("SecretLabel", fault.kind)
        assertFalse(fault.kind.contains("secret"))
    }
}
