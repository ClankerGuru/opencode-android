package ai.opencode.feature.session

import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.seams.AgentPickerKeys
import ai.opencode.server.model.OpenCodeAutocompleteAgent

// Ported by group MODELS (iOS `SessionFeature+Agents.swift`).

/** iOS `SessionFeature.AgentSelectionState` (Agent Selection, experimental). */
data class AgentSelectionState(
    /** The server's primary agents, as last listed. */
    val primaryAgents: List<OpenCodeAutocompleteAgent> = emptyList(),
    /** The agent a draft starts with, when picked before its first send. */
    val draftAgent: String? = null,
    /** The agent each queued send was sent with, applied when its message starts or Send Now makes it a steer (as `queuedPicks`). */
    val queuedPicks: Map<String, String> = emptyMap(),
    val queuedPickDue: String? = null,
    val sessionAgents: Shared<Map<String, String>> = shared(AgentPickerKeys.sessionAgents),
    val defaultAgentID: Shared<String> = shared(AgentPickerKeys.defaultAgentID),
    val knownPrimaryAgents: Shared<List<OpenCodeAutocompleteAgent>> = shared(AgentPickerKeys.knownPrimaryAgents),
)

/** iOS `State.queuedAgentPickDue` (get). */
val SessionFeature.State.queuedAgentPickDue: String? get() = agentSelection.queuedPickDue

/** iOS `State.queuedAgentPickDue` (set). */
fun SessionFeature.State.withQueuedAgentPickDue(value: String?): SessionFeature.State =
    copy(agentSelection = agentSelection.copy(queuedPickDue = value))

/**
 * iOS `applyDueAgentPick(_:)`: a queued send's message started with its agent still waiting.
 * TODO(group MODELS): port; `func applyDueAgentPick(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.applyDueAgentPick(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)
