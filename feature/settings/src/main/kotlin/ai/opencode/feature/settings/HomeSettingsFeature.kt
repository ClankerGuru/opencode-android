package ai.opencode.feature.settings

import ai.opencode.core.experiments.key
import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.LivingOrbStyle
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.livingOrbStyle
import ai.opencode.server.OpenCodeServer

/**
 * Port of iOS `Drawer/HomeSettingsFeature.swift` (moved down to `:feature:settings`: the Settings
 * pages are its only senders besides Home's sidebar toggle, and Android's Settings reducer owns
 * its preference writes). Preferences and their platform effects; every write goes through
 * [setShared], which skips unchanged values (iOS rule).
 *
 * Not here, by design: iOS's `developerModeChanged`, `motionWatchChanged`, `touchProbeChanged` and
 * `voiceDemoModeChanged` come from its Diagnostics page; on Android that page is `:devtools`'
 * `DevToolsView`, whose `DevToolsFeature` writes Developer Mode (turning recording on with it) and
 * Demo Call Audio itself; Motion Watch and Touch Probe are iOS-only tools. The Tuning mirrors of
 * New Plus Menu and Composer Autocomplete are n/a: the composer reads the experiment keys directly.
 */
class HomeSettingsFeature : Reducer<HomeSettingsFeature.State, HomeSettingsFeature.Action> {
    /** Home's server, for the voice broker check (iOS `State.server`); the sidebar flag is a shared key. */
    data class State(val server: OpenCodeServer? = null)

    sealed interface Action {
        data object SidebarToggled : Action
        data class InAppNoticeModeChanged(val mode: InAppNoticeMode) : Action
        data class ExperimentalFeatureChanged(val feature: ExperimentalFeature, val on: Boolean) : Action
        data class ModelPickerStyleChanged(val style: ModelPickerStyle) : Action
        data class LivingOrbStyleChanged(val style: LivingOrbStyle) : Action
        data class VoiceModeEnabledChanged(val on: Boolean) : Action
        data class RaiseToTalkChanged(val on: Boolean) : Action
        data class VoiceEngineChanged(val engine: VoiceEngine) : Action
        data class ServerVoiceProviderChanged(val provider: ServerVoiceProvider) : Action
        /** Settings › Voice › Voice Server. */
        data class VoiceServerChanged(val choice: VoiceServerChoice) : Action
        /** Asks the server that brokers Server Voice calls whether it offers them. */
        data object CheckServerVoiceSupport : Action
        data class ServerVoiceSupportLoaded(val serverID: String, val support: ServerVoiceSupport) : Action
        data class CallVoiceChanged(val voice: CallVoice) : Action
        data class OpenAIAPIKeyChanged(val key: String?) : Action
        data class XaiAPIKeyChanged(val key: String?) : Action
        data class VerifiedVoiceKeyFingerprintChanged(val fingerprint: String?) : Action
        data object OnboardingPlaygroundOpened : Action
        data class AppThemeChanged(val theme: AppTheme) : Action
        data class AppearanceColorSchemeChanged(val scheme: AppearanceColorScheme) : Action
        data class ReturnKeySendsChanged(val on: Boolean) : Action
        /** Agent Selection: the agent new sessions start with ("" is the server's). */
        data class DefaultAgentChanged(val id: String) : Action
    }

    private val haptics by Dependency(HapticsClient)
    private val serverVoice by Dependency(ServerVoiceClient)

    private fun <T> setShared(key: SharedKey<T>, next: T): Boolean {
        val current = shared(key)
        if (current.value == next) return false
        current.set(next)
        return true
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            Action.SidebarToggled -> shared(SharedKeys.sidebarShown).update { !it }
            is Action.InAppNoticeModeChanged -> setShared(InAppNoticeMode.key, action.mode)
            is Action.ExperimentalFeatureChanged -> {
                if (!setShared(action.feature.key, action.on)) return Reduced(state)
                if (action.feature == ExperimentalFeature.serverVoice && ExperimentalFeature.serverVoice.isActive(action.on)) {
                    return Reduced(state, Effect.send(Action.CheckServerVoiceSupport))
                }
            }
            is Action.ModelPickerStyleChanged -> setShared(ModelPickerStyle.key, action.style)
            is Action.LivingOrbStyleChanged ->
                if (setShared(VoiceSharedKeys.livingOrbStyle, action.style)) haptics.play(Haptic.selection)
            is Action.VoiceModeEnabledChanged -> setShared(SharedKeys.voiceModeEnabled, action.on)
            is Action.RaiseToTalkChanged -> setShared(SharedKeys.raiseToTalk, action.on)
            is Action.VoiceEngineChanged -> {
                setShared(SharedKeys.voiceEngineMigratedToGPTLive, true)
                setShared(VoiceSharedKeys.voiceEngine, action.engine)
            }
            is Action.ServerVoiceProviderChanged -> setShared(VoiceSharedKeys.serverVoiceProvider, action.provider)
            is Action.VoiceServerChanged -> setShared(VoiceSharedKeys.voiceServerChoice, action.choice)
            Action.CheckServerVoiceSupport -> {
                // Sent only while Server Voice is on.
                val choice = shared(VoiceSharedKeys.voiceServerChoice).value
                val broker = VoiceBroker(serverVoice = true, choice = choice, connected = state.server, saved = { VoiceFeature.savedServers })
                    .server ?: return Reduced(state)
                val client = serverVoice
                return Reduced(
                    state,
                    Effect.run { send ->
                        val support = client.checkSupport(broker)
                        send(Action.ServerVoiceSupportLoaded(serverID = broker.persistenceID, support = support))
                    },
                )
            }
            is Action.ServerVoiceSupportLoaded -> {
                if (action.support != ServerVoiceSupport.supported && action.support != ServerVoiceSupport.unsupported) return Reduced(state)
                val supportByServer = shared(VoiceSharedKeys.serverVoiceSupportByServer)
                if (supportByServer.value[action.serverID] != action.support) {
                    supportByServer.update { it + (action.serverID to action.support) }
                }
            }
            is Action.CallVoiceChanged -> setShared(VoiceSharedKeys.callVoice, action.voice)
            is Action.OpenAIAPIKeyChanged -> setShared(SharedKeys.openAIAPIKey, action.key)
            is Action.XaiAPIKeyChanged -> setShared(SharedKeys.xaiAPIKey, action.key)
            is Action.VerifiedVoiceKeyFingerprintChanged ->
                setShared(SharedKeys.verifiedVoiceKeyFingerprint, action.fingerprint)
            Action.OnboardingPlaygroundOpened -> Unit
            is Action.AppThemeChanged ->
                // The theme cross-fades in place: OpenCodeAppTheme observes the key.
                if (setShared(ThemeSharedKeys.appTheme, action.theme)) haptics.play(Haptic.selection)
            is Action.AppearanceColorSchemeChanged ->
                if (setShared(ThemeSharedKeys.appearanceColorScheme, action.scheme)) haptics.play(Haptic.selection)
            is Action.ReturnKeySendsChanged -> setShared(SharedKeys.returnKeySends, action.on)
            is Action.DefaultAgentChanged -> setShared(SharedKeys.defaultAgentID, action.id)
        }
        return Reduced(state, Effect.none())
    }
}
