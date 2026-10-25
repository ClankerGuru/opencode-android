package ai.opencode.feature.settings

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.store.Effect
import ai.opencode.core.store.cancellable
import ai.opencode.feature.voice.ServerVoiceModels
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import androidx.compose.runtime.Composable

// Port of iOS `Settings/VoiceServerPicker.swift`: Settings › Voice › Voice Server (the Server Voice
// experiment), which server brokers calls. The connected one by default, another saved server
// whose voice models can broker a call, or This Phone and its own keys.

/** The server calls start from: Home's, else the last one used. */
val VoiceSettingsFeature.State.connectedServer: OpenCodeServer? get() = server ?: savedServer

val VoiceSettingsFeature.State.broker: VoiceBroker
    get() = VoiceBroker(
        serverVoice = ExperimentalFeature.serverVoice.isActive(serverVoiceExperiment),
        choice = voiceServer,
        connected = connectedServer,
        saved = { savedServers + listOfNotNull(savedServer) },
    )

/** Every saved server but the connected one, each once, the last used first. */
val VoiceSettingsFeature.State.otherSavedServers: List<OpenCodeServer>
    get() {
        val seen = listOfNotNull(connectedServer?.persistenceID).toMutableSet()
        return (savedServers + listOfNotNull(savedServer)).filter { seen.add(it.persistenceID) }
    }

/**
 * The saved servers Voice Server offers: those whose models can broker a call, and the chosen one
 * while it's saved, even before its check answers.
 */
val VoiceSettingsFeature.State.voiceServerOptions: List<OpenCodeServer>
    get() = otherSavedServers.filter { server ->
        voiceServer == VoiceServerChoice.of(server) ||
            ServerVoiceModels.offersVoice(voiceModelsByServer[server.persistenceID].orEmpty())
    }

/**
 * The choice as the menu shows it: a saved server that's gone, or is now the connected one, reads
 * as the connected server, as calls resolve it.
 */
val VoiceSettingsFeature.State.voiceServerSelection: VoiceServerChoice
    get() {
        val choice = voiceServer
        if (choice !is VoiceServerChoice.Saved) return choice
        return if (choice.savedServer(voiceServerOptions) == null) VoiceServerChoice.Connected else choice
    }

/**
 * Where calls' credentials live. A broker that lists the call's model as OpenCode's starts it
 * through its OpenCode Console connection.
 */
val VoiceSettingsFeature.State.keySource: VoiceKeySource
    get() {
        val route = route
        val brokerServer = broker.server
        if (route is VoiceRoute.Server && brokerServer != null &&
            ServerVoiceModels.hostedByOpenCode(route.server, voiceModelsByServer[brokerServer.persistenceID].orEmpty())
        ) {
            return VoiceKeySource.OpenCode
        }
        return route.keySource
    }

val VoiceSettingsFeature.State.connectedServerName: String get() = connectedServer?.friendlyName ?: "Server"

val VoiceSettingsFeature.State.voiceServerValue: String
    get() = when (val selection = voiceServerSelection) {
        VoiceServerChoice.Connected -> connectedServerName
        VoiceServerChoice.Phone -> "This Phone"
        else -> selection.savedServer(voiceServerOptions)?.friendlyName ?: connectedServerName
    }

/**
 * Asks each saved server which voice models it holds keys for, so Voice Server lists the others
 * that can broker a call, and the key row says who pays for the broker's. A server that doesn't
 * answer keeps its last answer; one without the list (a 404) offers none.
 */
internal fun VoiceSettingsFeature.checkVoiceServers(state: VoiceSettingsFeature.State): Effect<VoiceSettingsFeature.Action> {
    if (!ExperimentalFeature.serverVoice.isActive(state.serverVoiceExperiment)) return Effect.none()
    val client = serverVoice
    return Effect.merge((listOfNotNull(state.connectedServer) + state.otherSavedServers).map { server ->
        Effect.run<VoiceSettingsFeature.Action> { send ->
            val models = client.models(server) ?: return@run
            send(VoiceSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, models))
        }.cancellable(VoiceSettingsFeature.CancelID.VoiceModels(server.persistenceID), cancelInFlight = true)
    })
}

/** Settings › Voice's Server Voice rows: **Voice Server**, then **Voice Provider** while a server brokers calls. */
@Composable
internal fun ServerVoiceRows(state: VoiceSettingsFeature.State, send: (HomeSettingsFeature.Action) -> Unit) {
    val options = buildList {
        add(SettingsMenuOption<VoiceServerChoice>(VoiceServerChoice.Connected, state.connectedServerName))
        state.voiceServerOptions.forEach { add(SettingsMenuOption(VoiceServerChoice.of(it), it.friendlyName)) }
        add(SettingsMenuOption<VoiceServerChoice>(VoiceServerChoice.Phone, "This Phone"))
    }
    SettingsMenuRow(
        title = "Voice Server",
        value = state.voiceServerValue,
        selection = state.voiceServerSelection,
        options = options,
        onSelect = { send(HomeSettingsFeature.Action.VoiceServerChanged(it)) },
    )
    if (state.broker.isEnabled) {
        SettingsDivider()
        SettingsMenuRow(
            title = "Voice Provider",
            value = state.serverVoiceProvider.title,
            selection = state.serverVoiceProvider,
            options = ServerVoiceProvider.entries.map { SettingsMenuOption(it, it.title) },
            onSelect = { send(HomeSettingsFeature.Action.ServerVoiceProviderChanged(it)) },
        )
    }
}
