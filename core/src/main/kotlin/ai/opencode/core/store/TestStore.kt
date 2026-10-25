package ai.opencode.core.store

import ai.opencode.core.dependencies.DependencyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Port of TCA's exhaustive `TestStore`. Use inside `runTest { }` and pass its scope:
 *
 * ```kotlin
 * @Test fun askOpensComposer() = runTest {
 *     val store = TestStore(BrowserFeature.State(), BrowserFeature(), this) {
 *         this[UuidClient] = UuidClient.incrementing()
 *     }
 *     store.send(BrowserFeature.Action.AskTapped) { it.copy(isAsking = true) }
 *     store.finish()
 * }
 * ```
 *
 * Exhaustive by default: every state change must be asserted by the `update` lambda,
 * every action an effect sends must be [receive]d, and [finish] fails while effects are
 * still running (cancellation included), exactly as TCA's default exhaustivity.
 * Dependencies resolve to `testValue` unless overridden: unimplemented ones fail loudly.
 * Never add test-only flags to production state; configure through dependencies.
 */
class TestStore<S, A>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    scope: CoroutineScope,
    dependencies: DependencyValues.Builder.() -> Unit = {},
) {
    private val values = DependencyValues.testing().update(dependencies)
    private val received = Channel<A>(Channel.UNLIMITED)
    private val pendingSync = ArrayDeque<A>()

    /** Set to false for non-exhaustive tests (TCA's `exhaustivity = .off`): state asserts become partial. */
    var exhaustive: Boolean = true

    var state: S = initialState
        private set

    private val runner = EffectRunner<A>(
        scope = scope,
        sendNow = { pendingSync.addLast(it) },
        sendFromEffect = { received.send(it) },
    )

    /** Sends [action] and asserts the resulting state equals `update(previous)`. */
    suspend fun send(action: A, update: ((S) -> S)? = null) {
        if (exhaustive) assertNoUnreceived("before sending $action")
        apply(action, update, "send($action)")
        yield()
    }

    /** Waits for an effect to send an action equal to [expected], then asserts the state change. */
    suspend fun receive(expected: A, timeout: Duration = 1.seconds, update: ((S) -> S)? = null) {
        receive({ it == expected }, "$expected", timeout, update)
    }

    /** Waits for an action matching [matches] (for actions carrying non-comparable payloads). */
    suspend fun receive(
        matches: (A) -> Boolean,
        description: String = "matching action",
        timeout: Duration = 1.seconds,
        update: ((S) -> S)? = null,
    ) {
        val action = pendingSync.removeFirstOrNull() ?: withTimeout(timeout) { received.receive() }
        if (!matches(action)) throw AssertionError("Expected to receive $description but received $action")
        apply(action, update, "receive($action)")
        yield()
    }

    /** Skips actions effects sent without asserting them (TCA's `skipReceivedActions`). */
    suspend fun skipReceivedActions() {
        yield()
        pendingSync.clear()
        while (received.tryReceive().isSuccess) Unit
    }

    /** Asserts every action was received and no effect is still running. */
    suspend fun finish(timeout: Duration = 1.seconds) {
        yield()
        assertNoUnreceived("at finish")
        try {
            withTimeout(timeout) { while (runner.hasRunningEffects) kotlinx.coroutines.delay(5) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val count = runner.runningCount
            runner.cancelAll()
            throw AssertionError("$count effect(s) still running at finish. Cancel them or await their actions.")
        }
        assertNoUnreceived("at finish")
    }

    private fun apply(action: A, update: ((S) -> S)?, label: String) {
        val before = state
        val reduced = DependencyValues.withValues(values) { reducer.reduce(before, action) }
        state = reduced.state
        DependencyValues.withValues(values) { runner.execute(reduced.effect) }
        val expected = update?.invoke(before) ?: before
        if (expected != reduced.state && (exhaustive || update != null)) {
            throw AssertionError("State mismatch after $label\nExpected: $expected\n  Actual: ${reduced.state}")
        }
    }

    private fun assertNoUnreceived(where: String) {
        if (pendingSync.isNotEmpty()) throw AssertionError("Unreceived actions $where: $pendingSync")
        val next = received.tryReceive().getOrNull() ?: return
        throw AssertionError("Unreceived action $where: $next")
    }
}
