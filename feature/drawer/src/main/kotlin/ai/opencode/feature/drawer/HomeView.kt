package ai.opencode.feature.drawer

import ai.opencode.devtools.traces.TraceUploadsHost
import ai.opencode.core.store.rememberScope
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
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.feature.session.InAppNotice
import ai.opencode.feature.drawer.notices.InAppNoticeSettings
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.core.experiments.ExperimentalCallStyle
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.Motion
import ai.opencode.core.sharing.shared
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.voice.CallTrayController
import ai.opencode.feature.voice.CallTrayDock
import ai.opencode.feature.voice.CallTrayDockGeometry
import ai.opencode.feature.voice.CallTrayDockOverlay
import ai.opencode.feature.voice.CallTrayTunables
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.FloatingCallOverlay
import ai.opencode.feature.voice.VoiceRoutePreferences
import ai.opencode.feature.voice.VoiceStoreOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.max
import ai.opencode.server.OpenCodeServer
import androidx.compose.runtime.collectAsState
import ai.opencode.feature.drawer.switcher.SessionSwitcherOverlay
import ai.opencode.feature.session.SessionFeature
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.SessionBrowserPage
import ai.opencode.feature.session.SessionListContext
import ai.opencode.feature.session.SessionPageStack
import ai.opencode.feature.session.SessionStackPage
import ai.opencode.feature.session.sessionBrowserStackPage
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.DisposableEffect
import ai.opencode.feature.session.SessionView
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.LocalAppearanceTranscriptPreview
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPresentation
import ai.opencode.feature.settings.SettingsView
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * Port of `OpenCode/Drawer/HomeView.swift`: one server's Home. The open session (or a draft) on
 * the session drawer's card ([SessionDrawerContainer]), its pushed subagents and in-app browser
 * pages (`:feature:session`'s [SessionPageStack], with predictive back), the call's tray and
 * floating orb, Catch Up, Settings pushed over everything, the delete confirmation, and the
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
        ai.opencode.feature.composer.OpenCodeSheetHost {
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
    }
    HomeSettingsBack(store)
    HomeLifecycle(store, view)
    // iOS hosts it on Home, so deletes from the session's own menu confirm too.
    HomeDeleteConfirmation(store)
    // iOS `.traceUploads(store.scope(\.traces, action: \.traces))`: Report a Problem and its toast.
    TraceUploadsHost(store.rememberScope({ it.traces }, HomeFeature.Action::Traces))
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
    // A subagent or a web page pushed over the session: its back gesture has the drag.
    val pushed by store.observe { it.subagentStack.isNotEmpty() || it.sessionDetail?.browser?.pageID != null }
    val settingsShown by store.observe { it.isSettingsShown }
    val sheetPresented by store.observe { it.topDetail?.activePicker != null || it.topDetail?.shellOutput != null }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val catchUpProxy = remember { CatchUpStageProxy() }
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    val callPresentation = rememberCallPresentation()
    val usesTray = callPresentation == ExperimentalCallStyle.tray
    SideEffect { view.usesCallTray = usesTray }
    val callTray = view.callTray
    // The open session's call (iOS `sessionStore.scope(\.voice, action: \.voice.presented)`).
    val call = rememberHomeCall(store)
    // How far the drawer is open, each frame it moves: only the travelling tray reads these.
    val drawerProgress = remember { mutableFloatStateOf(0f) }
    val drawerTravels = remember { mutableStateOf(true) }
    val firstUsesTray = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(usesTray) {
        val previous = firstUsesTray.value
        firstUsesTray.value = usesTray
        if (previous != null && !usesTray) callTray.reset()
    }
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
        // The Call Tray follows the drawer into its bottom slot (D-114).
        onDrawerProgress = { progress, travels ->
            drawerProgress.floatValue = progress
            drawerTravels.value = travels
        },
        dismissesKeyboardOnOpen = usesTray && (callTray.showsSurface || call != null),
        contentSessionID = DrawerKeyboard.closingSession(chosen = view.chosenSessionID, shown = selectedSessionID),
        isContentPushed = pushed,
        isSheetPresented = settingsShown || sheetPresented,
        content = {
            SessionSwitcherOverlay(
                store = store,
                drawerOpen = view.isSessionDrawerPresented || isDrawerOpen,
                usesSidebar = usesSidebar,
                modifier = Modifier.fillMaxSize(),
            ) {
                HomeSessionStack(store, view, usesSidebar, containerWidth, callTray = if (usesTray) callTray else null)
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
    // During a call the tray docks in the drawer beneath New Session (D-114).
    if (usesTray && !usesSidebar) {
        HomeCallTrayDock(
            controller = callTray,
            call = call,
            reducedMotion = reducedMotion,
            drawerWidth = drawerWidth,
            isCatchingUp = catchUp != null,
            drawerProgress = { drawerProgress.floatValue },
            drawerTravels = { drawerTravels.value },
        )
    }
    // A floating call stays put above both while the card slides and sessions change: the call
    // belongs to the root session.
    HomeFloatingCall(if (callPresentation == ExperimentalCallStyle.floating) call else null)
}

/** iOS `HomeView.callPresentation`: Settings' Call Style, from the call experiments. */
@Composable
private fun rememberCallPresentation(): ExperimentalCallStyle {
    val callTraySetting by remember { shared(ExperimentalFeature.callTray.key).flow }.collectAsState()
    val floatingCallSetting by remember { shared(ExperimentalFeature.floatingCall.key).flow }.collectAsState()
    val livingOrbSetting by remember { shared(ExperimentalFeature.livingOrb.key).flow }.collectAsState()
    val floatingCenter = remember { FloatingCallCenter.shared }
    return ExperimentalCallStyle.of(
        callTray = ExperimentalFeature.callTray.isActive(callTraySetting),
        floatingCall = floatingCenter.isOn(floatingCallSetting),
        livingOrb = ExperimentalFeature.livingOrb.isActive(livingOrbSetting),
    )
}

/**
 * The travelling Call Tray over Home (iOS `CallTrayDockOverlay` in `homeLayout`), fed the drawer's
 * slot (iOS `drawerCallTrayGeometry`: where New Session and Settings stand without a call, as
 * wide as their row, in window space) and whether it may dock ([CallTrayDock.mayDock]).
 */
@Composable
private fun HomeCallTrayDock(
    controller: CallTrayController,
    call: VoiceStoreOf?,
    reducedMotion: Boolean,
    drawerWidth: Dp,
    isCatchingUp: Boolean,
    drawerProgress: () -> Float,
    drawerTravels: () -> Boolean,
) {
    val density = LocalDensity.current
    val insetBottom = with(density) { WindowInsets.systemBars.union(WindowInsets.displayCutout).getBottom(this).toDp() }.value
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() / density.density }) {
        val screen = Rect(origin.x, origin.y, origin.x + maxWidth.value, origin.y + maxHeight.value)
        val height = CallTrayTunables.height
        val edge = DrawerMetrics.rowEdge.value
        // The slot's bottom is the resting composer's (`ComposerTunables.bottomGap`, 0).
        val slotTop = screen.bottom - insetBottom - height
        val geometry = CallTrayDockGeometry(
            slot = Rect(screen.left + edge, slotTop, screen.left + edge + max(1f, drawerWidth.value - 2 * edge), slotTop + height),
            bounds = Rect(screen.left, screen.top, screen.left + drawerWidth.value, screen.bottom),
            // The phone's drawer recedes behind the card as it closes (iOS: phones, not iPads).
            recedes = !reducedMotion,
            screen = screen,
        )
        CallTrayDockOverlay(
            controller = controller,
            store = call,
            reducedMotion = reducedMotion,
            drawerProgress = drawerProgress,
            drawerTravels = drawerTravels,
            mayDock = CallTrayDock.mayDock(usesTray = true, usesSidebar = false, isCatchingUp = isCatchingUp, isResting = controller.isResting),
            drawer = geometry,
        )
    }
}

