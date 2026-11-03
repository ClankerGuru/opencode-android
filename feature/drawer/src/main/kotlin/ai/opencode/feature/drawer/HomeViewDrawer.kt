package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.feature.drawer.list.DrawerListActions
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListRow
import ai.opencode.feature.drawer.list.DrawerListView
import ai.opencode.feature.drawer.list.DrawerPinDrag
import ai.opencode.feature.drawer.list.DrawerRowMenuItem
import ai.opencode.feature.drawer.list.InboxSidebarList
import ai.opencode.feature.drawer.list.rememberInboxSidebarOn
import ai.opencode.feature.drawer.motion.DrawerControlsTrace
import ai.opencode.feature.drawer.motion.DrawerControlsTraceView
import ai.opencode.feature.drawer.motion.DrawerKeyboardMonitor
import ai.opencode.feature.drawer.motion.DrawerRevealTrace
import ai.opencode.feature.drawer.motion.DrawerSearchPill
import ai.opencode.feature.drawer.motion.DrawerSearchPillDefaults
import ai.opencode.feature.drawer.motion.DrawerTimeReveal
import ai.opencode.feature.drawer.motion.RunServerSwapMotion
import ai.opencode.feature.drawer.motion.ServerSwapMotion
import ai.opencode.feature.drawer.motion.drawerControlsTraceProbe
import ai.opencode.feature.drawer.motion.drawerPanList
import ai.opencode.feature.drawer.motion.drawerSearchFocusFade
import ai.opencode.feature.drawer.motion.drawerSearchTitleFade
import ai.opencode.feature.drawer.motion.rememberDrawerShortcutMotion
import ai.opencode.feature.drawer.style.DrawerEdgeStyle
import ai.opencode.feature.drawer.style.DrawerFooterBackdrop
import ai.opencode.feature.drawer.style.DrawerHeaderBackdrop
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerRowLayout
import ai.opencode.feature.drawer.style.DrawerSectionBreak
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.switcher.DrawerTitleView
import ai.opencode.feature.drawer.switcher.ServerFlowTitle
import ai.opencode.feature.drawer.switcher.drawerTitleServers
import ai.opencode.feature.drawer.switcher.rememberServerFlowOn
import ai.opencode.feature.session.ComposerTunables
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.system.SessionWindowTarget
import ai.opencode.feature.system.WindowsClient
import ai.opencode.feature.voice.CallTrayComposerMetrics
import ai.opencode.feature.voice.CallTrayDockSlot
import ai.opencode.feature.voice.CallTrayDockTunables
import ai.opencode.feature.voice.CallTrayTunables
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.floatingCallDrawerButton
import ai.opencode.server.ServerSharedKeys
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/*
 * Port of iOS `HomeView+Drawer.swift`: the drawer's chrome (its title, which is the server
 * switcher, and search; New Session and Settings at its bottom), the list's inputs and the rows'
 * hold menus. The container (`SessionDrawerContainer`) places [HomeDrawer] in its drawer slot;
 * the view-local state iOS keeps on `HomeView` lives in [HomeViewState].
 *
 * The title and search, and New Session and Settings, lie over the list rather than beside it:
 * rows scroll on beneath them and soften into their edges, and they set the list's content
 * padding, so the first row starts below the title and the last scrolls clear of the controls.
 *
 * Android differences: iOS's system bottom scroll-edge effect (`scrollEdgeEffectStyle`) has no
 * Android counterpart, so the drawer always draws its own footer wash ([DrawerFooterBackdrop], iOS's
 * `customBottom` path). `drawerControlsRaisedTargets` is n/a: the controls draw above the list, so
 * Compose's hit testing already gives the part of New Session and Settings that rises above their
 * bar to the buttons. During a call the Call Tray docks in the drawer's bottom slot (D-114):
 * [CallTrayDockSlot] beneath New Session and Settings, which lift above it
 * (`drawerCarriesCallTray`); Home measures the slot for the travelling tray
 * (`drawerCallTrayGeometry`, `HomeCallTrayDock` in `HomeView.kt`).
 * `scrawlScreenName` and `tuningContext` live with [HomeViewState] / have no Tuning Panel.
 */

/** iOS `drawerHeaderTop`: search's centre on the session header buttons' centre line (`TranscriptMetrics.clustered` 8 + (48 − 48) / 2). */
private val DrawerHeaderTop: Dp = 8.dp

/**
 * iOS `drawerFooterBottom`: New Session and Settings centred on the resting composer's line
 * (`ComposerTunables.bottomGap` 0 + (bar 56 − footer 50) / 2). With a call the tray occupies the
 * bottom slot and keeps the composer's bottom edge instead (`bottomGap` 0).
 */
