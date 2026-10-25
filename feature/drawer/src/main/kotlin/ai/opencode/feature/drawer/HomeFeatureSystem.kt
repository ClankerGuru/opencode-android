package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.bridge.ReceivedShare
import ai.opencode.feature.drawer.bridge.SessionDraftState
import ai.opencode.feature.drawer.bridge.SessionScreenMessage
import ai.opencode.feature.drawer.bridge.SharedDraftTarget
import ai.opencode.feature.drawer.bridge.SystemActionDestination
import ai.opencode.feature.settings.whatsnew.whatsNewVoiceAvailable
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
 *
 * TODO(merge): iOS publishes `ShareCredentials(server)` to `shareInbox` at once, then again with
 * `sessionNotifications.callbackURL(server)`. `:feature:system` owns the share inbox; until it
 * ports there's nowhere to publish, so this resolves the callback (warming the relay) and stops.
 */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishShareCredentials(server: OpenCodeServer): Effect<Action> {
    val sessionNotifications = dependency(SessionNotificationsClient)
    if (!sessionNotifications.isAvailable) return Effect.none()
    return Effect.run { sessionNotifications.callbackURL(server) }
}

internal fun HomeFeature.reduceSystem(state: State, action: Action): HomeReduced = when (action) {
    // TODO(merge): iOS ends or mutes the open session's call (`.voice(.presented(.endTapped/.muteTapped))`),
    // and with no call clears `voiceCallActivity` and the activity. Needs `:feature:voice`'s call state.
    is Action.VoiceCallCommandReceived -> Reduced(state)

    // TODO(merge): iOS stops the recording the command came from (a pushed subagent's first, else
    // the session's: `.dictationStopRequested`), and with none clears `dictationActivity` and the
    // activity. Needs `:feature:session`'s dictation state.
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
        val target = s.resolvedShareTarget(share)
        val existing = s.sharedDrafts[target] ?: SessionDraftState()
        // iOS `SharedComposerDraft.append` (text joined by a blank line; attachments, files and the
        // project follow). TODO(merge): `:feature:system`'s `SharedComposerDraft`.
        s = s.copy(sharedDrafts = s.sharedDrafts + (target to existing.appending(SessionDraftState(share.text))))
        newestTarget = target
    }
    s = s.leavingCatchUp()
    dependency(HapticsClient).play(Haptic.success)
    // The composer on screen keeps only what's for it once the newest share's session is open;
    // until then it takes nothing it would lose.
    val onScreen = s.sessionDetail?.let { SharedDraftTarget.of(it.session?.id) }
    s = s.copy(shareNavigationTarget = if (onScreen == newestTarget) null else newestTarget)
    return when (newestTarget) {
        is SharedDraftTarget.Session -> Reduced(s, Effect.send(Action.SessionTapped(newestTarget.id)))
        SharedDraftTarget.NewSession -> {
            if (s.sessionDetail?.isDraft != true) s = s.openingDraft(reason = "something was shared to the app")
            Reduced(s)
        }
    }
}

/**
 * iOS `resolvedShareTarget(_:)` (`ShareInbox.swift`): a share for a session the list doesn't hold
 * goes to a new session (in the session's project).
 */
private fun State.resolvedShareTarget(share: ReceivedShare): SharedDraftTarget = when (val target = share.target) {
    is SharedDraftTarget.Session -> if (sessions.any { it.id == target.id }) target else SharedDraftTarget.NewSession
    SharedDraftTarget.NewSession -> SharedDraftTarget.NewSession
}

/**
 * Hands the list to Shortcuts, launcher actions and search, never before it has loaded: an empty
 * list then means "not yet", not "none".
 *
 * TODO(merge): `systemIntegration.publish(state.systemDirectoryEntry, state.systemVoiceAvailable)`
 * once `:feature:system` ports its integration client.
 */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishToSystem(state: State) {
    if (!state.snapshotLoaded) return
}

/**
 * Opens what a shortcut, launcher action or link names, and starts a call there through the call
 * button's own path when asked. `AppFeature` sends this only once the list has loaded.
 *
 * The bridge's destinations differ from iOS's `SystemAction.Destination` (`session(id)`,
 * `lastViewed`, `draft(projectID)`): [SystemActionDestination.NewSession] is iOS's `draft(nil)`,
 * and [SystemActionDestination.CatchUp] has no iOS counterpart here (iOS opens Catch Up through
 * its own route), so it opens nothing. TODO(merge): `:feature:system`'s `SystemAction.Destination`.
 */
internal fun HomeFeature.openSystemDestination(
    state: State,
    destination: SystemActionDestination,
    startsCall: Boolean,
    inBackground: Boolean = false,
): HomeReduced {
    var s = state.copy(settings = null)
    val session: OpenCodeSession? = when (destination) {
        is SystemActionDestination.Session -> s.sessions.firstOrNull { it.id == destination.sessionID } ?: return Reduced(s)
        SystemActionDestination.NewSession -> null
        SystemActionDestination.CatchUp -> return Reduced(s)
    }

    if (session == null) {
        // A draft, as New Session makes one; one already open keeps its text.
        if (s.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(s)
        s = s.withCatchUp(null)
        // iOS `draft(projectID)`: the project's folder, or the picker asked again when it's gone.
        // TODO(merge): the bridge's `NewSession` carries no project.
        if (s.sessionDetail?.isDraft != true) s = s.openingDraft(reason = "a shortcut started a new session")
        val effects = mutableListOf<Effect<Action>>()
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
 * TODO(merge): with `:feature:voice`'s call state: clear a terminal call, keep a live one, and if
 * Voice is available send the screen `.voiceTapped` (then `.voice(.presented(.startIfNeeded))` in
 * the background). Until then a call set up has nothing to start here; without Voice set up
 * (`:feature:settings`' `whatsNewVoiceAvailable`, the same keys) the call button's path opens Settings.
 */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
internal fun HomeFeature.startSystemCall(state: State, inBackground: Boolean = false): HomeReduced {
    if (state.sessionDetail == null) return Reduced(state)
    if (whatsNewVoiceAvailable(state.server)) return Reduced(state)
    return Reduced(state.showingSettings())
}

/** Tells the open screens their requests lost the connection. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.requestsDisconnected(state: State): Effect<Action> = Effect.merge(
    sendToDetail(state, SessionScreenMessage.RequestsConnectionChanged(false)),
    forwardToSubagents(state, SessionScreenMessage.RequestsConnectionChanged(false)),
)
