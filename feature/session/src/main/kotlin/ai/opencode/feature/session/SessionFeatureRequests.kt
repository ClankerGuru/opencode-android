package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.seams.isFront
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.PromptContext
import kotlin.time.Duration.Companion.seconds

// Port of iOS `SessionFeature+Requests.swift`: pending requests (permissions, questions, forms).

/**
 * iOS `protocol PendingRequest` (adopted by `OpenCodePermissionRequest` and `OpenCodeFormRequest`):
 * a permission or form awaiting an answer from some session. Kotlin can't add a supertype to
 * `:server` types, so each request is wrapped.
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
internal fun SessionFeature.reduceRequests(state: State, action: Action): Reduced<State, Action>? {
    when (action) {
        is Action.PendingRequestsResponse -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            val result = action.result
            val requests = result.getOrNull()
            return if (requests != null) {
                pendingRequestsLoaded(requests.permissions, requests.forms, state)
            } else {
                val error = result.exceptionOrNull() as? OpenCodeClientError
                    ?: OpenCodeClientError(result.exceptionOrNull()?.message ?: OpenCodeClientError.UNREACHABLE_MESSAGE)
                pendingRequestsFailed(error, state)
            }
        }

        is Action.RequestsConnectionChanged -> {
            if (state.requestsConnectionIsAvailable == action.isAvailable) return Reduced(state)
            var next = state.withRequestsConnectionIsAvailable(action.isAvailable)
                .copy(streamIsHealthy = action.isAvailable && state.streamIsHealthy)
                .withRequestLoadError(null)
            if (!action.isAvailable) {
                next = next.withRequestLoadAttempt(null).withRequestFailureAwaitingReconnect(true).copy(pendingWorkPollSeconds = null)
                // This read may predate the gap (including a local buffer overflow). Do not let
                // reconnect join it or replay its pre-gap deltas over REST.
                next = next.copy(liveText = emptyMap())
                val abandon = abandonRefresh(next)
                // Without the stream, reconciliation is the only content path: restart the watchdog at its unhealthy cadence.
                val watched = watchdog(abandon.state)
                return Reduced(
                    watched.state,
                    Effect.merge(
                        abandon.effect,
                        Effect.cancel(SessionFeature.CancelID.Requests),
                        Effect.cancel(SessionFeature.CancelID.Inbox),
                        Effect.cancel(SessionFeature.CancelID.InboxFetch),
                        Effect.cancel(SessionFeature.CancelID.RequestsRetry),
                        watched.effect,
                    ),
                )
            }
            val attempt = if (next.requestFailureAwaitingReconnect) {
                RequestDockFeature.State.LoadAttempt.reconnect
            } else {
                RequestDockFeature.State.LoadAttempt.initial
            }
            // Whatever failed for lack of a server gets one quiet second chance now that Home has heard from it again.
            val recovery = ArrayList<Effect<Action>>()
            if (next.earlierHistoryRetriesOnReconnect) {
                next = next.copy(earlierHistoryRetriesOnReconnect = false)
                recovery += Effect.send(Action.ShowEarlierTapped)
            }
            if (next.loadError != null && next.items.isEmpty() && next.session != null) {
                next = next.copy(loadError = null, isLoading = true)
                recovery += Effect.send(Action.RefreshRequested(RefreshReason.Reconnect))
            }
            val started = startPendingWork(next, attempt)
            return Reduced(started.state, Effect.merge(listOf(started.effect) + recovery))
        }

        is Action.RequestDock -> return reduceRequestDock(action.action, state)

        else -> return null
    }
}

private fun SessionFeature.pendingRequestsLoaded(
    permissions: List<OpenCodePermissionRequest>,
    forms: List<OpenCodeFormRequest>,
    state: State,
): Reduced<State, Action> {
    var next = state.withRequestLoadAttempt(null).withRequestFailureAwaitingReconnect(false)
    val oldIDs = next.reviewRequestIDs.toSet()
    val docked = next.dockRequestID
    val completedIDs = next.completedRequestIDs
    val protectedIDs = next.respondingRequestIDs + next.requestErrors.keys
    val coveredOwners = next.familySessions.map { it.id }.toSet() + "global"
    fun <R> reconcile(fetched: List<R>, requests: List<R>, id: (R) -> String, sessionID: (R) -> String): List<R> {
        val live = fetched.filter { id(it) !in completedIDs }
        val reconciled = live + requests.filter { previous ->
            (id(previous) in protectedIDs || sessionID(previous) !in coveredOwners) && live.none { id(it) == id(previous) }
        }
        return if (requests != reconciled) reconciled else requests
    }
    next = next.updateRequestDock { dock ->
        dock.copy(
            permissionRequests = reconcile(permissions, dock.permissionRequests, { it.id }, { it.sessionID }),
            formRequests = reconcile(forms, dock.formRequests, { it.id }, { it.sessionID }),
            requestLoadError = null,
        )
    }
    next = next.updateRequestDock { dock ->
        var answers = dock.formAnswers
        for (form in dock.formRequests) if (answers[form.id] == null) answers = answers + (form.id to form.defaultAnswers)
        if (answers === dock.formAnswers) dock else dock.copy(formAnswers = answers)
    }
    // Answered elsewhere or interrupted: the dock moves on.
    next = next.pruneRequests(keepingSelection = next.respondingRequestIDs)
    val liveReviewIDs = next.reviewRequestIDs.toSet()
    // What's docked stays until it's answered or the reader picks another; newer requests queue behind it.
    if (docked != null && docked in liveReviewIDs && next.dockRequestID != docked) next = next.withSelectedRequestID(docked)
    // Requests dock in the composer's place; nothing opens on its own. A catch-up card's waiting
    // requests were already there.
    val announcesArrivals = next.readsOnView || next.hasLoadedPendingRequests
    if (!next.readsOnView) next = next.copy(hasLoadedPendingRequests = true)
    // A call's bar takes the dock's place and has its own cues.
    if (announcesArrivals && !next.callOwnsAudio && !next.isCovered && sceneWindow.isFront && (liveReviewIDs - oldIDs).isNotEmpty()) {
        haptics.play(Haptic.attention)
    }
    return Reduced(next)
}

private fun SessionFeature.pendingRequestsFailed(error: OpenCodeClientError, state: State): Reduced<State, Action> {
    val attempt = state.requestLoadAttempt
    var next = state.withRequestLoadAttempt(null)
    if (!next.requestsConnectionIsAvailable) return Reduced(next)
    when (attempt) {
        RequestDockFeature.State.LoadAttempt.initial -> {
            // A first failure while connected may be a warm-up hiccup. Retry once before treating it
            // as settled; a reconnect also retries (below).
            next = next.withRequestFailureAwaitingReconnect(true)
            if (!sessionTimers.requestRetry) return Reduced(next)
            val clock = clock
            return Reduced(
                next,
                Effect.run<Action> { send ->
                    clock.sleep(2.seconds)
                    send(Action.requestsAutoRetry)
                }.cancellable(SessionFeature.CancelID.RequestsRetry, cancelInFlight = true),
            )
        }
        RequestDockFeature.State.LoadAttempt.reconnect, RequestDockFeature.State.LoadAttempt.manual -> {
            next = next.withRequestFailureAwaitingReconnect(true).withRequestLoadError(error.message)
        }
        // Periodic projection failures stay quiet. Keep a previously settled failure visible until a
        // successful authoritative snapshot lands.
        null -> Unit
    }
    return Reduced(next)
}

private fun SessionFeature.reduceRequestDock(action: RequestDockFeature.Action, state: State): Reduced<State, Action> = when (action) {
    is RequestDockFeature.Action.ReviewRequestSelected,
    is RequestDockFeature.Action.QuestionPageChanged,
    is RequestDockFeature.Action.FormValueChanged,
    -> Reduced(state)

    is RequestDockFeature.Action.SheetTapped -> {
        if (state.formRequests.none { it.id == action.id }) {
            Reduced(state)
        } else {
            Reduced(state.withSelectedRequestID(action.id).copy(activePicker = State.ActivePicker.requests))
        }
    }

    RequestDockFeature.Action.RefreshTapped -> {
        val next = state.withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.manual).withRequestLoadError(null)
        Reduced(next, refreshRequests(next))
    }

    RequestDockFeature.Action.AutoRetry -> {
        if (!state.requestsConnectionIsAvailable || state.requestLoadAttempt != null || !state.requestFailureAwaitingReconnect) {
            Reduced(state)
        } else {
            startPendingWork(state, RequestDockFeature.State.LoadAttempt.reconnect)
        }
    }

    is RequestDockFeature.Action.PermissionReplyTapped -> replyPermission(action.id, action.reply, action.message, state)

    is RequestDockFeature.Action.FormSubmitTapped -> submitForm(action.id, state)

    is RequestDockFeature.Action.FormCancelTapped -> cancelForm(action.id, action.message, state)

    RequestDockFeature.Action.DismissTapped -> dismissRequest(state)

    is RequestDockFeature.Action.BarSent -> {
        if (!state.isAnsweringRequest || state.dockRequestID != action.requestID) Reduced(state) else sendRequestBar(state)
    }

    RequestDockFeature.Action.CustomMarkTapped -> {
        // A choice changed: your own words became the answer, or were set aside.
        val (next, changed) = state.toggleCustomAnswer()
        if (changed) haptics.play(Haptic.selection)
        Reduced(next)
    }

    is RequestDockFeature.Action.ResponseFinished -> {
        val id = action.requestID
        val sessionID = action.sessionID
        val wasResponding = id in state.respondingRequestIDs
        var next = state.withRespondingRequestIDs(state.respondingRequestIDs - id)
        val requestIsKnown = next.permissionRequests.any { it.id == id && it.sessionID == sessionID } ||
            next.formRequests.any { it.id == id && it.sessionID == sessionID }
        if (!(wasResponding || requestIsKnown || sessionID == "global" || next.familySessions.any { it.id == sessionID })) {
            Reduced(next)
        } else {
            val error = action.error
            if (error == null) haptics.play(Haptic.success) else haptics.play(Haptic.error)
            next = if (error != null) {
                next.withRequestErrors(next.requestErrors + (id to error.message)).withFailedReplyIDs(next.failedReplyIDs + id)
            } else {
                next.withCompletedRequestIDs(next.completedRequestIDs + id)
                    .pruneRequests(removing = { it.id == id }, keepingSelection = next.respondingRequestIDs)
            }
            Reduced(next, refreshRequests(next))
        }
    }
}

private fun canRespond(id: String, state: State): Boolean =
    id !in state.completedRequestIDs && id !in state.respondingRequestIDs

private fun SessionFeature.replyPermission(
    id: String,
    reply: OpenCodePermissionReply,
    message: String?,
    state: State,
): Reduced<State, Action> {
    val request = state.permissionRequests.firstOrNull { it.id == id } ?: return Reduced(state)
    if (!canRespond(id, state) || (reply == OpenCodePermissionReply.Always && request.save.isNullOrEmpty())) return Reduced(state)
    // Only a denial carries a reason.
    val reason = if (reply == OpenCodePermissionReply.Reject) message?.trim()?.ifEmpty { null } else null
    val client = openCodeClient
    return respond(id, request.sessionID, state) { server ->
        client.replyPermission(server, request.sessionID, id, reply, reason)
    }
}

private fun SessionFeature.submitForm(id: String, state: State): Reduced<State, Action> {
    val form = state.formRequests.firstOrNull { it.id == id } ?: return Reduced(state)
    if (!canRespond(id, state)) return Reduced(state)
    val validated = form.validatedAnswer(state.formAnswers[id] ?: emptyMap())
    val answer = validated.getOrNull()
    if (answer == null) {
        val failure = validated.exceptionOrNull() as? OpenCodeFormRequest.ValidationFailure ?: return Reduced(state)
        haptics.play(Haptic.error)
        return Reduced(
            state.withRequestErrors(state.requestErrors + (id to failure.error.message))
                .withQuestionPageIndices(state.questionPageIndices + (id to failure.error.fieldIndex)),
        )
    }
    val directory = state.effectiveDirectory
    val client = openCodeClient
    return respond(id, form.sessionID, state) { server ->
        // A `"global"` (MCP elicitation) form is answered in the session's location.
        PromptContext.formLocationDirectory.withValue(directory) { client.replyForm(server, form.sessionID, id, answer) }
    }
}

private fun SessionFeature.cancelForm(id: String, message: String?, state: State): Reduced<State, Action> {
    val form = state.formRequests.firstOrNull { it.id == id } ?: return Reduced(state)
    if (!canRespond(id, state)) return Reduced(state)
    val reason = message?.trim()?.ifEmpty { null }
    val directory = state.effectiveDirectory
    val client = openCodeClient
    return respond(id, form.sessionID, state) { server ->
        PromptContext.formLocationDirectory.withValue(directory) { client.cancelForm(server, form.sessionID, id, reason) }
    }
}

private fun SessionFeature.dismissRequest(state: State): Reduced<State, Action> {
    val id = state.dockRequestID ?: return Reduced(state)
    if (state.respondingRequestIDs.isNotEmpty()) return Reduced(state)
    // A held mic's words are in its bar until it stops.
    val reason = if (state.isAnsweringRequest) state.dictationHold?.text ?: state.draft else null
    var next = state
    var stop: Effect<Action> = Effect.none()
    if (next.isAnsweringRequest && next.hasLiveDictation) {
        val stopped = stopDictation(next)
        next = stopped.state
        stop = stopped.effect
    }
    val responded = if (next.formRequests.any { it.id == id }) {
        cancelForm(id, reason, next)
    } else {
        replyPermission(id, OpenCodePermissionReply.Reject, reason, next)
    }
    return Reduced(responded.state, Effect.merge(stop, responded.effect))
}

/** Sends the bar while it answers a request: the next page, the answer, or Deny/Dismiss with its words as what to do instead. */
internal fun SessionFeature.sendRequestBar(state: State): Reduced<State, Action> {
    val id = state.dockRequestID ?: return Reduced(state)
    val bar = state.requestBar ?: return Reduced(state)
    if (state.respondingRequestIDs.isNotEmpty()) return Reduced(state)
    return when (bar) {
        is RequestBar.Decline -> dismissRequest(state)
        is RequestBar.Answer, is RequestBar.Choose -> {
            if (!bar.isReady) return Reduced(state)
            val page = state.dockedFormPage
            if (!bar.isLast && page != null) {
                return Reduced(state.copy(requestDock = state.requestDock.changeQuestionPage(id, page.page + 1)))
            }
            submitForm(id, state)
        }
    }
}

