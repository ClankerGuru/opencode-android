package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group SEND (iOS `SessionFeature+PendingInputs.swift`).

/** The inbox: queued and steering messages, and their deliveries. Null: the action passes on. */
internal fun SessionFeature.reducePendingInputs(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group SEND)

/**
 * iOS `applyDueModelPick(_:)`: a queued send's message started with its pick still waiting.
 * TODO(group SEND): port; `func applyDueModelPick(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.applyDueModelPick(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)
