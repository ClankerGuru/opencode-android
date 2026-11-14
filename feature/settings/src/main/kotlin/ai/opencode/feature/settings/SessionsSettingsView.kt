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
import androidx.compose.ui.res.stringResource

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
    val composerHeader = stringResource(R.string.settings_sessions_composer_header)
    val newSessionsHeader = stringResource(R.string.settings_sessions_new_sessions_header)
    val serverDefault = stringResource(R.string.settings_sessions_server_default)

    SettingsPageScaffold(title = stringResource(R.string.settings_sessions_title), store = store) {
        settingsSection(key = "return", header = composerHeader) {
            SettingsToggleRow(
                stringResource(R.string.settings_sessions_return_key_sends),
                checked = returnKeySends,
                onCheckedChange = { send(HomeSettingsFeature.Action.ReturnKeySendsChanged(it)) },
            )
        }
        if (ExperimentalFeature.agentSelection.isActive(agentSelection)) {
            settingsSection(
                key = "default-agent",
                header = newSessionsHeader,
            ) {
                val options = buildList {
                    add(SettingsMenuOption("", serverDefault))
                    agents.forEach { add(SettingsMenuOption(it.id, it.displayName)) }
                    // A saved agent this server doesn't list stays choosable.
                    if (defaultAgent.isNotEmpty() && agents.none { it.id == defaultAgent }) {
                        add(SettingsMenuOption(defaultAgent, AgentDisplayName.titleCased(defaultAgent)))
                    }
                }
                SettingsMenuRow(
                    title = stringResource(R.string.settings_sessions_default_agent),
                    value = options.firstOrNull { it.value == defaultAgent }?.title ?: serverDefault,
                    selection = defaultAgent,
                    options = options,
                    onSelect = { send(HomeSettingsFeature.Action.DefaultAgentChanged(it)) },
                )
            }
        }
    }
}
