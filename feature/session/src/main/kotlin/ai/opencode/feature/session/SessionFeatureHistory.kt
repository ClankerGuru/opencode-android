package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group STREAM (iOS `SessionFeature+History.swift`).

/** Earlier history paging, New Messages and Since You Left. Null: the action passes on. */
internal fun SessionFeature.reduceHistory(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group STREAM)

/** iOS `SessionFeature.prune(_:to:)`: drops entries whose keys are no longer in [live]. */
internal fun <V> SessionFeature.Companion.prune(values: Map<String, V>, live: Set<String>): Map<String, V> =
    if (values.keys.any { it !in live }) values.filterKeys { it in live } else values
