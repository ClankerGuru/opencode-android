package ai.opencode.feature.drawer

import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionView
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsView
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

/** Navigation3 keys for Home's session stack: the draft at the root, then opened sessions. */
sealed interface HomeRoute {
    data object Draft : HomeRoute
    data class Session(val sessionID: String) : HomeRoute
}

/**
 * Phase 0 placeholder for iOS `HomeView` + `SessionDrawerContainer`: a modal drawer over the
 * session stack ([NavDisplay] driven by [HomeFeature.State.stack]) and the Settings sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(store: StoreOf<HomeFeature.State, HomeFeature.Action>, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(HomeFeature.Action.Task) }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val serverName by store.observe { it.serverName }
    val stackIDs by store.observe { state -> state.stack.mapNotNull { it.sessionID } }
    val settingsShown by store.observe { it.settings != null }
    val drawerState = rememberDrawerState(if (isDrawerOpen) DrawerValue.Open else DrawerValue.Closed)

    // Reducer state is the source of truth; the drawer's own gestures report back.
    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue == DrawerValue.Open }.collect { open ->
            if (open != store.state.isDrawerOpen) store.send(HomeFeature.Action.DrawerOpenChanged(open))
        }
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text(serverName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    NavigationDrawerItem(
                        label = { Text("New Session") },
                        selected = stackIDs.isEmpty(),
                        onClick = { store.send(HomeFeature.Action.NewSessionTapped) },
                    )
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
        val backStack: List<HomeRoute> = listOf<HomeRoute>(HomeRoute.Draft) + stackIDs.map(HomeRoute::Session)
        NavDisplay(
            backStack = backStack,
            onBack = { store.send(HomeFeature.Action.StackPopped) },
            entryProvider = { route ->
                when (route) {
                    HomeRoute.Draft -> NavEntry(route) {
                        val draft = remember(store) { store.scope({ it.draft }, HomeFeature.Action::Draft) }
                        SessionView(draft, navigationIcon = { DrawerButton { store.send(HomeFeature.Action.DrawerOpenChanged(true)) } })
                    }
                    is HomeRoute.Session -> NavEntry(route) {
                        val session = remember(store, route) {
                            store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
                                state = { home -> home.stack.lastOrNull { it.sessionID == route.sessionID } ?: SessionFeature.State(sessionID = route.sessionID) },
                                action = { HomeFeature.Action.Session(route.sessionID, it) },
                            )
                        }
                        SessionView(session, navigationIcon = { DrawerButton { store.send(HomeFeature.Action.DrawerOpenChanged(true)) } })
                    }
                }
            },
        )
    }

    if (settingsShown) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { store.send(HomeFeature.Action.Settings(PresentationAction.Dismiss)) },
            sheetState = sheetState,
        ) {
            val settings = remember(store) {
                store.scope<HomeFeature.State, HomeFeature.Action, SettingsFeature.State, SettingsFeature.Action>(
                    state = { it.settings ?: SettingsFeature.State() },
                    action = { HomeFeature.Action.Settings(PresentationAction.Presented(it)) },
                )
            }
            SettingsView(settings)
        }
    }
}

@Composable
private fun DrawerButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Text("☰", style = MaterialTheme.typography.titleLarge)
    }
}
