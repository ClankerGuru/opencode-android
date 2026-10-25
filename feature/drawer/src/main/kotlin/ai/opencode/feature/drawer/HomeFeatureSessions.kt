package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.drawer.bridge.SessionDelegate
import ai.opencode.feature.drawer.bridge.SessionDetail
import ai.opencode.feature.drawer.bridge.SessionScreenEvent
import ai.opencode.feature.drawer.bridge.SessionScreenMessage
import ai.opencode.server.OpenCodeLiveContentEvent

/*
 * Port of iOS `HomeFeature+Sessions.swift`: what the open session and pushed subagents tell Home
 * (sends, receipts, family reads, model picks, delegate requests), and how Home forwards server
 * events to the screens that care.
 *
 * Screens report through [SessionScreenEvent] and hear back through [SessionScreenMessage]
 * (drawer-local stand-ins until `:feature:session` ports `SessionFeature`'s actions). Voice-call
 * coordination (`startCallOnTop`, `notifyVoice…`, `retargetCallToTop`) waits for `:feature:voice`.
 */


internal fun HomeFeature.reduceSubagent(state: State, id: Int, event: SessionScreenEvent): HomeReduced {
    val now = { dependency(DateClient).now() }
    return when (event) {
        // The drawer lists the root, which is where the activity shows.
        SessionScreenEvent.SendAccepted -> Reduced(state.notingScreenSendIfAccepted(state.subagentStack.firstOrNull { it.navigationID == id }, now).first)
        is SessionScreenEvent.ModelApplied -> Reduced(state.notingModel(event.model, event.sessionID))
        is SessionScreenEvent.ViewAcknowledged -> Reduced(state.notingViewAcknowledged(event.sessionID, event.idle))
        is SessionScreenEvent.VisibilityChanged -> Reduced(state.updatingSubagent(id) { it.copy(isVisible = event.isVisible) })
        is SessionScreenEvent.DraftChanged -> Reduced(state.updatingSubagent(id) { it.copy(draft = event.draft) })
        is SessionScreenEvent.Task -> {
            val detail = state.subagentStack.firstOrNull { it.navigationID == id }
            val sessionID = detail?.session?.id
            if (detail == null || detail.initialTranscriptRequestIsExternal || sessionID == null) Reduced(state)
            else cancelBackgroundTranscriptReads(state, setOf(sessionID))
        }
        is SessionScreenEvent.RefreshRequested -> {
            if (event.reason !in foregroundRefreshReasons) return Reduced(state)
            val sessionID = state.subagentStack.firstOrNull { it.navigationID == id }?.session?.id
            cancelBackgroundTranscriptReads(state, setOfNotNull(sessionID))
        }
        is SessionScreenEvent.Delegate -> reduceSessionDetail(state, SessionScreenEvent.Delegate(event.delegate))
        else -> Reduced(state)
    }
}

