package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group MODELS (iOS `SessionFeature+Dictation.swift`).

/** Dictation into the composer. Null: the action passes on. */
internal fun SessionFeature.reduceDictation(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group MODELS)

/**
 * iOS `stopDictation(_:)`: a keyboard edit mid-dictation wins.
 * TODO(group MODELS): port; `func stopDictation(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.stopDictation(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)

/**
 * iOS `yieldDictation(_:)`: a call takes the mic; the words heard so far wait in the draft.
 * TODO(group MODELS): port; `func yieldDictation(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.yieldDictation(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)
