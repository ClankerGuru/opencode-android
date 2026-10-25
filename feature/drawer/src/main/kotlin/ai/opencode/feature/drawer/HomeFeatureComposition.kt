package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.combine
import ai.opencode.core.store.forEach
import ai.opencode.core.store.ifLet
import ai.opencode.core.store.onChange
import ai.opencode.core.store.scope
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.bridge.SessionDetail
import ai.opencode.feature.drawer.bridge.SessionScreenMessage
import ai.opencode.feature.drawer.bridge.WhatsNewAction
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.notices.DeliveredNotificationsClient
import ai.opencode.feature.drawer.switcher.SessionSwitcherFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent

/*
 * Port of iOS `HomeFeature+Composition.swift`: how Home's reducers stack. Children settle after
 * Home's own coordination (`core`), the open session and pushed subagents run as presented and
 * identified children, and a chain of observers follows state Home derives (Catch Up's queue, the
 * selected transcript read, the pairing's drawer open, In-App Notices, the foreground read
 * settling, the What's New close).
 *
 * Fast streaming actions ([isFastStreamAction]) skip the observer chain and run only Home's own
 * reducer and the screens, as iOS does. iOS's `bodyCache` is a build-time optimisation with no
 * Kotlin counterpart: the body is built once per `HomeFeature` instance.
 */

private object SessionDetailSlot
private object SubagentStackSlot

internal fun HomeFeature.builtBody(): Reducer<State, Action> {
    val full = fullBody()
    val sessions = detailSessionsCore()
    return Reducer { state, action ->
        val reduced = if (isFastStreamAction(state, action)) sessions.reduce(state, action) else full.reduce(state, action)
        var s = reduced.state
        // The Catch Up preview's revision bumps when its inputs change for the same session.
        val oldSlice = state.catchUpPreviewSlice
        val newSlice = s.catchUpPreviewSlice
        if (oldSlice != newSlice && oldSlice != null && newSlice != null && oldSlice.sessionID == newSlice.sessionID &&
            (oldSlice.items != newSlice.items || oldSlice.isRunning != newSlice.isRunning ||
                oldSlice.snapshot != newSlice.snapshot || oldSlice.activeFamilyIDs != newSlice.activeFamilyIDs)
        ) {
            s = s.copy(previewRevision = s.previewRevision + 1)
        }
        // Unread or waiting work arriving, by whatever action (a failed receipt included), ends an
        // All Caught Up finish: its `.success` and rest are cancelled.
        if (!s.catchUpState.justFinished || s.catchUpSessions.isEmpty()) return@Reducer Reduced(s, reduced.effect)
        Reduced(s, Effect.merge(reduced.effect, Effect.send(Action.CatchUp(CatchUpFeature.Action.WorkArrived))))
    }
}

/**
 * What's New read on the pushed page is seen once the page closes, so its dots stay while you read
 * and the drawer's row leaves on the way back.
 */
private fun HomeFeature.fullBody(): Reducer<State, Action> =
    homeReducers().onChange({ it.isSettingsShown }) { _, _, state ->
        Reduced(state, if (state.closesWhatsNew) Effect.send(Action.WhatsNew(WhatsNewAction.Closed)) else Effect.none())
    }

