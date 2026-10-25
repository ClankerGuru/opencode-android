package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group STREAM (iOS `SessionFeature+Run.swift`).

/** Stop, run state and the run's end. Null: the action passes on. */
internal fun SessionFeature.reduceRunLifecycle(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group STREAM)
