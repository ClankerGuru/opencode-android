package ai.opencode.core.store

import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
) {
    private val lock = Any()
    private val inFlight = HashMap<Any, MutableSet<Job>>()
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
    private fun <B> execute(effect: Effect<B>, now: (B) -> Unit, later: suspend (B) -> Unit) {
        when (effect) {
            is Effect.None -> Unit
            is Effect.SendAction -> now(effect.action)
            is Effect.Cancel -> cancel(effect.ids)
            is Effect.Merge -> effect.effects.forEach { execute(it, now, later) }
            is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                execute(mapped.effect, { now(mapped.transform(it)) }, { later(mapped.transform(it)) })
            }
            is Effect.Cancellable -> {
                if (effect.cancelInFlight) cancel(listOf(effect.id))
                launchTracked(effect.id) { runToCompletion(effect.effect, later) }
            }
            is Effect.Run, is Effect.Concatenate -> launchTracked(null) { runToCompletion(effect, later) }
        }
    }

    private fun launchTracked(id: Any?, block: suspend () -> Unit) {
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

    private fun cancel(ids: List<Any>) {
        val jobs = synchronized(lock) { ids.flatMap { inFlight.remove(it).orEmpty() } }
        jobs.forEach { it.cancel() }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <B> runToCompletion(effect: Effect<B>, send: suspend (B) -> Unit) {
        when (effect) {
            is Effect.None -> Unit
            is Effect.SendAction -> send(effect.action)
            is Effect.Cancel -> cancel(effect.ids)
            is Effect.Run -> withContext(effect.dependencies.asContextElement()) {
                try {
                    effect.operation(Send { send(it) })
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // Effects never crash the app; reducers model failures as actions.
                    AppLog.app.error("Unhandled effect error: ${error::class.java.simpleName}")
                }
            }
            is Effect.Merge -> coroutineScope { effect.effects.forEach { launch { runToCompletion(it, send) } } }
            is Effect.Concatenate -> effect.effects.forEach { runToCompletion(it, send) }
            is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                runToCompletion(mapped.effect) { send(mapped.transform(it)) }
            }
            is Effect.Cancellable -> {
                if (effect.cancelInFlight) cancel(listOf(effect.id))
                coroutineScope {
                    val child = launch { runToCompletion(effect.effect, send) }
                    synchronized(lock) { inFlight.getOrPut(effect.id) { HashSet() } += child }
                    child.invokeOnCompletion {
                        synchronized(lock) { inFlight[effect.id]?.let { set -> set -= child; if (set.isEmpty()) inFlight.remove(effect.id) } }
                    }
                }
            }
        }
    }

    @Suppress("unused")
    private suspend fun currentJob(): Job = currentCoroutineContext().job
}