/**
 * The floating call, full-window over Home (iOS `homeLayout`'s second overlay): shown while the
 * call doesn't show its card. A call that fails or drops hands over to its card: the orb fades
 * rather than vanishing (a hang-up already shrinks it away).
 */
@Composable
private fun HomeFloatingCall(call: VoiceStoreOf?) {
    val showsCard = if (call == null) null else call.observe { it.phase.showsCard }.value
    AnimatedVisibility(
        visible = call != null && showsCard == false,
        enter = EnterTransition.None,
        exit = fadeOut(Motion.easeOut(0.25)),
    ) {
        if (call != null) FloatingCallOverlay(call)
    }
}

/**
 * The open session's call, scoped from its screen (iOS `sessionStore.scope(\.voice, action:
 * \.voice.presented)`): null while there's none. It keeps its last state while it leaves, so a
 * view still drawing it never reads a missing call.
 */
@Composable
internal fun rememberHomeCall(store: StoreOf<HomeFeature.State, HomeFeature.Action>): VoiceStoreOf? {
    val present by store.observe { it.sessionDetail?.voice != null }
    if (!present) return null
    return remember(store) {
        var last: VoiceFeature.State? = null
        store.scope<HomeFeature.State, HomeFeature.Action, VoiceFeature.State, VoiceFeature.Action>(
            state = { home -> (home.sessionDetail?.voice ?: last ?: error("rememberHomeCall read before the call appeared")).also { last = it } },
            action = { HomeFeature.Action.detail(SessionFeature.Action.voicePresented(it)) },
        )
    }
}

