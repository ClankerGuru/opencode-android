package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.bridge.SessionScreenEvent
import ai.opencode.feature.drawer.catchup.CatchUpChrome
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.catchup.CatchUpStage
import ai.opencode.feature.drawer.catchup.CatchUpStageProxy
import ai.opencode.feature.drawer.container.DrawerKeyboard
import ai.opencode.feature.drawer.container.SessionDrawerContainer
import ai.opencode.feature.drawer.container.SidebarMetrics
import ai.opencode.feature.drawer.container.rememberSessionDrawerController
import ai.opencode.feature.drawer.motion.DrawerButton
import ai.opencode.feature.drawer.motion.rememberDrawerKeyboardHold
import ai.opencode.feature.drawer.motion.rememberDrawerShortcutMotion
import ai.opencode.feature.drawer.notices.InAppNotice
import ai.opencode.feature.drawer.switcher.SessionSwitcherOverlay
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionView
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPresentation
import ai.opencode.feature.settings.SettingsView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** Navigation3 keys for the session's stack: the root session (or draft), then pushed subagents. */
sealed interface HomeRoute {
    data object Root : HomeRoute
    data class Subagent(val navigationID: Int) : HomeRoute
}

/**
 * Port of `OpenCode/Drawer/HomeView.swift`: one server's Home. The open session (or a draft) on
 * the session drawer's card ([SessionDrawerContainer]), its pushed subagents (Navigation3 with
 * predictive back), Catch Up, Settings pushed over everything, the delete confirmation, and the
 * lifecycle that tells [HomeFeature] about the screen.
 *
 * A window at least [SidebarMetrics.minimumWindowWidth] wide lists the sessions in a sidebar
 * beside the session, shown or hidden with the header's button
 * ([HomeFeature.State.isSidebarShown]); anything narrower slides the phone's drawer under it.
 * Per-frame drawer motion never recomposes this view or the session: the container's plain
 * objects own it, and only settled open/shut reaches the reducer.
 */
@Composable
fun HomeView(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    modifier: Modifier = Modifier,
    initiallyOpenDrawer: Boolean = false,
) {
    val view = rememberHomeViewState(store, initiallyOpenDrawer)
    val reducedMotion = rememberReduceMotion()
    view.keyboardHold = rememberDrawerKeyboardHold(view.keyboardMonitor)
    view.shortcutMotion = rememberDrawerShortcutMotion()
    val hasDetail by store.observe { it.sessionDetail != null }

    val settingsShown by store.observe { it.settings != null }
    val settingsAtRoot by store.observe { it.settings?.path?.isEmpty() ?: true }
    SettingsPresentation(
        isShown = settingsShown,
        atRoot = settingsAtRoot,
        // At the root Back closes Settings (`SettingsDelegate.Close`, which Home handles).
        onClose = { store.send(HomeFeature.Action.SettingsScreen(PresentationAction.Presented(SettingsFeature.Action.BackTapped))) },
        settings = { HomeSettings(store) },
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // An iPad-wide window lists the sessions in a sidebar beside the session; anything
            // narrower slides the phone's drawer under it.
            val usesSidebar = maxWidth >= SidebarMetrics.minimumWindowWidth
            // Three quarters of a phone; on a wider window, no wider than a sidebar.
            val drawerWidth = if (usesSidebar) SidebarMetrics.width else min(maxWidth * 0.75f, 400.dp)
            val containerWidth = maxWidth
            SideEffect { view.usesSidebar = usesSidebar }
            if (hasDetail) HomeLayout(store, view, usesSidebar, drawerWidth, containerWidth, reducedMotion)
            HomeSidebarEffects(store, view, usesSidebar)
        }
    }
    HomeSettingsBack(store)
    HomeLifecycle(store, view)
    // iOS hosts it on Home, so deletes from the session's own menu confirm too.
    HomeDeleteConfirmation(store)
}

