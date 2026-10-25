package ai.opencode.core.dependencies

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.properties.ReadOnlyProperty

/**
 * Port of swift-dependencies' `DependencyKey`: a client's identity plus its live and test
 * implementations. Declare one as the companion of the client type:
 *
 * ```kotlin
 * class HapticsClient(val play: (Haptic) -> Unit) {
 *     companion object Key : DependencyKey<HapticsClient> {
 *         override val liveValue by lazy { HapticsClient(play = LiveHaptics::play) }
 *         override val testValue = HapticsClient(play = {})
 *     }
 * }
 * ```
 *
 * `liveValue` is read once per process and cached (as TCA caches it), so a single
 * process-wide instance stays private behind its key. `testValue` must be inert or
 * isolated; it is what [DependencyValues.testing] resolves when nothing overrides it.
 */
interface DependencyKey<T> {
    val liveValue: T
    /** Consulted only in test containers. Without an override, tests fail loudly instead of running live I/O. */
    val testValue: T
        get() = throw AssertionError(
            "${javaClass.enclosingClass?.simpleName ?: javaClass.simpleName} has no testValue; override it in the test",
        )
}

/**
 * Port of `DependencyValues`: an immutable map of overridden clients layered over the
 * key defaults. Reducers and effects read clients with [dependency]; tests and previews
 * override them with [withDependencies] or `TestStore(dependencies = { ... })`.
 *
 * Resolution order: the innermost override (thread/coroutine-local), then the key's
 * `testValue` when [isTesting] (created once per test container), else its cached `liveValue`.
 *
 * There are no business singletons: a service is a [DependencyKey] client, never a
 * top-level `object` reached directly from a reducer, effect or composable.
 */
class DependencyValues private constructor(
    private val overrides: Map<DependencyKey<*>, Any?>,
    val isTesting: Boolean,
    /** `testValue`s resolved by this test container and its copies (one instance per test, as in TCA). */
    private val testCache: HashMap<DependencyKey<*>, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: DependencyKey<T>): T {
        if (overrides.containsKey(key)) return overrides[key] as T
        if (!isTesting) return live(key)
        return synchronized(testCache) {
            if (testCache.containsKey(key)) testCache[key] as T else key.testValue.also { testCache[key] = it }
        }
    }

    /** A copy with [key] resolved to [value]. */
    fun <T> with(key: DependencyKey<T>, value: T): DependencyValues =
        DependencyValues(overrides + (key to value), isTesting, testCache)

    /** A copy with every override in [other] applied over this one. */
    fun merging(other: DependencyValues): DependencyValues =
        DependencyValues(overrides + other.overrides, isTesting || other.isTesting, testCache)

    /** Builder used by [withDependencies] and `TestStore`. */
    class Builder internal constructor(base: DependencyValues) {
        internal var values = base
        operator fun <T> set(key: DependencyKey<T>, value: T) {
            values = values.with(key, value)
        }
        operator fun <T> get(key: DependencyKey<T>): T = values[key]
    }

    fun update(block: Builder.() -> Unit): DependencyValues = Builder(this).apply(block).values

    companion object {
        private val liveCache = HashMap<DependencyKey<*>, Any?>()

        @Suppress("UNCHECKED_CAST")
        private fun <T> live(key: DependencyKey<T>): T = synchronized(liveCache) {
            if (liveCache.containsKey(key)) liveCache[key] as T else key.liveValue.also { liveCache[key] = it }
        }

        /** The process root: live values plus whatever the Application installed at startup. */
        @Volatile
        var root: DependencyValues = DependencyValues(emptyMap(), isTesting = false, HashMap())
            private set

        /** Installs process-wide overrides (only `Application.onCreate`, e.g. the app [android.content.Context]). */
        fun installRoot(block: Builder.() -> Unit) {
            root = root.update(block)
        }

        /** A test container: unresolved keys fall back to `testValue`. */
        fun testing(): DependencyValues = DependencyValues(emptyMap(), isTesting = true, HashMap())

        private val local = ThreadLocal<DependencyValues?>()

        /** The values in effect on this thread/coroutine. */
        val current: DependencyValues get() = local.get() ?: root

        /** Runs [block] synchronously with [values] installed (used by the store around `reduce`). */
        fun <R> withValues(values: DependencyValues, block: () -> R): R {
            val previous = local.get()
            local.set(values)
            try {
                return block()
            } finally {
                local.set(previous)
            }
        }

        internal fun element(values: DependencyValues): CoroutineContext = DependencyContext(values)
    }

    /** Propagates [DependencyValues.current] across coroutine suspension and dispatch. */
    private class DependencyContext(private val values: DependencyValues) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<DependencyValues?> {
        companion object Key : CoroutineContext.Key<DependencyContext>

        override fun updateThreadContext(context: CoroutineContext): DependencyValues? {
            val previous = local.get()
            local.set(values)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: DependencyValues?) {
            local.set(oldState)
        }
    }
}

/** Reads a client from the current [DependencyValues] (TCA's `@Dependency(\.x) var x`). */
fun <T> dependency(key: DependencyKey<T>): T = DependencyValues.current[key]

/**
 * Property-delegate form of `@Dependency`, resolved on every read so a reducer object
 * always sees the store's (or test's) values:
 * `private val haptics by Dependency(HapticsClient)`.
 * Hot loops resolve once into a local `val` instead (iOS architecture §2).
 */
@Suppress("FunctionName")
fun <T> Dependency(key: DependencyKey<T>): ReadOnlyProperty<Any?, T> =
    ReadOnlyProperty { _, _ -> DependencyValues.current[key] }

/** Runs [block] with overridden dependencies (TCA's `withDependencies`). */
fun <R> withDependencies(update: DependencyValues.Builder.() -> Unit, block: () -> R): R =
    DependencyValues.withValues(DependencyValues.current.update(update), block)

/** Suspending variant: the overrides follow the coroutine across threads. */
suspend fun <R> withDependenciesSuspending(
    update: DependencyValues.Builder.() -> Unit,
    block: suspend () -> R,
): R = withContext(DependencyValues.element(DependencyValues.current.update(update))) { block() }

/** The coroutine context element carrying [values]; effects launch with it. */
fun DependencyValues.asContextElement(): CoroutineContext = DependencyValues.element(this)