/** The open session's screen (iOS `store.scope(\.sessionDetail, action: \.sessionDetail.presented)`), keeping its last state while it leaves. */
@Composable
private fun rememberRootStore(store: StoreOf<HomeFeature.State, HomeFeature.Action>): StoreOf<SessionFeature.State, SessionFeature.Action> =
    remember(store) {
        var last: SessionFeature.State? = store.state.sessionDetail
        store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
            state = { home -> (home.sessionDetail ?: last ?: SessionFeature.State.draft(home.server, null, emptyList())).also { last = it } },
            action = { HomeFeature.Action.detail(it) },
        )
    }

/** A pushed subagent's screen by its `navigationID` (iOS `subagentStack` element scope), drawable while it animates out. */
private fun subagentStore(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    navigationID: Int,
): StoreOf<SessionFeature.State, SessionFeature.Action> {
    var last: SessionFeature.State? = store.state.subagentStack.firstOrNull { it.navigationID == navigationID }
    return store.scope(
        state = { home ->
            (home.subagentStack.firstOrNull { it.navigationID == navigationID } ?: last ?: SessionFeature.State.draft(home.server, null, emptyList()))
                .also { last = it }
        },
        action = { HomeFeature.Action.SubagentStack(navigationID, it) },
    )
}

/** One page of the session's stack (iOS `StackEntry`): a subagent at [index], or a web page over the screen [owner] names. */
private sealed interface StackEntry {
    val page: SessionStackPage

    data class Subagent(val index: Int, val navigationID: Int, override val page: SessionStackPage) : StackEntry

    /** [owner]: the screen that opened it, a subagent's `navigationID` or null for the session. */
    data class Web(val owner: Int?, override val page: SessionStackPage) : StackEntry
}

/**
 * What the session's stack shows, bottom first: each subagent pushed over the session, and a web
 * page over whichever screen opened it (iOS `stackPages`, D-40). Back (the back button, the back
 * gesture, TalkBack's escape) goes to the screen beneath.
 */
private fun stackEntries(state: HomeFeature.State): List<StackEntry> {
    val entries = ArrayList<StackEntry>()
    fun web(owner: Int?, screen: SessionFeature.State) {
        val page = sessionBrowserStackPage(screen) ?: return
        entries += StackEntry.Web(owner, page)
    }
    state.sessionDetail?.let { web(null, it) }
    for ((index, child) in state.subagentStack.withIndex()) {
        entries += StackEntry.Subagent(index, child.navigationID, SessionStackPage(id = child.navigationID))
        web(child.navigationID, child)
    }
    return entries
}

/**
 * The session (or draft) at the root of [SessionPageStack], with each subagent pushed over it and
 * any in-app browser page over the screen that opened it. The root's call shows on whichever
 * subagent is on top (`hostedCall`). With the Call Tray style every page's composer hosts Home's
 * [callTray] (iOS `callTray: usesTray ? callTray : nil`).
 */