private fun HomeFeature.homeReducers(): Reducer<State, Action> = combine(
    scope(
        state = { it.sessionSwitcher },
        setState = { s: State, c: SessionSwitcherFeature.State -> s.copy(sessionSwitcher = c) },
        action = { (it as? Action.SessionSwitcher)?.action },
        embed = Action::SessionSwitcher,
        child = SessionSwitcherFeature(),
    ),
    detailReducer().homeChildrenObservers(this),
    // Whatever replaced the detail an uncached tap's read belongs to (New Session, a voice draft,
    // Catch Up, a deletion, another tap) ends it.
    Reducer { state, _ ->
        val fetch = state.selectedTranscriptFetch
        if (fetch == null || fetch.transition == state.detailGeneration) {
            Reduced(state)
        } else {
            Reduced(state.copy(selectedTranscriptFetch = null), Effect.cancel(HomeFeature.CancelID.SelectedTranscript))
        }
    },
    // The pairing's drawer opens only over the bare draft it was asked on: after any action that
    // navigates or presents anything, it's dropped.
    Reducer { state, _ ->
        if (state.drawerOpenRequest == null || state.opensDrawerForPairing) {
            Reduced(state)
        } else {
            AppLog.app.info { "paired computer drawer open dropped" }
            Reduced(state.copy(drawerOpenRequest = null))
        }
    },
    // The label steps aside for the drawer, Settings, Catch Up, and the session it names once that's on screen.
    Reducer { state, _ ->
        val notice = state.inAppNotice ?: return@Reducer Reduced(state)
        if (state.isDrawerOpen || state.isSettingsShown || state.catchUp != null) {
            return@Reducer Reduced(state.copy(inAppNotice = null), Effect.cancel(HomeFeature.CancelID.InAppNotice))
        }
        val openIDs = state.openSessionIDs
        if (notice.events.none { it.sessionID in openIDs }) return@Reducer Reduced(state)
        val remaining = notice.events.filterNot { it.sessionID in openIDs }
        if (remaining.isEmpty()) {
            Reduced(state.copy(inAppNotice = null), Effect.cancel(HomeFeature.CancelID.InAppNotice))
        } else {
            Reduced(state.copy(inAppNotice = notice.copy(events = remaining)))
        }
    },
    // Only list and attention changes move the stack; skip the transcript's own high-frequency
    // traffic and actions that already maintain `catchUp` or only update the transcript cache.
    Reducer { state, action ->
        val skips = when (action) {
            is Action.SessionDetail, is Action.SessionDetailEvent, is Action.SubagentStack, is Action.SubagentEvent,
            is Action.ScreenMessage, is Action.CatchUp, is Action.TranscriptPrefetched, is Action.CachedTranscriptRefreshed,
            is Action.SelectedTranscriptResponse, is Action.ViewAcknowledged -> true
            is Action.ServerEvent -> action.event is OpenCodeServerEvent.ContentBatch || action.event is OpenCodeServerEvent.MessageInvalidated
            else -> false
        }
        if (skips) return@Reducer Reduced(state)
        val front = state.catchUp?.queue?.firstOrNull()
        val s = state.reconcilingCatchUp()
        val openedID = s.catchUp?.queue?.firstOrNull()
        if (openedID == null || openedID == front) Reduced(s) else cancelBackgroundTranscriptReads(s, setOf(openedID))
    },
)

/** `detailSessions`, dropping a gone screen's late actions first (iOS `detailReducer`). */
private fun HomeFeature.detailReducer(): Reducer<State, Action> {
    val chain = detailSessions()
    return Reducer { state, action ->
        if (action is Action.SubagentStack && state.subagentStack.none { it.navigationID == action.id }) return@Reducer Reduced(state)
        if (action is Action.SessionDetail && state.sessionDetail == null) return@Reducer Reduced(state)
        chain.reduce(state, action)
    }
}

/** Home's own actions, the open session and its pushed subagents (iOS `detailSessionsCore`). */
private fun HomeFeature.detailSessionsCore(): Reducer<State, Action> {
    val catchUp = CatchUpFeature()
    val settings = HomeSettingsFeature()
    val notifications = HomeNotificationsFeature()
    val connection = HomeConnectionFeature()
    val session = SessionDetail.reducer(SessionFeature())
    val home = combine<State, Action>(
        scope(
            state = { it.catchUpState },
            setState = { s: State, c: CatchUpFeature.State -> s.copy(catchUpState = c) },
            action = { (it as? Action.CatchUp)?.action },
            embed = Action::CatchUp,
            child = catchUp,
        ),
        // Home first coordinates against the previous child state (permission changes and
        // connection recovery), then the children settle that action.
        Reducer { state, action -> core(state, action) },
        scope(
            state = { it.settings },
            setState = { s: State, c: HomeSettingsFeature.State -> s.withSettings(c) },
            action = { (it as? Action.Settings)?.action },
            embed = Action::Settings,
            child = settings,
        ),
        scope(
            state = { it.notifications },
            setState = { s: State, c: HomeNotificationsFeature.State -> s.copy(notificationState = c.status) },
            action = ::notificationsAction,
            embed = ::homeAction,
            child = notifications,
        ),
        scope(
            state = { it.connection },
            setState = { s: State, c: HomeConnectionFeature.State -> s.copy(connection = c) },
            action = ::connectionAction,
            embed = ::homeAction,
            child = connection,
        ),
    )
    val presented = ifLet<State, Action, SessionDetail, SessionFeature.Action>(
        id = SessionDetailSlot,
        parent = home,
        state = { it.sessionDetail },
        setState = { s: State, d: SessionDetail? -> s.copy(sessionDetail = d) },
        action = { (it as? Action.SessionDetail)?.action },
        embed = Action::SessionDetail,
        child = session,
        childID = { it.navigationID },
    )
    return forEach<State, Action, Int, SessionDetail, SessionFeature.Action>(
        slot = SubagentStackSlot,
        parent = presented,
        elements = { it.subagentStack },
        setElements = { s: State, list: List<SessionDetail> -> s.copy(subagentStack = list) },
        elementID = { it.navigationID },
        action = { (it as? Action.SubagentStack)?.let { a -> a.id to a.action } },
        embed = { id, a -> Action.SubagentStack(id, a) },
        child = session,
    )
}

