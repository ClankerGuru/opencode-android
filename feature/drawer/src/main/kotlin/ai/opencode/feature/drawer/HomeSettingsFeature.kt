package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
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
import ai.opencode.feature.drawer.notices.InAppNoticeMode
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.MessageDigest

/**
 * Port of iOS `HomeSettingsFeature` (`OpenCode/Drawer/HomeSettingsFeature.swift`): preferences and
 * their platform effects. Home supplies the current server; session navigation (including the
 * onboarding playground) stays with Home. Every preference is a shared key written here, and only
 * when it changes.
 */
class HomeSettingsFeature : Reducer<HomeSettingsFeature.State, HomeSettingsFeature.Action> {
    /**
     * [isSidebarShown] mirrors `SharedKeys.sidebarShown` as a plain value (as Home's state does) so
     * Home's scope copies a toggle back; the reducer writes it through to the key. Home builds
     * this from its own state: `State(server, isSidebarShown)`.
     */
    data class State(
        val server: OpenCodeServer,
        val isSidebarShown: Boolean = true,
    )

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

    /** Writes [next] when it differs; true if it changed. */
    private fun <T> setShared(key: SharedKey<T>, next: T): Boolean {
        val current = shared(key)
        if (current.value == next) return false
        current.set(next)
        return true
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val server = state.server
        when (action) {
            Action.SidebarToggled -> {
                val next = !state.isSidebarShown
                shared(SharedKeys.sidebarShown).set(next)
                return Reduced(state.copy(isSidebarShown = next))
            }

            is Action.InAppNoticeModeChanged -> setShared(InAppNoticeMode.key, action.mode)

            is Action.ExperimentalFeatureChanged -> {
                if (!setShared(action.feature.key, action.on)) return Reduced(state)
                when (action.feature) {
                    ExperimentalFeature.serverVoice -> {
                        if (!ExperimentalFeature.serverVoice.isActive(action.on)) return Reduced(state)
                        return Reduced(state, Effect.send(Action.CheckServerVoiceSupport))
                    }
                    // iOS also mirrors New Plus Menu and Composer Autocomplete into its Tuning
                    // overrides (`ComposerMorphTunables`, `ComposerAutocompleteTunables`). Android
                    // has no Tuning port (`:devtools` is Android-native): the composer reads the
                    // experiment's shared key directly.
                    ExperimentalFeature.plusMenuSheet, ExperimentalFeature.composerAutocomplete -> Unit
                    else -> Unit
                }
            }

            is Action.ModelPickerStyleChanged -> setShared(ModelPickerStyle.key, action.style)

            is Action.LivingOrbStyleChanged -> {
                if (!setShared(LivingOrbStyle.key, action.style)) return Reduced(state)
                dependency(HapticsClient).play(Haptic.selection)
            }

            is Action.VoiceModeEnabledChanged -> setShared(SharedKeys.voiceModeEnabled, action.on)

            is Action.RaiseToTalkChanged -> setShared(SharedKeys.raiseToTalk, action.on)

            is Action.VoiceEngineChanged -> {
                setShared(SharedKeys.voiceEngineMigratedToGPTLive, true)
                setShared(VoiceSharedKeys.voiceEngine, action.engine)
            }

            is Action.ServerVoiceProviderChanged -> setShared(VoiceSharedKeys.serverVoiceProvider, action.provider)

            is Action.VoiceServerChanged -> setShared(VoiceServerChoice.key, action.choice)

            Action.CheckServerVoiceSupport -> {
                // Sent only while Server Voice is on.
                val choice = shared(VoiceServerChoice.key).value
                val broker = VoiceBroker(serverVoice = true, choice = choice, connected = server, saved = { VoiceBroker.savedServers() })
                    .server ?: return Reduced(state)
                val serverVoice = dependency(ServerVoiceClient)
                return Reduced(
                    state,
                    Effect.run { send ->
                        val support = serverVoice.checkSupport(broker)
                        send(Action.ServerVoiceSupportLoaded(serverID = broker.persistenceID, support = support))
                    },
                )
            }

            is Action.ServerVoiceSupportLoaded -> {
                if (action.support != ServerVoiceSupport.supported && action.support != ServerVoiceSupport.unsupported) return Reduced(state)
                val supportByServer = shared(VoiceSharedKeys.serverVoiceSupportByServer)
                if (supportByServer.value[action.serverID] == action.support) return Reduced(state)
                supportByServer.update { it + (action.serverID to action.support) }
            }

            is Action.CallVoiceChanged -> setShared(VoiceSharedKeys.callVoice, action.voice)

            is Action.OpenAIAPIKeyChanged -> setShared(SharedKeys.openAIAPIKey, action.key)

            is Action.XaiAPIKeyChanged -> setShared(SharedKeys.xaiAPIKey, action.key)

            is Action.VerifiedVoiceKeyFingerprintChanged -> setShared(SharedKeys.verifiedVoiceKeyFingerprint, action.fingerprint)

            is Action.DeveloperModeChanged -> {
                setShared(SharedKeys.developerMode, action.on)
                val diagnostics = dependency(DiagnosticsClient)
                diagnostics.developerModeChanged(action.on)
                // Traces need a timeline.
                if (action.on && !diagnostics.isEnabled()) diagnostics.setEnabled(true)
            }

            is Action.MotionWatchChanged -> dependency(DiagnosticsClient).setMotionWatchEnabled(action.on)

            is Action.TouchProbeChanged -> {
                setShared(SharedKeys.touchProbe, action.on)
                dependency(DiagnosticsClient).setTouchProbeEnabled(action.on)
            }

            is Action.VoiceDemoModeChanged -> setShared(SharedKeys.voiceDemoMode, action.on)

            Action.OnboardingPlaygroundOpened -> Unit

            // iOS repaints its windows here (`ThemePalette.applyToWindows`); Compose's theme
            // observes the keys and recomposes, so writing them is the whole change.
            is Action.AppThemeChanged -> {
                if (!setShared(ThemeSharedKeys.appTheme, action.theme)) return Reduced(state)
                dependency(HapticsClient).play(Haptic.selection)
            }

            is Action.AppearanceColorSchemeChanged -> {
                if (!setShared(ThemeSharedKeys.appearanceColorScheme, action.scheme)) return Reduced(state)
                dependency(HapticsClient).play(Haptic.selection)
            }

            is Action.ReturnKeySendsChanged -> setShared(SharedKeys.returnKeySends, action.on)

            is Action.DefaultAgentChanged -> setShared(DefaultAgent.key, action.id)
        }
        return Reduced(state)
    }
}

