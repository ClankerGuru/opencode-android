package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.sessionComposer
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.system.share.ShareCredentials
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.voice.VoiceSessionBoard
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.TranscriptCachePersistence
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

/*
 * Port of iOS `HomeFeature+Connection.swift`: what Home does as the connection comes and goes
 * (before `HomeConnectionFeature` settles the same action), the authoritative snapshot's
 * reconciliation into the list and the open screens, the active-ancestry merge, and the attention
 * read.
 */

internal fun HomeFeature.reduceConnection(state: State, action: Action): HomeReduced = when (action) {
    Action.Task -> {
        LabMarks.mark("home_task_started")
        val s = if (state.isConnected) state.initializingInbox() else state
        val load = if (s.isConnected) Reduced(s, Effect.merge(refreshAttention(s), resolveActiveAncestry(s))) else refreshSnapshot(s)
        // The call's and the recording's ongoing-notification buttons.
        val voiceLiveActivity = dependency(VoiceLiveActivityClient)
        val dictationLiveActivity = dependency(DictationLiveActivityClient)
        Reduced(
            load.state,
            Effect.merge(
                load.effect,
                Effect.run<Action> { send -> voiceLiveActivity.commands().collect { send(Action.VoiceCallCommandReceived(it)) } }
                    .cancellable(HomeFeature.CancelID.VoiceCallCommands, cancelInFlight = true),
                Effect.run<Action> { send -> dictationLiveActivity.commands().collect { send(Action.DictationCommandReceived(it)) } }
                    .cancellable(HomeFeature.CancelID.DictationCommands, cancelInFlight = true),
            ),
        )
    }

    Action.TranscriptCacheFlushRequested -> {
        // Leaving the app is when the share sheet becomes reachable: it offers these sessions and
        // sends with this server's credentials.
        val shareInbox = dependency(ShareInboxClient)
        shareInbox.publishTargets(state.shareTargets)
        shareInbox.publishCredentials(ShareCredentials(state.server), true)
        val persistence = dependency(TranscriptCachePersistence)
        Reduced(state, Effect.merge(Effect.run { persistence.flush() }, flushPersistedComposer(state)))
    }

    Action.SceneEnteredBackground -> Reduced(state, publishAppBadge(state))

    is Action.ServerConnected -> {
        // The stream started before any rename since; the name on screen wins.
        val server = action.server.copy(name = state.server.name)
        val s = state.unsettlingNotices().withServer(server)
        // The share sheet offers this server's sessions and sends with its credentials, as soon
        // as it's connected.
        dependency(ShareInboxClient).publishTargets(s.shareTargets)
        Reduced(
            s,
            Effect.merge(
                Effect.send(Action.ServerEvent(OpenCodeServerEvent.Connected)),
                sendToDetail(s, SessionFeature.Action.EventStreamConnected(server)),
                forwardToSubagents(s, SessionFeature.Action.EventStreamConnected(server)),
                publishShareCredentials(server),
            ),
        )
    }

    is Action.EventStreamFailed -> {
        val s = state.unsettlingNotices()
        Reduced(s, requestsDisconnected(s))
    }

    Action.ConnectionLost -> {
        val s = state.unsettlingNotices()
        Reduced(
            s,
            Effect.merge(
                Effect.cancel(HomeFeature.CancelID.Refresh),
                Effect.cancel(HomeFeature.CancelID.ActiveAncestry),
                Effect.cancel(HomeFeature.CancelID.Attention),
                Effect.cancel(HomeFeature.CancelID.SnapshotRetry),
                requestsDisconnected(s),
            ),
        )
    }

    Action.SnapshotFailed -> {
        if (state.isEventStreamLive) {
            Reduced(state)
        } else {
            val s = state.unsettlingNotices()
            Reduced(s, requestsDisconnected(s))
        }
    }

    Action.SnapshotRetryRequested -> refreshSnapshot(state)

    Action.LastKnownActivityExpired -> Reduced(state)

    Action.NetworkRerouted ->
        if (!state.isStreaming || state.credentialsRejected) Reduced(state) else refreshSnapshot(state.unsettlingNotices())

    Action.RetryConnectionTapped ->
        if (state.credentialsRejected) Reduced(state) else refreshSnapshot(state.unsettlingNotices())

    is Action.SnapshotRefreshed -> snapshotRefreshed(state, action)

    is Action.ActiveAncestryResolved -> activeAncestryResolved(state, action.parents)

    is Action.AttentionRefreshed -> {
        val sessionIDs = action.sessionIDs
        var s = state.copy(attentionSessionIDs = sessionIDs)
        // The read the list started: from here on, changes are news.
        if (s.noticeReadiness == State.NoticeReadiness.listed) s = s.copy(noticeReadiness = State.NoticeReadiness.settled)
        var snapshots = s.sessionSnapshots
        for ((id, snapshot) in s.sessionSnapshots) {
            val rootID = snapshot.family.firstOrNull { it.parentID == null }?.id ?: snapshot.session.parentID ?: id
            if (rootID in sessionIDs) continue
            snapshots = snapshots + (id to snapshot.pruneRequests { it is PendingRequest.Permission || it.sessionID != "global" })
        }
        Reduced(if (snapshots == s.sessionSnapshots) s else s.copy(sessionSnapshots = snapshots))
    }

    else -> Reduced(state)
}

