package ai.opencode.feature.settings.servers

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeButtonRole
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.settings.SettingsDivider
import ai.opencode.feature.settings.SettingsFooterText
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.feature.settings.settingsSection
import ai.opencode.server.ConnectionRoute
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

private val smooth = MotionSpring(0.3, 0.0)

private sealed interface ServerTitleRoute {
    data object Root : ServerTitleRoute
    /** A pushed page by position and kind; its content reads the live page (a rename updates it in place). */
    data class Pushed(val index: Int, val kind: String) : ServerTitleRoute
}

private val ServerTitleFeature.Page.kind: String
    get() = when (this) {
        ServerTitleFeature.Page.Servers -> "servers"
        ServerTitleFeature.Page.Connect -> "connect"
        is ServerTitleFeature.Page.Detail -> "detail"
    }

/**
 * Port of iOS `Settings/ServerTitleView.swift`: the experimental Servers sheet's content (the
 * parent presents it full height, as iOS's `.large` detent, and keeps it from closing while
 * `busy`). Its own stack: the large-titled server list, then Connect ([ServerConnectView]) and a
 * server's details pushed over it, each with Close. With `connectOnly` (first run, no servers) the
 * connect page is the whole screen, with no bar.
 *
 * Probes run while the screen is resumed (`Appeared`) and stop when it pauses (`Disappeared`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerTitleView(store: ServerTitleStore, modifier: Modifier = Modifier) {
    val connectOnly by store.observe { it.connectOnly }
    val path by store.observe { it.path }
    val busy by store.observe { it.busy }
    LifecycleResumeEffect(store) {
        store.send(ServerTitleFeature.Action.Appeared)
        onPauseOrDispose { store.send(ServerTitleFeature.Action.Disappeared) }
    }
    if (connectOnly) {
        // First run: only the connect page, with nothing behind it to go back or close to, so no bar.
        Scaffold(modifier = modifier, containerColor = OpenCodeTheme.colors.groupedCanvas) { padding ->
            ServerConnectView(store, contentPadding = padding)
        }
        return
    }
    val backStack: List<ServerTitleRoute> = remember(path) {
        listOf<ServerTitleRoute>(ServerTitleRoute.Root) + path.mapIndexed { index, page -> ServerTitleRoute.Pushed(index, page.kind) }
    }
    BackHandler(enabled = busy) { /* A connection in progress keeps the sheet and its page. */ }
    Box(modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { store.send(ServerTitleFeature.Action.Back) },
            entryProvider = { route ->
                when (route) {
                    ServerTitleRoute.Root -> NavEntry(route) { ServersPage(store) }
                    is ServerTitleRoute.Pushed -> NavEntry(route) { PushedPage(store, route.index) }
                }
            },
        )
    }
}

@Composable
private fun PushedPage(store: ServerTitleStore, index: Int) {
    val live by store.observe { it.path.getOrNull(index) }
    // While popping, the page leaving still draws what it showed.
    val last = remember { arrayOfNulls<ServerTitleFeature.Page>(1) }
    if (live != null) last[0] = live
    when (val page = live ?: last[0]) {
        ServerTitleFeature.Page.Servers, null -> ServersPage(store, pushed = true)
        ServerTitleFeature.Page.Connect -> ServerSheetPage(store, title = "", pushed = true) { padding ->
            ServerConnectView(store, contentPadding = padding)
        }
        is ServerTitleFeature.Page.Detail -> ServerSheetPage(store, title = "", pushed = true) { padding ->
            ServerDetailView(store, page.server, padding)
        }
    }
}

