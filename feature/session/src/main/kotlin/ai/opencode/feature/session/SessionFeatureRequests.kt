package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.seams.AnswerContext
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodePermissionRequest

// Ported by group SEND (iOS `SessionFeature+Requests.swift`).

/**
 * iOS `protocol PendingRequest` (adopted by `OpenCodePermissionRequest` and `OpenCodeFormRequest`).
 * Kotlin can't add a supertype to `:server` types, so each request is wrapped.
 */
sealed interface PendingRequest {
    val id: String
    val sessionID: String

    data class Permission(val request: OpenCodePermissionRequest) : PendingRequest {
        override val id: String get() = request.id
        override val sessionID: String get() = request.sessionID
    }

    data class Form(val request: OpenCodeFormRequest) : PendingRequest {
        override val id: String get() = request.id
        override val sessionID: String get() = request.sessionID
    }
}

/** iOS `SessionFeature.VoiceSessionName`: the call's session and its title, for renames. */
data class VoiceSessionName(
    val sessionID: String?,
    val title: String?,
)

/** Pending requests. Null: the action passes on. */
internal fun SessionFeature.reduceRequests(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group SEND)

/**
 * iOS `reconcileAnswering(from:in:)`: a pending request takes the composer as its answer and
 * gives it back as it was. TODO(group SEND): port;
 * `func reconcileAnswering(from old: State.AnswerContext, in state: inout State) -> Effect<Action>`.
 */
@Suppress("UNUSED_PARAMETER")
internal fun SessionFeature.reconcileAnswering(
    old: AnswerContext,
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)