/** iOS `homeLayout`: the container with the session, the drawer, and Catch Up. */
@Composable
private fun HomeLayout(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
    drawerWidth: Dp,
    containerWidth: Dp,
    reducedMotion: Boolean,
) {
    val controller = rememberSessionDrawerController(view.isSessionDrawerPresented)
    val haptics = remember { dependency(HapticsClient) }
    val density = LocalDensity.current
    val catchUp by store.observe { it.catchUp }
    val isSidebarShown by store.observe { it.isSidebarShown }
    val previewRevision by store.observe { it.previewRevision }
    val selectedSessionID by store.observe { it.sessionDetail?.session?.id }
    val pushed by store.observe { it.subagentStack.isNotEmpty() }
    val settingsShown by store.observe { it.isSettingsShown }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val catchUpProxy = remember { CatchUpStageProxy() }
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    // The last stack shown, so the stage keeps its last count while it fades out.
    val lastCatchUp = remember { arrayOfNulls<CatchUpFeature.Stack>(1) }
    catchUp?.let { lastCatchUp[0] = it }

    /**
     * iOS `leaveCatchUp(finished:)`: Back, TalkBack's escape, or the last card gone (`finished`,
     * which the drawer's Catch Up row celebrates). The stack hands back to the drawer it was opened
     * from, as it was; set before the stack ends, so the card sees both in one update and returns
     * to the open drawer rather than landing shut and opening again. Navigation: no haptic.
     */
    fun leaveCatchUp(finished: Boolean) {
        if (finished && store.state.catchUp?.queue?.isEmpty() != true) return
        val action = if (finished) HomeFeature.Action.catchUpFinished(currentReducedMotion) else HomeFeature.Action.catchUpExitTapped
        // The card's picture first: it fades over the session coming back.
        controller.captureCardThen {
            if (store.state.catchUp == null) return@captureCardThen
            if (!store.state.showsDrawerLabel) {
                store.send(action)
                return@captureCardThen
            }
            view.isSessionDrawerPresented = true
            store.send(action)
            // TalkBack lands back on the Catch Up row (`DrawerListController`).
            view.catchUpFocusRequests += 1
        }
    }

    val stage = catchUp?.let { stack ->
        CatchUpStage(
            queue = stack.queue,
            undoDirection = stack.handled.lastOrNull()?.disposition,
            onDismiss = { disposition ->
                val sessionID = store.state.catchUp?.queue?.firstOrNull() ?: return@CatchUpStage
                store.send(HomeFeature.Action.catchUpCardDismissed(sessionID, disposition))
            },
            onUndo = { store.send(HomeFeature.Action.catchUpUndoTapped) },
            onExit = { leaveCatchUp(finished = false) },
            onFinish = { leaveCatchUp(finished = true) },
        )
    }

    SessionDrawerContainer(
        controller = controller,
        isPresented = if (usesSidebar) false else view.isSessionDrawerPresented,
        onPresentationChanged = { presented ->
            if (!usesSidebar && view.isSessionDrawerPresented != presented) view.isSessionDrawerPresented = presented
        },
        width = drawerWidth,
        sidebarShown = if (usesSidebar) isSidebarShown else null,
        reducedMotion = reducedMotion,
        catchUp = stage,
        catchUpProxy = catchUpProxy,
        keyboardHold = requireNotNull(view.keyboardHold),
        panRegions = view.panRegions,
        previewRevision = previewRevision,
        onArrived = { open ->
            if (!open) view.cancelPinDrag()
            store.send(HomeFeature.Action.SessionDrawerArrived(open))
        },
        onCommitted = { haptics.play(Haptic.snap) },
        onTouchDown = { haptics.prepare(Haptic.snap) },
        onReveal = { phase ->
            // iOS `@ScaledMetric(relativeTo: .footnote) timeRevealReach = 76`.
            view.timeReveal.update(phase, reach = TIME_REVEAL_REACH * density.fontScale, reducedMotion = currentReducedMotion)
        },
        // TODO(merge): the Call Tray (`dismissesKeyboardOnOpen`, `onDrawerProgress` → `callTray.dock`) once `:feature:voice` ports it.
        dismissesKeyboardOnOpen = false,
        contentSessionID = DrawerKeyboard.closingSession(chosen = view.chosenSessionID, shown = selectedSessionID),
        isContentPushed = pushed,
        isSheetPresented = settingsShown,
        content = {
            SessionSwitcherOverlay(
                store = store,
                drawerOpen = view.isSessionDrawerPresented || isDrawerOpen,
                usesSidebar = usesSidebar,
                modifier = Modifier.fillMaxSize(),
            ) {
                HomeSessionStack(store, view, usesSidebar, containerWidth)
            }
        },
        // The drawer's panel (iOS `sessionDrawer(width:insets:)`); Home hosts the delete confirmation.
        drawer = { HomeDrawer(store, view, Modifier.fillMaxSize(), hostsDeleteConfirmation = false) },
        chrome = {
            val shown = catchUp ?: lastCatchUp[0]
            if (shown != null) {
                CatchUpChrome(
                    remaining = shown.queue.size,
                    canUndo = shown.handled.isNotEmpty(),
                    onExit = { leaveCatchUp(finished = false) },
                    onUndo = { catchUpProxy.undoCard() },
                    onDismiss = { catchUpProxy.dismissCard(it) },
                )
            }
        },
        preview = { sessionID, revision -> CatchUpPreviewCard(store, sessionID, revision) },
    )
}

