package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.bridge.NotificationPermission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import kotlinx.coroutines.CancellationException

/**
 * Port of iOS `HomeNotificationsFeature` (`OpenCode/Drawer/HomeNotificationsFeature.swift`): this
 * phone's notification permission and the server's notify plugin, as Settings › Notifications
 * reads them. Home supplies the current server and keeps [Status] as `notificationState`.
 */
class HomeNotificationsFeature : Reducer<HomeNotificationsFeature.State, HomeNotificationsFeature.Action> {
    data class Status(
        /** Null until read, and while this build offers no notifications. */
        val notificationPermission: NotificationPermission? = null,
        val isRequestingNotifications: Boolean = false,
        val notificationSetupError: String? = null,
        /** The server half of notifications, read when Notifications opens. */
        val notifyPlugin: NotifyPluginCheck? = null,
    )

    sealed interface NotifyPluginCheck {
        data object Checking : NotifyPluginCheck
        data class Checked(val status: OpenCodeNotifyPluginStatus) : NotifyPluginCheck
        data object Failed : NotifyPluginCheck
    }

    data class State(
        val server: OpenCodeServer,
        val status: Status = Status(),
    )

    sealed interface Action {
        data object NotificationSettingsRequested : Action
        data class NotificationPermissionLoaded(val permission: NotificationPermission) : Action
        data object EnableNotificationsTapped : Action
        data class NotificationPermissionRequested(val permission: NotificationPermission) : Action
        data class NotificationSettingsOpened(val opened: Boolean) : Action
        data object NotifyPluginCheckRequested : Action
        data class NotifyPluginChecked(val status: OpenCodeNotifyPluginStatus?) : Action
    }

    private enum class CancelID { notifyPlugin }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val status = state.status
        return when (action) {
            Action.NotificationSettingsRequested -> {
                val sessionNotifications = dependency(SessionNotificationsClient)
                if (!sessionNotifications.isAvailable) return Reduced(state)
                val notificationPermission = dependency(NotificationPermissionClient)
                Reduced(state, Effect.run { send -> send(Action.NotificationPermissionLoaded(notificationPermission.status())) })
            }

            is Action.NotificationPermissionLoaded -> {
                val next = state.copy(status = status.copy(notificationPermission = action.permission))
                if (!action.permission.isEnabled) return Reduced(next)
                // Mint ahead so the next Send rarely waits on the relay.
                val sessionNotifications = dependency(SessionNotificationsClient)
                val server = state.server
                Reduced(next, Effect.run { sessionNotifications.prepare(server) })
            }

            Action.EnableNotificationsTapped -> {
                if (status.isRequestingNotifications) return Reduced(state)
                val next = state.copy(status = status.copy(notificationSetupError = null, isRequestingNotifications = true))
                val notificationPermission = dependency(NotificationPermissionClient)
                val permission = status.notificationPermission
                if (permission != null && permission != NotificationPermission.NotDetermined) {
                    // Disabled or enabled: only the system's settings can change it.
                    return Reduced(next, Effect.run { send -> send(Action.NotificationSettingsOpened(notificationPermission.openSettings())) })
                }
                Reduced(next, Effect.run { send -> send(Action.NotificationPermissionRequested(notificationPermission.request())) })
            }

            is Action.NotificationPermissionRequested -> {
                val error = if (action.permission == NotificationPermission.NotDetermined) {
                    "Couldn’t request permission. Try again."
                } else {
                    status.notificationSetupError
                }
                Reduced(
                    state.copy(status = status.copy(isRequestingNotifications = false, notificationSetupError = error)),
                    Effect.send(Action.NotificationPermissionLoaded(action.permission)),
                )
            }

            is Action.NotificationSettingsOpened -> {
                val error = if (!action.opened) {
                    "Couldn’t open Settings. Open Android Settings and choose Notifications → OpenCode."
                } else {
                    status.notificationSetupError
                }
                Reduced(state.copy(status = status.copy(isRequestingNotifications = false, notificationSetupError = error)))
            }

            Action.NotifyPluginCheckRequested -> {
                val openCodeClient = dependency(OpenCodeClient)
                val server = state.server
                Reduced(
                    state.copy(status = status.copy(notifyPlugin = NotifyPluginCheck.Checking)),
                    Effect.run<Action> { send ->
                        val result = try {
                            openCodeClient.notifyPluginStatus(server)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        send(Action.NotifyPluginChecked(result))
                    }.cancellable(CancelID.notifyPlugin, cancelInFlight = true),
                )
            }

            is Action.NotifyPluginChecked -> Reduced(
                state.copy(status = status.copy(notifyPlugin = action.status?.let(NotifyPluginCheck::Checked) ?: NotifyPluginCheck.Failed)),
            )
        }
    }
}

