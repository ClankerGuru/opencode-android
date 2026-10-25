package ai.opencode.feature.settings

import ai.opencode.core.experiments.key
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.feature.settings.connection.ConnectionCard
import ai.opencode.feature.settings.connection.ServerPage
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundView
import ai.opencode.feature.settings.serverdetails.ServerDetailsPage
import ai.opencode.feature.settings.whatsnew.WhatsNewDetailPage
import ai.opencode.feature.settings.whatsnew.WhatsNewListPage
import ai.opencode.feature.settings.whatsnew.WhatsNewSmallerListPage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/** Every Settings store page composables take (pages, not list rows, may hold a store). */
typealias SettingsStore = StoreOf<SettingsFeature.State, SettingsFeature.Action>

private sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data class Page(val index: Int, val page: SettingsPage) : SettingsRoute
}

/**
 * Port of iOS `SessionSettingsView` (`Settings/SettingsView.swift`): Settings' own stack, the root
 * page (connection card; Appearance, Composer, Models, Voice, Notifications, What’s New; Server
 * Details; Advanced and the version line) and the pages it pushes. Back on a pushed page goes back
 * one level; at the root it closes Settings ([SettingsFeature.SettingsDelegate.Close]).
 */
@Composable
fun SettingsView(store: SettingsStore, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(SettingsFeature.Action.Task) }
    val path by store.observe { it.path }
    val root by store.observe { it.root }
    val backStack: List<SettingsRoute> = remember(path) {
        listOf<SettingsRoute>(SettingsRoute.Root) + path.mapIndexed { index, page -> SettingsRoute.Page(index, page) }
    }
    Box(modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { store.send(SettingsFeature.Action.BackTapped) },
            entryProvider = { route ->
                when (route) {
                    SettingsRoute.Root -> NavEntry(route) {
                        when (root) {
                            SettingsFeature.State.Root.settings -> SettingsRootPage(store)
                            SettingsFeature.State.Root.whatsNew -> WhatsNewListPage(store)
                        }
                    }
                    is SettingsRoute.Page -> NavEntry(route) { SettingsPageContent(store, route.page) }
                }
            },
        )
    }
    OnboardingPlaygroundPresentation(store)
}

@Composable
private fun SettingsPageContent(store: SettingsStore, page: SettingsPage) {
    when (page) {
        SettingsPage.Server -> ServerPage(store)
        SettingsPage.Appearance -> AppearanceSettingsView(store)
        SettingsPage.Themes -> ThemesSettingsView(store)
        SettingsPage.Composer -> ComposerSettingsView(store)
        SettingsPage.Models -> ModelVisibilitySettingsView(store)
        is SettingsPage.ModelProvider -> ModelVisibilityProviderView(store, page.providerID)
        SettingsPage.Voice -> VoiceSettingsView(store)
        SettingsPage.CallVoicePicker -> CallVoicePickerView(store)
        SettingsPage.CallHistory -> CallHistoryView(store)
        SettingsPage.Notifications -> NotificationSettingsView(store)
        SettingsPage.WhatsNew -> WhatsNewListPage(store)
        is SettingsPage.WhatsNewDetail -> WhatsNewDetailPage(store, page.entryID)
        SettingsPage.WhatsNewSmallerChanges -> WhatsNewSmallerListPage(store)
        SettingsPage.ServerDetails -> ServerDetailsPage(store)
        is SettingsPage.Advanced -> ExperimentalSettingsView(store, focus = page.focus?.let(ExperimentalFeature::fromRawValue))
        SettingsPage.Developer -> DeveloperSettingsView(store)
        SettingsPage.Playgrounds -> PlaygroundsSettingsView(store)
    }
}

