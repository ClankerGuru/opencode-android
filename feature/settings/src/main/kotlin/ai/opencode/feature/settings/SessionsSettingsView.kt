package ai.opencode.feature.settings

import ai.opencode.core.experiments.key
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.AgentDisplayName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Port of iOS `Settings/SessionsSettingsView.swift` (and `DefaultAgentSection` from
 * `Composer/AgentPicker.swift`): Settings › Sessions, Composer (Return Key Sends and the default
 * agent). Replaces Settings › Composer (iOS 302b95e7).
 */
@Composable
fun SessionsSettingsView(store: SettingsStore) {
    val returnKeySends by remember { shared(SharedKeys.returnKeySends).flow }.collectAsState()
    val agentSelection by remember { shared(ExperimentalFeature.agentSelection.key).flow }.collectAsState()
    val defaultAgent by remember { shared(SharedKeys.defaultAgentID).flow }.collectAsState()
    val agents by remember { shared(ServerSharedKeys.knownPrimaryAgents).flow }.collectAsState()
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }

    SettingsPageScaffold(title = "Sessions", store = store) {
        // iOS: “⌘↩︎ always sends.”; Android hardware keyboards send with Ctrl+Enter.
        settingsSection(
            key = "return",
            header = "Composer",
            description = "Ctrl+Enter always sends on a hardware keyboard",
        ) {
            SettingsToggleRow(
                "Return Key Sends",
                checked = returnKeySends,
                onCheckedChange = { send(HomeSettingsFeature.Action.ReturnKeySendsChanged(it)) },
            )
        }
        if (ExperimentalFeature.agentSelection.isActive(agentSelection)) {
            settingsSection(
                key = "default-agent",
                header = "New Sessions",
                description = "Each session remembers the agent you choose",
            ) {
                val options = buildList {
                    add(SettingsMenuOption("", "Server Default"))
                    agents.forEach { add(SettingsMenuOption(it.id, it.displayName)) }
                    // A saved agent this server doesn't list stays choosable.
                    if (defaultAgent.isNotEmpty() && agents.none { it.id == defaultAgent }) {
                        add(SettingsMenuOption(defaultAgent, AgentDisplayName.titleCased(defaultAgent)))
                    }
                }
                SettingsMenuRow(
                    title = "Default Agent",
                    value = options.firstOrNull { it.value == defaultAgent }?.title ?: "Server Default",
                    selection = defaultAgent,
                    options = options,
                    onSelect = { send(HomeSettingsFeature.Action.DefaultAgentChanged(it)) },
                )
            }
        }
    }
}
