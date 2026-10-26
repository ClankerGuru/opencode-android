package ai.opencode.feature.settings.servers

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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ServerTitleRecoveryTests.swift`, the `:feature:settings` part: Try Again on the
 * server in use, driving `ServerTitleFeature` on its own. The tests over `AppFeature` (Home kept
 * through failed attempts, pairing from the sheet) are `:app`'s port.
 */
class ServerTitleRecoveryTests {
    @get:Rule val isolation = TestIsolationRule()

    private val laptop = OpenCodeServer(url = "http://laptop.test", username = "opencode", password = "", name = "My Mac")

    @Test
    fun tryAgainOnTheServerInUseRechecksItAndClearsBusy() = runTest {
        val shared = SettingsTestShared()
        val state = shared.read { ServerTitleFeature.State(current = laptop) }
            .copy(checks = mapOf(laptop.persistenceID to Health.asleep), path = listOf(Page.Detail(laptop)))
        val store = TestStore(state, ServerTitleFeature(), this) {
            this[SharedStore] = shared.store
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { })
        }
        store.send(Action.Retry(laptop)) { it.copy(busy = true, retrying = laptop.persistenceID) }
        store.receive(Action.Delegate(Delegate.Reconnect(laptop)))
        store.receive(Action.Checked(laptop.persistenceID, Health.connected)) {
            it.copy(checks = it.checks + (laptop.persistenceID to Health.connected), retrying = null, busy = false)
        }
        store.finish()
    }

    @Test
    fun tryAgainStillFailingClearsBusyAndLeavingCancelsIt() = runTest {
        val shared = SettingsTestShared()
        val state = shared.read { ServerTitleFeature.State(current = laptop) }
            .copy(checks = mapOf(laptop.persistenceID to Health.asleep))
        val store = TestStore(state, ServerTitleFeature(), this) {
            this[SharedStore] = shared.store
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError(message = "Offline", statusCode = 503) })
        }
        store.send(Action.Retry(laptop)) { it.copy(busy = true, retrying = laptop.persistenceID) }
        store.receive(Action.Delegate(Delegate.Reconnect(laptop)))
        store.receive(Action.Checked(laptop.persistenceID, Health.asleep)) { it.copy(retrying = null, busy = false) }

        store.updateDependencies { this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = { awaitCancellation() }) }
        store.send(Action.Retry(laptop)) { it.copy(busy = true, retrying = laptop.persistenceID) }
        store.receive(Action.Delegate(Delegate.Reconnect(laptop)))
        store.send(Action.Disappeared) { it.copy(retrying = null, busy = false) }
        store.finish()
    }
}