internal fun HomeFeature.reduceSessionDetail(state: State, event: SessionScreenEvent): HomeReduced {
    val now = { dependency(DateClient).now() }
    when (event) {
        SessionScreenEvent.SendAccepted -> {
            // The session reducer has already run: an accepted send cleared the draft and is awaiting
            // its reply. Recents lists roots.
            val (s, sessionID) = state.notingScreenSendIfAccepted(state.sessionDetail, now)
            // iOS donates on `.sendTapped` only, not the browser's `.askSent`; the stand-in event
            // carries both. TODO(merge) round 2b / `:feature:session`: match `.sendTapped`.
            val session = sessionID?.let { id -> s.sessions.firstOrNull { it.id == id } }
            if (session != null) dependency(ShareInboxClient).donateSuggestion(s.server.persistenceID, session.id, session.title)
            return Reduced(s)
        }

        is SessionScreenEvent.ViewAcknowledged -> return Reduced(state.notingViewAcknowledged(event.sessionID, event.idle))

        is SessionScreenEvent.DraftChanged -> return Reduced(state.updatingDetail { it.copy(draft = event.draft) })

        is SessionScreenEvent.FamilyPicked -> {
            // Save the outgoing member before the screen selects another one (iOS composition).
            var s = state.rememberingSession()
            val detail = s.sessionDetail
            val snapshot = s.sessionSnapshots[event.sessionID]
            if (detail != null && detail.session?.id == event.sessionID && snapshot != null &&
                snapshot.session.directory == detail.session.directory
            ) {
                s = s.copy(sessionDetail = snapshot.restoreSessionData(detail))
            }
            if (s.sessionDetail?.readsOnView == true) {
                s.sessionDetail?.familySessions?.firstOrNull { it.id == event.sessionID }?.let { s = s.markingReadLocally(it) }
            }
            return Reduced(s)
        }

        is SessionScreenEvent.FamilyLoaded -> {
            val s = state.updatingDetail { it.copy(familySessions = event.family, familyReadAt = dependency(DateClient).now()) }
            return Reduced(s.syncingBusyAncestryFromFamily())
        }

        is SessionScreenEvent.FamilySessionDeleted -> return Reduced(state.syncingBusyAncestryFromFamily(event.sessionID))

        SessionScreenEvent.Task -> {
            val detail = state.sessionDetail ?: return Reduced(state)
            val sessionID = detail.session?.id
            if (detail.initialTranscriptRequestIsExternal || sessionID == null) return Reduced(state)
            return cancelBackgroundTranscriptReads(state, setOf(sessionID))
        }

        is SessionScreenEvent.RefreshRequested -> {
            if (event.reason !in foregroundRefreshReasons) return Reduced(state)
            return cancelBackgroundTranscriptReads(state, setOfNotNull(state.sessionDetail?.session?.id))
        }

        is SessionScreenEvent.VisibilityChanged -> {
            var s = state.updatingDetail { it.copy(isVisible = event.isVisible) }
            if (event.isVisible || s.selectedTranscriptFetch == null) return Reduced(s)
            s = s.copy(selectedTranscriptFetch = null)
                .updatingDetail { it.copy(initialTranscriptRequestIsExternal = false, isLoading = false) }
            return Reduced(s, Effect.cancel(HomeFeature.CancelID.SelectedTranscript))
        }

        is SessionScreenEvent.AcceptedTranscriptUpdated -> {
            val s = state.updatingDetail { if (it.session?.id == event.sessionID) it.copy(items = event.items) else it }
                .publishingTranscript(event.sessionID, event.items)
                .persistingTranscriptCache(event.sessionID)
            return Reduced(s, prefetchTranscripts(s))
        }

        is SessionScreenEvent.ModelApplied -> return Reduced(state.notingModel(event.model, event.sessionID))

        is SessionScreenEvent.SessionCreated -> {
            val session = event.session
            // The event stream may have listed it already.
            val s = state.copy(sessions = listOf(session) + state.sessions.filterNot { it.id == session.id })
                .updatingDetail { if (it.isDraft) it.copy(session = session, familyRootID = session.parentID ?: session.id, familySessions = listOf(session)) else it }
                .updatingSessionReadDates { it + (state.sessionKey(session.id) to session.updatedAt) }
                .updatingLastViewedSessionIDs { it + (state.server.persistenceID to session.id) }
            // `core` persists the snapshot after this action.
            return Reduced(s)
        }

        is SessionScreenEvent.Delegate -> return reduceSessionDelegate(state, event.delegate)
    }
}

private fun HomeFeature.reduceSessionDelegate(state: State, delegate: SessionDelegate): HomeReduced = when (delegate) {
    is SessionDelegate.OpenSession -> {
        val session = delegate.session
        val index = state.sessions.indexOfFirst { it.id == session.id }
        val s = if (index >= 0) {
            // The event stream may have listed it before its model was set.
            session.model?.let { model -> state.updatingSession(session.id) { it.copy(model = model) } } ?: state
        } else {
            state.copy(sessions = listOf(session) + state.sessions)
        }
        Reduced(s.copy(nextMoveReason = "the voice agent switched"), Effect.send(Action.SessionTapped(session.id)))
    }
    is SessionDelegate.OpenSubagent -> {
        // A Catch Up card swaps its family in place: its swipe is the deck's, so nothing is pushed over it.
        if (state.catchUp != null) {
            Reduced(state, Effect.send(Action.SessionDetailEvent(SessionScreenEvent.FamilyPicked(delegate.session.id))))
        } else if (state.topDetail?.session?.id == delegate.session.id) {
            Reduced(state)
        } else {
            cancelBackgroundTranscriptReads(state.pushingSubagent(delegate.session), setOf(delegate.session.id))
        }
    }
    is SessionDelegate.ResolveSessionLinks -> resolveSessionLinks(state, delegate.ids)
    is SessionDelegate.OpenSessionLink -> openSessionLink(state, delegate.id)
    // TODO(merge) round 2b / `:feature:session`: `startCallOnTop` (the screen's `VoiceFeature`); until then Voice set-up shows Settings.
    SessionDelegate.CallRequested -> Reduced(state.showingSettings())
    is SessionDelegate.TogglePin -> Reduced(state, Effect.send(Action.TogglePin(delegate.sessionID)))
    is SessionDelegate.SetPinned -> {
        // From the voice agent: the session may be outside Recents.
        var s = state
        if (s.sessions.none { it.id == delegate.session.id }) s = s.copy(sessions = listOf(delegate.session) + s.sessions)
        if (s.isPinned(delegate.session.id) == delegate.pinned) {
            Reduced(s)
        } else {
            // No finger on a control: no haptic, unlike `togglePin`.
            val key = s.sessionKey(delegate.session.id)
            Reduced(s.updatingPinnedSessionKeys { keys -> if (delegate.pinned) keys + key else keys.filterNot { it == key } })
        }
    }
    is SessionDelegate.OpenDraft ->
        if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) Reduced(state)
        else Reduced(state.leavingCatchUp().openingDraft(delegate.directory, reason = "the voice agent opened a draft"))
    is SessionDelegate.ToggleMute -> Reduced(state, Effect.send(Action.ToggleMute(delegate.sessionID)))
    is SessionDelegate.KeepDraft -> {
        val drafts = state.sharedDrafts
        drafts[delegate.target] = drafts.getOrDefault(delegate.target) { SharedComposerDraft() }.appending(delegate.draft)
        Reduced(state)
    }
    is SessionDelegate.ToggleRead -> {
        val session = state.sessions.firstOrNull { it.id == delegate.sessionID }
        if (session == null) Reduced(state)
        else Reduced(state, Effect.send(if (state.isUnread(session)) Action.MarkRead(delegate.sessionID) else Action.MarkUnread(delegate.sessionID)))
    }
    is SessionDelegate.DeleteSession -> {
        // The sheet already confirmed; the drawer's deletion path does the rest.
        val session = state.sessions.firstOrNull { it.id == delegate.sessionID }
        if (session == null) Reduced(state)
        else Reduced(state.copy(pendingDeletion = session, deletionError = null), Effect.send(Action.DeleteSessionConfirmed))
    }
    // TODO(merge): Developer Mode trace upload (`traces(.reportProblemTapped)`) is `:devtools`' (n/a on Android).
    SessionDelegate.ReportProblem -> Reduced(state)
}