private fun drawerFooterBottom(carriesCallTray: Boolean): Dp =
    if (carriesCallTray) 0.dp else ((CallTrayComposerMetrics.barHeight - DrawerTunables.footerHeight.value) / 2).dp

/**
 * The session drawer's panel: everything inside the drawer, for the container's `drawer` slot.
 *
 * Size it with [modifier] (the drawer's width, full height); it pads itself by the system bars
 * and display cutout (never the keyboard: New Session and Settings stay put while it comes and
 * goes) and fills its background with the theme's canvas. It owns the drawer's
 * [DrawerPinDrag] and installs [HomeViewState.cancelPinDrag] while composed; it reads
 * [HomeViewState.shortcutMotion] and [HomeViewState.keyboardHold] when the container has set them.
 *
 * @param hostsDeleteConfirmation shows the delete confirmation for [HomeFeature.State.pendingDeletion]
 *   (iOS hosts it on `HomeView`, so deletes from the session's own menu confirm too). Pass `false`
 *   when the container places [HomeDeleteConfirmation] at Home's root instead.
 */
@Composable
fun HomeDrawer(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    viewState: HomeViewState,
    modifier: Modifier = Modifier,
    hostsDeleteConfirmation: Boolean = true,
) {
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val reducedMotion = rememberReduceMotion()
    val highContrast = rememberHighContrast()
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val insetTop = with(density) { safe.getTop(this).toDp() }
    val insetBottom = with(density) { safe.getBottom(this).toDp() }

    val pinDrag = remember { DrawerPinDrag() }
    DisposableEffect(pinDrag, viewState) {
        viewState.cancelPinDrag = { pinDrag.cancelDrag() }
        onDispose {
            pinDrag.cancelDrag()
            viewState.cancelPinDrag = {}
        }
    }
    val localShortcutMotion = rememberDrawerShortcutMotion()
    val shortcutMotion = viewState.shortcutMotion ?: localShortcutMotion
    val controlsTrace = remember { DrawerControlsTrace() }

    val isDrawerVisible = rememberIsDrawerVisible(store, viewState)
    if (viewState.shortcutMotion == null) {
        LaunchedEffect(isDrawerVisible) { if (!isDrawerVisible) localShortcutMotion.drawerDidHide() }
    }
    val selectedSessionID by store.observe { viewState.drawerSelectedSessionID(it) }
    val disconnected by store.observe { it.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected }
    val search by store.observe { it.search }
    val projectIconsSwitch by remember { shared(ExperimentalFeature.projectIcons.key).flow }.collectAsState()
    val showsProjectIcons = ExperimentalFeature.projectIcons.isActive(projectIconsSwitch)
    val inboxSidebarOn = rememberInboxSidebarOn()
    // Server Flow (experimental, iOS d8a311bd): the title and the list share one swap motion.
    val serverFlowOn = rememberServerFlowOn()
    val serverSwap = remember { ServerSwapMotion() }
    if (serverFlowOn) RunServerSwapMotion(serverSwap)
    val serverSwapTarget = if (serverFlowOn) {
        val current by store.observe { it.server }
        val switchingTo by store.observe { it.switchingTo }
        val saved by remember { shared(ServerSharedKeys.savedServers).flow }.collectAsState()
        ServerSwapTarget(current, switchingTo, drawerTitleServers(current, saved))
    } else {
        null
    }

    // iOS draws its own bottom edge only with a keyboard on screen (`customBottom`); Android always does.
    val keyboardMonitor: DrawerKeyboardMonitor = viewState.keyboardMonitor
    val hardBottomEdge = highContrast || DrawerTunables.bottomEdge == DrawerEdgeStyle.Hard

    // Measured: the first row starts below the title, the last scrolls clear of the controls.
    var headerHeight by remember { mutableFloatStateOf(0f) }
    var controlsHeight by remember { mutableFloatStateOf(DrawerTunables.footerHeight.value) }
    val controlsReach = (controlsHeight * DrawerTunables.controlsReach.coerceIn(0f, 1f)).dp
    // The drawer carries the Call Tray in its bottom slot (D-114): New Session and Settings lift above it.
    val carriesCallTray = viewState.callTray.dock.liftsDrawerControls
    val trayMotion = if (reducedMotion) snap<Dp>() else CallTrayTunables.close.spec<Dp>()
    val footerBottom by animateDpAsState(drawerFooterBottom(carriesCallTray), trayMotion, label = "drawerFooterBottom")
    val callTrayLift by animateDpAsState(
        if (carriesCallTray) (CallTrayTunables.height + CallTrayDockTunables.gap).dp else 0.dp,
        trayMotion,
        label = "drawerCallTrayLift",
    )

    val inputs = DrawerListInputs(
        selectedSessionID = selectedSessionID,
        showsProjectIcons = showsProjectIcons,
        reducedMotion = reducedMotion,
        increasedContrast = highContrast,
        alwaysShowsHeaders = DrawerTunables.sectionBreak == DrawerSectionBreak.Header,
        twoLineRows = DrawerTunables.rowLayout == DrawerRowLayout.TwoLine,
        serverSwap = serverSwapTarget,
    )
    val actions = remember(store, viewState) {
        DrawerListActions(
            select = viewState::selectSession,
            menu = { row -> rowMenu(store, row) },
            openCatchUp = viewState::openCatchUp,
            openWhatsNew = viewState::openWhatsNew,
            hideWhatsNew = { store.send(HomeFeature.Action.WhatsNew(WhatsNewFeature.Action.HideTapped)) },
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        val listPadding = PaddingValues(
            top = headerHeight.dp + DrawerTunables.topGap,
            bottom = controlsHeight.dp + callTrayLift + insetBottom + footerBottom + DrawerTunables.bottomGap,
        )
        // Inbox Sidebar (experimental, iOS 338d8d92): the sessions as an inbox.
        if (inboxSidebarOn) InboxSidebarList(
            store = store,
            selectedSessionID = selectedSessionID,
            headerScroll = viewState.headerScroll,
            panRegions = viewState.panRegions,
            contentPadding = listPadding,
            select = viewState::selectSession,
            openCatchUp = viewState::openCatchUp,
            modifier = Modifier.fillMaxSize(),
        ) else DrawerListView(
            store = store,
            pinDrag = pinDrag,
            timeReveal = viewState.timeReveal,
            headerScroll = viewState.headerScroll,
            shortcutMotion = shortcutMotion,
            inputs = inputs,
            isDrawerVisible = isDrawerVisible,
            catchUpFocusRequests = viewState.catchUpFocusRequests,
            actions = actions,
            contentPadding = listPadding,
            modifier = Modifier.fillMaxSize(),
            serverSwap = if (serverFlowOn) serverSwap else null,
        )
        // The list as the drawer's pan sees it: between the title band and the controls, which
        // take their own touches (iOS: the hit view is the band, not the collection view).
        Spacer(
            Modifier
                .fillMaxSize()
                .padding(top = headerHeight.dp, bottom = controlsHeight.dp + callTrayLift + insetBottom + footerBottom)
                .drawerPanList(viewState.panRegions),
        )
        if (DrawerTimeReveal.IS_TRACED) DrawerRevealTrace(viewState.timeReveal)

        // The title band: the server's name (the switcher) and search, over the list.
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = it.height / density.density }
                // The band takes its own touches: a row scrolled beneath it is never tapped through it.
                .swallowingTouches(),
        ) {
            // Revealed by the list's scroll, so the list at rest reads clear.
            DrawerHeaderBackdrop(
                opaque = highContrast,
                safeTop = insetTop,
                scrollState = viewState.headerScroll,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DrawerMetrics.rowEdge)
                    .padding(top = insetTop + DrawerHeaderTop, bottom = DrawerTunables.headerBottom),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val titleModifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DrawerMetrics.textInset, end = DrawerSearchPillDefaults.size + DrawerTunables.searchGap)
                    .drawerSearchTitleFade(viewState.search)
                if (serverFlowOn) {
                    ServerFlowTitle(store = store, motion = serverSwap, onManage = viewState::manageServers, modifier = titleModifier)
                } else {
                    DrawerTitleView(store = store, onManage = viewState::manageServers, modifier = titleModifier)
                }
                DrawerSearchPill(
                    state = viewState.search,
                    text = search,
                    onTextChange = { store.send(HomeFeature.Action.SearchChanged(it)) },
                    reducedMotion = reducedMotion,
                    onBegin = viewState::beginSearch,
                    onEnd = viewState::endSearch,
                    keyboardHold = viewState.keyboardHold,
                )
            }
        }

        // New Session and Settings, pinned to the drawer's bottom; they never move with the keyboard.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .drawerSearchFocusFade(viewState.search, reducedMotion),
        ) {
            // From the buttons' top edge to the screen's bottom: rows run on behind their upper
            // part (`controlsReach`), then fade into the drawer's colour.
            DrawerFooterBackdrop(
                opaque = hardBottomEdge,
                reach = controlsReach,
                insetBottom = insetBottom,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DrawerMetrics.rowEdge)
                    .padding(bottom = insetBottom + footerBottom)
                    // Their band takes its own touches, the disabled pill's included.
                    .swallowingTouches(),
            ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { controlsHeight = it.height / density.density }
                    .then(if (DrawerControlsTrace.IS_TRACED) Modifier.drawerControlsTraceProbe(controlsTrace) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawerNewSessionButton(enabled = !disconnected, onClick = viewState::startNewSessionFromDrawer)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DrawerCallSlot(store, disconnected, reducedMotion)
                    OpenCodeIconButton(
                        symbol = SFSymbol.GearshapeFill,
                        contentDescription = "Settings",
                        onClick = viewState::openSettings,
                        size = DrawerTunables.footerHeight,
                        modifier = Modifier.testTag("drawer-settings"),
                    )
                }
            }
            AnimatedVisibility(
                visible = carriesCallTray,
                enter = if (reducedMotion) EnterTransition.None else expandVertically(CallTrayTunables.close.spec()),
                exit = if (reducedMotion) ExitTransition.None else shrinkVertically(CallTrayTunables.close.spec()),
            ) {
                // The open session's call, for the slot's Mute and End Call actions.
                CallTrayDockSlot(store = rememberHomeCall(store))
            }
            }
        }
        if (DrawerControlsTrace.IS_TRACED) DrawerControlsTraceView(controlsTrace, keyboardMonitor)
    }

    if (hostsDeleteConfirmation) HomeDeleteConfirmation(store)
}

