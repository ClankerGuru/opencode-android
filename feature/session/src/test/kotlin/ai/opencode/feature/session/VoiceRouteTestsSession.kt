package ai.opencode.feature.session

import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SessionFeature` halves of iOS `VoiceRouteTests.swift`'s
 * `inactiveGeminiNeverDisablesThePhoneKeyAcrossEntryPoints` and `grokSuppressesUnrelatedServerErrors`,
 * deferred from `:feature:voice`. Their `VoiceSettingsFeature` halves belong to `:feature:settings`;
 * `VoiceRoutePreferences.isAvailable` (Home's entry point) to the drawer. Each test's shared keys
 * live in its own in-memory store, set as iOS's `preserveSettings()` leaves them.
 */
class VoiceRouteTestsSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "route", title = "Routing", projectID = "p", directory = "/project", updatedAt = distantPast)

    /** iOS `preserveSettings()`'s starting point: voice on, GPT-Live, no keys, Server Voice off. */
    private fun Harness.preserveSettings() = state {
        shared(SharedKeys.voiceModeEnabled).set(true)
        shared(VoiceSharedKeys.voiceEngine).set(VoiceEngine.gptLive)
        shared(SharedKeys.openAIAPIKey).set(null)
        shared(SharedKeys.xaiAPIKey).set(null)
        shared(ExperimentalFeature.serverVoice.key).set(false)
        shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.openAI)
        shared(VoiceSharedKeys.serverVoiceSupportByServer).set(emptyMap())
    }

    private fun Harness.isVoiceAvailable(): Boolean = state { State.session(server = server, session = session).isVoiceAvailable }

    /**
     * A stored Gemini choice with Server Voice off has no effect: GPT-Live
     * needs the phone's key, and Settings, the session and Home agree.
     */
    @Test fun inactiveGeminiNeverDisablesThePhoneKeyAcrossEntryPoints() = sessionTest { h ->
        h.preserveSettings()
        h.state { shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.geminiLive) }
        assertFalse(h.isVoiceAvailable())

        h.state { shared(SharedKeys.openAIAPIKey).set("sk-phone") }
        val detail = h.state { State.session(server = server, session = session) }
        assertTrue(h.state { detail.isVoiceAvailable })
        assertTrue(h.state { detail.voiceRoute.warmsPhoneConnection })
    }

    @Test fun grokSuppressesUnrelatedServerErrors() = sessionTest { h ->
        h.preserveSettings()
        h.state {
            shared(ExperimentalFeature.serverVoice.key).set(true)
            shared(VoiceSharedKeys.serverVoiceSupportByServer).set(mapOf(server.persistenceID to ServerVoiceSupport.unsupported))
            shared(VoiceSharedKeys.voiceEngine).set(VoiceEngine.grok)
        }
        assertFalse(h.isVoiceAvailable())
        h.state { shared(SharedKeys.xaiAPIKey).set("xai-test") }
        assertTrue(h.isVoiceAvailable())
    }
}