private fun HomeFeature.snapshotRefreshed(state: State, action: Action.SnapshotRefreshed): HomeReduced {
    var s = state
    if (s.noticeReadiness == State.NoticeReadiness.unsettled) s = s.copy(noticeReadiness = State.NoticeReadiness.listed)
    val wasConnected = s.isConnected
    val previousAttentionDirectories = HomeFeature.attentionDirectories(s)
    val incoming = action.snapshot
    val incomingSessionIDs = incoming.sessions.mapTo(HashSet()) { it.id }
    s = s.copy(recentlyDeletedIDs = s.recentlyDeletedIDs.filterTo(LinkedHashSet()) { it in incomingSessionIDs })
    val recentlyDeleted = s.recentlyDeletedIDs
    // Runs that started or ended after the snapshot's active read.
    val readActiveIDs = incoming.activeSessionIDs
    val runChanges = s.runChangesDuringRefresh
    val snapshotActive = LinkedHashSet(incoming.activeSessionIDs)
    for ((sessionID, isRunning) in runChanges) {
        if (isRunning) snapshotActive += sessionID else snapshotActive -= sessionID
    }
    s = s.copy(runChangesDuringRefresh = emptyMap())
    snapshotActive.removeAll(recentlyDeleted)
    val previousUpdatedAt = s.sessions.associate { it.id to it.updatedAt }
    val localActivity = s.localActivity.toMutableMap()
    val locallyViewed = s.locallyViewedMilliseconds.toMutableMap()
    val snapshotSessions: List<OpenCodeSession> = incoming.sessions.filter { it.id !in recentlyDeleted }.map { listed ->
        var session = listed
        localActivity[session.id]?.let { local ->
            if (session.updatedAt > local.serverUpdatedAt) {
                localActivity -= session.id
            } else {
                session = session.copy(updatedAt = local.sentAt)
            }
        }
        val local = locallyViewed[session.id] ?: return@map session
        if ((session.viewedMilliseconds ?: -1) >= local) {
            locallyViewed -= session.id
        } else {
            session = session.copy(viewedMilliseconds = local)
        }
        session
    }
    s = s.copy(
        localActivity = localActivity,
        locallyViewedMilliseconds = locallyViewed,
        projects = incoming.projects,
        snapshotLoaded = true,
    )
    // The list is one page of the newest roots, not the whole server. Whatever is open or pinned
    // and fell off that page is unknown, not gone (deletion arrives as its own event), so keep the
    // last copy instead of kicking the reader to a draft or silently dropping a pin.
    val openRootID = s.sessionDetail?.familyRootID
    val carried = s.sessions.filter { session ->
        session.id !in incomingSessionIDs && session.id !in recentlyDeleted && (session.id == openRootID || s.isPinned(session.id))
    }
    s = s.copy(sessions = snapshotSessions + carried, activeSessionIDs = snapshotActive)
    var parents = s.activeSessionParents + (incoming.activeSessionParents ?: emptyMap())
    if (parents.size > HomeFeature.parentLinkLimit) parents = SessionActivity.ancestry(s.activeSessionIDs, parents)
    s = s.copy(activeSessionParents = parents)
    // A run that started mid-request after the active read may belong to a subagent whose
    // ancestry that request didn't look up.
    val knownParents = if (runChanges.isEmpty()) emptyMap() else s.knownParents
    val unattributed = runChanges.any { (sessionID, isRunning) ->
        isRunning && sessionID !in readActiveIDs && !s.isAttributed(sessionID, knownParents)
    }
    if (s.sessionDetail?.session != null) s = s.updatingDetail { it.copy(activeFamilySessionIDs = snapshotActive) }
    s = s.updatingSubagents { it.copy(activeFamilySessionIDs = snapshotActive) }
    val subagentRuns = Effect.merge(
        s.subagentStack.mapNotNull { child ->
            val childID = child.session?.id ?: return@mapNotNull null
            val running = childID in snapshotActive
            Effect.merge(
                if (child.isExternallyActive == running) Effect.none()
                else Effect.send(Action.SubagentStack(child.navigationID, SessionFeature.Action.ExternalRunStateChanged(running))),
                if (wasConnected) Effect.none()
                else Effect.send(Action.SubagentStack(child.navigationID, SessionFeature.Action.RequestsConnectionChanged(true))),
            )
        },
    )
    val shown = s.sessionDetail
    if (shown != null && shown.isVisible && shown.readsOnView) {
        val selectedID = shown.session?.id
        s.sessions.firstOrNull { it.id == selectedID }?.let { s = s.markingReadLocally(it) }
    }
    val openID = s.sessionDetail?.familyRootID
    if (openID != null && s.sessions.none { it.id == openID }) {
        val serverID = s.server.persistenceID
        s = s.updatingLastViewedSessionIDs { it - serverID }.openingDraft(reason = "the session went away on the server")
    } else if (s.sessionDetail?.isDraft == true) {
        // A draft opened before the first snapshot (cold launch, empty cache) has no folders to
        // offer; refresh its choices as the list arrives so the Project picker is never empty on a
        // live server.
        val projects = s.projectsByRecency
        s = s.updatingDetail { it.copy(projects = projects, draftProject = it.draftProject ?: projects.firstOrNull()) }
    }
    val currentUpdatedAt = s.sessions.associate { it.id to it.updatedAt }
    // A cached session that changed is read again rather than dropped, so opening it shows what's
    // new without waiting for the read.
    val changedCached = s.transcriptCache.keys.filter { sessionID ->
        val current = currentUpdatedAt[sessionID] ?: return@filter false
        previousUpdatedAt[sessionID]?.let { it != current } ?: false
    }
    s = s.copy(
        transcriptCache = s.transcriptCache.filterKeys { currentUpdatedAt[it] != null },
        dirtyCachedTranscriptIDs = s.dirtyCachedTranscriptIDs.filter { currentUpdatedAt[it] != null },
    )
    for (sessionID in changedCached.sorted()) s = s.notingDirtyCachedTranscript(sessionID)
    s = s.restoringTranscriptCache()
    s = s.copy(
        connection = s.connection.copy(
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.connected,
            connectionErrorMessage = null,
            snapshotFailures = 0,
            lastKnownActivityDeadline = null,
        ),
    )
    s.server.remember()
    s = s.initializingInbox()
    val needsAttentionRefresh = !wasConnected ||
        !s.isEventStreamLive ||
        s.noticeReadiness != State.NoticeReadiness.settled ||
        HomeFeature.attentionDirectories(s) != previousAttentionDirectories
    val attention = if (needsAttentionRefresh) refreshAttention(s) else Effect.none()
    val ancestry = resolveActiveAncestry(s)
    // A few at a time, behind the open session's own read.
    val drained = drainDirtyCachedTranscripts(s)
    s = drained.state
    val prefetch = prefetchTranscripts(s)
    // A live call hears the list.
    val voiceNotified = notifyVoiceSnapshot(s, snapshotActive)
    s = voiceNotified.state
    var retry: Effect<Action> = Effect.none()
    if (unattributed) {
        val refreshed = refreshSnapshot(s)
        s = refreshed.state
        retry = refreshed.effect
    }
    val voiceSnapshot = Effect.merge(
        voiceNotified.effect,
        Effect.cancel(HomeFeature.CancelID.SnapshotRetry),
        Effect.cancel(HomeFeature.CancelID.LastKnownActivity),
        retry,
    )
    // A whole run can start and finish while disconnected. Even idle → idle must revalidate the
    // transcript, not just the working indicator.
    val detail = s.sessionDetail
    val openSessionID = detail?.session?.id
    if (detail != null && openSessionID != null) {
        val update = snapshotSessions.firstOrNull { it.id == openSessionID }
            ?.let { sendToDetail(s, SessionFeature.Action.SessionSnapshotUpdated(it)) } ?: Effect.none()
        val runState = openSessionID in snapshotActive
        return Reduced(
            s,
            Effect.merge(
                attention,
                ancestry,
                prefetch,
                drained.effect,
                voiceSnapshot,
                subagentRuns,
                update,
                if (detail.isExternallyActive == runState) Effect.none()
                else sendToDetail(s, SessionFeature.Action.ExternalRunStateChanged(runState)),
                if (wasConnected) Effect.none() else sendToDetail(s, SessionFeature.Action.RequestsConnectionChanged(true)),
            ),
        )
    }
    return Reduced(s, Effect.merge(attention, ancestry, prefetch, drained.effect, voiceSnapshot, subagentRuns))
}

