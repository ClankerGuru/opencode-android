package ai.opencode.feature.settings

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.RealtimePreflightClient
import ai.opencode.feature.voice.RealtimePreflightError
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModel
import ai.opencode.feature.voice.ServerVoiceModels
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoicePreviewClient
import ai.opencode.feature.voice.VoicePreviewError
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Data-driven provider group for the Voice Model menu so additional providers (such as Gemini
 * Live) can slot in without changing the view structure (iOS `VoiceProviderGroup`).
 */
data class VoiceProviderGroup(val id: String, val name: String, val keyRowTitle: String, val engines: List<VoiceEngine>) {
    constructor(provider: VoiceProvider) : this(provider.rawValue, provider.displayName, provider.keyRowTitle, provider.engines)

    companion object {
        val defaultGroups: List<VoiceProviderGroup> = VoiceProvider.allCases.map(::VoiceProviderGroup)
    }
}

/**
 * Port of iOS `VoiceSettingsFeature` (`Settings/SettingsView.swift`) with `VoiceServerPicker.swift`'s
 * state extension: Settings › Voice. The shared values are read live through [shared]; the reducer
 * writes them only when they change.
 */
class VoiceSettingsFeature : Reducer<VoiceSettingsFeature.State, VoiceSettingsFeature.Action> {
    data class State(
        /** The server calls start from; the last one used when null. */
        val server: OpenCodeServer? = null,
        val providerGroups: List<VoiceProviderGroup> = VoiceProviderGroup.defaultGroups,
        /** Each saved server's voice models, the connected one's too, by `persistenceID`, as it last answered. */
        val voiceModelsByServer: Map<String, List<ServerVoiceModel>> = emptyMap(),
        val isEditingKey: Boolean = false,
        val revealsKey: Boolean = false,
        val isCheckingKey: Boolean = false,
        val keyVerificationError: String? = null,
        val previewingVoice: CallVoice? = null,
        val previewError: String? = null,
    ) {
        val serverVoiceSupportByServer: Map<String, ServerVoiceSupport> get() = shared(VoiceSharedKeys.serverVoiceSupportByServer).value
        val savedServer: OpenCodeServer? get() = shared(ServerSharedKeys.savedServer).value
        val savedServers: List<OpenCodeServer> get() = shared(ServerSharedKeys.savedServers).value
        val serverVoiceExperiment: Boolean get() = shared(ExperimentalFeature.serverVoice.key).value
        val serverVoiceProvider: ServerVoiceProvider get() = shared(VoiceSharedKeys.serverVoiceProvider).value
        val voiceServer: VoiceServerChoice get() = shared(VoiceSharedKeys.voiceServerChoice).value
        val voiceModeEnabled: Boolean get() = shared(SharedKeys.voiceModeEnabled).value
        val voiceEngine: VoiceEngine get() = shared(VoiceSharedKeys.voiceEngine).value
        val callVoice: CallVoice get() = shared(VoiceSharedKeys.callVoice).value
        val raiseToTalk: Boolean get() = shared(SharedKeys.raiseToTalk).value
        val openAIAPIKey: String? get() = shared(SharedKeys.openAIAPIKey).value
        val xaiAPIKey: String? get() = shared(SharedKeys.xaiAPIKey).value
        val verifiedKeyFingerprint: String? get() = shared(SharedKeys.verifiedVoiceKeyFingerprint).value

        val activeProvider: VoiceProvider get() = voiceEngine.provider
        val modelRowValue: String get() = voiceEngine.providerModelTitle
        val serverVoiceSupport: ServerVoiceSupport get() = broker.support(serverVoiceSupportByServer)

        val route: VoiceRoute
            get() = VoiceRoute.resolve(
                selectedEngine = voiceEngine,
                serverVoiceEnabled = broker.isEnabled,
                serverVoiceProvider = serverVoiceProvider,
                serverVoiceSupport = serverVoiceSupport,
                hasOpenAIKey = !openAIAPIKey.orEmpty().trim().isEmpty(),
                hasXAIKey = !xaiAPIKey.orEmpty().trim().isEmpty(),
            )

        val showsServerUnsupported: Boolean
            get() = broker.isEnabled && voiceEngine != VoiceEngine.grok && serverVoiceSupport == ServerVoiceSupport.unsupported

        val keyRowTitle: String get() = keySource.rowTitle

        val activeAPIKey: String get() = voiceEngine.apiKey(openAIAPIKey, xaiAPIKey).orEmpty().trim()

        val isActiveKeyVerified: Boolean
            get() = voiceEngine.hasVerifiedAPIKey(openAIAPIKey, xaiAPIKey, verifiedKeyFingerprint)

        val keyStatus: VoiceKeyStatus
            get() {
                when (val source = keySource) {
                    is VoiceKeySource.Server -> return VoiceKeyStatus.OnServer(source.providerName)
                    VoiceKeySource.OpenCode -> return VoiceKeyStatus.OnServer("OpenCode")
                    is VoiceKeySource.Phone -> Unit
                }
                if (activeAPIKey.isEmpty()) return VoiceKeyStatus.Missing
                if (isCheckingKey) return VoiceKeyStatus.Checking
                keyVerificationError?.let { return VoiceKeyStatus.Invalid(it) }
                if (isActiveKeyVerified) return VoiceKeyStatus.Verified
                return VoiceKeyStatus.Saved
            }

        val keyBadgeTitle: String get() = keyStatus.badgeTitle
        val canPreviewVoices: Boolean get() = activeAPIKey.isNotEmpty()
        val previewDisabledHint: String?
            get() = if (canPreviewVoices) null else "Add your ${activeProvider.displayName} key to preview voices"
    }

