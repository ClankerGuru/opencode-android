package ai.opencode.feature.drawer.switcher

import ai.opencode.core.motion.Motion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Collator

// Port of iOS `OpenCode/Drawer/ServerSwitcher.swift`, the view half (`DrawerTitle.body`, `menu`,
// `title`). The rules (`switchableServers`, `DrawerTitle.Presentation`, copy) are in
// `ServerSwitcher.kt`. The title shows a server only by its friendly name, never an address.

/** Which of iOS `DrawerTitle.body`'s branches shows; changing branch is a cross-fade. */
private enum class DrawerTitleVariant { Menu, Plain }

/**
 * The drawer's title (iOS `DrawerTitle`): the server in use by its friendly name. With several
 * servers (or one while offline) it's a pull-down listing the servers (the current one checked,
 * with its issue as a second line), Try Again while offline (Edit Server… instead when the
 * server refused the credentials), and Manage Servers….
 *
 * Dimming and the one-server menu coming and going are cross-fades of
 * [DrawerTitle.CROSS_FADE_MILLIS] (0.25 s ease-in-out), with or without reduced motion: nothing
 * moves.
 *
 * The drawer chrome (`HomeViewDrawer.kt`, iOS `HomeView.drawerHeader`) places it with
 * `DrawerMetrics.textInset` leading padding and room for the search pill trailing; the
 * store-bound overload below wires it the way iOS `HomeView` does.
 *
 * @param servers what the menu lists, in a steady order ([drawerTitleServers]).
 * @param switchingTo another server being connected to; this one stays until it's ready.
 * @param onSwitch a server other than the current one was picked.
 * @param onRetry Try Again (iOS `.retryConnectionTapped`).
 * @param onManage Manage Servers… (iOS opens Settings focused on Servers).
 * @param credentialsRejected the server answered with a 401; trying again can't help.
 * @param onEdit Edit Server… (iOS `.editServerRequested`), offered instead of Try Again then.
 */
@Composable
fun DrawerTitleView(
    current: OpenCodeServer,
    servers: List<OpenCodeServer>,
    status: HomeConnectionFeature.State.ConnectionStatus,
    switchingTo: OpenCodeServer?,
    onSwitch: (OpenCodeServer) -> Unit,
    onRetry: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    credentialsRejected: Boolean = false,
    onEdit: () -> Unit = {},
) {
    val presentation = DrawerTitle.Presentation.of(servers, status, switchingTo, credentialsRejected)
    val variant = when {
        presentation.hasMenu -> DrawerTitleVariant.Menu
        else -> DrawerTitleVariant.Plain
    }
    Crossfade(
        targetState = variant,
        modifier = modifier,
        animationSpec = tween(DrawerTitle.CROSS_FADE_MILLIS, easing = Motion.EaseInOut),
        label = "drawer-title",
    ) { shown ->
        when (shown) {
            DrawerTitleVariant.Menu -> DrawerTitleMenu(current, servers, switchingTo, presentation, onSwitch, onRetry, onEdit, onManage)
            DrawerTitleVariant.Plain -> Box(
                Modifier
                    .heightIn(min = DrawerTitle.MIN_HEIGHT.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = presentation.accessibilityValue
                    }
                    .testTag(DrawerTitle.TAG),
                contentAlignment = Alignment.CenterStart,
            ) {
                DrawerTitleText(current.friendlyName, presentation)
            }
        }
    }
}

/**
 * The title wired to Home as iOS `HomeView.drawerHeader` wires it: saved servers from
 * `ServerSharedKeys.savedServers` ([switchableServers], sorted by friendly name), switch / Try
 * Again / Title Is the Switch sent as Home actions. [onManage] stays with the caller (Home sends
 * `ManageServersTapped`: Settings opens on its server page, iOS `settingsFocusesServers`).
 */
@Composable
fun DrawerTitleView(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by store.observe { it.server }
    val status by store.observe { it.connectionStatus }
    val switchingTo by store.observe { it.switchingTo }
    val credentialsRejected by store.observe { it.credentialsRejected }
    val saved by remember { shared(ServerSharedKeys.savedServers).flow }.collectAsState()
    DrawerTitleView(
        current = current,
        servers = drawerTitleServers(current, saved),
        status = status,
        switchingTo = switchingTo,
        onSwitch = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerSwitchTapped(it))) },
        onRetry = { store.send(HomeFeature.Action.RetryConnectionTapped) },
        onManage = onManage,
        modifier = modifier,
        credentialsRejected = credentialsRejected,
        onEdit = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.EditServerRequested(current))) },
    )
}