/**
 * One page of the sheet: a large “Servers” title on the list, an inline (empty) title on pushed
 * pages with Back (hidden while busy), and Close everywhere (iOS `serverSheetChrome`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheetPage(
    store: ServerTitleStore,
    title: String,
    pushed: Boolean,
    large: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val busy by store.observe { it.busy }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = OpenCodeTheme.colors.groupedCanvas,
        scrolledContainerColor = OpenCodeTheme.colors.groupedCanvas,
    )
    val close: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { store.send(ServerTitleFeature.Action.Close) }) {
            OpenCodeIcon(SFSymbol.Xmark, contentDescription = "Close")
        }
    }
    val back: @Composable () -> Unit = {
        if (pushed && !busy) {
            IconButton(onClick = { store.send(ServerTitleFeature.Action.Back) }) {
                OpenCodeIcon(SFSymbol.ChevronBackward, contentDescription = "Back")
            }
        }
    }
    val scrollBehavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = OpenCodeTheme.colors.groupedCanvas,
        topBar = {
            if (large) {
                LargeTopAppBar(
                    title = { Text(title) },
                    navigationIcon = back,
                    actions = close,
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(title, style = OpenCodeTextStyles.headline) },
                    navigationIcon = back,
                    actions = close,
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding -> content(padding) }
}

@Composable
private fun ServersPage(store: ServerTitleStore, pushed: Boolean = false) {
    val savedShared = remember(store) { store.state.savedServers }
    val saved by savedShared.flow.collectAsState()
    val state by store.observe { it }
    val servers = remember(state.current, saved) { state.servers(saved) }
    ServerSheetPage(store, title = if (pushed) "" else "Servers", pushed = pushed, large = !pushed) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (servers.isNotEmpty()) {
                settingsSection(key = "servers") {
                    servers.forEachIndexed { index, server ->
                        if (index > 0) SettingsDivider(inset = true)
                        ServerRow(
                            server = server,
                            status = state.status(server),
                            tone = state.tone(server),
                            enabled = !state.busy,
                            onSelect = { store.send(ServerTitleFeature.Action.Select(server)) },
                            onDetail = { store.send(ServerTitleFeature.Action.Detail(server)) },
                        )
                    }
                }
            }
            sectionSpacer("servers-gap")
            settingsSection(
                key = "add",
                footer = when {
                    state.busy -> { { SettingsFooterText("Connecting…") } }
                    state.error != null -> { { SettingsFooterText(state.error!!) } }
                    else -> null
                },
            ) {
                TileRow(
                    symbol = "plus",
                    title = "Connect Your Computer",
                    tint = MaterialTheme.colorScheme.primary,
                    chevron = true,
                    minHeight = ServerTitleTunables.rowHeight,
                    modifier = Modifier.testTag("servers-add"),
                ) { store.send(ServerTitleFeature.Action.Add) }
            }
        }
    }
}

/** Cards are [ServerTitleTunables.sectionSpacing] apart (settingsSection already adds 16 dp). */
private fun LazyListScope.sectionSpacer(key: String) {
    item(key = key) { Spacer(Modifier.heightIn(min = ServerTitleTunables.sectionSpacing - 16.dp)) }
}

@Composable
private fun ServerRow(
    server: OpenCodeServer,
    status: String,
    tone: ServerStatusTone,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDetail: () -> Unit,
) {
    val name = server.friendlyName
    Row(Modifier.fillMaxWidth().heightIn(min = ServerTitleTunables.rowHeight), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = ServerTitleTunables.rowHeight)
                .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
                .padding(start = 20.dp)
                .testTag("servers-pick-$name"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ServerIconTile(serverSymbol(server))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine(status, tone, OpenCodeTextStyles.footnote)
            }
        }
        Box(
            Modifier
                .clickable(role = Role.Button, onClick = onDetail)
                .openCodeTapTarget()
                .padding(end = 12.dp)
                .semantics { contentDescription = "$name details" },
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon("info.circle", contentDescription = null, size = 22.dp, tint = OpenCodeTheme.colors.tertiaryText)
        }
    }
}

