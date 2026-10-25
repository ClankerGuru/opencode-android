package ai.opencode.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Port of Swift's `@TaskLocal`: a value bound for the duration of a block and
 * inherited by everything (child coroutines included) that block starts.
 *
 * ```kotlin
 * val current = TaskLocal<String?> { null }
 * current.withValue("x") { current.current() } // "x"
 * ```
 */
class TaskLocal<T>(private val defaultValue: () -> T) {
    private class Key<T> : CoroutineContext.Key<Element<T>>
    private class Element<T>(val value: T, override val key: CoroutineContext.Key<*>) : CoroutineContext.Element

    private val key = Key<T>()

    /** The bound value in this coroutine, else the default. */
    suspend fun current(): T = get(currentCoroutineContext())

    fun get(context: CoroutineContext): T {
        val element = context[key] ?: return defaultValue()
        return element.value
    }

    /** The context element binding [value], for `withContext` / `launch`. */
    fun asContextElement(value: T): CoroutineContext = Element(value, key)

    suspend fun <R> withValue(value: T, block: suspend () -> R): R = withContext(Element(value, key)) { block() }
}