/**
 * The menu's servers: [switchableServers] in a steady order, so the menu never moves as servers
 * are used (iOS sorts by `friendlyName.localizedStandardCompare`: case-insensitive, numbers by
 * value).
 */
fun drawerTitleServers(current: OpenCodeServer, saved: List<OpenCodeServer>): List<OpenCodeServer> =
    switchableServers(current, saved).sortedWith { a, b -> localizedStandardCompare(a.friendlyName, b.friendlyName) }

/** Finder-like ordering (iOS `localizedStandardCompare`): runs of digits compare by value, text by the locale's collator. */
internal fun localizedStandardCompare(a: String, b: String): Int {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    val left = chunks(a)
    val right = chunks(b)
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val result = if (x[0].isDigit() && y[0].isDigit()) {
            val byValue = x.trimStart('0').length.compareTo(y.trimStart('0').length)
            if (byValue != 0) byValue else x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            collator.compare(x, y)
        }
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun chunks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var start = 0
    for (i in 1..text.length) {
        if (i == text.length || text[i].isDigit() != text[start].isDigit()) {
            out += text.substring(start, i)
            start = i
        }
    }
    return out
}

/** iOS `title(_:_:)`: bold title-style text, dimmed (secondary) on a confirmed outage, one line, truncated in the middle. */
@Composable
private fun DrawerTitleText(text: String, presentation: DrawerTitle.Presentation) {
    val colors = OpenCodeTheme.colors
    val color by animateColorAsState(
        if (presentation.isDimmed) colors.secondaryText else colors.text,
        tween(DrawerTitle.CROSS_FADE_MILLIS, easing = Motion.EaseInOut),
        label = "drawer-title-tone",
    )
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            fadeIn(tween(DrawerTitle.CROSS_FADE_MILLIS, easing = Motion.EaseInOut)) togetherWith
                fadeOut(tween(DrawerTitle.CROSS_FADE_MILLIS, easing = Motion.EaseInOut))
        },
        label = "drawer-title-text",
    ) { shown ->
        Text(
            shown,
            style = DrawerTunables.titleFont.font.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** iOS `menu(_:)`: the title with a chevron that opens the servers, Try Again (or Edit Server…) and Manage Servers…. */
@Composable
private fun DrawerTitleMenu(
    current: OpenCodeServer,
    servers: List<OpenCodeServer>,
    switchingTo: OpenCodeServer?,
    presentation: DrawerTitle.Presentation,
    onSwitch: (OpenCodeServer) -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onManage: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = OpenCodeTheme.colors
    val chevronColor by animateColorAsState(
        if (presentation.isDimmed && !colors.highContrast) colors.tertiaryText else colors.secondaryText,
        tween(DrawerTitle.CROSS_FADE_MILLIS, easing = Motion.EaseInOut),
        label = "drawer-title-chevron",
    )
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.DropdownList,
                    onClickLabel = presentation.accessibilityHint,
                ) { expanded = true }
                .semantics(mergeDescendants = true) {
                    contentDescription = current.friendlyName
                    stateDescription = presentation.accessibilityValue
                    heading()
                }
                .testTag(DrawerTitle.TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f, fill = false)) {
                DrawerTitleText(DrawerTitle.titleText(current, switchingTo), presentation)
            }
            // iOS `.footnote.weight(.bold)` chevron.
            OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = 18.dp, tint = chevronColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (presentation.listsServers) {
                for (server in servers) {
                    val isCurrent = server.persistenceID == current.persistenceID
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(server.friendlyName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                val issue = presentation.issue
                                if (isCurrent && issue != null) {
                                    Text(issue, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                                }
                            }
                        },
                        leadingIcon = {
                            // A menu toggle draws the checkmark (iOS `Toggle` in a `Menu`).
                            if (isCurrent) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null) else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            expanded = false
                            if (!isCurrent) onSwitch(server)
                        },
                        modifier = Modifier.semantics { selected = isCurrent },
                    )
                }
                HorizontalDivider()
            }
            if (presentation.offersTryAgain) {
                DropdownMenuItem(
                    text = { Text(DrawerTitle.TRY_AGAIN) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRetry()
                    },
                )
                HorizontalDivider()
            }
            if (presentation.offersEdit) {
                DropdownMenuItem(
                    text = { Text(DrawerTitle.EDIT_SERVER) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Key, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onEdit()
                    },
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(DrawerTitle.MANAGE_SERVERS) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Gearshape, contentDescription = null) },
                onClick = {
                    expanded = false
                    onManage()
                },
            )
        }
    }
}
