package ai.opencode.core.store

import ai.opencode.core.dependencies.DependencyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Port of TCA's `TestStore`. Use inside `runTest { }` and pass its scope:
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
 * Exhaustive by default: every state change must be asserted by the `update` lambda, every
 * action an effect sends must be [receive]d, and [finish] fails while effects still run
 * (cancellation included), as TCA's default. Set [exhaustive] = false for TCA's
 * `exhaustivity = .off`: `update` then applies to the actual new state (partial asserts),
 * unasserted effect actions are reduced before the next send, and [receive] skips
 * non-matching actions.
 *
 * Dependencies resolve to `testValue` unless overridden; keys without one fail loudly. An
 * effect that throws fails the test at the next call. Effects should take dispatchers from a
 * dependency, never `Dispatchers.IO` directly, so virtual time stays in control.
 * Never add test-only flags to production state; configure through dependencies.
 */
class TestStore<S, A>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    scope: CoroutineScope,
    dependencies: DependencyValues.Builder.() -> Unit = {},
) {
    /** The container reducers and effects resolve; read `Shared` values the reducer wrote through it. */
    var dependencies: DependencyValues = DependencyValues.testing().update(dependencies)
        private set

    /** iOS `store.dependencies.x = ...` after construction. */
    fun updateDependencies(block: DependencyValues.Builder.() -> Unit) {
        dependencies = dependencies.update(block)
    }

    /** Reads a value with this store's dependencies installed (e.g. `withDependencies { shared(key).value }`). */
    fun <R> withDependencies(block: () -> R): R = DependencyValues.withValues(dependencies, block)

    /** Every action effects send, in the order production would reduce them. */
    private val received = Channel<A>(Channel.UNLIMITED)

    /**
     * Actions sent synchronously (`Effect.send`): `Store` reduces them right after the action
     * that sent them, ahead of anything a running effect sent meanwhile, so they come first.
     */
    private val immediate = ArrayDeque<A>()

    private fun nextReceived(): A? = synchronized(immediate) { immediate.removeFirstOrNull() } ?: received.tryReceive().getOrNull()
    private var unhandledError: Throwable? = null

    var exhaustive: Boolean = true

    var state: S = initialState
        private set

    private val runner = EffectRunner<A>(
        scope = scope,
        sendNow = { synchronized(immediate) { immediate.addLast(it) } },
        sendFromEffect = { received.send(it) },
        onUnhandledError = { if (unhandledError == null) unhandledError = it },
    )

    /** Sends [action] and asserts the resulting state equals `update(previous)`. */
    suspend fun send(action: A, update: ((S) -> S)? = null) {
        throwUnhandled()
        if (exhaustive) assertNoUnreceived("before sending $action") else drain()
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
        throwUnhandled()
        while (true) {
            val action = nextReceived() ?: withTimeout(timeout) { received.receive() }
            if (matches(action)) {
                apply(action, update, "receive($action)")
                break
            }
            if (exhaustive) throw AssertionError("Expected to receive $description but received $action")
            apply(action, null, "skipped receive($action)", assert = false)
        }
        yield()
        throwUnhandled()
    }

    /** Reduces actions effects sent without asserting them (TCA's `skipReceivedActions`). */
    suspend fun skipReceivedActions() {
        yield()
        drain()
    }

    /** Asserts every action was received and no effect is still running. */
    suspend fun finish(timeout: Duration = 1.seconds) {
        yield()
        throwUnhandled()
        if (exhaustive) assertNoUnreceived("at finish") else drain()
        try {
            withTimeout(timeout) { while (runner.hasRunningEffects) delay(5) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val count = runner.runningCount
            runner.cancelAll()
            throw AssertionError("$count effect(s) still running at finish. Cancel them or await their actions.")
        }
        throwUnhandled()
        if (exhaustive) assertNoUnreceived("at finish") else drain()
    }

    private fun drain() {
        while (true) {
            val action = nextReceived() ?: return
            apply(action, null, "skipped($action)", assert = false)
        }
    }

    private fun apply(action: A, update: ((S) -> S)?, label: String, assert: Boolean = true) {
        val before = state
        val reduced = DependencyValues.withValues(dependencies) { reducer.reduce(before, action) }
        state = reduced.state
        DependencyValues.withValues(dependencies) { runner.execute(reduced.effect) }
        if (!assert) return
        val base = if (exhaustive) before else reduced.state
        val expected = update?.invoke(base) ?: base
        if (expected != reduced.state) {
            throw AssertionError("State mismatch after $label\nExpected: $expected\n  Actual: ${reduced.state}")
        }
    }

    private fun throwUnhandled() {
        val error = unhandledError ?: return
        unhandledError = null
        throw AssertionError("An effect threw: $error", error)
    }

    private fun assertNoUnreceived(where: String) {
        val next = nextReceived() ?: return
        throw AssertionError("Unreceived action $where: $next")
    }
}
