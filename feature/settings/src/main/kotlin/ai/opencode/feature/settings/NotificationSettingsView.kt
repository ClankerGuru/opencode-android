package ai.opencode.feature.settings

import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.observe
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * iOS `HomeNotificationsFeature.NotifyPluginCheck`, as Settings shows it: Home reads the server's
 * notify plugin and passes the result in [SettingsFeature.Action.NotificationsChanged].
 */
sealed interface NotifyPluginCheck {
    data object Checking : NotifyPluginCheck
    data class Checked(val status: OpenCodeNotifyPluginStatus) : NotifyPluginCheck
    data object Failed : NotifyPluginCheck
}

/**
 * Port of iOS `Settings/NotificationSettingsView.swift`: Settings → Notifications, this phone's
 * permission, whether the server runs the notify plugin (with Set Up with OpenCode to have
 * OpenCode install it, 7969c85c), and the In-App Notices picker.
 */
@Composable
fun NotificationSettingsView(store: SettingsStore) {
    val permission by store.observe { it.notificationPermission }
    val isRequesting by store.observe { it.isRequestingNotifications }
    val setupError by store.observe { it.notificationSetupError }
    val notifyPlugin by store.observe { it.notifyPlugin }
    val disconnected by store.observe { it.connectionStatus == ConnectionStatus.disconnected }
    val noticeMode by remember { shared(InAppNoticeMode.key).flow }.collectAsState()
    LaunchedEffect(store) {
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotificationSettingsRequested))
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyPluginCheckRequested))
    }
    val enabled = permission == NotificationPermission.Enabled

    SettingsPageScaffold(title = "Notifications", store = store) {
        settingsSection(
            key = "permission",
            header = "This $ThisDeviceName",
            footer = footerText(
                if (permission == NotificationPermission.Disabled) {
                    "Notifications are off. Turn on Allow Notifications in Android Settings."
                } else {
                    "Notifications pass through a relay that keeps nothing: no account, no history, no logs. Only sessions started on this $ThisDeviceName notify it."
                },
            ),
        ) {
            SettingsRowFrame(Modifier.semantics(mergeDescendants = true) {}) {
                Text("Allow Notifications", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                Text(if (enabled) "On" else "Off", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
            }
            if (!enabled) {
                SettingsDivider()
                SettingsButtonRow(
                    if (permission == NotificationPermission.Disabled) "Open Android Settings" else "Turn On Notifications",
                    onClick = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.EnableNotificationsTapped))
                    },
                    enabled = !isRequesting,
                )
            }
            setupError?.let { error ->
                SettingsDivider()
                SettingsRowFrame {
                    Text(error, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.destructive)
                }
            }
        }
        val canSetUp = notifyPlugin == NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Missing) ||
            notifyPlugin == NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Inactive)
        settingsSection(
            key = "server",
            header = "Server",
            footer = footerText(
                when {
                    canSetUp -> "Set Up opens a new session with a prompt asking OpenCode to add the plugin to your global config. Review it, tap Send, then check again."
                    notifyPlugin == NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Active) ->
                        "Sessions you start on this $ThisDeviceName notify you when they finish."
                    else -> "The server needs the OpenCode notify plugin to send notifications."
                },
            ),
        ) {
            SettingsRowFrame(Modifier.semantics(mergeDescendants = true) {}) {
                Text("Notify Plugin", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                when (val check = notifyPlugin) {
                    NotifyPluginCheck.Checking, null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    is NotifyPluginCheck.Checked -> Text(
                        when (check.status) {
                            OpenCodeNotifyPluginStatus.Active -> "Installed"
                            OpenCodeNotifyPluginStatus.Inactive -> "Not running"
                            OpenCodeNotifyPluginStatus.Missing -> "Not installed"
                        },
                        style = OpenCodeTextStyles.body,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                    NotifyPluginCheck.Failed ->
                        Text("Couldn’t check status", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                }
            }
            if (canSetUp) {
                SettingsDivider()
                SettingsButtonRow(
                    "Set Up with OpenCode",
                    onClick = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyPluginSetUpTapped))
                    },
                    enabled = !disconnected,
                )
            }
            SettingsDivider()
            SettingsButtonRow(
                "Check Again",
                onClick = {
                    store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyPluginCheckRequested))
                },
                enabled = notifyPlugin != NotifyPluginCheck.Checking,
            )
        }
        settingsSection(
            key = "in-app",
            footer = footerText("Names sessions needing your input or finishing at the drawer button."),
        ) {
            SettingsMenuRow(
                title = "In-App Notices",
                value = noticeMode.title,
                selection = noticeMode,
                options = InAppNoticeMode.entries.map { SettingsMenuOption(it, it.title) },
                onSelect = { store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.InAppNoticeModeChanged(it))) },
            )
        }
    }
}

/** iOS `ThisDevice.name` (“iPhone”/“iPad”): “phone” or “tablet”. */
val ThisDeviceName: String get() = ai.opencode.core.ui.ThisDevice.name
