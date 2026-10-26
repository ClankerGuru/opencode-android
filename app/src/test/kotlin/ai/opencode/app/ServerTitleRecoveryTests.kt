package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.servers.ServerTitleFeature
import ai.opencode.feature.settings.servers.ServerTitleFeature.Health
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ServerTitleRecoveryTests.swift`, the `AppFeature` part: the server sheet over Home
 * (Title Is the Switch), Try Again on the server in use, and failed attempts that leave Home and
 * the saved server as they were. The two tests of `ServerTitleFeature` alone are `:feature:settings`'.
 */
class ServerTitleRecoveryTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope
    private val snapshot = emptySnapshot
    private val laptop = OpenCodeServer(url = "http://laptop.test", username = "opencode", password = "", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.test", username = "opencode", password = "", name = "Studio")

    private fun TestScope.store(
        state: AppFeature.State,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects, dependencies)
    }

    private val savedServer get() = shared.run { shared(ServerSharedKeys.savedServer).value }

    private fun title(action: ServerTitleFeature.Action) = AppFeature.Action.ServerTitle(PresentationAction.Presented(action))

    private fun isTitleDelegate(action: AppFeature.Action) =
        ((action as? AppFeature.Action.ServerTitle)?.action as? PresentationAction.Presented)?.action is ServerTitleFeature.Action.Delegate

    private val isSwitchTapped: (AppFeature.Action) -> Boolean = {
        ((it as? AppFeature.Action.Home)?.action as? HomeFeature.Action.Delegate)?.action is HomeFeature.DelegateAction.ServerSwitchTapped
    }

    /** Connected to the laptop, with the sheet open over Home. */
    private fun sheetOverHome(): AppFeature.State {
        val state = shared.appState().copy(
            connection = AppFeature.State.Connection.Connected,
            home = shared.run { HomeFeature.State(laptop, snapshot) },
            serverTitle = shared.run { ServerTitleFeature.State(current = laptop) },
        ).load(laptop)
        shared.run {
            shared(ServerSharedKeys.savedServer).set(laptop)
            shared(ServerSharedKeys.savedServers).set(listOf(laptop, studio))
        }
        return state
    }

    @Test
    fun tryAgainOnTheServerInUseRefreshesHomeWithoutSwitching() = runTest {
        val base = sheetOverHome()
        val state = base.copy(serverTitle = base.serverTitle?.copy(checks = mapOf(laptop.persistenceID to Health.asleep)))
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = {}, homeSnapshot = { snapshot })
            // Home's refresh waits on the clock; it never needs to land here.
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(title(ServerTitleFeature.Action.Retry(laptop)))
        store.receive({ ((it as? AppFeature.Action.ServerTitle)?.action as? PresentationAction.Presented)?.action is ServerTitleFeature.Action.Checked }, "serverTitle.presented.checked")
        assertEquals(false, store.state.serverTitle?.busy)
        assertEquals(laptop, store.state.home?.server)
        assertNull(store.state.connectionAttempt)
        effects.skipInFlightEffects()
    }

    @Test
    fun aSwitchToTheServerInUseNeverLeavesTheSheetWaiting() = runTest {
        val base = sheetOverHome()
        val state = base.copy(serverTitle = base.serverTitle?.copy(busy = true))
        val store = store(state)
        store.send(title(ServerTitleFeature.Action.Delegate(ServerTitleFeature.Delegate.Select(laptop)))) {
            it.copy(serverTitle = it.serverTitle?.copy(busy = false))
        }
        store.finish()
    }

    @Test
    fun aHealthFailureFromTheSheetKeepsHomeAndShowsTheError() = runTest {
        val store = store(sheetOverHome()) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError("Offline") })
        }
        store.send(title(ServerTitleFeature.Action.Select(studio))) { it.copy(serverTitle = it.serverTitle?.copy(busy = true)) }
        store.receive(::isTitleDelegate, "serverTitle.presented.delegate")
        store.receive(isSwitchTapped, "home.serverSwitchTapped") {
            it.copy(
                returnServer = laptop,
                home = it.home?.copy(switchingTo = studio),
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = studio),
            ).load(studio)
        }
        store.receiveCase<AppFeature.Action.HealthResponse> {
            it.copy(
                serverTitle = it.serverTitle?.copy(busy = false, error = "Offline", failureRevision = 1),
                home = it.home?.copy(switchingTo = null),
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                returnServer = null,
            ).load(laptop)
        }
        assertEquals(laptop, savedServer)
        // Closing the sheet lands on the same Home.
        store.send(title(ServerTitleFeature.Action.Close))
        store.receive(::isTitleDelegate, "serverTitle.presented.delegate") { it.copy(serverTitle = null) }
        assertEquals(laptop, store.state.home?.server)
        store.finish()
    }

    @Test
    fun aHomeFailureAfterHealthKeepsHomeAndRestoresTheSavedServer() = runTest {
        val reads = Channel<Unit>()
        val probes = Channel<Unit>()
        val store = store(sheetOverHome()) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                health = { for (unit in probes) {} },
                homeSnapshot = {
                    for (unit in reads) {}
                    throw OpenCodeClientError("Couldn’t load sessions")
                },
            )
        }
        store.send(title(ServerTitleFeature.Action.Select(studio))) { it.copy(serverTitle = it.serverTitle?.copy(busy = true)) }
        store.receive(::isTitleDelegate, "serverTitle.presented.delegate")
        store.receive(isSwitchTapped, "home.serverSwitchTapped") {
            it.copy(
                returnServer = laptop,
                home = it.home?.copy(switchingTo = studio),
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = studio),
            ).load(studio)
        }
        probes.close()
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(studio)) }
        assertEquals(studio, savedServer)
        reads.close()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                serverTitle = it.serverTitle?.copy(busy = false, error = "Couldn’t load sessions", failureRevision = 1),
                home = it.home?.copy(switchingTo = null),
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                returnServer = null,
            ).load(laptop)
        }
        assertEquals(laptop, savedServer)
        // Tapping the server in use closes the sheet onto the same Home.
        store.send(title(ServerTitleFeature.Action.Select(laptop)))
        store.receive(::isTitleDelegate, "serverTitle.presented.delegate") { it.copy(serverTitle = null) }
        assertEquals(laptop, store.state.home?.server)
        store.finish()
    }

    @Test
    fun pairingFromTheSheetKeepsHomeUntilTheNewServerLoads() = runTest {
        val code = "https://studio.example/auth/connect/abc"
        val paired = OpenCodeServer(url = "https://studio.example", username = "opencode", password = "token", name = "Studio")
        val store = store(sheetOverHome()) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(redeemPairing = { paired }, health = { throw OpenCodeClientError("Offline") })
        }
        store.exhaustive = false
        store.send(title(ServerTitleFeature.Action.Scanned(code)))
        store.receive(::isTitleDelegate, "serverTitle.presented.delegate")
        store.receiveCase<AppFeature.Action.PairingCodeScanned>()
        assertEquals("Pairing doesn't tear Home down behind the sheet", laptop, store.state.home?.server)
        store.receiveCase<AppFeature.Action.PairingRedeemed>()
        assertEquals(paired.persistenceID, store.state.home?.switchingTo?.persistenceID)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertEquals(laptop, store.state.home?.server)
        assertNull(store.state.home?.switchingTo)
        assertEquals(laptop, savedServer)
        assertEquals(AppFeature.State.Connection.Connected, store.state.connection)
        assertEquals(false, store.state.serverTitle?.busy)
        assertEquals("Offline", store.state.serverTitle?.error)
        store.finish()

        // A refused code also leaves Home as it was.
        store.updateDependencies {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(redeemPairing = { throw OpenCodeClientError("Code expired") })
        }
        store.send(title(ServerTitleFeature.Action.Scanned(code)))
        store.receive(::isTitleDelegate, "serverTitle.presented.delegate")
        store.receiveCase<AppFeature.Action.PairingCodeScanned>()
        store.receiveCase<AppFeature.Action.PairingRedeemed>()
        assertEquals(laptop, store.state.home?.server)
        assertEquals(laptop, savedServer)
        assertEquals(AppFeature.State.Connection.Connected, store.state.connection)
        assertEquals("Code expired", store.state.serverTitle?.error)
        assertEquals(false, store.state.serverTitle?.busy)
        effects.skipInFlightEffects()
    }
}
