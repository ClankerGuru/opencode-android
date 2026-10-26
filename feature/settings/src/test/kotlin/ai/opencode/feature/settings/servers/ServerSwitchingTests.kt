package ai.opencode.feature.settings.servers

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.SettingsTestShared
import ai.opencode.feature.settings.servers.ServerTitleFeature.Action
import ai.opencode.feature.settings.servers.ServerTitleFeature.Delegate
import ai.opencode.feature.settings.servers.ServerTitleFeature.Health
import ai.opencode.feature.settings.servers.ServerTitleFeature.Page
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ServerSwitchingTests.swift`, the `:feature:settings` part: the tests that drive
 * `ServerTitleFeature` on its own (the server sheet). Its `AppFeature` tests (switching, pairing,
 * the sheet's opening page) and the `OpenCodeServer` header tests are `:app`'s and `:server`'s ports.
 */
class ServerSwitchingTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun TestScope.store(
        shared: SettingsTestShared,
        state: (() -> ServerTitleFeature.State) = { ServerTitleFeature.State() },
        dependencies: ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {},
    ) = TestStore(shared.read(state), ServerTitleFeature(), this) {
        this[SharedStore] = shared.store
        dependencies()
    }

    @Test
    fun titleSheetRejectsInvalidCodesAndIgnoresDuplicateSubmissions() = runTest {
        val code = "https://studio.example/auth/connect/abc"
        val store = store(SettingsTestShared())
        store.send(Action.Scanned("not a pairing code")) { it.copy(error = "Couldn’t read a pairing link", failureRevision = 1) }
        store.send(Action.Scanned(code)) { it.copy(busy = true, error = null) }
        store.receive(Action.Delegate(Delegate.Pair(code)))
        store.send(Action.Scanned(code))
        store.send(Action.Connect)
        store.send(Action.Back)
        store.finish()
    }

    @Test
    fun titleSheetSaysWhenTheClipboardIsEmpty() = runTest {
        val store = store(SettingsTestShared()) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, string = { "  " })
        }
        store.send(Action.Paste) { it.copy(error = "Your clipboard is empty", failureRevision = 1) }
        store.finish()
    }

    @Test
    fun titleSheetRoutesAnOfflineServerToRecovery() = runTest {
        val server = OpenCodeServer(url = "http://mini.test", username = "opencode", password = "")
        val store = store(SettingsTestShared(), { ServerTitleFeature.State(checks = mapOf(server.persistenceID to Health.asleep)) })
        store.send(Action.Select(server)) { it.withPage(Page.Detail(server)).copy(nameDraft = server.friendlyName) }
        store.send(Action.Retry(server)) { it.copy(busy = true) }
        store.receive(Action.Delegate(Delegate.Select(server)))
        store.finish()
    }

    @Test
    fun titleSheetRenamesInlineAndPairsAgainOverTheDetails() = runTest {
        val server = OpenCodeServer(url = "http://mini.test", username = "opencode", password = "")
        val store = store(SettingsTestShared())
        store.send(Action.Detail(server)) { it.copy(path = listOf(Page.Detail(server)), nameDraft = server.friendlyName) }
        // Leaving the field unchanged saves nothing.
        store.send(Action.SaveName)
        store.send(Action.NameDraftChanged("  Den  ")) { it.copy(nameDraft = "  Den  ") }
        store.send(Action.SaveName)
        store.receive(Action.Delegate(Delegate.Rename(server, "Den")))
        store.send(Action.Add) { it.copy(path = listOf(Page.Detail(server), Page.Connect)) }
        store.send(Action.Back) { it.copy(path = listOf(Page.Detail(server))) }
        store.send(Action.Back) { it.copy(path = emptyList()) }
        store.finish()
    }

    @Test
    fun titleSheetClassifiesHealthAndCancelsPendingProbes() = runTest {
        val server = OpenCodeServer(url = "http://mini.test", username = "opencode", password = "")
        val shared = SettingsTestShared()
        shared.set(ServerSharedKeys.savedServers, listOf(server))
        val store = store(shared, { ServerTitleFeature.State(current = server) }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { awaitCancellation() })
        }
        store.send(Action.Appeared)
        store.send(Action.Disappeared)
        store.finish()
        assertEquals(Health.signIn, Health.failure(OpenCodeClientError(message = "Auth", statusCode = 401)))
        assertEquals(Health.update, Health.failure(OpenCodeClientError(message = "Old", statusCode = 404)))
        assertEquals(Health.asleep, Health.failure(OpenCodeClientError(message = "Offline", statusCode = 503)))
    }
}