/**
 * Whether the drawer can be seen (iOS `isDrawerVisible`): in the foreground, not catching up, and
 * open (or, beside a sidebar, the sidebar showing).
 */
@Composable
private fun rememberIsDrawerVisible(store: StoreOf<HomeFeature.State, HomeFeature.Action>, viewState: HomeViewState): Boolean {
    val catchingUp by store.observe { it.catchUp != null }
    val showsDrawerLabel by store.observe { it.showsDrawerLabel }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val sidebarShown by store.observe { it.isSidebarShown }
    return viewState.isSceneActive && !catchingUp &&
        (if (showsDrawerLabel) viewState.isSessionDrawerPresented || isDrawerOpen else sidebarShown)
}

/**
 * A row's hold menu (iOS `rowMenu(_:isPinned:)`): Pin or Unpin, Mark as Read or Unread, Mute or
 * Unmute, and Delete (which asks first, [HomeDeleteConfirmation]). Where the device has several
 * windows (a tablet, iOS's iPad), Open in New Window comes first ([WindowsClient]).
 */
private fun rowMenu(store: StoreOf<HomeFeature.State, HomeFeature.Action>, row: DrawerListRow): List<DrawerRowMenuItem> {
    val state = store.state
    val session = row.session
    val unread = state.isUnread(session)
    val muted = state.isMuted(session.id)
    val isPinned = row.isPinnedSection
    val windows = dependency(WindowsClient)
    // Multiple Windows (tablet): the session in a window of its own.
    val newWindow = if (windows.isAvailable()) {
        listOf(
            DrawerRowMenuItem("Open in New Window", SFSymbol.MacwindowBadgePlus) {
                windows.open(SessionWindowTarget(serverID = state.server.persistenceID, sessionID = session.id))
            },
        )
    } else {
        emptyList()
    }
    return newWindow + listOf(
        DrawerRowMenuItem(if (isPinned) "Unpin" else "Pin", if (isPinned) SFSymbol.PinSlash else SFSymbol.Pin) {
            store.send(HomeFeature.Action.TogglePin(session.id))
        },
        DrawerRowMenuItem(
            if (unread) "Mark as Read" else "Mark as Unread",
            if (unread) SFSymbol.EnvelopeOpen else SFSymbol.EnvelopeBadge,
        ) {
            store.send(if (unread) HomeFeature.Action.MarkRead(session.id) else HomeFeature.Action.MarkUnread(session.id))
        },
        DrawerRowMenuItem(if (muted) "Unmute" else "Mute", if (muted) SFSymbol.Bell else SFSymbol.BellSlash) {
            store.send(HomeFeature.Action.ToggleMute(session.id))
        },
        DrawerRowMenuItem("Delete", SFSymbol.Trash, destructive = true) {
            store.send(HomeFeature.Action.DeleteSessionTapped(session.id))
        },
    )
}