private fun HomeFeature.activeAncestryResolved(state: State, resolved: Map<String, String>): HomeReduced {
    val previousDirectories = HomeFeature.attentionDirectories(state)
    var parents = state.activeSessionParents
    var added = false
    for ((childID, parentID) in resolved) {
        if (parents[childID] == parentID) continue
        parents = parents + (childID to parentID)
        added = true
    }
    if (!added) return Reduced(state)
    if (parents.size > HomeFeature.parentLinkLimit) parents = SessionActivity.ancestry(state.activeSessionIDs, parents)
    val s = state.copy(activeSessionParents = parents).persistingSnapshot(dependency(HomeSnapshotDate).now())
    val known = s.knownParents
    val activeIDs = s.activeSessionIDs
    val reread = { screen: SessionFeature.State -> activeIDs.any { joinsUnlisted(screen, it, known) } }
    val pushed = forwardToSubagents(s, SessionFeature.Action.FamilyRefreshRequested, reread)
    val detailReread = s.sessionDetail?.let {
        if (reread(it)) sendToDetail(s, SessionFeature.Action.FamilyRefreshRequested) else Effect.none()
    } ?: Effect.none()
    val attention = if (HomeFeature.attentionDirectories(s) == previousDirectories) Effect.none() else refreshAttention(s)
    return Reduced(s, Effect.merge(attention, pushed, detailReread))
}

