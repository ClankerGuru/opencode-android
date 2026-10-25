package ai.opencode.feature.drawer

import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.bridge.SessionScreenEvent
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionView
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsView
import ai.opencode.server.friendlyName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/** Navigation3 keys for Home's screens: the root session (or draft), then pushed subagents. */
sealed interface HomeRoute {
    data class Root(val navigationID: Int) : HomeRoute
    data class Subagent(val navigationID: Int) : HomeRoute
}

/**
 * Interim stand-in for iOS `HomeView` + `SessionDrawerContainer`, kept compiling against the ported
 * [HomeFeature.State] until the drawer view helpers replace it: a modal drawer over the root screen
 * and pushed subagents, and Settings (the stub) while [HomeFeature.State.isSettingsShown].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(store: StoreOf<HomeFeature.State, HomeFeature.Action>, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(HomeFeature.Action.Task) }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val serverName by store.observe { it.server.friendlyName }
    val rootID by store.observe { it.sessionDetail?.navigationID }
    val openSessionID by store.observe { it.sessionDetail?.session?.id }
    val stackIDs by store.observe { state -> state.subagentStack.map { it.navigationID } }
    val sessions by store.observe { it.sessions }
    val settingsShown by store.observe { it.isSettingsShown }
    val drawerState = rememberDrawerState(if (isDrawerOpen) DrawerValue.Open else DrawerValue.Closed)

    // Reducer state is the source of truth; the drawer's own gestures report back.
    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue == DrawerValue.Open }.collect { open ->
            if (open != store.state.isDrawerOpen) store.send(HomeFeature.Action.SessionDrawerArrived(open))
        }
    }
    val openDrawer = { store.send(HomeFeature.Action.SessionDrawerArrived(true)) }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text(serverName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    NavigationDrawerItem(
                        label = { Text("New Session") },
                        selected = openSessionID == null,
                        onClick = { store.send(HomeFeature.Action.NewSessionTapped) },
                    )
                    sessions.forEach { session ->
                        NavigationDrawerItem(
                            label = { Text(session.title.ifBlank { "Untitled" }, maxLines = 1) },
                            selected = openSessionID == session.id,
                            onClick = {
                                store.send(HomeFeature.Action.SessionTapped(session.id))
                                store.send(HomeFeature.Action.SessionDrawerArrived(false))
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = { store.send(HomeFeature.Action.SettingsTapped) },
                    )
                }
            }
        },
    ) {
        val root = rootID
        val backStack: List<HomeRoute> = listOfNotNull(root?.let(HomeRoute::Root)) + stackIDs.map(HomeRoute::Subagent)
        if (backStack.isNotEmpty()) {
            NavDisplay(
                backStack = backStack,
                onBack = { store.send(HomeFeature.Action.SubagentsPopped(maxOf(stackIDs.size - 1, 0))) },
                entryProvider = { route ->
                    when (route) {
                        is HomeRoute.Root -> NavEntry(route) {
                            val detail = remember(store, route) {
                                store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
                                    state = { it.sessionDetail?.feature ?: SessionFeature.State() },
                                    action = { HomeFeature.Action.SessionDetail(PresentationAction.Presented(it)) },
                                )
                            }
                            LaunchedEffect(route) {
                                store.send(HomeFeature.Action.SessionDetailEvent(SessionScreenEvent.VisibilityChanged(true)))
                            }
                            SessionView(detail, navigationIcon = { DrawerButton(openDrawer) })
                        }
                        is HomeRoute.Subagent -> NavEntry(route) {
                            val detail = remember(store, route) {
                                store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
                                    state = { home ->
                                        home.subagentStack.firstOrNull { it.navigationID == route.navigationID }?.feature ?: SessionFeature.State()
                                    },
                                    action = { HomeFeature.Action.SubagentStack(route.navigationID, it) },
                                )
                            }
                            SessionView(detail, navigationIcon = { DrawerButton(openDrawer) })
                        }
                    }
                },
            )
        }
    }

    if (settingsShown) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { store.send(HomeFeature.Action.SettingsShownChanged(false)) },
            sheetState = sheetState,
        ) {
            val settings = remember(store) {
                store.scope<HomeFeature.State, HomeFeature.Action, SettingsFeature.State, SettingsFeature.Action>(
                    state = { SettingsFeature.State(serverName = it.server.friendlyName) },
                    action = { action ->
                        when (action) {
                            SettingsFeature.Action.ServersTapped ->
                                HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServersRequested)
                            SettingsFeature.Action.Task -> HomeFeature.Action.SettingsShownChanged(true)
                        }
                    },
                )
            }
            SettingsView(settings)
        }
    }
}

@Composable
private fun DrawerButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        OpenCodeIcon(SFSymbol.Line3Horizontal, contentDescription = "Sessions")
    }
}