// ---------------------------------------------------------------------------------------------
// Stand-ins for types other areas own and haven't landed on this branch yet. Each names its iOS
// original and where the real one lives (or is landing); delete them at merge.
// ---------------------------------------------------------------------------------------------

/**
 * iOS `ExperimentalFeature` (`Settings/ExperimentalFeatures.swift`): features still being tried
 * out, off by default. TODO(merge): replace with `ai.opencode.core.experiments.ExperimentalFeature`
 * (settings area, commit 080700c on `area/settings`; same cases, raw values and keys).
 */
@Suppress("EnumEntryName")
enum class ExperimentalFeature(val rawValue: String) {
    composerAutocomplete("composer-autocomplete"),
    plusMenuSheet("plus-menu-sheet"),
    agentSelection("agent-selection"),
    hideModels("hide-models"),
    callTray("call-tray"),
    floatingCall("floating-call"),
    livingOrb("living-orb"),
    newMessagesDivider("new-messages-divider"),
    sinceYouLeft("since-you-left"),
    imageLinkPreviews("image-link-previews"),
    projectIcons("project-icons"),
    sessionSwitcher("session-switcher"),
    serverTitleSwitch("server-title-switch"),
    serverDetails("server-details"),
    inAppBrowser("in-app-browser"),
    serverVoice("server-voice"),
    backgroundCallIntent("background-call-intent"),
    ;