    sealed interface Action {
        data object Task : Action
        data class VoiceModeEnabledChanged(val on: Boolean) : Action
        data class VoiceEngineChanged(val engine: VoiceEngine) : Action
        data object RouteChanged : Action
        data object KeyRowTapped : Action
        data class ApiKeyEdited(val value: String) : Action
        data object ToggleRevealKeyTapped : Action
        data object VerifyKeyTapped : Action
        data class KeyVerificationFinished(val key: String, val model: String, val result: Result<String>) : Action
        data class CallVoiceSelected(val voice: CallVoice) : Action
        data class PreviewVoiceTapped(val voice: CallVoice) : Action
        data class VoicePreviewFinished(val voice: CallVoice) : Action
        data class VoicePreviewFailed(val voice: CallVoice) : Action
        data object VoicePickerDisappeared : Action
        data class RaiseToTalkChanged(val on: Boolean) : Action
        data class VoiceModelsLoaded(val serverID: String, val models: List<ServerVoiceModel>) : Action
    }

    internal sealed interface CancelID {
        data object VerifyKey : CancelID
        data object VoicePreview : CancelID
        data class VoiceModels(val serverID: String) : CancelID
    }

    private val clock by Dependency(Clock)
    private val realtimePreflight by Dependency(RealtimePreflightClient)
    private val voicePreview by Dependency(VoicePreviewClient)
    internal val serverVoice by Dependency(ServerVoiceClient)

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val voiceServers = checkVoiceServers(state)
            if (!state.voiceModeEnabled || !state.keySource.isPhoneKey) {
                Reduced(state, voiceServers)
            } else {
                val verified = verifyCurrentKey(state, debounce = true)
                Reduced(verified.state, Effect.merge(voiceServers, verified.effect))
            }
        }
        is Action.VoiceModeEnabledChanged -> {
            shared(SharedKeys.voiceModeEnabled).set(action.on)
            if (!action.on) {
                val next = state.copy(isEditingKey = false, revealsKey = false, isCheckingKey = false, keyVerificationError = null)
                val stopped = stopPreview(next)
                Reduced(stopped.state, Effect.merge(Effect.cancel(CancelID.VerifyKey), stopped.effect))
            } else if (!state.keySource.isPhoneKey) {
                Reduced(state)
            } else {
                verifyCurrentKey(state, debounce = true)
            }
        }
        is Action.VoiceEngineChanged -> {
            shared(SharedKeys.voiceEngineMigratedToGPTLive).set(true)
            val previousProvider = state.activeProvider
            shared(VoiceSharedKeys.voiceEngine).set(action.engine)
            var next = state
            if (previousProvider != action.engine.provider) next = next.copy(revealsKey = false)
            next = next.copy(isCheckingKey = false, keyVerificationError = null, previewError = null)
            val stopped = stopPreview(next)
            if (!stopped.state.voiceModeEnabled || !stopped.state.keySource.isPhoneKey) {
                Reduced(stopped.state, Effect.merge(Effect.cancel(CancelID.VerifyKey), stopped.effect))
            } else {
                val verified = verifyCurrentKey(stopped.state, debounce = true)
                Reduced(verified.state, Effect.merge(stopped.effect, verified.effect))
            }
        }
        Action.RouteChanged ->
            if (!state.keySource.isPhoneKey) {
                Reduced(
                    state.copy(isEditingKey = false, revealsKey = false, isCheckingKey = false, keyVerificationError = null),
                    Effect.cancel(CancelID.VerifyKey),
                )
            } else {
                Reduced(state)
            }
        Action.KeyRowTapped ->
            if (!state.keySource.isPhoneKey) {
                Reduced(state)
            } else {
                val editing = !state.isEditingKey
                Reduced(state.copy(isEditingKey = editing, revealsKey = if (editing) state.revealsKey else false))
            }
        is Action.ApiKeyEdited -> apiKeyEdited(state, action.value)
        Action.ToggleRevealKeyTapped -> Reduced(state.copy(revealsKey = !state.revealsKey))
        Action.VerifyKeyTapped ->
            if (!state.keySource.isPhoneKey || state.activeAPIKey.isEmpty() || state.isCheckingKey) {
                Reduced(state)
            } else {
                verifyCurrentKey(state, debounce = false)
            }
        is Action.KeyVerificationFinished ->
            if (state.activeAPIKey != action.key || state.voiceEngine.modelID != action.model) {
                Reduced(state)
            } else {
                action.result.fold(
                    onSuccess = { fingerprint ->
                        shared(SharedKeys.verifiedVoiceKeyFingerprint).set(fingerprint)
                        Reduced(state.copy(isCheckingKey = false, keyVerificationError = null))
                    },
                    onFailure = { error -> Reduced(state.copy(isCheckingKey = false, keyVerificationError = error.message)) },
                )
            }
        is Action.CallVoiceSelected -> {
            shared(VoiceSharedKeys.callVoice).set(action.voice)
            Reduced(state)
        }
        is Action.PreviewVoiceTapped -> previewVoice(state, action.voice)
        is Action.VoicePreviewFinished ->
            Reduced(if (state.previewingVoice == action.voice) state.copy(previewingVoice = null) else state)
        is Action.VoicePreviewFailed -> Reduced(
            state.copy(
                previewingVoice = if (state.previewingVoice == action.voice) null else state.previewingVoice,
                previewError = VoicePreviewError.Failed.errorDescription,
            ),
        )
        Action.VoicePickerDisappeared -> stopPreview(state.copy(previewError = null))
        is Action.RaiseToTalkChanged -> {
            shared(SharedKeys.raiseToTalk).set(action.on)
            Reduced(state)
        }
        is Action.VoiceModelsLoaded ->
            if (state.voiceModelsByServer[action.serverID] == action.models) {
                Reduced(state)
            } else {
                Reduced(state.copy(voiceModelsByServer = state.voiceModelsByServer + (action.serverID to action.models)))
            }
    }

    private fun apiKeyEdited(state: State, rawValue: String): Reduced<State, Action> {
        val source = state.keySource as? VoiceKeySource.Phone ?: return Reduced(state)
        val trimmed = rawValue.trim()
        val saved = trimmed.ifEmpty { null }
        when (source.provider) {
            VoiceProvider.openAI -> shared(SharedKeys.openAIAPIKey).set(saved)
            VoiceProvider.xAI -> shared(SharedKeys.xaiAPIKey).set(saved)
        }
        val next = state.copy(isCheckingKey = false, keyVerificationError = null, previewError = null)
        if (saved == null) {
            val stopped = stopPreview(next)
            return Reduced(stopped.state, Effect.merge(Effect.cancel(CancelID.VerifyKey), stopped.effect))
        }
        if (!next.voiceModeEnabled) return Reduced(next, Effect.cancel(CancelID.VerifyKey))
        return verifyCurrentKey(next, debounce = true)
    }

    private fun previewVoice(state: State, voice: CallVoice): Reduced<State, Action> {
        if (!state.canPreviewVoices) return Reduced(state)
        if (state.previewingVoice == voice) return stopPreview(state)
        val provider = state.activeProvider
        val apiKey = state.activeAPIKey
        val preview = voicePreview
        return Reduced(
            state.copy(previewingVoice = voice, previewError = null),
            Effect.run<Action> { send ->
                try {
                    preview.play(voice, provider, apiKey)
                    send(Action.VoicePreviewFinished(voice))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    send(Action.VoicePreviewFailed(voice))
                }
            }.cancellable(CancelID.VoicePreview, cancelInFlight = true),
        )
    }

    private fun stopPreview(state: State): Reduced<State, Action> {
        if (state.previewingVoice == null) return Reduced(state)
        val preview = voicePreview
        return Reduced(
            state.copy(previewingVoice = null),
            Effect.merge(Effect.cancel(CancelID.VoicePreview), Effect.run { preview.stop() }),
        )
    }

    private fun verifyCurrentKey(state: State, debounce: Boolean): Reduced<State, Action> {
        val key = state.activeAPIKey
        val model = state.voiceEngine.modelID
        if (key.isEmpty()) {
            return Reduced(state.copy(isCheckingKey = false, keyVerificationError = null), Effect.cancel(CancelID.VerifyKey))
        }
        val fingerprint = VoiceKeyFingerprint.make(key = key, model = model)
        if (debounce && state.verifiedKeyFingerprint == fingerprint) {
            return Reduced(state.copy(isCheckingKey = false, keyVerificationError = null), Effect.cancel(CancelID.VerifyKey))
        }
        val clock = clock
        val preflight = realtimePreflight
        return Reduced(
            state.copy(isCheckingKey = !debounce, keyVerificationError = null),
            Effect.run<Action> { send ->
                try {
                    if (debounce) clock.sleep(800.milliseconds)
                    preflight.verify(key, model)
                    send(Action.KeyVerificationFinished(key, model, Result.success(fingerprint)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RealtimePreflightError) {
                    send(Action.KeyVerificationFinished(key, model, Result.failure(error)))
                } catch (error: Exception) {
                    send(Action.KeyVerificationFinished(key, model, Result.failure(RealtimePreflightError.Service(error.message.orEmpty()))))
                }
            }.cancellable(CancelID.VerifyKey, cancelInFlight = true),
        )
    }
}