@Composable
private fun StatusLine(status: String, tone: ServerStatusTone, style: androidx.compose.ui.text.TextStyle, spacing: Int = 6) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.dp)) {
        ServerStatusDot(tone)
        AnimatedContent(status, transitionSpec = { fadeIn(smooth.spec()) togetherWith fadeOut(smooth.spec()) }, label = "status") {
            Text(it, style = style, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/** A tile row in a card: icon tile, title, optional chevron (the row pushes a page). */
@Composable
private fun TileRow(
    symbol: String,
    title: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    titleColor: Color = OpenCodeTheme.colors.text,
    chevron: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 52.dp,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ServerIconTile(symbol, tint = tint)
        Text(title, style = OpenCodeTextStyles.body, color = titleColor, modifier = Modifier.weight(1f))
        if (chevron) ServerChevron()
    }
}

/** A Settings row's trailing chevron: the row pushes a page. */
@Composable
fun ServerChevron(modifier: Modifier = Modifier) {
    OpenCodeIcon(
        "chevron.right",
        contentDescription = null,
        size = 14.dp,
        tint = OpenCodeTheme.colors.tertiaryText,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** The hardware symbol for a server's row, from its name. */
fun serverSymbol(server: OpenCodeServer): String {
    val name = server.friendlyName.lowercase()
    if ("studio" in name) return "macstudio"
    if ("mini" in name) return "macmini"
    if ("mac" in name || "laptop" in name) return "laptopcomputer"
    return "network"
}

/** A server's details: its pulse, status, recovery or Switch to, Name/Address/Connection, Pair Again and Remove. */
@Composable
private fun ServerDetailView(store: ServerTitleStore, server: OpenCodeServer, padding: PaddingValues) {
    val state by store.observe { it }
    val health = state.checks[server.persistenceID]
    val isCurrent = state.current?.persistenceID == server.persistenceID
    val live = health == null || health == ServerTitleFeature.Health.connected
    val name = server.friendlyName
    var nameFocused by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LazyColumn(
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ServerPulseScope(health)
                AnimatedContent(name, transitionSpec = { fadeIn(smooth.spec()) togetherWith fadeOut(smooth.spec()) }, label = "name") {
                    Text(
                        it,
                        style = OpenCodeTextStyles.title1.copy(fontWeight = FontWeight.Bold),
                        color = OpenCodeTheme.colors.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 22.dp),
                    )
                }
                Box(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    StatusLine(state.status(server), state.tone(server), OpenCodeTextStyles.subheadline, spacing = 7)
                }
            }
        }
        if (health != null && health != ServerTitleFeature.Health.connected) {
            settingsSection(key = "advice") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val tint = when (health) {
                        ServerTitleFeature.Health.update -> SettingsTint.Orange.color()
                        ServerTitleFeature.Health.signIn -> SettingsTint.Red.color()
                        else -> null
                    }
                    ServerIconTile(adviceSymbol(health), tint = tint)
                    Text(advice(health, name), style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.text)
                }
                SettingsDivider()
                ServerCommandRow(
                    if (health == ServerTitleFeature.Health.update) "opencode upgrade" else "opencode pair --remote",
                    onCopy = { store.send(ServerTitleFeature.Action.Copy(it)) },
                )
            }
            primary("try-again", "Try Again", state.busy) { store.send(ServerTitleFeature.Action.Retry(server)) }
        } else if (!isCurrent && live) {
            primary("switch", "Switch to $name", state.busy) { store.send(ServerTitleFeature.Action.Retry(server)) }
        }
        settingsSection(
            key = "info",
            footer = state.error?.let { error -> { SettingsFooterText(error) } },
        ) {
            DetailRow("Name") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = state.nameDraft,
                        onValueChange = { store.send(ServerTitleFeature.Action.NameDraftChanged(it)) },
                        placeholder = { Text(name, style = OpenCodeTextStyles.body.copy(textAlign = TextAlign.End), modifier = Modifier.fillMaxWidth()) },
                        textStyle = OpenCodeTextStyles.body.copy(
                            textAlign = TextAlign.End,
                            color = if (nameFocused) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            store.send(ServerTitleFeature.Action.SaveName)
                            focusManager.clearFocus()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(nameFocus)
                            .onFocusChanged {
                                val was = nameFocused
                                nameFocused = it.isFocused
                                // Leaving the field saves it (iOS `onChange(of: nameFocused)`).
                                if (was && !it.isFocused) store.send(ServerTitleFeature.Action.SaveName)
                            }
                            .testTag("servers-name"),
                    )
                    AnimatedVisibility(!nameFocused, enter = fadeIn(MotionSpring(0.2, 0.0).spec()), exit = fadeOut(MotionSpring(0.2, 0.0).spec())) {
                        Box(
                            Modifier
                                .clickable(role = Role.Button) { nameFocus.requestFocus() }
                                .openCodeTapTarget()
                                .semantics { contentDescription = "Rename" }
                                .testTag("servers-name-edit"),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpenCodeIcon("pencil", contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.tertiaryText)
                        }
                    }
                }
            }
            SettingsDivider()
            DetailRow("Address") {
                Text(
                    server.host,
                    style = OpenCodeTextStyles.body.copy(fontFeatureSettings = "tnum"),
                    color = OpenCodeTheme.colors.secondaryText,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingsDivider()
            DetailRow("Connection") {
                Text(
                    ConnectionRoute.of(server.url).rawValue,
                    style = OpenCodeTextStyles.body,
                    color = OpenCodeTheme.colors.secondaryText,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        sectionSpacer("info-gap")
        settingsSection(key = "actions") {
            TileRow(symbol = "qrcode", title = "Pair Again", chevron = true) { store.send(ServerTitleFeature.Action.Add) }
            if (!isCurrent) {
                SettingsDivider(inset = true)
                val red = SettingsTint.Red.color()
                TileRow(symbol = "trash", title = "Remove Server…", tint = red, titleColor = red) {
                    store.send(ServerTitleFeature.Action.Remove(server))
                }
            }
        }
    }

    if (state.removing != null) {
        AlertDialog(
            onDismissRequest = { store.send(ServerTitleFeature.Action.RemovingDismissed) },
            title = { Text("Remove Server?") },
            text = { Text("Sessions stay on your computer.") },
            confirmButton = {
                TextButton(onClick = { store.send(ServerTitleFeature.Action.ConfirmRemove) }) {
                    Text("Remove Server", color = OpenCodeTheme.colors.destructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { store.send(ServerTitleFeature.Action.RemovingDismissed) }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { content() }
    }
}

/** The page's one prominent button, the width of the cards; progress shows in place. */
private fun LazyListScope.primary(key: String, title: String, busy: Boolean, onClick: () -> Unit) {
    item(key = key) {
        OpenCodePrimaryButton(
            onClick = onClick,
            enabled = !busy,
            role = OpenCodeButtonRole.Normal,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(title, modifier = Modifier.graphicsLayer { alpha = if (busy) 0f else 1f })
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp).semantics { contentDescription = "Connecting" },
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                }
            }
        }
    }
}

private fun adviceSymbol(health: ServerTitleFeature.Health): String = when (health) {
    ServerTitleFeature.Health.update -> "arrow.up.circle"
    ServerTitleFeature.Health.signIn -> "key"
    else -> "moon.zzz"
}

private fun advice(health: ServerTitleFeature.Health, name: String): String = when (health) {
    ServerTitleFeature.Health.update -> "Update OpenCode on $name, then try again."
    ServerTitleFeature.Health.signIn -> "Pair with $name again to sign in."
    else -> "Wake $name and open OpenCode."
}