    /** One experimental feature's switch (iOS `.experimental(feature)`): `experimental-<raw>`, off by default. */
    val key: SharedKey<Boolean> get() = keys.getValue(this)

    /** Whether this experiment is on. iOS lab runs may force it; Android uses the stored switch. */
    fun isActive(stored: Boolean): Boolean = stored

    private companion object {
        val keys: Map<ExperimentalFeature, SharedKey<Boolean>> =
            entries.associateWith { SharedKey.appStorage("experimental-${it.rawValue}", false) }
    }
}

/**
 * iOS `ExperimentalFeatures` (`@Dependency(\.experimentalFeatures)`): the switches as reducers
 * read them. TODO(merge): replace with `:core`'s `ExperimentalFeatures` client.
 */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue = ExperimentalFeatures { feature -> feature.isActive(shared(feature.key).value) }
        override val testValue = ExperimentalFeatures { false }
    }
}

/**
 * iOS `ModelPickerStyle` (`Settings/ExperimentalFeatures.swift`). TODO(merge): replace with
 * `ai.opencode.core.experiments.ModelPickerStyle`.
 */
@Suppress("EnumEntryName")
@Serializable
enum class ModelPickerStyle(val rawValue: String) {
    @SerialName("today") today("today"),
    @SerialName("sheet") sheet("sheet"),
    @SerialName("grow-from-composer") growFromComposer("grow-from-composer");

    companion object {
        /** Settings › Advanced › Model Picker. Today by default. */
        val key: SharedKey<ModelPickerStyle> = SharedKey.appStorage("experimental-model-picker", today)
    }
}

/** iOS `LivingOrbStyle` (`Voice/LivingOrbStyle.swift`). TODO(merge): replace with `:feature:voice`'s. */
@Suppress("EnumEntryName")
@Serializable
enum class LivingOrbStyle(val title: String) {
    polarBloom("Polar Bloom"),
    livingClouds("Living Clouds"),
    silkRibbons("Silk Ribbons");

    companion object {
        val key: SharedKey<LivingOrbStyle> = SharedKey.appStorage("living-orb-style", polarBloom)
    }
}

/**
 * iOS `SharedKey.defaultAgentID` (`Composer/AgentPicker.swift`): the agent new sessions start with
 * (an agent ID); empty is the server's default. TODO(merge): replace with `:core`'s key.
 */
object DefaultAgent {
    val key: SharedKey<String> = SharedKey.appStorage("default-agent", "")
}

/**
 * iOS `VoiceServerChoice` (`Voice/ServerVoiceClient.swift`): Settings › Voice › Voice Server, which
 * server brokers Server Voice calls. Stored without addresses: a saved server by
 * [VoiceBroker.serverID]. TODO(merge): replace with `:feature:voice`'s.
 */
@Serializable(with = VoiceServerChoice.Serializer::class)
sealed interface VoiceServerChoice {
    /** The server the window is connected to, as before there was a choice. */
    data object Connected : VoiceServerChoice
    /** None: calls use this phone's own OpenAI or xAI key. */
    data object Phone : VoiceServerChoice
    data class Saved(val serverID: String) : VoiceServerChoice

    val rawValue: String
        get() = when (this) {
            Connected -> ""
            Phone -> "phone"
            is Saved -> serverID
        }

    /** The saved server this choice names, if it's still saved. */
    fun savedServer(servers: List<OpenCodeServer>): OpenCodeServer? {
        val saved = this as? Saved ?: return null
        return servers.firstOrNull { VoiceBroker.serverID(it) == saved.serverID }
    }

