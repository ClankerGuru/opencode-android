package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.model.keepingFirstPrompt
import ai.opencode.server.model.keepingFirstPrompts

// Port of iOS `SessionFeature+Snapshots.swift`: switch snapshots. `SwitchSnapshot` and `State.Refresh`
// stay nested in `SessionFeature.kt` (Kotlin nested types can't live in another file).

/** iOS `SwitchSnapshot.init?(_:)`. */
fun SessionFeature.SwitchSnapshot.Companion.from(state: State): SessionFeature.SwitchSnapshot? {
    val session = state.session ?: return null
    val queued = state.queuedInputs.filter { state.promptStates[it.id]?.admission != State.PromptState.Admission.sent }
    val queuedIDs = queued.map { it.id }.toSet()
    val seenItemIDs: Set<String>
    var hasUnseenItems = false
    val mark = state.newMessages
    val markIndex = mark?.let { m -> state.items.indexOfFirst { it.id == m.itemID } } ?: -1
    val baseline = state.newMessagesBaseline
    if (!state.readsOnView) {
        seenItemIDs = state.catchUpOpeningSeen ?: baseline ?: emptySet()
        hasUnseenItems = mark != null || state.newMessagesUnread
    } else if (markIndex >= 0) {
        seenItemIDs = state.items.subList(0, markIndex).mapTo(HashSet()) { it.id }
        hasUnseenItems = true
    } else if (baseline != null && (baseline.isNotEmpty() || state.newMessagesUnread || state.newMessagesBaselineTimestamp != null ||
            state.isTranscriptCovered || state.isBackgrounded || state.items.isEmpty())
    ) {
        seenItemIDs = baseline + state.newMessagesWatched
        hasUnseenItems = state.newMessagesUnread
    } else {
        seenItemIDs = state.items.mapTo(HashSet()) { it.id }
    }
    return SessionFeature.SwitchSnapshot(
        session = session,
        family = state.familySessions,
        familyReadAt = state.familyReadAt,
        shells = state.shells,
        permissions = state.permissionRequests,
        forms = state.formRequests,
        formAnswers = state.formAnswers,
        questionPageIndices = state.questionPageIndices,
        collapsedRequests = state.requestDock.collapsedRequestIDs,
        answerDrafts = state.answerDraftsIncludingBar,
        hasLoadedRequests = state.hasLoadedPendingRequests,
        completedRequests = state.completedRequestIDs,
        selectedRequest = state.selectedRequestID,
        queued = queued,
        positions = state.pendingPromptPositions.filterKeys { it in queuedIDs },
        models = state.models,
        currentModel = state.currentModel,
        pendingModel = state.pendingModel,
        queuedPicks = state.queuedPicks,
        context = state.contextUsage,
        movableWorkKey = state.movableWorkKey,
        // A claim no send has acted on yet isn't a move.
        movedWorkKeys = state.movedWorkKeys - state.autoBackgroundClaims.values.toSet(),
        seenItemIDs = seenItemIDs,
        hasUnseenItems = hasUnseenItems,
    )
}

/** iOS `SwitchSnapshot.restore(into:)`. */
fun SessionFeature.SwitchSnapshot.restore(state: State): State {
    var next = state
    val selected = next.session
    if (selected != null && session.updatedAt >= selected.updatedAt) {
        next = if (session.updatedAt > selected.updatedAt) {
            next.copy(session = session.keepingFirstPrompt(selected))
        } else {
            next.copy(session = selected.copy(revertMessageID = session.revertMessageID))
        }
    }
    next = next.copy(familySessions = family.keepingFirstPrompts(next.familySessions), familyReadAt = familyReadAt, hasLoadedPendingRequests = hasLoadedRequests)
    next = next.updateRequestDock {
        it.copy(
            permissionRequests = permissions,
            formRequests = forms,
            formAnswers = forms.associate { form -> form.id to (formAnswers[form.id] ?: form.defaultAnswers) },
            questionPageIndices = questionPageIndices,
            collapsedRequestIDs = collapsedRequests,
            answerDrafts = if (next.isAnsweringRequest) it.answerDrafts else answerDrafts,
            completedRequestIDs = completedRequests,
            // The pin comes back only while its request is still waiting.
            selectedRequestID = selectedRequest,
        )
    }
    next = next.pruneRequests()
    next = restoreSessionData(next)
    // A waiting request takes the composer from the first frame: the screen, a Catch Up card, or
    // the session Catch Up returns to.
    if (next.wantsRequestDock && !next.isAnsweringRequest) next = next.beginAnswering()
    return next
}

/** iOS `SwitchSnapshot.pruneRequests(removing:)`. */
fun SessionFeature.SwitchSnapshot.pruneRequests(
    removing: (PendingRequest) -> Boolean = { false },
): SessionFeature.SwitchSnapshot {
    val permissions = permissions.filterNot { removing(PendingRequest.Permission(it)) }
    val forms = forms.filterNot { removing(PendingRequest.Form(it)) }
    val live = (permissions.map { it.id } + forms.map { it.id }).toSet()
    return copy(
        permissions = permissions,
        forms = forms,
        formAnswers = SessionFeature.prune(formAnswers, live),
        questionPageIndices = SessionFeature.prune(questionPageIndices, live),
        collapsedRequests = collapsedRequests.intersect(live),
        answerDrafts = SessionFeature.pruneAnswerDrafts(answerDrafts, live),
        selectedRequest = selectedRequest?.takeIf { it in live },
    )
}

/** iOS `SwitchSnapshot.restoreSessionData(into:)`: family navigation keeps the family's discovery and requests. */
fun SessionFeature.SwitchSnapshot.restoreSessionData(state: State): State {
    val promoted = state.promotedPromptIDs.toSet() + SessionFeature.promptIDs(state.items)
    // The outbox is the source of truth for this phone's own sends: laid over the snapshot.
    val local = state.queuedInputs.filter { state.promptStates[it.id] != null }
    val localIDs = local.map { it.id }.toSet()
    val positions = positions.filterKeys { it !in promoted && it !in localIDs } +
        state.pendingPromptPositions.filterKeys { it in localIDs }
    var next = state.copy(
        shells = shells,
        restoredShellIDs = shells.map { it.id }.toSet(),
        queuedInputs = queued.filter { it.id !in promoted && it.id !in localIDs } + local,
        pendingPromptPositions = positions,
        models = models,
        currentModel = currentModel,
        pendingModel = pendingModel,
        queuedPicks = queuedPicks + state.queuedPicks,
        contextUsage = context,
        movableWorkKey = movableWorkKey,
        movedWorkKeys = movedWorkKeys,
    )
    next = next.syncVoiceModel()
    return next.copy(sessionSheet = next.sessionSheet?.copy(pendingModel = pendingModel))
}
