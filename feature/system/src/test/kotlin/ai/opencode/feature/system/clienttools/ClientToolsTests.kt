package ai.opencode.feature.system.clienttools

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.clienttools.ClientToolsFeature.Action
import ai.opencode.feature.system.clienttools.ClientToolsFeature.DelegateAction
import ai.opencode.feature.system.clienttools.ClientToolsFeature.State
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientMcpProtocol
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.ClientToolsEvent
import ai.opencode.server.clienttools.ClientToolsTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `ClientToolsTests.swift`, its reducer tests (MARK: Reducer). The protocol and
 * transport tests are in `:server`, the app's in `:app`.
 */
class ClientToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://studio.example.ts.net:4096", username = "opencode", password = "pw")
    private val screen = setOf(ClientTool.voiceRequestScreen)
    private val target get() = ClientToolsTarget(server = server, directory = "/work/app", tools = screen)
    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val third = UUID.fromString("00000000-0000-0000-0000-000000000021")

    @Test fun connectsReconnectsWithBackoffAndDisconnects() = runTest {
        val clock = TestClock()
        val sockets = mutableListOf<Channel<ClientToolsEvent>>()
        val store = TestStore(State(tools = screen), ClientToolsFeature(), this) {
            this[Clock] = clock
            this[ClientToolsClient] = ClientToolsClient(
                connect = {
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    synchronized(sockets) { sockets.add(channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, _ -> true },
            )
        }

        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Connecting) }
        waitForSockets(sockets, count = 1)
        sockets[0].send(ClientToolsEvent.Attached(connection))
        store.receive(Action.Socket(ClientToolsEvent.Attached(connection))) {
            it.copy(status = State.Status.Attached, connection = connection)
        }

        sockets[0].send(ClientToolsEvent.Closed(connection, "close code 1006"))
        sockets[0].close()
        store.receive(Action.Socket(ClientToolsEvent.Closed(connection, "close code 1006"))) {
            it.copy(status = State.Status.Waiting, failures = 1, connection = null)
        }
        // What its calls showed goes with it.
        store.receive(Action.Delegate(DelegateAction.Disconnected(connection)))
        clock.advance(499.milliseconds)
        clock.advance(1.milliseconds)
        store.receive(Action.RetryElapsed) { it.copy(status = State.Status.Connecting) }
        waitForSockets(sockets, count = 2)

        // A second failure before any handshake backs off longer.
        sockets[1].send(ClientToolsEvent.Closed(second, "HTTP 502"))
        sockets[1].close()
        store.receive(Action.Socket(ClientToolsEvent.Closed(second, "HTTP 502"))) {
            it.copy(status = State.Status.Waiting, failures = 2)
        }
        store.receive(Action.Delegate(DelegateAction.Disconnected(second)))
        clock.advance(999.milliseconds)
        clock.advance(1.milliseconds)
        store.receive(Action.RetryElapsed) { it.copy(status = State.Status.Connecting) }
        waitForSockets(sockets, count = 3)
        sockets[2].send(ClientToolsEvent.Attached(third))
        store.receive(Action.Socket(ClientToolsEvent.Attached(third))) {
            it.copy(status = State.Status.Attached, failures = 0, connection = third)
        }

        // The same target again keeps the socket, renamed or not; null closes it.
        val renamed = ClientToolsTarget(server = server.copy(name = "Studio"), directory = "/work/app", tools = screen)
        store.send(Action.TargetChanged(renamed))
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle, connection = null) }
        store.receive(Action.Delegate(DelegateAction.Disconnected(third)))
        store.finish()
    }

    @Test fun aServerWithoutTheRouteIsNotRetriedUntilTheServerChanges() = runTest {
        val clock = TestClock()
        val sockets = mutableListOf<Channel<ClientToolsEvent>>()
        val store = TestStore(State(tools = screen), ClientToolsFeature(), this) {
            this[Clock] = clock
            this[ClientToolsClient] = ClientToolsClient(
                connect = {
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    synchronized(sockets) { sockets.add(channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, _ -> true },
            )
        }
        assertEquals(setOf(404, 405, 501), ClientMcpProtocol.PERMANENT_STATUSES)

        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Connecting) }
        waitForSockets(sockets, count = 1)
        sockets[0].send(ClientToolsEvent.Unavailable(connection, 404))
        sockets[0].close()
        store.receive(Action.Socket(ClientToolsEvent.Unavailable(connection, 404))) {
            it.copy(status = State.Status.Unavailable, unavailableServer = server)
        }
        store.receive(Action.Delegate(DelegateAction.Disconnected(connection)))
        // No backoff: nothing wakes to try again.
        clock.advance(60.seconds)
        assertEquals(1, synchronized(sockets) { sockets.size })

        // Backgrounding and coming back (the target goes and returns), or a different tool list,
        // still finds the same server without the route.
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle) }
        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Unavailable) }
        val more = ClientToolsTarget(server = server, directory = "/work/app", tools = setOf(ClientTool.voiceRequestScreen, ClientTool.voiceOpenURL))
        store.send(Action.TargetChanged(more)) { it.copy(target = more) }
        clock.advance(60.seconds)
        assertEquals(1, synchronized(sockets) { sockets.size })

        // A changed server (here its password) tries again.
        val changed = ClientToolsTarget(server = server.copy(password = "new"), directory = "/work/app", tools = screen)
        store.send(Action.TargetChanged(changed)) {
            it.copy(target = changed, status = State.Status.Connecting, unavailableServer = null)
        }
        waitForSockets(sockets, count = 2)
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle) }
        store.finish()
    }

    @Test fun backoffDoublesToTenSeconds() {
        val delays = mutableListOf<kotlin.time.Duration>()
        for (failures in 0..6) delays.add(ClientToolsFeature.backoff(failures))
        assertEquals(
            listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 8.seconds, 10.seconds, 10.seconds),
            delays,
        )
    }

    private suspend fun waitForSockets(sockets: List<Channel<ClientToolsEvent>>, count: Int) {
        for (attempt in 0 until 1_000) {
            if (synchronized(sockets) { sockets.size } >= count) break
            yield()
        }
        assertEquals(count, synchronized(sockets) { sockets.size })
    }
}
