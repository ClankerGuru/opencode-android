package ai.opencode.feature.settings

import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `HomeChildFeatureTests.swift`, the `:feature:settings` part: the two
 * `HomeSettingsFeature` tests (Android keeps that reducer here). The `HomeNotificationsFeature`
 * and other Home children are `:feature:drawer`'s port.
 */
class HomeChildFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    @Test fun settingsChecksSupportForItsScopedServer() = runTest {
        val shared = SettingsTestShared()
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { requested ->
                    assertEquals(server, requested)
                    ServerVoiceSupport.supported
                },
                models = { throw AssertionError("Unused") },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
        }
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(server.persistenceID, ServerVoiceSupport.supported))
        assertEquals(ServerVoiceSupport.supported, shared.get(VoiceSharedKeys.serverVoiceSupportByServer)[server.persistenceID])
        store.finish()
    }

    @Test fun modelPickerStyleIsOneSetting() = runTest {
        val shared = SettingsTestShared()
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
        }
        store.send(HomeSettingsFeature.Action.ModelPickerStyleChanged(ModelPickerStyle.sheet))
        assertEquals(ModelPickerStyle.sheet, shared.get(ModelPickerStyle.key))
        store.send(HomeSettingsFeature.Action.ModelPickerStyleChanged(ModelPickerStyle.sheet))
        store.send(HomeSettingsFeature.Action.ModelPickerStyleChanged(ModelPickerStyle.growFromComposer))
        assertEquals(ModelPickerStyle.growFromComposer, shared.get(ModelPickerStyle.key))
        store.finish()
    }
}