    companion object {
        fun of(rawValue: String): VoiceServerChoice = when (rawValue) {
            "" -> Connected
            "phone" -> Phone
            else -> Saved(rawValue)
        }

        fun of(server: OpenCodeServer): VoiceServerChoice = Saved(VoiceBroker.serverID(server))

        /** Settings › Voice › Voice Server. The connected server by default. */
        val key: SharedKey<VoiceServerChoice> = SharedKey.appStorage("voice-server", Connected)
    }

    object Serializer : KSerializer<VoiceServerChoice> {
        override val descriptor = PrimitiveSerialDescriptor("VoiceServerChoice", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: VoiceServerChoice) = encoder.encodeString(value.rawValue)
        override fun deserialize(decoder: Decoder): VoiceServerChoice = of(decoder.decodeString())
    }
}

/**
 * iOS `VoiceBroker` (`Voice/VoiceRoute.swift`): which server brokers a new call's Server Voice
 * session. TODO(merge): replace with `:feature:voice`'s.
 */
class VoiceBroker(serverVoice: Boolean, choice: VoiceServerChoice, connected: OpenCodeServer?, saved: () -> List<OpenCodeServer>) {
    /** Server Voice brokers new calls: it's on, and This Phone isn't chosen. */
    val isEnabled: Boolean = serverVoice && choice != VoiceServerChoice.Phone

    /**
     * The chosen saved server, else the connected one (by default, or once the chosen one is no
     * longer saved). Null while nothing brokers.
     */
    val server: OpenCodeServer? = if (isEnabled) choice.savedServer(saved()) ?: connected else null

    companion object {
        /** iOS `VoiceDelegatorWorkspace.serverID`: `srv_` and the first 4 bytes of SHA-256(persistenceID), hex. */
        fun serverID(server: OpenCodeServer): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(server.persistenceID.toByteArray())
            return "srv_" + digest.take(4).joinToString("") { "%02x".format(it) }
        }

        /** iOS `VoiceFeature.savedServers`: the saved servers and the last-used one. */
        fun savedServers(): List<OpenCodeServer> =
            shared(ServerSharedKeys.savedServers).value + listOfNotNull(shared(ServerSharedKeys.savedServer).value)
    }
}

/**
 * iOS `ServerVoiceClient.checkSupport` (`Voice/ServerVoiceClient.swift`). TODO(merge): replace with
 * `:feature:voice`'s client; until then the live value knows nothing (`unknown`, which Settings
 * doesn't record).
 */
class ServerVoiceClient(val checkSupport: suspend (OpenCodeServer) -> ServerVoiceSupport) {
    companion object Key : DependencyKey<ServerVoiceClient> {
        override val liveValue = ServerVoiceClient { ServerVoiceSupport.unknown }
        override val testValue = ServerVoiceClient { ServerVoiceSupport.unknown }
    }
}

/**
 * The parts of iOS `DiagnosticsClient` (`Diagnostics/DiagnosticsClient.swift`) Settings drives.
 * Motion Watch and Touch Probe are iOS-only tools (their keys are kept so the key list matches);
 * the timeline switch is held in memory. TODO(merge): replace with the diagnostics client the
 * Android port settles on (`:devtools` is Android-native, so this may stay drawer-side).
 */
class DiagnosticsClient(
    val isEnabled: () -> Boolean = { false },
    val setEnabled: (Boolean) -> Unit = {},
    val setMotionWatchEnabled: (Boolean) -> Unit = {},
    /** Main thread. */
    val developerModeChanged: (Boolean) -> Unit = {},
    /** Main thread. */
    val setTouchProbeEnabled: (Boolean) -> Unit = {},
) {
    companion object Key : DependencyKey<DiagnosticsClient> {
        override val liveValue: DiagnosticsClient by lazy {
            var enabled = false
            DiagnosticsClient(
                isEnabled = { enabled },
                setEnabled = { enabled = it },
                setMotionWatchEnabled = { on -> shared(SharedKeys.motionWatch).set(on) },
            )
        }
        override val testValue = DiagnosticsClient()
    }
}
