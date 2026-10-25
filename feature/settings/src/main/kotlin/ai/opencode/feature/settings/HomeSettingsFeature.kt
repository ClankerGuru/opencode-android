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
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceSharedKeys

/**
 * Port of iOS `Drawer/HomeSettingsFeature.swift` (moved down to `:feature:settings`: the Settings
 * pages are its only senders besides Home's sidebar toggle, and Android's Settings reducer owns
 * its preference writes). Preferences and their platform effects; every write goes through
 * [setShared], which skips unchanged values (iOS rule).
 *
 * Voice-server actions (`voiceServerChanged`, `checkServerVoiceSupport`,
 * `serverVoiceSupportLoaded`), `livingOrbStyleChanged`, the Tuning hooks for `plusMenuSheet` /
 * `composerAutocomplete`, and the Diagnostics calls of `developerModeChanged`/`motionWatchChanged`/
 * `touchProbeChanged` wait for `:feature:voice`'s `VoiceServerChoice`/`LivingOrbStyle` and the
 * Android developer tools; see docs/parity/settings.md.
 */
class HomeSettingsFeature : Reducer<HomeSettingsFeature.State, HomeSettingsFeature.Action> {
    /** iOS keeps the server here for the voice broker check; the sidebar flag is a shared key. */
    data class State(val placeholder: Unit = Unit)

    sealed interface Action {
        data object SidebarToggled : Action
        data class InAppNoticeModeChanged(val mode: InAppNoticeMode) : Action
        data class ExperimentalFeatureChanged(val feature: ExperimentalFeature, val on: Boolean) : Action
        data class ModelPickerStyleChanged(val style: ModelPickerStyle) : Action
        data class VoiceModeEnabledChanged(val on: Boolean) : Action
        data class RaiseToTalkChanged(val on: Boolean) : Action
        data class VoiceEngineChanged(val engine: VoiceEngine) : Action
        data class ServerVoiceProviderChanged(val provider: ServerVoiceProvider) : Action
        data class CallVoiceChanged(val voice: CallVoice) : Action
        data class OpenAIAPIKeyChanged(val key: String?) : Action
        data class XaiAPIKeyChanged(val key: String?) : Action
        data class VerifiedVoiceKeyFingerprintChanged(val fingerprint: String?) : Action
        data class DeveloperModeChanged(val on: Boolean) : Action
        data class MotionWatchChanged(val on: Boolean) : Action
        data class TouchProbeChanged(val on: Boolean) : Action
        data class VoiceDemoModeChanged(val on: Boolean) : Action
        data object OnboardingPlaygroundOpened : Action
        data class AppThemeChanged(val theme: AppTheme) : Action
        data class AppearanceColorSchemeChanged(val scheme: AppearanceColorScheme) : Action
        data class ReturnKeySendsChanged(val on: Boolean) : Action
        /** Agent Selection: the agent new sessions start with ("" is the server's). */
        data class DefaultAgentChanged(val id: String) : Action
    }

    private val haptics by Dependency(HapticsClient)

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
            is Action.ExperimentalFeatureChanged -> setShared(action.feature.key, action.on)
            is Action.ModelPickerStyleChanged -> setShared(ModelPickerStyle.key, action.style)
            is Action.VoiceModeEnabledChanged -> setShared(SharedKeys.voiceModeEnabled, action.on)
            is Action.RaiseToTalkChanged -> setShared(SharedKeys.raiseToTalk, action.on)
            is Action.VoiceEngineChanged -> {
                setShared(SharedKeys.voiceEngineMigratedToGPTLive, true)
                setShared(VoiceSharedKeys.voiceEngine, action.engine)
            }
            is Action.ServerVoiceProviderChanged -> setShared(VoiceSharedKeys.serverVoiceProvider, action.provider)
            is Action.CallVoiceChanged -> setShared(VoiceSharedKeys.callVoice, action.voice)
            is Action.OpenAIAPIKeyChanged -> setShared(SharedKeys.openAIAPIKey, action.key)
            is Action.XaiAPIKeyChanged -> setShared(SharedKeys.xaiAPIKey, action.key)
            is Action.VerifiedVoiceKeyFingerprintChanged ->
                setShared(SharedKeys.verifiedVoiceKeyFingerprint, action.fingerprint)
            is Action.DeveloperModeChanged -> setShared(SharedKeys.developerMode, action.on)
            is Action.MotionWatchChanged -> setShared(SharedKeys.motionWatch, action.on)
            is Action.TouchProbeChanged -> setShared(SharedKeys.touchProbe, action.on)
            is Action.VoiceDemoModeChanged -> setShared(SharedKeys.voiceDemoMode, action.on)
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