/**
 * What the session's stack shows, bottom first: the session (or draft), then each subagent pushed
 * over it (iOS `stackPages` / `StackEntry`). Back (the back button, the predictive back gesture)
 * goes to the screen beneath. TODO(merge): web pages over the screen that opened them
 * (`StackEntry.web`) once `:feature:media` ports the in-app browser.
 */
@Composable
private fun HomeSessionStack(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
    containerWidth: Dp,
) {
    val rootID by store.observe { it.sessionDetail?.navigationID }
    val stackIDs by store.observe { state -> state.subagentStack.map { it.navigationID } }
    val backStack: List<HomeRoute> = listOf(HomeRoute.Root) + stackIDs.map(HomeRoute::Subagent)
    NavDisplay(
        backStack = backStack,
        // The reader went back one page: the subagent on top pops to the screen beneath it.
        onBack = { store.send(HomeFeature.Action.SubagentsPopped(maxOf(store.state.subagentStack.size - 1, 0))) },
        entryProvider = { route ->
            when (route) {
                HomeRoute.Root -> NavEntry(route) {
                    val detail = remember(store) {
                        store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
                            state = { it.sessionDetail?.feature ?: SessionFeature.State.draft(it.server, null, emptyList()) },
                            action = { HomeFeature.Action.SessionDetail(PresentationAction.Presented(it)) },
                        )
                    }
                    // Each presented screen has its own identity (`navigationID`), as iOS's `ifLet` does.
                    key(rootID) {
                        LaunchedEffect(rootID) {
                            store.send(HomeFeature.Action.SessionDetailEvent(SessionScreenEvent.VisibilityChanged(true)))
                        }
                        SessionView(
                            detail,
                            navigationIcon = { HomeDrawerButton(store, view, usesSidebar, containerWidth) },
                        )
                    }
                }
                is HomeRoute.Subagent -> NavEntry(route) {
                    val detail = remember(store, route) {
                        store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
                            state = { home ->
                                home.subagentStack.firstOrNull { it.navigationID == route.navigationID }?.feature ?: SessionFeature.State.draft(home.server, null, emptyList())
                            },
                            action = { HomeFeature.Action.SubagentStack(route.navigationID, it) },
                        )
                    }
                    SessionView(
                        detail,
                        navigationIcon = {
                            OpenCodeIconButton(
                                symbol = SFSymbol.ChevronBackward,
                                contentDescription = "Back",
                                style = OpenCodeIconButtonStyle.Standard,
                                onClick = {
                                    val index = store.state.subagentStack.indexOfFirst { it.navigationID == route.navigationID }
                                    if (index >= 0) store.send(HomeFeature.Action.SubagentsPopped(index))
                                },
                            )
                        },
                    )
                }
            }
        },
    )
}

/**
 * The session header's drawer button (iOS `SessionView(onOpenSessions:onNoticeTapped:)`): opens
 * the drawer, or toggles the sidebar beside a wide window. Its In-App Notice label opens what it
 * names; several sessions open the drawer, which lists them all.
 */
@Composable
private fun HomeDrawerButton(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
    containerWidth: Dp,
) {
    val badge by store.observe { if (it.catchUp != null) null else it.drawerBadge(excluding = it.sessionDetail?.session?.id) }
    val notice by store.observe { if (it.catchUp != null) null else it.inAppNotice }
    DrawerButton(
        badge = badge,
        notice = notice,
        // Up to the header's trailing controls.
        maxWidth = containerWidth * HEADER_LABEL_FRACTION,
        onOpenSessions = {
            if (usesSidebar) {
                store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.SidebarToggled))
            } else {
                view.isSessionDrawerPresented = true
            }
        },
        onNoticeTapped = { target ->
            // Several sessions: the drawer lists them all.
            if (target == InAppNotice.Target.Drawer && !usesSidebar) view.isSessionDrawerPresented = true
            store.send(HomeFeature.Action.InAppNoticeTapped)
        },
    )
}

