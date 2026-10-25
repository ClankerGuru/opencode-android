package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group MODELS (iOS `SessionFeature+Models.swift`).

/** Models, the Project sheet, and the session family. Null: the action passes on. */
internal fun SessionFeature.reduceModelsAndFamily(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group MODELS)