/**
 * Debounced and serialized: event bursts (subagent swarms finishing) coalesce into one fetch, and
 * cancel-in-flight guarantees an older response can never land after a newer one and resurrect
 * stale state. Execution events from here until the response are re-applied over it.
 */
internal fun HomeFeature.refreshSnapshot(state: State): HomeReduced {
    val server = state.server
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    return Reduced(
        state,
        Effect.run<Action> { send ->
            clock.sleep(250.milliseconds)
            val snapshot = try {
                LabMarks.mark("home_snapshot_request")
                client.homeSnapshot(server).also { LabMarks.mark("home_snapshot_response") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) return@run
                send(Action.SnapshotFailed)
                return@run
            }
            if (!currentCoroutineContext().isActive) return@run
            send(Action.SnapshotRefreshed(snapshot))
        }.cancellable(HomeFeature.CancelID.Refresh, cancelInFlight = true),
    )
}

/** Upper bound on directories one attention refresh may query (iOS `HomeFeature.attentionDirectoryLimit`). */
internal const val attentionDirectoryLimit = 8

/**
 * Directories that can actually hold a pending request. Every location-scoped read makes the
 * server boot that directory's full service graph (MCP servers, watchers, plugins) and keep it for
 * an hour, so asking about all historical directories on each Home refresh once booted 152 graphs
 * and ~900 child processes. A request can only pend inside a booted graph, and a session blocked
 * on one is still running, so running roots, roots already flagged, and the open session cover it.
 */
