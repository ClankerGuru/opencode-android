package ai.opencode.feature.settings.servers

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.SettingsTestShared
import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Port of iOS `ServerQuietStyleTests.swift`: the server sheet's live pulse and its status colours
 * (D-117 Quiet Settings).
 *
 * Dropped: `markIntroWindsBackTurnsOnceAndRestsWithoutAJump` and `markFrontOnIsTheLogosOwnRectangle`
 * test `ServerMarkGeometry`, the wireframe mark's geometry, which Android doesn't port
 * (`ServerWireframeMark` is unused on iOS; see settings.md).
 */
class ServerQuietStyleTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun pulseSpikesOncePerBeatAndThePhosphorDecaysBehindThePen() {
        assertTrue(abs(ServerPulseTrace.beat(0.41) - 1) < 0.001)
        assertTrue(abs(ServerPulseTrace.beat(0.95)) < 0.01)
        // Just drawn is brightest; older is dimmer; just ahead of the pen is dark.
        val fresh = ServerPulseTrace.brightness(x = 0.499, pen = 0.5, decay = 1.4)
        val older = ServerPulseTrace.brightness(x = 0.2, pen = 0.5, decay = 1.4)
        val ahead = ServerPulseTrace.brightness(x = 0.52, pen = 0.5, decay = 1.4)
        assertTrue(fresh > 0.99)
        assertTrue(older < fresh && older > 0)
        assertEquals(0.0, ahead, 0.0)
        // The pen crosses the width once per three beats.
        assertTrue(abs(ServerPulseTrace.pen(time = 1.8, beats = 3.0, period = 1.2) - 0.5) < 0.0001)
    }

    @Test
    fun statusTonesFollowTheChecks() {
        val current = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "")
        val other = OpenCodeServer(url = "http://mini.local:4096", username = "opencode", password = "")
        var state = SettingsTestShared().read { ServerTitleFeature.State(current = current) }
        assertEquals(ServerStatusTone.live, state.tone(current))
        assertEquals(ServerStatusTone.checking, state.tone(other))
        state = state.copy(checks = state.checks + (other.persistenceID to ServerTitleFeature.Health.asleep))
        assertEquals(ServerStatusTone.off, state.tone(other))
        state = state.copy(checks = state.checks + (other.persistenceID to ServerTitleFeature.Health.update))
        assertEquals(ServerStatusTone.warning, state.tone(other))
        state = state.copy(checks = state.checks + (other.persistenceID to ServerTitleFeature.Health.signIn))
        assertEquals(ServerStatusTone.failed, state.tone(other))
        state = state.copy(checks = state.checks + (current.persistenceID to ServerTitleFeature.Health.asleep))
        assertEquals("A failed check wins over “In use”", ServerStatusTone.off, state.tone(current))
    }
}
