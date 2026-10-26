package ai.opencode.core.store

import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes [Effect]s for a store. Shared by [Store] and [TestStore] so tests exercise the
 * exact cancellation semantics production uses.
 *
 * Synchronous parts run before the call returns: [Effect.Cancel] cancels at once,
 * `cancelInFlight` cancels the previous run at once, and [Effect.SendAction] is handed to
 * [sendNow]. Coroutine parts launch in [scope].
 */
internal class EffectRunner<A>(
    private val scope: CoroutineScope,
    /** Delivers an action synchronously (queued after the current reduce). */
    private val sendNow: (A) -> Unit,
    /** Delivers an action from a running effect. */
    private val sendFromEffect: suspend (A) -> Unit,
    /** An effect threw (not cancellation): `Store` logs it, `TestStore` fails the test. */
    private val onUnhandledError: (Throwable) -> Unit = { error ->
        AppLog.app.error { "Unhandled effect error: ${error.logSummary}" }
    },
) {
    private val lock = Any()
    private data class CancelKey(val path: List<Any>, val id: Any)

    private val inFlight = HashMap<CancelKey, MutableSet<Job>>()
    private val running = HashSet<Job>()

    /** Whether any effect coroutine is still running (`TestStore.finish` asserts none are). */
    val hasRunningEffects: Boolean get() = synchronized(lock) { running.any { it.isActive } }

    val runningCount: Int get() = synchronized(lock) { running.count { it.isActive } }

    fun execute(effect: Effect<A>) = execute(effect, sendNow, sendFromEffect)

    fun cancelAll() {
        val jobs = synchronized(lock) { running.toList().also { running.clear(); inFlight.clear() } }
        jobs.forEach { it.cancel() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <B> execute(effect: Effect<B>, now: (B) -> Unit, later: suspend (B) -> Unit, path: List<Any> = emptyList()) {
        when (effect) {
            is Effect.None -> Unit
            is Effect.SendAction -> now(effect.action)
            is Effect.Cancel -> cancel(effect.ids.map { CancelKey(path, it) })
            is Effect.Merge -> effect.effects.forEach { execute(it, now, later, path) }
            is Effect.NavigationScoped -> execute(effect.effect, now, later, path + effect.id)
            is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                execute(mapped.effect, { now(mapped.transform(it)) }, { later(mapped.transform(it)) }, path)
            }
            is Effect.Cancellable -> {
                val key = CancelKey(path, effect.id)
                if (effect.cancelInFlight) cancel(listOf(key))
                // A body with nothing to suspend (send/cancel only) stays synchronous, so a
                // child's `.send` or `.cancel` under `ifLet`/`forEach` keeps TCA's ordering. A
                // merge's synchronous parts run now too; only its suspending parts are launched.
                executeCancellable(effect.effect, key, now, later, path)
            }
            is Effect.Run, is Effect.Concatenate -> launchTracked(null) { runToCompletion(effect, later, path) }
        }
    }

    /**
     * The body of a cancellable: synchronous parts (TCA delivers a `.send` at once, cancellable or
     * not) run now; the rest launch tracked under [key].
     */
    @Suppress("UNCHECKED_CAST")
    private fun <B> executeCancellable(effect: Effect<B>, key: CancelKey, now: (B) -> Unit, later: suspend (B) -> Unit, path: List<Any>) {
        when {
            isSynchronous(effect) -> execute(effect, now, later, path)
            effect is Effect.Merge -> effect.effects.forEach { executeCancellable(it, key, now, later, path) }
            effect is Effect.NavigationScoped -> executeCancellable(effect.effect, key, now, later, path + effect.id)
            effect is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                executeCancellable(mapped.effect, key, { now(mapped.transform(it)) }, { later(mapped.transform(it)) }, path)
            }
            else -> launchTracked(key) { runToCompletion(effect, later, path) }
        }
    }

    private fun isSynchronous(effect: Effect<*>): Boolean = when (effect) {
        is Effect.None, is Effect.SendAction, is Effect.Cancel -> true
        is Effect.Merge -> effect.effects.all(::isSynchronous)
        is Effect.Mapped<*, *> -> isSynchronous(effect.effect)
        is Effect.Cancellable -> isSynchronous(effect.effect)
        is Effect.NavigationScoped -> isSynchronous(effect.effect)
        is Effect.Run, is Effect.Concatenate -> false
    }

    private fun launchTracked(id: CancelKey?, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(lock) {
            running += job
            if (id != null) inFlight.getOrPut(id) { HashSet() } += job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                running -= job
                if (id != null) inFlight[id]?.let { set -> set -= job; if (set.isEmpty()) inFlight.remove(id) }
            }
        }
        job.start()
    }

    private fun cancel(ids: List<CancelKey>) {
        val jobs = synchronized(lock) { ids.flatMap { inFlight.remove(it).orEmpty() } }
        jobs.forEach { it.cancel() }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <B> runToCompletion(effect: Effect<B>, send: suspend (B) -> Unit, path: List<Any>) {
        when (effect) {
            is Effect.None -> Unit
            is Effect.SendAction -> send(effect.action)
            is Effect.Cancel -> cancel(effect.ids.map { CancelKey(path, it) })
            is Effect.NavigationScoped -> runToCompletion(effect.effect, send, path + effect.id)
            is Effect.Run -> withContext(effect.dependencies.asContextElement()) {
                try {
                    effect.operation(Send { send(it) })
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // Effects never crash the app; reducers model failures as actions.
                    onUnhandledError(error)
                }
            }
            is Effect.Merge -> coroutineScope { effect.effects.forEach { launch { runToCompletion(it, send, path) } } }
            is Effect.Concatenate -> effect.effects.forEach { runToCompletion(it, send, path) }
            is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                runToCompletion(mapped.effect, { send(mapped.transform(it)) }, path)
            }
            is Effect.Cancellable -> {
                val key = CancelKey(path, effect.id)
                if (effect.cancelInFlight) cancel(listOf(key))
                coroutineScope {
                    val child = launch(start = CoroutineStart.LAZY) { runToCompletion(effect.effect, send, path) }
                    synchronized(lock) { inFlight.getOrPut(key) { HashSet() } += child }
                    child.start()
                    child.invokeOnCompletion {
                        synchronized(lock) { inFlight[key]?.let { set -> set -= child; if (set.isEmpty()) inFlight.remove(key) } }
                    }
                }
            }
        }
    }
}
