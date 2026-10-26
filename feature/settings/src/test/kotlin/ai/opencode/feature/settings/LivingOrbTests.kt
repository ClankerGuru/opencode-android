package ai.opencode.feature.settings

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.LivingOrbStyle
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.livingOrbStyle
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `LivingOrbTests.swift`, the `:feature:settings` part (`HomeSettingsFeature`); the
 * rest is `:feature:voice`'s and `:core`'s ports.
 *
 * iOS checks the raw `UserDefaults` value; Android's in-memory shared store keeps encoded values
 * private, so this checks the key's name and the value's stored encoding.
 */
class LivingOrbTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun settingsPersistStyleWithoutEnablingTheExperiment() = runTest {
        val shared = SettingsTestShared()
        assertEquals(LivingOrbStyle.polarBloom, shared.get(VoiceSharedKeys.livingOrbStyle))
        assertFalse(shared.get(experimental(ExperimentalFeature.livingOrb)))
        val store = TestStore(
            HomeSettingsFeature.State(server = OpenCodeServer(url = "https://example.test", username = "", password = "")),
            HomeSettingsFeature(),
            this,
        ) { this[SharedStore] = shared.store }

        store.send(HomeSettingsFeature.Action.LivingOrbStyleChanged(LivingOrbStyle.silkRibbons))
        assertEquals(LivingOrbStyle.silkRibbons, shared.get(VoiceSharedKeys.livingOrbStyle))
        assertEquals("living-orb-style", VoiceSharedKeys.livingOrbStyle.id)
        assertEquals("\"silkRibbons\"", Json.encodeToString(LivingOrbStyle.serializer(), LivingOrbStyle.silkRibbons))
        assertFalse(shared.get(experimental(ExperimentalFeature.livingOrb)))
        store.send(HomeSettingsFeature.Action.LivingOrbStyleChanged(LivingOrbStyle.polarBloom))
        store.finish()
    }
}