/**
 * Home's own actions and the screens, then what follows them: a link tap dropped when its screen
 * stops being the one on top, and background reads resuming once the foreground read settles.
 * (iOS also discards a gone screen's in-app browser page and stops its recording here; those wait
 * for `:feature:media` and `:feature:voice`.)
 */
private fun HomeFeature.detailSessions(): Reducer<State, Action> =
    detailSessionsCore()
        // A link tap waits on the system only while the screen it was tapped on stays the one on
        // screen. Whatever changes that drops the tap: its answer must open nothing.
        .onChange({ it.visibleScreen }) { old, _, state ->
            val id = old.navigationID ?: return@onChange Reduced(state)
            // TODO(merge): only when that screen holds a link probe (`linkProbe != nil`).
            if (old.stackDepth > 0) {
                if (state.subagentStack.none { it.navigationID == id }) return@onChange Reduced(state)
                return@onChange Reduced(state, Effect.send(Action.ScreenMessage(id, SessionScreenMessage.VisibleScreenChanged)))
            }
            if (state.sessionDetail?.navigationID != id) return@onChange Reduced(state)
            Reduced(state, Effect.send(Action.ScreenMessage(id, SessionScreenMessage.VisibleScreenChanged)))
        }
        .onChange({ it.isInitialTranscriptInFlight }) { wasInFlight, _, state ->
            if (!wasInFlight || state.isInitialTranscriptInFlight) Reduced(state) else settleForegroundTranscriptLoad(state)
        }

/**
 * Streaming traffic that skips the observer chain (iOS `isFastStreamAction`): it moves neither the
 * list, Catch Up, notices nor navigation.
 */
internal fun isFastStreamAction(state: State, action: Action): Boolean = when (action) {
    is Action.ServerEvent -> when (val event = action.event) {
        is OpenCodeServerEvent.ContentBatch ->
            event.sessionID in state.activeSessionIDs || event.events.none { it is OpenCodeLiveContentEvent.StepStarted }
        is OpenCodeServerEvent.MessageInvalidated -> true
        else -> false
    }
    is Action.TranscriptPrefetched, is Action.CachedTranscriptRefreshed -> true
    // iOS `.sessionDetail(.presented(.contentBatch | .familyRunStateChanged))`.
    // TODO(merge): match the real `SessionFeature` actions once `:feature:session` ports them.
    is Action.ScreenMessage ->
        (action.message is SessionScreenMessage.ContentBatch || action.message is SessionScreenMessage.FamilyRunStateChanged) &&
            state.sessionDetail?.navigationID == action.navigationID
    else -> false
}

/** Settings effects use the live server without maintaining a second copy (iOS computed `settings`). */
val State.settings: HomeSettingsFeature.State get() = HomeSettingsFeature.State(server = server, isSidebarShown = isSidebarShown)

internal fun State.withSettings(settings: HomeSettingsFeature.State): State {
    var s = if (settings.server == server) this else withServer(settings.server)
    val sidebar = settings.isSidebarShown
    if (sidebar != s.isSidebarShown) s = s.copy(isSidebarShown = sidebar)
    return s
}

/** iOS computed `notifications`. */
val State.notifications: HomeNotificationsFeature.State
    get() = HomeNotificationsFeature.State(server = server, status = notificationState)

private fun notificationsAction(action: Action): HomeNotificationsFeature.Action? = when (action) {
    Action.NotificationSettingsRequested -> HomeNotificationsFeature.Action.NotificationSettingsRequested
    is Action.NotificationPermissionLoaded -> HomeNotificationsFeature.Action.NotificationPermissionLoaded(action.permission)
    Action.EnableNotificationsTapped -> HomeNotificationsFeature.Action.EnableNotificationsTapped
    is Action.NotificationPermissionRequested -> HomeNotificationsFeature.Action.NotificationPermissionRequested(action.permission)
    is Action.NotificationSettingsOpened -> HomeNotificationsFeature.Action.NotificationSettingsOpened(action.opened)
    Action.NotifyPluginCheckRequested -> HomeNotificationsFeature.Action.NotifyPluginCheckRequested
    is Action.NotifyPluginChecked -> HomeNotificationsFeature.Action.NotifyPluginChecked(action.status)
    else -> null
}

private fun homeAction(action: HomeNotificationsFeature.Action): Action = when (action) {
    HomeNotificationsFeature.Action.NotificationSettingsRequested -> Action.NotificationSettingsRequested
    is HomeNotificationsFeature.Action.NotificationPermissionLoaded -> Action.NotificationPermissionLoaded(action.permission)
    HomeNotificationsFeature.Action.EnableNotificationsTapped -> Action.EnableNotificationsTapped
    is HomeNotificationsFeature.Action.NotificationPermissionRequested -> Action.NotificationPermissionRequested(action.permission)
    is HomeNotificationsFeature.Action.NotificationSettingsOpened -> Action.NotificationSettingsOpened(action.opened)
    HomeNotificationsFeature.Action.NotifyPluginCheckRequested -> Action.NotifyPluginCheckRequested
    is HomeNotificationsFeature.Action.NotifyPluginChecked -> Action.NotifyPluginChecked(action.status)
}

