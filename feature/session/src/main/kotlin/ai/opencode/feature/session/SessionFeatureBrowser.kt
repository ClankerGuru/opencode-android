package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group MODELS (iOS `SessionFeature+Browser.swift`).

/** The in-app browser, links, shells and Move to Background. Null: the action passes on. */
internal fun SessionFeature.reduceBrowserAndBackgroundWork(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group MODELS)

/**
 * iOS `scheduleBackgroundHint(_:)`: offers Move to Background once foreground work has blocked the run long enough.
 * TODO(group MODELS): port; `func scheduleBackgroundHint(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.scheduleBackgroundHint(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)