/**
 * The real session screen for the card behind the open one, from what Home knows of it (iOS
 * `CatchUpPreviewCard`). Inert: it never sends anything, and the stack hides it from TalkBack.
 * TODO(merge): render the cached transcript with an inert `SessionFeature` store
 * (`CatchUpPreviewStores`) once `:feature:session` ports it.
 */
@Composable
private fun CatchUpPreviewCard(store: StoreOf<HomeFeature.State, HomeFeature.Action>, sessionID: String, revision: Int) {
    val session = remember(sessionID, revision) { store.state.sessions.firstOrNull { it.id == sessionID } }
    if (session == null) {
        Box(Modifier.fillMaxSize().background(OpenCodeTheme.colors.canvas))
        return
    }
    val inert = remember(session.id, session.title) { InertStore<SessionFeature.State, SessionFeature.Action>(SessionFeature.State.session(store.state.server, session)) }
    SessionView(inert, Modifier.fillMaxSize().clearAndSetSemantics {})
}

/** A store that shows [initial] and drops every action (the preview card's). */
private class InertStore<S, A>(initial: S) : StoreOf<S, A> {
    private val flow = MutableStateFlow(initial)
    override val state: S get() = flow.value
    override val states: StateFlow<S> get() = flow
    override fun send(action: A) = Unit
}

/** iOS `homeLayout`'s `onChange(of: usesSidebar)` and `onChange(of: store.opensDrawerForPairing)`, both `initial: true`. */
@Composable
private fun HomeSidebarEffects(store: StoreOf<HomeFeature.State, HomeFeature.Action>, view: HomeViewState, usesSidebar: Boolean) {
    val opensDrawerForPairing by store.observe { it.opensDrawerForPairing }
    // Beside a sidebar the drawer button has no label, so In-App Notices acts as Off, banners included.
    LaunchedEffect(usesSidebar) {
        if (usesSidebar) view.isSessionDrawerPresented = false
        // TODO(merge): `sessionNotifications.setShowsDrawerLabel(!sidebar)` once `:feature:system` ports it.
        store.send(HomeFeature.Action.DrawerLabelAvailabilityChanged(!usesSidebar))
    }
    // A computer just paired: open its sessions as the drawer button does.
    LaunchedEffect(opensDrawerForPairing) {
        if (!opensDrawerForPairing || !store.state.opensDrawerForPairing) return@LaunchedEffect
        store.send(HomeFeature.Action.DrawerOpenRequestHandled)
        if (view.usesSidebar) {
            if (!store.state.isSidebarShown) store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.SidebarToggled))
        } else {
            view.isSessionDrawerPresented = true
        }
    }
}

