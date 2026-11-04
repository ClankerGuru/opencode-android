package ai.opencode.feature.settings

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalFeatures
import ai.opencode.core.experiments.key
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ExperimentalFeatureTests.swift`, the `:feature:settings` part (the rest is
 * `:core`'s port): the Sessions page's divider switch goes through [HomeSettingsFeature].
 */
class ExperimentalFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun theSessionsPageDividerSwitchKeepsTheSavedChoice() = runTest {
        val shared = SettingsTestShared()
        val key = ExperimentalFeature.newMessagesDivider.key
        shared.set(key, false)
        val live = { shared.read { ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider) } }
        // Switched off under Advanced, still off in Sessions.
        assertFalse(shared.get(key))
        assertFalse(live())
        val server = OpenCodeServer(url = "https://example.test", username = "", password = "")
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[HapticsClient] = HapticsRecorder().client
        }
        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.newMessagesDivider, true))
        assertTrue(shared.get(key))
        assertTrue(live())
        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.newMessagesDivider, true))
        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.newMessagesDivider, false))
        assertFalse(shared.get(key))
        assertFalse(live())
        store.finish()
    }
}
