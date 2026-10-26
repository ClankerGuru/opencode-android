package ai.opencode.feature.settings

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.experimental
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceRoutePreferences
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `VoiceRouteTests.swift`, the `:feature:settings` part: the Settings and
 * `VoiceRoutePreferences` checks of `inactiveGeminiNeverDisablesThePhoneKeyAcrossEntryPoints` and
 * `grokSuppressesUnrelatedServerErrors`. Their `SessionFeature.State` checks are
 * `:feature:session`'s; the rest of the file is `:feature:voice`'s port.
 */
class VoiceRouteTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    /** iOS `preserveSettings()`: each test's in-memory shared store starts from these values. */
    private fun preserveSettings(): SettingsTestShared {
        val shared = SettingsTestShared()
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        shared.set(SharedKeys.openAIAPIKey, null)
        shared.set(SharedKeys.xaiAPIKey, null)
        shared.set(experimental(ExperimentalFeature.serverVoice), false)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Connected)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, emptyMap())
        return shared
    }

    @Test fun inactiveGeminiNeverDisablesThePhoneKeyAcrossEntryPoints() {
        val shared = preserveSettings()
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.geminiLive)
        shared.read { assertFalse(VoiceRoutePreferences().isAvailable(server)) }

        shared.set(SharedKeys.openAIAPIKey, "sk-phone")
        shared.read {
            val settings = VoiceSettingsFeature.State(server = server)
            assertEquals(VoiceKeySource.Phone(VoiceProvider.openAI), settings.keySource)
            assertEquals("OpenAI Key", settings.keyRowTitle)
            assertTrue(VoiceRoutePreferences().isAvailable(server))
        }
    }

    @Test fun grokSuppressesUnrelatedServerErrors() {
        val shared = preserveSettings()
        shared.set(experimental(ExperimentalFeature.serverVoice), true)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, mapOf(server.persistenceID to ServerVoiceSupport.unsupported))
        val settings = VoiceSettingsFeature.State(server = server)
        shared.read { assertTrue(settings.showsServerUnsupported) }
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.grok)
        shared.read {
            assertFalse(settings.showsServerUnsupported)
            assertEquals(VoiceKeySource.Phone(VoiceProvider.xAI), settings.keySource)
            assertEquals("xAI Key", settings.keyRowTitle)
        }
    }
}
