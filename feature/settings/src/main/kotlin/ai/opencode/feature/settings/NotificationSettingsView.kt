package ai.opencode.feature.settings

import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics

/**
 * Port of iOS `Settings/NotificationSettingsView.swift`: Settings → Notifications, this phone's
 * permission and the In-App Notices picker. iOS hides the notify-plugin section
 * (`showsNotifyPlugin = false`); it stays hidden here.
 */
@Composable
fun NotificationSettingsView(store: SettingsStore) {
    val permission by store.observe { it.notificationPermission }
    val isRequesting by store.observe { it.isRequestingNotifications }
    val setupError by store.observe { it.notificationSetupError }
    val noticeMode by remember { shared(InAppNoticeMode.key).flow }.collectAsState()
    LaunchedEffect(store) {
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotificationSettingsRequested))
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
