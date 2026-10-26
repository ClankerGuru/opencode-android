package ai.opencode.app

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.servers.ServerTitleFeature
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `ServerSwitchingTests.swift`, the `AppFeature` part: renaming, re-pairing,
 * switching from Home and Settings, the server form and scanner over Home, removal from the
 * drawer, closing the server sheet mid-switch, where the sheet opens, and the form's headers.
 * Names, decoding and headers on requests are `:server`'s; Home's own tests `:feature:drawer`'s;
 * `ServerTitleFeature` alone `:feature:settings`'.
 *
 * iOS asserts shared values inside state closures; a `Shared` here is a reference (equal across
 * states), so those values are checked after the step.
 */
class ServerSwitchingTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope
    private val snapshot = emptySnapshot

    private fun TestScope.store(
        state: AppFeature.State,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects, dependencies)
    }

    private fun home(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot = this.snapshot): HomeFeature.State =
        shared.run { HomeFeature.State(server, snapshot) }

    private fun delegate(action: HomeFeature.DelegateAction) = AppFeature.Action.Home(HomeFeature.Action.Delegate(action))

    private val savedServer get() = shared.run { shared(ServerSharedKeys.savedServer).value }
    private val savedServers get() = shared.run { shared(ServerSharedKeys.savedServers).value }
    private val cachedHomeSnapshots get() = shared.run { shared(ServerSharedKeys.cachedHomeSnapshots).value }
    private fun save(server: OpenCodeServer?) = shared.run { shared(ServerSharedKeys.savedServer).set(server) }
    private fun saveAll(servers: List<OpenCodeServer>) = shared.run { shared(ServerSharedKeys.savedServers).set(servers) }

    private fun connected(server: OpenCodeServer) = shared.appState().copy(connection = AppFeature.State.Connection.Connected, home = home(server))

    // MARK: Names

    @Test
    fun renamingInPlaceKeepsHomeAndPersistsTheName() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b")
        val state = connected(laptop)
        save(laptop)
        saveAll(listOf(laptop, studio))
        val store = store(state)

        val renamed = laptop.copy(name = "Work Mac")
        store.send(delegate(HomeFeature.DelegateAction.ServerRenamed(laptop, "  Work Mac "))) {
            it.copy(home = it.home?.withServer(renamed))
        }
        assertEquals(renamed, savedServer)
        assertEquals(listOf(renamed, studio), savedServers)
        // Blank goes back to the default name.
        store.send(delegate(HomeFeature.DelegateAction.ServerRenamed(renamed, ""))) {
            it.copy(home = it.home?.withServer(laptop))
        }
        assertEquals(laptop, savedServer)
        assertEquals(listOf(laptop, studio), savedServers)
        // Another saved server renames without touching Home.
        val renamedStudio = studio.copy(name = "Studio")
        store.send(delegate(HomeFeature.DelegateAction.ServerRenamed(studio, "Studio")))
        assertEquals(listOf(laptop, renamedStudio), savedServers)
        store.finish()
    }

    @Test
    fun rePairingKeepsTheSavedNameAndHeaders() = runTest {
        val saved = OpenCodeServer(
            url = "http://10.0.10.26:4196", username = "opencode", password = "1780000000.old",
            name = "Studio", headers = listOf(OpenCodeServer.Header("X-Proxy", "1")),
        )
        val paired = OpenCodeServer(url = saved.url, username = "opencode", password = "1790000000.new")
        saveAll(listOf(saved))
        val state = shared.appState()
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(redeemPairing = { paired }, health = {}, homeSnapshot = { snapshot })
        }
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("http://10.0.10.26:4196/auth/connect/abc"))
        store.receiveCase<AppFeature.Action.PairingRedeemed> { it.copy(serverName = "Studio", headers = saved.headers) }
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        assertEquals(paired.password, savedServer?.password)
        assertEquals("Studio", savedServer?.name)
        assertEquals(listOf("Studio"), savedServers.map { it.name })
        assertEquals(saved.headers, savedServers.firstOrNull()?.headers)
        effects.skipInFlightEffects()
    }

    // MARK: Switching

    @Test
    fun switchingFromHomeConnectsToTheOtherServerAndOffersTheWayBack() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b", name = "Studio")
        val state = connected(laptop)
        saveAll(listOf(laptop, studio))
        val gate = GatedHome()
        val store = store(state) { this[OpenCodeClient] = gate.client() }

        // The server in use is already there.
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(laptop)))

        // Home stays up, marking the server it's switching to, until Studio's sessions load.
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio))) {
            it.copy(
                returnServer = laptop,
                home = it.home?.copy(switchingTo = studio),
                serverURL = studio.url,
                password = studio.password,
                serverName = "Studio",
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = studio),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(studio)) }
        assertEquals(studio, savedServer)
        assertEquals(listOf(studio, laptop), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                returnServer = null,
                home = home(studio),
            )
        }
        assertEquals(
            CachedHomeSnapshot(serverID = studio.persistenceID, snapshot = snapshot, capturedAt = Instant.EPOCH),
            cachedHomeSnapshots[studio.persistenceID],
        )
        store.finish()
    }

    /** A switch made from Settings' server page stays in Settings: the new server's Home opens with Settings shown. */
    @Test
    fun aSwitchFromSettingsStaysInSettings() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b", name = "Studio")
        val state = shared.appState().copy(
            connection = AppFeature.State.Connection.Connected,
            home = shared.run { home(laptop).showingSettings() },
        )
        saveAll(listOf(laptop, studio))
        val store = store(state) { this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = {}, homeSnapshot = { snapshot }) }
        store.exhaustive = false
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio))) { it.copy(home = it.home?.copy(switchingTo = studio)) }
        assertEquals("Settings stays up while the switch connects", true, store.state.home?.isSettingsShown)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        assertEquals(studio.persistenceID, store.state.home?.server?.persistenceID)
        assertNull(store.state.home?.switchingTo)
        assertEquals("and on the new server's Home", true, store.state.home?.isSettingsShown)
        effects.skipInFlightEffects()
    }

    @Test
    fun aSwitchThatFailsLeavesHomeForThePickerAndItsWayBack() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b")
        val state = connected(laptop)
        saveAll(listOf(laptop, studio))
        val store = store(state) { this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError("Offline") }) }
        store.exhaustive = false
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio)))
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertNull(store.state.home)
        assertEquals(laptop, store.state.returnServer)
        assertEquals("Offline", store.state.errorMessage)
        effects.skipInFlightEffects()
    }

    @Test
    fun editingFromTheDrawerOpensTheFormOverHome() = runTest {
        val studio = OpenCodeServer(
            url = "https://studio.example", username = "opencode", password = "b",
            name = "Studio", headers = listOf(OpenCodeServer.Header("CF-Access-Client-Id", "id")),
        )
        val state = connected(studio)
        saveAll(listOf(studio))
        val store = store(state)

        store.send(delegate(HomeFeature.DelegateAction.EditServerRequested(studio))) {
            it.copy(
                editingSavedServer = studio,
                isEditingServer = true,
                serverURL = studio.url,
                password = studio.password,
                serverName = "Studio",
                headers = studio.headers,
            )
        }
        assertEquals(studio, store.state.server)
        // Cancelling leaves Home and its connection as they were.
        store.send(AppFeature.Action.CancelEditingTapped) { it.copy(isEditingServer = false, editingSavedServer = null) }
        assertEquals(AppFeature.State.Connection.Connected, store.state.connection)
        store.finish()
    }

    @Test
    fun editingTheServerInUseToANewAddressReconnectsInsteadOfKeepingItsHome() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        saveAll(listOf(laptop))
        // The server in use, open in its form with a new port: a new persistence ID, but still the server in use.
        val state = connected(laptop).copy(editingSavedServer = laptop, isEditingServer = true).load(laptop)
            .copy(serverURL = "http://192.168.1.20:4097")
        val store = store(state) { this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError("Offline") }) }
        store.exhaustive = false
        store.send(AppFeature.Action.ConnectButtonTapped)
        assertNull("The stale Home ends; the edited address connects afresh", store.state.home)
        assertEquals(AppFeature.State.Connection.Connecting, store.state.connection)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        effects.skipInFlightEffects()
    }

    @Test
    fun aServerFormFromSettingsOpensOverIt() = runTest {
        // Settings is an overlay on Home, not a sheet: the form presents over it, and Cancel returns to the server page.
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val state = shared.appState().copy(
            connection = AppFeature.State.Connection.Connected,
            home = shared.run { home(laptop).showingSettings() },
        )
        saveAll(listOf(laptop))
        val store = store(state)
        store.exhaustive = false
        store.send(delegate(HomeFeature.DelegateAction.EditServerRequested(laptop)))
        assertTrue(store.state.isEditingServer)
        assertEquals("Settings stays beneath the form", true, store.state.home?.isSettingsShown)
        store.send(delegate(HomeFeature.DelegateAction.PairServerRequested))
        assertTrue(store.state.isScanningPairingCode)
        assertEquals(true, store.state.home?.isSettingsShown)
        effects.skipInFlightEffects()
    }

    @Test
    fun addingFromTheDrawerOpensTheFormOrScannerOverHome() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        saveAll(listOf(laptop))
        val state = connected(laptop).load(laptop)
        val store = store(state)

        store.send(delegate(HomeFeature.DelegateAction.PairServerRequested)) { it.copy(isScanningPairingCode = true) }
        store.send(AppFeature.Action.ScannerDismissed) { it.copy(isScanningPairingCode = false) }
        store.send(delegate(HomeFeature.DelegateAction.AddServerRequested)) {
            it.copy(isEditingServer = true, serverURL = "", password = "")
        }
        assertEquals(AppFeature.State.Connection.Connected, store.state.connection)
        assertNotNull(store.state.home)
        store.finish()
    }

    @Test
    fun removingFromTheDrawerForgetsAnotherServerButNotTheOneInUse() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b")
        val state = connected(laptop)
        saveAll(listOf(laptop, studio))
        val store = store(state)
        store.exhaustive = false

        store.send(delegate(HomeFeature.DelegateAction.ServerRemovalConfirmed(laptop)))
        store.send(delegate(HomeFeature.DelegateAction.ServerRemovalConfirmed(studio)))
        store.receiveCase<AppFeature.Action.ServerDeleted>()
        assertEquals(listOf(laptop), savedServers)
        assertEquals(laptop, store.state.home?.server)
        effects.skipInFlightEffects()
    }

    // MARK: Headers

    @Test
    fun closingTitleSheetCancelsTheHomeReadAndRestoresTheVisibleServer() = runTest {
        val laptop = OpenCodeServer(url = "http://laptop.test", username = "opencode", password = "", name = "My Mac")
        val studio = OpenCodeServer(url = "http://studio.test", username = "opencode", password = "", name = "Studio")
        val state = connected(laptop)
            .copy(serverTitle = shared.run { ServerTitleFeature.State(current = laptop) })
            .load(laptop)
        save(laptop)
        saveAll(listOf(laptop, studio))
        val gate = GatedHome()
        val store = store(state) { this[OpenCodeClient] = gate.client() }
        try {
            store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio))) {
                it.copy(
                    returnServer = laptop,
                    home = it.home?.copy(switchingTo = studio),
                    connection = AppFeature.State.Connection.Connecting,
                    connectionGeneration = 1,
                    connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = studio),
                ).load(studio)
            }
            store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(studio)) }
            assertEquals(studio, savedServer)
            assertEquals(listOf(studio, laptop), savedServers)
            store.send(AppFeature.Action.ServerTitle(PresentationAction.Presented(ServerTitleFeature.Action.Delegate(ServerTitleFeature.Delegate.Close)))) {
                it.copy(
                    serverTitle = null,
                    home = it.home?.copy(switchingTo = null),
                    returnServer = null,
                    connection = AppFeature.State.Connection.Connected,
                    connectionAttempt = null,
                ).load(laptop)
            }
            assertEquals(laptop, savedServer)
            store.finish()
            // A response which raced with dismissal cannot replace Home.
            store.send(AppFeature.Action.HomeResponse(attemptID = 1, result = Result.success(snapshot)))
        } finally {
            gate.finish()
        }
    }

    @Test
    fun firstRunWithNoServersOpensOnlyTheConnectPage() = runTest {
        val store = store(shared.appState()) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.serverTitleSwitch))
        }
        store.send(AppFeature.Action.ServerTitleOpened) {
            it.copy(serverTitle = shared.run { ServerTitleFeature.State() }.copy(connectOnly = true))
        }
        assertEquals(emptyList<ServerTitleFeature.Page>(), store.state.serverTitle?.path)
        store.finish()
    }

    @Test
    fun withSavedServersAndNoHomeTheSheetOpensOnConnectOverTheList() = runTest {
        val laptop = OpenCodeServer(url = "http://laptop.test", username = "opencode", password = "", name = "My Mac")
        saveAll(listOf(laptop))
        val store = store(shared.appState()) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.serverTitleSwitch))
        }
        store.send(AppFeature.Action.ServerTitleOpened) {
            it.copy(serverTitle = shared.run { ServerTitleFeature.State() }.withPage(ServerTitleFeature.Page.Connect))
        }
        assertEquals(false, store.state.serverTitle?.connectOnly)
        store.finish()
    }

    @Test
    fun theFormDropsBlankHeaderRowsAndNamesAnInvalidOne() {
        var state = shared.appState().copy(
            serverURL = "https://studio.example",
            headers = listOf(OpenCodeServer.Header(" X-Proxy ", " 1 "), OpenCodeServer.Header("", "")),
        )
        assertEquals(listOf(OpenCodeServer.Header("X-Proxy", "1")), state.server.headers)
        assertNull(state.headerProblem)

        state = state.copy(headers = state.headers + OpenCodeServer.Header("Bad Name", "x"))
        assertEquals("“Bad Name” isn’t a valid header name", state.headerProblem)

        state = state.copy(headers = listOf(OpenCodeServer.Header("X-Proxy", "a\nInjected: b")))
        assertEquals("The value of X-Proxy can’t contain a line break", state.headerProblem)
    }
}
