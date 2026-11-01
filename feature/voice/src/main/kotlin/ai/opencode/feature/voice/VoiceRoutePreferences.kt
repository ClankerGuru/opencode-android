package ai.opencode.feature.voice

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeServer

/**
 * Shared settings read by Home's call entry points, without loading a session
 * (iOS `VoiceRoutePreferences`). Holds the `@Shared` references; views that must follow a
 * change collect their `flow`s. Equality is by reference identity (`Shared`), like iOS.
 */
data class VoiceRoutePreferences(
    val enabled: Shared<Boolean> = shared(SharedKeys.voiceModeEnabled),
    val engine: Shared<VoiceEngine> = shared(VoiceSharedKeys.voiceEngine),
    val openAIKey: Shared<String?> = shared(SharedKeys.openAIAPIKey),
    val xaiKey: Shared<String?> = shared(SharedKeys.xaiAPIKey),
    val serverVoice: Shared<Boolean> = shared(experimental(ExperimentalFeature.serverVoice)),
    val provider: Shared<ServerVoiceProvider> = shared(VoiceSharedKeys.serverVoiceProvider),
    val voiceServer: Shared<VoiceServerChoice> = shared(VoiceSharedKeys.voiceServerChoice),
    val support: Shared<Map<String, ServerVoiceSupport>> = shared(VoiceSharedKeys.serverVoiceSupportByServer),
    val voiceModels: Shared<Map<String, ServerVoiceModelsAnswer>> = shared(VoiceSharedKeys.voiceModelsByServer),
) {
    fun route(server: OpenCodeServer): VoiceRoute {
        val hasOpenAIKey = !(openAIKey.value ?: "").trim().isEmpty()
        val broker = VoiceBroker(
            serverVoice = ExperimentalFeature.serverVoice.isActive(serverVoice.value),
            choice = voiceServer.value,
            provider = provider.value,
            connected = server,
            saved = { VoiceFeature.savedServers },
            readiness = VoiceReadiness(answers = voiceModels.value, support = support.value, hasPhoneKey = hasOpenAIKey, engine = engine.value),
        )
        return broker.route(
            engine = engine.value,
            support = broker.support(support.value),
            hasOpenAIKey = hasOpenAIKey,
            hasXAIKey = !(xaiKey.value ?: "").trim().isEmpty(),
        )
    }

    fun isAvailable(server: OpenCodeServer): Boolean {
        // The lab's scripted call, stand-in servers and lab key (debug builds only).
        if (VoiceLabFlags.isEnabled || VoiceLabFlags.hasStandInServer || VoiceLabFlags.labAPIKey != null) return true
        return enabled.value && route(server).isAvailable
    }
}
