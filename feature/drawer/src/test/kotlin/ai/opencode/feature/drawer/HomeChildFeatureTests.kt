package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `HomeChildFeatureTests.swift`: Home's child features, `HomeNotificationsFeature` and
 * `HomeConnectionFeature`, on their own.
 *
 * Not ported here (`HomeSettingsFeature` is `:feature:settings`'): `settingsChecksSupportForItsScopedServer`
 * and `modelPickerStyleIsOneSetting`.
 *
 * `notificationPermissionDecisionIsOwnedByChild` is parameterized in iOS; each permission runs in
 * turn inside the one method.
 */
class HomeChildFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    @Test fun notificationPermissionDecisionIsOwnedByChild() = runTest {
        for (permission in listOf(NotificationPermission.Enabled, NotificationPermission.Disabled, NotificationPermission.NotDetermined)) {
            val state = HomeNotificationsFeature.State(
                server = server,
                status = HomeNotificationsFeature.Status(notificationPermission = NotificationPermission.NotDetermined),
            )
            val prepared = Collections.synchronizedList(mutableListOf<OpenCodeServer>())
            val store = TestStore(state, HomeNotificationsFeature(), this) {
                this[NotificationPermissionClient] = NotificationPermissionClient(
                    status = { permission },
                    request = { permission },
                    openSettings = { true },
                )
                this[SessionNotificationsClient] = SessionNotificationsClient(
                    isAvailable = true,
                    callbackURL = { null },
                    prepare = { prepared += it },
                    forget = {},
                )
            }
            store.send(HomeNotificationsFeature.Action.EnableNotificationsTapped) {
                it.copy(status = it.status.copy(isRequestingNotifications = true))
            }
            store.receive(HomeNotificationsFeature.Action.NotificationPermissionRequested(permission)) {
                it.copy(
                    status = it.status.copy(
                        isRequestingNotifications = false,
                        notificationSetupError = if (permission == NotificationPermission.NotDetermined) {
                            "Couldn’t request permission. Try again."
                        } else {
                            it.status.notificationSetupError
                        },
                    ),
                )
            }
            if (permission == NotificationPermission.NotDetermined) {
                store.receive(HomeNotificationsFeature.Action.NotificationPermissionLoaded(permission))
            } else {
                store.receive(HomeNotificationsFeature.Action.NotificationPermissionLoaded(permission)) {
                    it.copy(status = it.status.copy(notificationPermission = permission))
                }
            }
            store.finish()
            assertEquals(
                "$permission",
                if (permission == NotificationPermission.Enabled) listOf(server) else emptyList(),
                prepared.toList(),
            )
        }
    }

    @Test fun notificationPluginCheckCancelsThePreviousRead() = runTest {
        val clock = TestClock()
        var calls = 0
        val store = TestStore(HomeNotificationsFeature.State(server = server), HomeNotificationsFeature(), this) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                notifyPluginStatus = {
                    calls += 1
                    val call = calls
                    try {
                        clock.sleep((if (call == 1) 2 else 1).seconds)
                    } catch (cancellation: CancellationException) {
                        throw OpenCodeClientError("Cancelled")
                    }
                    if (call == 1) OpenCodeNotifyPluginStatus.Missing else OpenCodeNotifyPluginStatus.Active
                },
            )
        }
        store.send(HomeNotificationsFeature.Action.NotifyPluginCheckRequested) {
            it.copy(status = it.status.copy(notifyPlugin = HomeNotificationsFeature.NotifyPluginCheck.Checking))
        }
        store.send(HomeNotificationsFeature.Action.NotifyPluginCheckRequested)
        clock.advance(1.seconds)
        store.receive(HomeNotificationsFeature.Action.NotifyPluginChecked(OpenCodeNotifyPluginStatus.Active)) {
            it.copy(status = it.status.copy(notifyPlugin = HomeNotificationsFeature.NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Active)))
        }
        clock.advance(1.seconds)
        store.finish()
        assertEquals(2, calls)
    }

    @Test fun connectionRetriesReplaceThePreviousTimerWithoutDisconnecting() = runTest {
        val clock = TestClock()
        val state = HomeConnectionFeature.State(server = server, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
        }
        store.send(HomeConnectionFeature.Action.SnapshotFailed) { it.copy(snapshotFailures = 1) }
        store.send(HomeConnectionFeature.Action.SnapshotFailed) { it.copy(snapshotFailures = 2) }
        clock.advance(1.seconds)
        clock.advance(1.seconds)
        store.receive(HomeConnectionFeature.Action.SnapshotRetryRequested)
        store.finish()
        assertTrue(store.state.isConnected)
        assertTrue(store.state.isEventStreamLive)
    }

    @Test fun connectionLossKeepsOneLastKnownDeadlineAcrossRepeatedFailures() = runTest {
        val clock = TestClock()
        val now = Instant.ofEpochSecond(100)
        val state = HomeConnectionFeature.State(server = server, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
        }
        store.send(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
            )
        }
        store.send(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected)
        }
        clock.advance(HomeFeature.lastKnownActivityLifetime)
        store.receive(HomeConnectionFeature.Action.LastKnownActivityExpired) { it.copy(lastKnownActivityDeadline = null) }
        store.finish()
    }

    @Test fun backgroundingAnOutageCancelsTheChildStream() = runTest {
        val clock = TestClock()
        var subscriptions = 0
        val state = HomeConnectionFeature.State(
            server = server,
            isStreaming = true,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
        )
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions += 1
                    flow { throw IOException("cannot connect to host") }
                },
            )
            this[BackgroundTimeClient] = BackgroundTimeClient { _, _ -> throw AssertionError("An outage must not hold background time") }
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ConnectionLost)
        store.send(HomeConnectionFeature.Action.SceneEnteredBackground)
        clock.advance(120.seconds)
        store.finish()
        assertEquals(1, subscriptions)
    }

    @Test fun rejectedCredentialsDoNotRestartTheChildStream() = runTest {
        val state = HomeConnectionFeature.State(server = server, isStreaming = true, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this)
        store.send(HomeConnectionFeature.Action.EventStreamFailed(OpenCodeClientError("Sign-in failed", statusCode = 401))) {
            it.copy(
                connectionErrorMessage = "Sign-in failed",
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                isEventStreamLive = false,
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.send(HomeConnectionFeature.Action.RetryConnectionTapped)
        store.finish()
    }

    @Test fun overflowResubscribesAndEmitsConnectedAgain() = runTest {
        val clock = TestClock()
        var subscriptions = 0
        val second = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
        val now = Instant.ofEpochSecond(100)
        val state = HomeConnectionFeature.State(server = server, isStreaming = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions += 1
                    if (subscriptions > 1) {
                        second.consumeAsFlow()
                    } else {
                        // iOS buffers the newest one: the second `.connected` overflows it.
                        flow {
                            emit(OpenCodeServerEvent.Connected)
                            throw EventStreamOverflow()
                        }
                    }
                },
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(hasConnected = true, isEventStreamLive = true)
        }
        store.receive(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
            )
        }
        clock.advance(1.seconds)
        second.send(OpenCodeServerEvent.Connected)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) { it.copy(isEventStreamLive = true) }
        assertEquals(2, subscriptions)
        store.send(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected, isEventStreamLive = false)
        }
        store.send(HomeConnectionFeature.Action.SceneEnteredBackground)
        clock.advance(HomeFeature.lastKnownActivityLifetime)
        store.receive(HomeConnectionFeature.Action.LastKnownActivityExpired) { it.copy(lastKnownActivityDeadline = null) }
        store.finish()
    }
}