@Composable
private fun HomeSessionStack(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
    containerWidth: Dp,
    callTray: CallTrayController?,
) {
    val root = rememberRootStore(store)
    val call = rememberHomeCall(store)
    val entries by store.observe(::stackEntries)
    val catchingUp by store.observe { it.catchUp != null }
    val drawerOpen by store.observe { it.isDrawerOpen }
    val sheetPresented by store.observe {
        val top = it.topDetail
        top?.activePicker != null || top?.shellOutput != null
    }
    val listContext by store.observe { it.sessionListContext(it.sessionDetail?.session) }
    // Pages drawn while they animate out, by their identity.
    val known = remember { HashMap<Int, StackEntry>() }
    for (entry in entries) known[entry.page.id] = entry
    val subagentStores = remember(store) { HashMap<Int, StoreOf<SessionFeature.State, SessionFeature.Action>>() }
    fun subagent(navigationID: Int) = subagentStores.getOrPut(navigationID) { subagentStore(store, navigationID) }
    SessionPageStack(
        pages = entries.map { it.page },
        onPop = { depth ->
            // The reader went back one page: a web page closes; a subagent pops to the screen beneath it.
            val current = stackEntries(store.state)
            when (val entry = current.getOrNull(depth)) {
                null -> store.send(HomeFeature.Action.SubagentsPopped(depth))
                is StackEntry.Web -> {
                    val close = SessionFeature.Action.Browser(BrowserFeature.Action.CloseTapped)
                    store.send(if (entry.owner == null) HomeFeature.Action.detail(close) else HomeFeature.Action.SubagentStack(entry.owner, close))
                }
                is StackEntry.Subagent -> store.send(HomeFeature.Action.SubagentsPopped(entry.index))
            }
        },
        modifier = Modifier.fillMaxSize(),
        enabled = !catchingUp && !drawerOpen && !view.isSessionDrawerPresented && !sheetPresented,
        root = {
            SessionView(
                root,
                navigationIcon = { HomeDrawerButton(store, view, usesSidebar, containerWidth) },
                listContext = listContext,
                onCatchUpOpen = if (catchingUp) ({ store.send(HomeFeature.Action.catchUpOpenTapped) }) else null,
                callTray = callTray,
            )
        },
    ) { page ->
        when (val entry = known[page.id]) {
            is StackEntry.Subagent -> {
                val child = subagent(entry.navigationID)
                val project by child.observe { SessionListContext.project(it.session, store.state.projects) }
                SessionView(
                    child,
                    listContext = SessionListContext(project = project),
                    onBack = {
                        val index = store.state.subagentStack.indexOfFirst { it.navigationID == entry.navigationID }
                        if (index >= 0) store.send(HomeFeature.Action.SubagentsPopped(index))
                    },
                    hostedCall = call,
                    callTray = callTray,
                )
            }
            is StackEntry.Web -> SessionBrowserPage(if (entry.owner == null) root else subagent(entry.owner), Modifier.fillMaxSize())
            null -> Unit
        }
    }
    // Stores for subagents that left go once their pages have.
    val liveIDs = entries.mapNotNull { (it as? StackEntry.Subagent)?.navigationID }.toSet()
    SideEffect { subagentStores.keys.retainAll { it in liveIDs || known[it] != null } }
}

/**
 * What a session screen shows of the drawer's knowledge of it (iOS `sessionListContext(for:)`):
 * its project, pin, read and mute state, the drawer button's dot for other sessions, and the
 * In-App Notice, outside Catch Up.
 */
