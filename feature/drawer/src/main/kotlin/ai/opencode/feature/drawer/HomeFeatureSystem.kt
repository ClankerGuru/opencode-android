package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.bridge.SessionScreenMessage
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.SystemPublication
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.system.share.ShareCredentials
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.system.share.ShareTargets
import ai.opencode.feature.system.share.ShareTargetsPublication
import ai.opencode.feature.voice.VoiceRoutePreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareHandoff
import ai.opencode.feature.system.share.resolvedShareTarget
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession

/*
 * Port of iOS `HomeFeature+System.swift`: the share sheet's credentials, the call's and the
 * recording's ongoing-notification commands, shares arriving, and what Shortcuts, launcher actions
 * and links open (and calls they start), and the list published to the system.
 */

/**
 * The share sheet's credentials, then its sends' "done" push: the callback this phone's
 * notifications use, or none while they're off.
 */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishShareCredentials(server: OpenCodeServer): Effect<Action> {
    val shareInbox = dependency(ShareInboxClient)
    val sessionNotifications = dependency(SessionNotificationsClient)
    return Effect.run {
        shareInbox.publishCredentials(ShareCredentials(server), true)
        val callbackURL = sessionNotifications.callbackURL(server)
        if (!currentCoroutineContext().isActive) return@run
        shareInbox.publishCredentials(ShareCredentials(server, callbackURL), false)
    }
}

internal fun HomeFeature.reduceSystem(state: State, action: Action): HomeReduced = when (action) {
    // TODO(merge) round 2b / `:feature:session`: iOS ends or mutes the open session's call
    // (`.voice(.presented(.endTapped/.muteTapped))`), and with no call clears `voiceCallActivity` and
    // the activity (`voiceLiveActivity.update(nil, nil)`). Needs the session's `VoiceFeature` child.
    is Action.VoiceCallCommandReceived -> Reduced(state)

    // TODO(merge) round 2b / `:feature:session`: iOS stops the recording the command came from (a
    // pushed subagent's first, else the session's: `.dictationStopRequested`), and with none clears
    // `dictationActivity` and the activity. Needs the session's dictation state.
    is Action.DictationCommandReceived -> Reduced(state)

    is Action.SharesReceived -> sharesReceived(state, action.shares)

    is Action.SystemActionOpened ->
        openSystemDestination(state, action.destination, startsCall = action.startsCall, inBackground = action.inBackground)

    is Action.SystemPublishRequested -> {
        // A change publishes through `systemPublication`'s watch.
        if (state.systemVoiceAvailable == action.voiceAvailable) publishToSystem(state)
        Reduced(state.copy(systemVoiceAvailable = action.voiceAvailable))
    }

    else -> Reduced(state)
}

private fun HomeFeature.sharesReceived(state: State, shares: List<ReceivedShare>): HomeReduced {
    if (shares.isEmpty()) return Reduced(state)
    var s = state
    var newestTarget: SharedDraftTarget = SharedDraftTarget.NewSession
    for (share in shares) {
        val resolved = resolvedShareTarget(share.handoff.target, s.sessions)
        val target = SharedDraftTarget(resolved.sessionID)
        val drafts = s.sharedDrafts
        drafts[target] = drafts.getOrDefault(target) { SharedComposerDraft() }.appending(sharedComposerDraft(share, resolved.projectID))
        newestTarget = target
    }
    s = s.leavingCatchUp()
    dependency(HapticsClient).play(Haptic.success)
    // The composer on screen keeps only what's for it once the newest share's session is open;
    // until then it takes nothing it would lose.
    val onScreen = s.sessionDetail?.let { SharedDraftTarget(it.session?.id) }
    s = s.copy(shareNavigationTarget = if (onScreen == newestTarget) null else newestTarget)
    return when (val newest = newestTarget) {
        is SharedDraftTarget.Session -> Reduced(s, Effect.send(Action.SessionTapped(newest.id)))
        SharedDraftTarget.NewSession -> {
            if (s.sessionDetail?.isDraft != true) s = s.openingDraft(reason = "something was shared to the app")
            Reduced(s)
        }
    }
}

/**
 * iOS `SharedComposerDraft.init(_ share: ReceivedShare, projectID:)` (`ShareInbox.swift`): the
 * composer half of a share. Built here because `:feature:composer` can't see `:feature:system`'s
 * [ReceivedShare].
 */
private fun sharedComposerDraft(share: ReceivedShare, projectID: String?): SharedComposerDraft {
    val base = SharedComposerDraft(
        text = share.handoff.text.trim(),
        attachments = share.images.map { OpenCodeOutgoingAttachment(id = it.id.toString(), data = it.data, mime = it.mime) },
        files = share.files,
        projectID = projectID,
    )
    return when (share.handoff.resolvedDelivery) {
        ShareHandoff.Delivery.draft -> base
        ShareHandoff.Delivery.openInComposer -> base.copy(focusesComposer = true)
        ShareHandoff.Delivery.send -> base.copy(send = SharedComposerDraft.Send(share.handoff.messageID, share.handoff.newSessionID))
    }
}

/**
 * Hands the list to Shortcuts, launcher actions and search, never before it has loaded: an empty
 * list then means "not yet", not "none".
 */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishToSystem(state: State) {
    if (!state.snapshotLoaded) return
    dependency(SystemIntegrationClient).publish(state.systemPublication.systemDirectoryEntry, state.systemVoiceAvailable)
}