/** iOS `homeLifecycle` and the body's `scenePhase` handling. */
@Composable
private fun HomeLifecycle(store: StoreOf<HomeFeature.State, HomeFeature.Action>, view: HomeViewState) {
    val serverID by store.observe { it.server.persistenceID }
    val settingsShown by store.observe { it.isSettingsShown }
    val drawerCloseRequests by store.observe { it.drawerCloseRequests }
    val selectedSessionID by store.observe { it.sessionDetail?.session?.id }
    val systemVoiceAvailable by store.observe { it.systemVoiceAvailable }
    val isDrawerVisible by store.observe { view.isDrawerVisible(it) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Keyed by the server: a switch from the drawer swaps Home's state in place, and the new
    // server's Home starts here like a fresh one.
    LaunchedEffect(serverID) {
        store.send(HomeFeature.Action.Task)
        store.send(HomeFeature.Action.NotificationSettingsRequested)
    }
    val firstServer = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverID) {
        val previous = firstServer.value
        firstServer.value = serverID
        if (previous == null || previous == serverID) return@LaunchedEffect
        view.cancelPinDrag()
        view.endSearch()
    }
    // Search belongs to one visit to the drawer on one server.
    LaunchedEffect(view) {
        snapshotFlow { view.isSessionDrawerPresented }.drop(1).collect { open ->
            if (!open) {
                view.cancelPinDrag()
                view.endSearch()
            }
            // A new visit to the drawer starts from the session on screen.
            if (open) view.chosenSessionID = null
        }
    }
    LaunchedEffect(isDrawerVisible, view.isSessionDrawerPresented, view.isSceneActive) {
        if (!view.isDrawerVisible()) view.shortcutMotion?.drawerDidHide()
    }
    LaunchedEffect(selectedSessionID) {
        if (selectedSessionID != null && selectedSessionID == view.chosenSessionID) view.chosenSessionID = null
    }
    val firstCloseRequest = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(drawerCloseRequests) {
        val previous = firstCloseRequest.value
        firstCloseRequest.value = drawerCloseRequests
        if (previous != null && previous != drawerCloseRequests) view.closeSessionDrawer()
    }
    // TODO(merge): iOS publishes `voicePreferences.isAvailable(server:)`; until `:feature:voice`
    // ports it, the last observed value is republished per server.
    LaunchedEffect(serverID, systemVoiceAvailable) {
        store.send(HomeFeature.Action.SystemPublishRequested(systemVoiceAvailable))
    }
    // iOS `scenePhase`: RESUMED is active, STARTED inactive, below that the background.
    LaunchedEffect(lifecycle) {
        var first = true
        lifecycle.currentStateFlow.scenePhases().collect { phase ->
            view.isSceneActive = phase == ScenePhase.Active
            if (first) {
                first = false
                return@collect
            }
            if (phase == ScenePhase.Active) {
                store.send(HomeFeature.Action.RetryConnectionTapped)
                store.send(HomeFeature.Action.NotificationSettingsRequested)
            } else {
                view.cancelPinDrag()
                store.send(HomeFeature.Action.TranscriptCacheFlushRequested)
                if (phase == ScenePhase.Background) store.send(HomeFeature.Action.SceneEnteredBackground)
            }
        }
    }
}

private enum class ScenePhase { Active, Inactive, Background }

private fun Flow<Lifecycle.State>.scenePhases(): Flow<ScenePhase> = map { state ->
    when {
        state.isAtLeast(Lifecycle.State.RESUMED) -> ScenePhase.Active
        state.isAtLeast(Lifecycle.State.STARTED) -> ScenePhase.Inactive
        else -> ScenePhase.Background
    }
}.distinctUntilChanged()

/**
 * The presented Settings ([HomeFeature.State.settings]) as `:feature:settings`' [SettingsView]. It
 * keeps showing the last state while it slides out (Home drops the state as it closes); anything
 * sent then is dropped by the presentation.
 */
@Composable
private fun HomeSettings(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val settings = remember(store) {
        var last: SettingsFeature.State? = null
        store.scope<HomeFeature.State, HomeFeature.Action, SettingsFeature.State, SettingsFeature.Action>(
            state = { home -> home.settings?.also { last = it } ?: last ?: SettingsFeature.State(server = home.server) },
            action = { HomeFeature.Action.SettingsScreen(PresentationAction.Presented(it)) },
        )
    }
    SettingsView(settings)
}

/**
 * Back closes Settings at its root, not what waits beneath it. `:feature:settings`'
 * [SettingsPresentation] registers its predictive handler with Home's first frame, before the
 * session's stack (subagent pushes) composes, so while a subagent is pushed beneath, this one —
 * registered anew each time Settings opens, so last — takes Back instead. (The drawer's and Catch
 * Up's own handlers stand down while Settings is up: `SessionDrawerContainer(isSheetPresented)`.)
 */
@Composable
private fun HomeSettingsBack(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val shown by store.observe { it.settings != null }
    val atRoot by store.observe { it.settings?.path?.isEmpty() ?: true }
    val pushedBeneath by store.observe { it.subagentStack.isNotEmpty() }
    key(shown) {
        BackHandler(enabled = shown && atRoot && pushedBeneath) {
            store.send(HomeFeature.Action.SettingsScreen(PresentationAction.Presented(SettingsFeature.Action.BackTapped)))
        }
    }
}

/** iOS `timeRevealReach` (76 pt, scaled with the footnote style). */
private const val TIME_REVEAL_REACH = 76f
/** How much of the window the drawer button's notice label may span. */
private const val HEADER_LABEL_FRACTION = 0.6f