internal fun HomeFeature.Companion.attentionDirectories(state: State): List<String> {
    val busy = state.busySessionIDs
    val openID = state.sessionDetail?.session?.id
    val hot = state.sessions
        .filter { it.id in busy || it.id in state.attentionSessionIDs || it.id == openID }
        .sortedByDescending { it.updatedAt }
    val seen = HashSet<String>()
    return hot.mapNotNull { if (seen.add(it.directory)) it.directory else null }.take(attentionDirectoryLimit)
}

internal fun HomeFeature.refreshAttention(state: State): Effect<Action> {
    val server = state.server
    val roots = state.sessions
    val directories = HomeFeature.attentionDirectories(state)
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    val board = dependency(VoiceSessionBoardClient)
    return Effect.run<Action> { send ->
        clock.sleep(150.milliseconds)
        val sessionIDs = try {
            client.homeAttention(server, roots, directories)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (sessionIDs != null) {
            send(Action.AttentionRefreshed(sessionIDs))
            board.attentionRead(server.persistenceID, true)
        } else if (currentCoroutineContext().isActive) {
            // Kept rows stay on screen, but the delegator reports them as unknown.
            board.attentionRead(server.persistenceID, false)
        }
    }.cancellable(HomeFeature.CancelID.Attention, cancelInFlight = true)
}

/**
 * iOS `HomeFeature.flushPersistedComposer(_:)` (`ComposerPersistence.swift`): leaving the app
 * saves every composer on screen that keeps its draft, then writes at once.
 */
internal fun HomeFeature.flushPersistedComposer(state: State): Effect<Action> {
    val persistence = dependency(ComposerPersistence)
    val serverID = state.server.persistenceID
    fun schedule(detail: SessionFeature.State) {
        val composer = detail.sessionComposer
        if (composer.isEmpty) return
        persistence.scheduleDraft(serverID, detail.session?.id, if (detail.session == null) detail.draftProject else null, composer)
    }
    val detail = state.sessionDetail
    if (detail != null && state.catchUp == null && detail.readsOnView) schedule(detail)
    for (child in state.subagentStack) if (child.readsOnView) schedule(child)
    return Effect.run { persistence.flush() }
}

/**
 * The board the call's delegator reads while a call is up; null otherwise, so Home pays nothing
 * for it between calls (iOS `HomeFeature.State.voiceSessionBoard`, `VoiceSessionBoard.swift`).
 */
internal val State.voiceSessionBoard: VoiceSessionBoard?
    get() {
        if (sessionDetail?.voice == null) return null
        val status = statusSnapshot()
        return VoiceSessionBoard.make(
            hasVoiceCall = true,
            serverID = server.persistenceID,
            sessions = sessions,
            busyIDs = status.busy,
            attentionIDs = status.attention,
            unreadIDs = sessions.filter { status.isUnread(it) && !status.isMuted(it.id) }.mapTo(HashSet()) { it.id },
            pinnedSessionKeys = pinnedSessionKeys,
            attentionDirectories = HomeFeature.attentionDirectories(this).toSet(),
            isLive = status.showsActivity,
        )
    }
