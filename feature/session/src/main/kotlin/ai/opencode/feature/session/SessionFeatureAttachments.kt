package ai.opencode.feature.session

import ai.opencode.core.store.Reduced

// Ported by group MODELS (iOS `SessionFeature+Attachments.swift`).

/** Composer attachments as the session sees them. Null: the action passes on. */
internal fun SessionFeature.reduceAttachments(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group MODELS)