/** The Onboarding playground, full screen over Settings (iOS `.fullScreenCover`). */
@Composable
private fun OnboardingPlaygroundPresentation(store: SettingsStore) {
    val shown by store.observe { it.onboardingPlayground != null }
    if (!shown) return
    Dialog(
        onDismissRequest = { store.send(SettingsFeature.Action.OnboardingPlayground(PresentationAction.Dismiss)) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val playground = remember(store) {
            store.scope<SettingsFeature.State, SettingsFeature.Action, ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.State, ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Action>(
                state = { it.onboardingPlayground ?: ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.State() },
                action = { SettingsFeature.Action.OnboardingPlayground(PresentationAction.Presented(it)) },
            )
        }
        OnboardingPlaygroundView(
            playground,
            onClose = { store.send(SettingsFeature.Action.OnboardingPlayground(PresentationAction.Dismiss)) },
        )
    }
}

/**
 * One Settings page's chrome (iOS `List` + inline `navigationTitle` + Back): a centred title, a
 * Back control (“Back” closes Settings at the root, pops otherwise) and an inset-grouped list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    store: SettingsStore,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = OpenCodeTheme.colors.groupedCanvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = OpenCodeTextStyles.headline) },
                navigationIcon = {
                    IconButton(onClick = { store.send(SettingsFeature.Action.BackTapped) }) {
                        OpenCodeIcon(SFSymbol.ChevronBackward, contentDescription = "Back")
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OpenCodeTheme.colors.groupedCanvas,
                    scrolledContainerColor = OpenCodeTheme.colors.groupedCanvas,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

/** Settings' root page (iOS `SessionSettingsView.page`). */
@Composable
private fun SettingsRootPage(store: SettingsStore) {
    val appTheme by remember { shared(ThemeSharedKeys.appTheme).flow }.collectAsState()
    val voiceModeEnabled by remember { shared(SharedKeys.voiceModeEnabled).flow }.collectAsState()
    val developerMode by remember { shared(SharedKeys.developerMode).flow }.collectAsState()
    val hideModels by remember { shared(ExperimentalFeature.hideModels.key).flow }.collectAsState()
    val serverDetails by remember { shared(ExperimentalFeature.serverDetails.key).flow }.collectAsState()
    val notificationPermission by store.observe { it.notificationPermission }
    val push = { page: SettingsPage -> store.send(SettingsFeature.Action.PagePushed(page)) }

    SettingsPageScaffold(title = "Settings", store = store) {
        item(key = "connection", contentType = "connection") {
            ConnectionCard(store, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        // Preferences: Appearance, Composer, Voice, Notifications, and What’s New.
        settingsSection(key = "preferences") {
            SettingsRow("Appearance", "paintpalette.fill", SettingsTint.Purple, value = appTheme.title) { push(SettingsPage.Appearance) }
            SettingsDivider(inset = true)
            SettingsRow("Composer", "keyboard.fill", SettingsTint.Blue) { push(SettingsPage.Composer) }
            if (ExperimentalFeature.hideModels.isActive(hideModels)) {
                SettingsDivider(inset = true)
                SettingsRow("Models", "cpu", SettingsTint.Teal) { push(SettingsPage.Models) }
            }
            SettingsDivider(inset = true)
            SettingsRow("Voice", "waveform", SettingsTint.Indigo, value = if (voiceModeEnabled) "On" else "Off") {
                push(SettingsPage.Voice)
            }
            notificationPermission?.let { permission ->
                SettingsDivider(inset = true)
                SettingsRow(
                    "Notifications",
                    "bell.badge.fill",
                    SettingsTint.Red,
                    value = if (permission == NotificationPermissionStatus.enabled) "On" else "Off",
                ) { push(SettingsPage.Notifications) }
            }
            SettingsDivider(inset = true)
            SettingsRow("What’s New", "sparkles", SettingsTint.Orange) { push(SettingsPage.WhatsNew) }
        }
        if (ExperimentalFeature.serverDetails.isActive(serverDetails)) {
            settingsSection(key = "server-details") {
                SettingsRow("Server Details", "server.rack", SettingsTint.Teal) { push(SettingsPage.ServerDetails) }
            }
        }
        settingsSection(
            key = "advanced",
            footer = {
                SettingsFooterText("Experimental features, diagnostics, and developer tools.")
                SelectionContainer(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        AppVersion.current.label,
                        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                        color = OpenCodeTheme.colors.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        ) {
            SettingsRow(
                "Advanced",
                "gearshape.2.fill",
                SettingsTint.Gray,
                value = if (developerMode) "Developer Mode" else null,
            ) { push(SettingsPage.Advanced()) }
        }
    }
}

/** Shared body for pages whose list sits under [SettingsPageScaffold]. */
internal fun LazyListScope.footerOnly(key: Any, content: @Composable ColumnScope.() -> Unit) {
    item(key = key) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), content = content)
    }
}