/**
 * The delete confirmation (iOS `HomeView`'s `confirmationDialog`): “Delete “<title>”?”, This
 * removes the session from the server for all clients., Delete (destructive) and Cancel.
 */
@Composable
fun HomeDeleteConfirmation(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val pending by store.observe { it.pendingDeletion }
    val session = pending ?: return
    AlertDialog(
        onDismissRequest = { store.send(HomeFeature.Action.DeleteSessionCancelled) },
        title = { Text("Delete “" + session.title + "”?") },
        text = { Text("This removes the session from the server for all clients.") },
        confirmButton = {
            TextButton(onClick = { store.send(HomeFeature.Action.DeleteSessionConfirmed) }) {
                Text("Delete", color = OpenCodeTheme.colors.destructive)
            }
        },
        dismissButton = {
            TextButton(onClick = { store.send(HomeFeature.Action.DeleteSessionCancelled) }) { Text("Cancel") }
        },
        modifier = Modifier.testTag("drawer-delete-confirmation"),
    )
}

/**
 * New Session's pill (iOS `DrawerNewSessionLabel` in its glass capsule; glass → an M3 tonal
 * capsule). The symbol's ink sits as far from the pill's leading end as from its top at every
 * text size (OC-477), so its leading padding shrinks as the symbol grows; the trailing stays
 * `footerPadding`. Disconnected, the label dims (not the capsule) and it takes no taps.
 */