private fun connectionAction(action: Action): HomeConnectionFeature.Action? = when (action) {
    Action.Task -> HomeConnectionFeature.Action.Task
    Action.SceneEnteredBackground -> HomeConnectionFeature.Action.SceneEnteredBackground
    is Action.ServerConnected -> HomeConnectionFeature.Action.ServerConnected(action.server)
    is Action.EventStreamFailed -> HomeConnectionFeature.Action.EventStreamFailed(action.error)
    Action.ConnectionLost -> HomeConnectionFeature.Action.ConnectionLost
    Action.SnapshotFailed -> HomeConnectionFeature.Action.SnapshotFailed
    Action.SnapshotRetryRequested -> HomeConnectionFeature.Action.SnapshotRetryRequested
    Action.LastKnownActivityExpired -> HomeConnectionFeature.Action.LastKnownActivityExpired
    Action.NetworkRerouted -> HomeConnectionFeature.Action.NetworkRerouted
    Action.RetryConnectionTapped -> HomeConnectionFeature.Action.RetryConnectionTapped
    is Action.ServerEvent -> HomeConnectionFeature.Action.ServerEvent(action.event)
    else -> null
}

private fun homeAction(action: HomeConnectionFeature.Action): Action = when (action) {
    HomeConnectionFeature.Action.Task -> Action.Task
    HomeConnectionFeature.Action.SceneEnteredBackground -> Action.SceneEnteredBackground
    is HomeConnectionFeature.Action.ServerConnected -> Action.ServerConnected(action.server)
    is HomeConnectionFeature.Action.EventStreamFailed -> Action.EventStreamFailed(action.error)
    HomeConnectionFeature.Action.ConnectionLost -> Action.ConnectionLost
    HomeConnectionFeature.Action.SnapshotFailed -> Action.SnapshotFailed
    HomeConnectionFeature.Action.SnapshotRetryRequested -> Action.SnapshotRetryRequested
    HomeConnectionFeature.Action.LastKnownActivityExpired -> Action.LastKnownActivityExpired
    HomeConnectionFeature.Action.NetworkRerouted -> Action.NetworkRerouted
    HomeConnectionFeature.Action.RetryConnectionTapped -> Action.RetryConnectionTapped
    is HomeConnectionFeature.Action.ServerEvent -> Action.ServerEvent(action.event)
}

/** What Shortcuts, launcher actions and search are handed (iOS `SystemPublication`). */
internal data class SystemPublication(
    val server: ai.opencode.server.OpenCodeServer,
    val loaded: Boolean,
    val sessions: List<ai.opencode.server.model.OpenCodeSession>,
    val projects: List<ai.opencode.server.model.OpenCodeProject>,
    val pinnedSessionKeys: List<String>,
    val lastViewedSessionIDs: Map<String, String>,
    val voiceAvailable: Boolean,
)

internal val State.systemPublication: SystemPublication
    get() = SystemPublication(server, snapshotLoaded, sessions, projects, pinnedSessionKeys, lastViewedSessionIDs, systemVoiceAvailable)

/**
 * The observers iOS chains on `detailReducer` and `detailChain`: In-App Notices follow Home's live
 * state, a session on screen clears its delivered notifications, an answered request clears its
 * needs-input notifications, and the system publication follows the list
 * (`HomeFeature+Notifications.swift`, `HomeFeature+System.swift`). The app badge's watch waits for
 * `:feature:system`'s `AppBadgeLedger` (`publishAppBadge` is inert until then).
 */
private fun Reducer<State, Action>.homeChildrenObservers(feature: HomeFeature): Reducer<State, Action> =
    onChange({ feature.noticeInput(it) }) { old, _, state ->
        feature.inAppNoticeSignalChanged(old, feature.noticeInput(state), state)
    }
        .onChange({ it.viewing }) { _, _, state ->
            val threads = state.viewedNotificationThreads
            if (threads.isEmpty()) return@onChange Reduced(state)
            val delivered = dependency(DeliveredNotificationsClient)
            Reduced(state, Effect.run { delivered.removeAll(threads) })
        }
        .onChange({ it.attentionSessionIDs }) { old, new, state ->
            val answered = old - new
            if (answered.isEmpty() || !state.isConnected) return@onChange Reduced(state)
            val delivered = dependency(DeliveredNotificationsClient)
            Reduced(state, Effect.run { delivered.removeNeedsInput(answered) })
        }
        .onChange({ it.systemPublication }) { _, _, state ->
            feature.publishToSystem(state)
            Reduced(state)
        }