/**
 * Takes the composer for a request, moves the bar's words between pages and requests, and gives
 * the composer back. A recording into an answer never outlives it: it ends with the page or
 * request it was for.
 */
@Suppress("UNUSED_PARAMETER")
internal fun SessionFeature.reconcileAnswering(old: AnswerContext, state: State): Reduced<State, Action> {
    val new = state.answerContext
    if (new.wants && !state.isAnsweringRequest) return Reduced(state.beginAnswering())
    if (!state.isAnsweringRequest) return Reduced(state)
    if (!new.wants) {
        var next = state
        var stop: Effect<Action> = Effect.none()
        if (next.hasLiveDictation) {
            val stopped = stopDictation(next)
            next = stopped.state
            stop = stopped.effect
        }
        next = next.endAnswering()
        // A message that was waiting on its uploads goes now, if they're done.
        val sent = sendIfUploadsFinished(next)
        return Reduced(sent.state, Effect.merge(stop, sent.effect))
    }
    // Compared with what the bar shows, not the last context: a reduce nested in another sees the same change twice.
    if (state.answerShownTarget == new.target) return Reduced(state)
    var next = state
    // A hands-free recording's words so far stay with the page they were said on.
    val hold = next.dictationHold
    if (hold != null && hold.isLocked) next = next.withDraft(hold.text)
    var stop: Effect<Action> = Effect.none()
    if (next.hasLiveDictation) {
        val stopped = stopDictation(next)
        next = stopped.state
        stop = stopped.effect
    }
    return Reduced(next.moveAnswer(), stop)
}

/**
 * Marks a request as being answered, then reports how the reply settled. Callers own validation;
 * `requestResponseFinished` owns the outcome.
 */
private fun respond(
    requestID: String,
    sessionID: String,
    state: State,
    reply: suspend (OpenCodeServer) -> Unit,
): Reduced<State, Action> {
    val next = state.withRequestErrors(state.requestErrors - requestID)
        .withFailedReplyIDs(state.failedReplyIDs - requestID)
        .withRespondingRequestIDs(state.respondingRequestIDs + requestID)
    val server = next.server
    return Reduced(
        next,
        Effect.run { send ->
            val error = try {
                reply(server)
                null
            } catch (error: OpenCodeClientError) {
                error
            }
            send(Action.requestResponseFinished(sessionID, requestID, error))
        },
    )
}