/**
 * iOS `NotificationPermission.enabled`: authorized (iOS also counts provisional and ephemeral).
 * TODO(merge): drop once `NotificationPermission` is `:feature:system`'s three-case enum
 * (`notDetermined`, `disabled`, `enabled`); then compare with `enabled` directly.
 */
internal val NotificationPermission.isEnabled: Boolean
    get() = this == NotificationPermission.Authorized || this == NotificationPermission.Provisional

/**
 * Port of iOS `NotificationPermissionClient` (`System/Notifications/SessionNotifications.swift`).
 *
 * TODO(merge): replace with `:feature:system`'s client once the notifications port lands. Until
 * then the live value reports Not Determined and opens nothing, as a build without a relay would.
 */
class NotificationPermissionClient(
    val status: suspend () -> NotificationPermission,
    val request: suspend () -> NotificationPermission,
    /** Opens the system's notification settings for this app; false if it couldn't. */
    val openSettings: suspend () -> Boolean,
) {
    companion object Key : DependencyKey<NotificationPermissionClient> {
        override val liveValue = NotificationPermissionClient(
            status = { NotificationPermission.NotDetermined },
            request = { NotificationPermission.NotDetermined },
            openSettings = { false },
        )
        override val testValue = NotificationPermissionClient(
            status = { NotificationPermission.Authorized },
            request = { NotificationPermission.Authorized },
            openSettings = { true },
        )
    }
}

/**
 * Port of iOS `SessionNotificationsClient` (`System/Notifications/SessionNotifications.swift`): the
 * callback URL a send attaches so the server's notify plugin can tell this phone when the run
 * ends. Null means "send without one": notifications only ever enhance a send and never delay or
 * fail it.
 *
 * TODO(merge): replace with `:feature:system`'s client once its FCM bridge lands. Until then the
 * live value is unavailable (no relay), as an iOS build without one is.
 */
class SessionNotificationsClient(
    /** Whether this build has a relay and a push environment to offer at all. */
    val isAvailable: Boolean,
    val callbackURL: suspend (OpenCodeServer) -> String?,
    /** Mints ahead of the first send so Send rarely waits on the relay. */
    val prepare: suspend (OpenCodeServer) -> Unit,
    /** Drops a removed server's ref and callback (by persistence ID). */
    val forget: suspend (String) -> Unit,
    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. Main thread. */
    val ref: (String) -> String = { "" },
    /** Adds the badge to a permission granted before the app asked for it. */
    val includeBadges: suspend () -> Unit = {},
    /** Tells notifications which session is on screen so its own don't show. Main thread. */
    val setVisibleSession: (shown: Boolean, sessionID: String?) -> Unit = { _, _ -> },
    /** Whether the drawer button can show In-App Notices' label. Main thread. */
    val setShowsDrawerLabel: (Boolean) -> Unit = {},
) {
    companion object Key : DependencyKey<SessionNotificationsClient> {
        override val liveValue = SessionNotificationsClient(
            isAvailable = false,
            callbackURL = { null },
            prepare = {},
            forget = {},
        )
        override val testValue = SessionNotificationsClient(
            isAvailable = true,
            callbackURL = { null },
            prepare = {},
            forget = {},
        )
    }
}