internal fun HomeFeature.State.sessionListContext(session: OpenCodeSession?): SessionListContext {
    val listed = session?.let { s -> sessions.firstOrNull { it.id == s.id } }
    val isCatchUp = catchUp != null
    return SessionListContext(
        project = SessionListContext.project(session, projects),
        isPinned = listed?.let { isPinned(it.id) },
        isUnread = listed?.let { isUnread(it) } ?: false,
        isMuted = listed?.let { isMuted(it.id) } ?: false,
        elsewhere = if (isCatchUp) null else drawerBadge(excluding = session?.id),
        notice = if (isCatchUp) null else inAppNotice,
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
 * The real session screen for the card behind the open one, rendered from its cached transcript
 * by an inert store (iOS `CatchUpPreviewCard` over `CatchUpPreviewStores`). One store for the
 * preview host: its state is replaced in place, so its screen's scaffold is reused across swipes.
 * Inert: it reads nothing and sends nothing, and the stack hides it from TalkBack.
 */
@Composable
private fun CatchUpPreviewCard(store: StoreOf<HomeFeature.State, HomeFeature.Action>, sessionID: String, revision: Int) {
    val home = store.state
    val session = remember(sessionID, revision) { home.sessions.firstOrNull { it.id == sessionID } }
    if (session == null) {
        Box(Modifier.fillMaxSize().background(OpenCodeTheme.colors.canvas))
        return
    }
    val previews = remember { CatchUpPreviewStores() }
    DisposableEffect(previews) { onDispose { previews.close() } }
    val preview = previews.store(session, home)
    SideEffect { previews.commit() }
    SessionView(
        preview,
        Modifier.fillMaxSize().clearAndSetSemantics {},
        listContext = remember(session, revision) { home.sessionListContext(session) },
        onCatchUpOpen = {},
    )
}

/**
 * iOS `CatchUpPreviewStores`: the inert store behind the Catch Up preview card, rebuilt from what
 * Home knows (cached transcript, run state, remembered snapshot) only when that changes. Its
 * reducer honours the renderer's first-frame event, as the live screen does, and nothing else: a
 * preview performs no loading, request handling or read receipts.
 */
internal class CatchUpPreviewStores {
    private data class Inputs(
        val session: OpenCodeSession,
        val items: List<OpenCodeTranscriptItem>?,
        val isRunning: Boolean,
        val snapshot: SessionFeature.SwitchSnapshot?,
        val activeFamilyIDs: Set<String>,
    )

    private var inputs: Inputs? = null
    private var store: Store<SessionFeature.State, SessionFeature.Action>? = null
    private var pending: SessionFeature.State? = null

    fun store(session: OpenCodeSession, home: HomeFeature.State): StoreOf<SessionFeature.State, SessionFeature.Action> {
        val items = home.transcriptCache[session.id]
        val isRunning = session.id in home.activeSessionIDs
        val snapshot = home.sessionSnapshots[session.id]
        val familyIDs = snapshot?.family?.mapTo(HashSet()) { it.id } ?: setOf(session.id)
        val active = if (home.activeSessionIDs.isEmpty()) emptySet() else home.activeSessionIDs intersect familyIDs
        val next = Inputs(session, items, isRunning, snapshot, active)
        val existing = store
        if (existing != null && inputs == next) return existing
        var state = SessionFeature.State.session(home.server, session, cachedItems = items, isRunning = isRunning)
        state = home.seedingSessionSnapshot(state).copy(readsOnView = false)
        if (existing != null && inputs?.session?.id == session.id && existing.state.hasRenderedFirstContentFrame) {
            state = state.copy(hasRenderedFirstContentFrame = true)
        }
        inputs = next
        if (existing != null) {
            // Applied after composition ([commit]): one host, one store, its state replaced in place.
            pending = state
            return existing
        }
        val created = Store<SessionFeature.State, SessionFeature.Action>(
            state,
            Reducer { current, action ->
                when (action) {
                    SessionFeature.Action.FirstContentFrameRendered -> Reduced(current.copy(hasRenderedFirstContentFrame = true))
                    is SessionFeature.Action.PreviewStateReplaced -> Reduced(action.state)
                    else -> Reduced(current)
                }
            },
        )
        store = created
        return created
    }

    fun commit() {
        val next = pending ?: return
        pending = null
        store?.send(SessionFeature.Action.PreviewStateReplaced(next))
    }

    fun close() {
        store?.close()
        store = null
    }
}

/** iOS `homeLayout`'s `onChange(of: usesSidebar)` and `onChange(of: store.opensDrawerForPairing)`, both `initial: true`. */
@Composable
private fun HomeSidebarEffects(store: StoreOf<HomeFeature.State, HomeFeature.Action>, view: HomeViewState, usesSidebar: Boolean) {
    val opensDrawerForPairing by store.observe { it.opensDrawerForPairing }
    // Beside a sidebar the drawer button has no label, so In-App Notices acts as Off, banners included.
    LaunchedEffect(usesSidebar) {
        if (usesSidebar) view.isSessionDrawerPresented = false
        dependency(SessionNotificationsClient).setShowsDrawerLabel(!usesSidebar)
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
    val server by store.observe { it.server }
    val isVoiceAvailable = rememberVoiceAvailable(server)
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
        if (view.usesCallTray) view.callTray.reset()
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
    // Settings changes these outside any Home action; the view sees them and tells Home, so the
    // Start Call quick action follows.
    LaunchedEffect(serverID, isVoiceAvailable) {
        store.send(HomeFeature.Action.SystemPublishRequested(isVoiceAvailable))
    }
    // Notifications arriving in the foreground read In-App Notices' mode through Home's reader
    // (iOS reads the shared key directly; `:feature:system` can't see the drawer's settings).
    LaunchedEffect(Unit) {
        val settings = dependency(InAppNoticeSettings)
        dependency(SessionNotificationsClient).setInAppNoticesProvider { settings.mode() != InAppNoticeMode.off }
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
    CompositionLocalProvider(LocalAppearanceTranscriptPreview provides AppearanceTranscriptPreviewRows) { SettingsView(settings) }
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

/**
 * iOS `HomeView.isVoiceAvailable` (`store.voicePreferences.isAvailable(server:)`): Voice set up for
 * [server], recomputed whenever a setting it reads changes (Settings changes them outside any
 * Home action).
 */
@Composable
private fun rememberVoiceAvailable(server: OpenCodeServer): Boolean {
    val preferences = remember { VoiceRoutePreferences() }
    val enabled by preferences.enabled.flow.collectAsState()
    val engine by preferences.engine.flow.collectAsState()
    val openAIKey by preferences.openAIKey.flow.collectAsState()
    val xaiKey by preferences.xaiKey.flow.collectAsState()
    val serverVoice by preferences.serverVoice.flow.collectAsState()
    val provider by preferences.provider.flow.collectAsState()
    val voiceServer by preferences.voiceServer.flow.collectAsState()
    val support by preferences.support.flow.collectAsState()
    return remember(server, enabled, engine, openAIKey, xaiKey, serverVoice, provider, voiceServer, support) {
        preferences.isAvailable(server)
    }
}