/** What Shortcuts, launcher actions and search are handed (iOS `HomeFeature.State.systemPublication`). */
internal val State.systemPublication: SystemPublication
    get() = SystemPublication(
        server = server,
        loaded = snapshotLoaded,
        sessions = sessions,
        projects = projects,
        projectsByRecency = projectsByRecency,
        pinnedSessionKeys = pinnedSessionKeys,
        lastViewedSessionIDs = lastViewedSessionIDs,
        voiceAvailable = systemVoiceAvailable,
    )

/**
 * What the share sheet's targets are built from (iOS `shareTargetsPublication`, `ShareInbox.swift`):
 * Catch Up's card isn't the session you'd share to, the one it was opened from is.
 */
internal val State.shareTargetsPublication: ShareTargetsPublication
    get() = ShareTargetsPublication(
        hasConnected = hasConnected,
        server = server,
        sessions = sessions,
        projects = projects,
        pinnedSessionKeys = pinnedSessionKeys,
        lastViewedSessionID = if (catchUp == null) sessionDetail?.session?.id else (catchUp?.returnSessionID ?: lastViewedSessionIDs[server.persistenceID]),
        draftProject = if (catchUp == null && sessionDetail?.isDraft == true) sessionDetail.draftProject else null,
    )

/** Pinned and recent sessions in drawer order, without the drawer's search (iOS `shareTargets`). */
internal val State.shareTargets: ShareTargets get() = shareTargetsPublication.targets()

/**
 * Opens what a shortcut, launcher action, search result or link names, and starts a call there
 * through the call button's own path when asked. `AppFeature` sends this only once the list has loaded.
 */
internal fun HomeFeature.openSystemDestination(
    state: State,
    destination: SystemAction.Destination,
    startsCall: Boolean,
    inBackground: Boolean = false,
): HomeReduced {
    var s = state.copy(settings = null)
    val session: OpenCodeSession? = when (destination) {
        is SystemAction.Destination.Session -> s.sessions.firstOrNull { it.id == destination.id } ?: return Reduced(s)
        SystemAction.Destination.LastViewed ->
            s.lastViewedSessionIDs[s.server.persistenceID]?.let { id -> s.sessions.firstOrNull { it.id == id } }
                ?: s.sessions.filter { it.parentID == null }.maxByOrNull { it.updatedAt }
        is SystemAction.Destination.Draft -> null
    }

    if (session == null) {
        // A draft, as New Session makes one; one already open keeps its text.
        if (s.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(s)
        s = s.withCatchUp(null)
        val projectID = (destination as? SystemAction.Destination.Draft)?.projectID
        val project = projectID?.let { id -> s.projects.firstOrNull { it.id == id } }
        val effects = mutableListOf<Effect<Action>>()
        if (s.sessionDetail?.isDraft != true) {
            s = s.openingDraft(directory = project?.directory, reason = "a shortcut started a new session")
        } else if (project != null && s.sessionDetail?.draftProject != project) {
            // The picker's own path, which also moves a live call's draft.
            // TODO(merge) round 2b / `:feature:session`: send the screen `.projectPicked(project)`.
            s = s.updatingDetail { it.copy(draftProject = project) }
        }
        // A project that's gone is asked for again, never swapped for another.
        if (projectID != null && project == null) s = s.updatingDetail { it.copy(draftProject = null) }
        if (startsCall) {
            val call = startSystemCall(s, inBackground)
            s = call.state
            effects += call.effect
        }
        return Reduced(s, Effect.concatenate(effects))
    }

    if (!startsCall) return Reduced(s, Effect.send(Action.SessionTapped(session.id)))
    // Opened at once rather than after its transcript loads, so the call starts on this session;
    // the session loads its own transcript. A subagent of it on screen gives way to the session itself.
    s = s.withCatchUp(null).copy(pendingSubagentPush = null)
    val opened = openViewedSession(s, session, cachedItems = s.cachedTranscriptItems(session), reason = "a shortcut started a call")
    val call = startSystemCall(opened.state, inBackground)
    return Reduced(call.state, Effect.merge(opened.effect, call.effect))
}

/**
 * The call button's path. A call already live has moved here with the screen; one that ended is
 * cleared first, as the screen does when it changes; without Voice set up, Settings opens where
 * it's set up. In the background no call view appears to start the call (`startIfNeeded`), so
 * it's started here.
 *
 * TODO(merge) round 2b / `:feature:session`: with the screen's `VoiceFeature` child: clear a
 * terminal call, keep a live one, and with Voice available send the screen `.voiceTapped` (then
 * `.voice(.presented(.startIfNeeded))` in the background). Until then a call set up has nothing to
 * start here; without Voice set up (`VoiceRoutePreferences.isAvailable`, iOS
 * `detail.isVoiceAvailable`) the call button's path opens Settings.
 */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
internal fun HomeFeature.startSystemCall(state: State, inBackground: Boolean = false): HomeReduced {
    if (state.sessionDetail == null) return Reduced(state)
    if (VoiceRoutePreferences().isAvailable(state.server)) return Reduced(state)
    return Reduced(state.showingSettings())
}

/** Tells the open screens their requests lost the connection. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.requestsDisconnected(state: State): Effect<Action> = Effect.merge(
    sendToDetail(state, SessionScreenMessage.RequestsConnectionChanged(false)),
    forwardToSubagents(state, SessionScreenMessage.RequestsConnectionChanged(false)),
)