@Composable
private fun DrawerNewSessionButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    // iOS caps the label at accessibility1 (28 pt over 17 pt body).
    val fontScale = min(LocalDensity.current.fontScale, 28f / 17f)
    val glyph = 17f * fontScale
    val height = DrawerTunables.footerHeight.value
    val bearing = DrawerTunables.footerGlyphBearing.value * glyph / 17f
    val rise = DrawerTunables.footerGlyphRise.value * glyph / 17f
    val tint = if (enabled) colors.text else colors.tertiaryText
    Surface(
        shape = OpenCodeShape.capsule,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = tint,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .testTag("drawer-new-session")
            .clearAndSetSemantics {
                contentDescription = "New Session"
                role = Role.Button
                if (!enabled) disabled()
                onClick { onClick(); true }
            },
    ) {
        Row(
            Modifier
                .heightIn(min = DrawerTunables.footerHeight)
                .padding(start = max(4f, (height - glyph) / 2 - bearing).dp, end = DrawerTunables.footerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpenCodeIcon(
                SFSymbol.SquareAndPencil,
                contentDescription = null,
                size = glyph.dp,
                tint = tint,
                modifier = Modifier.offset(y = -rise.dp),
            )
            Text(
                "New Session",
                style = OpenCodeTextStyles.body.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Takes the touches that land on this band, so nothing beneath it reacts (iOS `contentShape` over the band). */
private fun Modifier.swallowingTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * The drawer's call button (iOS `DrawerCallButton`), left of Settings while the drawer offers a
 * call ([drawerOffersCall]): a call on the session behind the drawer. Its slot stays while a call
 * is live, so a call ending with the drawer open lands back in it; the button itself shows only
 * with no call ([drawerShowsCallButton]).
 */
@Composable
private fun DrawerCallSlot(store: StoreOf<HomeFeature.State, HomeFeature.Action>, disconnected: Boolean, reducedMotion: Boolean) {
    val center = remember { FloatingCallCenter.shared }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val offersCall by store.observe { it.drawerOffersCall }
    val showsCall by store.observe { it.drawerShowsCallButton }
    SideEffect { center.isDrawerOpen = isDrawerOpen }
    DisposableEffect(center) {
        onDispose {
            center.isDrawerOpen = false
            center.drawerCallButton = null
        }
    }
    if (!offersCall) return
    Box(Modifier.size(DrawerTunables.footerHeight), contentAlignment = Alignment.Center) {
        // The circle a floating call breaks out of and lands back in, as the composer's call button's.
        Box(Modifier.size(ComposerTunables.buttonDiameter.dp).floatingCallDrawerButton())
        AnimatedVisibility(
            visible = showsCall,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(CallTrayTunables.close.spec()),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(CallTrayTunables.close.spec()),
        ) {
            OpenCodeIconButton(
                symbol = SFSymbol.Waveform,
                contentDescription = "Start Voice Call",
                onClick = {
                    // The orb breaks out of this button, not the composer's under the drawer.
                    center.breakoutFrom = center.drawerCallButton
                    store.send(HomeFeature.Action.DrawerCallTapped)
                },
                size = DrawerTunables.footerHeight,
                enabled = !disconnected,
                modifier = Modifier.testTag("drawer-call"),
            )
        }
    }
}