/** Sends pushed subagents' screens what Home sends the session's, to those [matching] picks. */
internal fun forwardToSubagents(
    state: State,
    message: SessionScreenMessage,
    matching: (SessionDetail) -> Boolean = { true },
): Effect<Action> = Effect.merge(
    state.subagentStack.filter(matching).map { Effect.send(Action.ScreenMessage(it.navigationID, message)) },
)

internal fun forwardToSubagents(state: State, sessionID: String, message: SessionScreenMessage): Effect<Action> =
    forwardToSubagents(state, message) { it.session?.id == sessionID }

/** Sends [message] to the root session detail and every pushed screen matching [matching]. */
internal fun forwardToScreens(
    state: State,
    message: SessionScreenMessage,
    matching: (SessionDetail) -> Boolean = { true },
): Effect<Action> {
    val pushed = forwardToSubagents(state, message, matching)
    val detail = state.sessionDetail
    if (detail == null || !matching(detail)) return pushed
    return Effect.merge(sendToDetail(state, message), pushed)
}

internal fun forwardToSession(state: State, sessionID: String, message: SessionScreenMessage): Effect<Action> =
    forwardToScreens(state, message) { it.session?.id == sessionID }

/** `.send(.sessionDetail(.presented(message)))`. */
internal fun sendToDetail(state: State, message: SessionScreenMessage): Effect<Action> {
    val detail = state.sessionDetail ?: return Effect.none()
    return Effect.send(Action.ScreenMessage(detail.navigationID, message))
}

/** Whether [sessionID] descends from a member of the screen's family by [parents] without being listed in it. */
internal fun joinsUnlisted(screen: SessionDetail, sessionID: String, parents: Map<String, String>): Boolean {
    val members = screen.familySessions.mapTo(HashSet()) { it.id }
    if (sessionID in members) return false
    var id = sessionID
    val seen = hashSetOf(id)
    while (true) {
        val parent = parents[id] ?: return false
        if (!seen.add(parent)) return false
        if (parent in members) return true
        id = parent
    }
}

/**
 * Whether [screen] cares about a family run-state change on [sessionID]: members of its family,
 * sessions descending from it by [parents], or unattributed sessions that may belong to any open
 * family until their ancestry resolves.
 */
internal fun shouldForwardFamilyRunState(
    screen: SessionDetail,
    sessionID: String,
    unattributed: Boolean,
    parents: Map<String, String>,
): Boolean {
    if (screen.session == null) return false
    if (screen.session.id == sessionID || screen.familyRootID == sessionID) return true
    if (screen.familySessions.any { it.id == sessionID }) return true
    if (joinsUnlisted(screen, sessionID, parents)) return true
    return unattributed
}

internal fun forwardToSubagentFamilies(state: State, member: String, message: SessionScreenMessage): Effect<Action> =
    forwardToSubagents(state, message) { screen -> screen.familySessions.any { it.id == member } }

/**
 * A content batch for the screens showing [sessionID] (iOS `notifyVoiceContentBatch`; the call's
 * reply watch waits for `:feature:voice`).
 */
internal fun notifyContentBatch(state: State, sessionID: String, events: List<OpenCodeLiveContentEvent>): Effect<Action> {
    val message = SessionScreenMessage.ContentBatch(sessionID, events.size)
    val pushed = forwardToSubagents(state, sessionID, message)
    if (state.sessionDetail?.session?.id != sessionID) return pushed
    return Effect.merge(sendToDetail(state, message), pushed)
}
