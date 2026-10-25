package ai.opencode.core.dependencies

import android.content.Context
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The application [Context], installed once by `OpenCodeApplication.onCreate` via
 * [DependencyValues.installRoot]. Live clients that need Android services read it;
 * reducers never do.
 */
object AppContext : DependencyKey<Context> {
    override val liveValue: Context
        get() = error("AppContext is installed by OpenCodeApplication.onCreate before first use")
    override val testValue: Context
        get() = error("AppContext has no test value: override it, or keep Android out of the reducer")
}

/** Port of `@Dependency(\.continuousClock)`: monotonic time and sleeping. */
interface Clock {
    /** Monotonic time since an arbitrary origin. */
    fun now(): Duration
    suspend fun sleep(duration: Duration)

    companion object Key : DependencyKey<Clock> {
        override val liveValue: Clock = object : Clock {
            override fun now(): Duration = System.nanoTime().nanoseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        /** Tests must override with [TestClock] or [ImmediateClock]; the default never fires. */
        override val testValue: Clock get() = UnimplementedClock
    }
}

private object UnimplementedClock : Clock {
    override fun now(): Duration = Duration.ZERO
    override suspend fun sleep(duration: Duration) =
        throw AssertionError("Unimplemented: Clock.sleep. Override Clock in this test (TestClock/ImmediateClock).")
}

/** A clock whose sleeps return at once, advancing its time (swift-clocks' `ImmediateClock`). */
class ImmediateClock : Clock {
    @Volatile private var time = Duration.ZERO
    override fun now(): Duration = time
    override suspend fun sleep(duration: Duration) {
        time += duration
    }
}

/** A clock that only moves when the test calls [advance] (swift-clocks' `TestClock`). */
class TestClock : Clock {
    private class Sleeper(val deadline: Duration, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var time = Duration.ZERO
    private val sleepers = mutableListOf<Sleeper>()

    override fun now(): Duration = synchronized(lock) { time }

    override suspend fun sleep(duration: Duration) {
        if (duration <= Duration.ZERO) return
        suspendCancellableCoroutine { continuation ->
            val sleeper = synchronized(lock) { Sleeper(time + duration, continuation).also(sleepers::add) }
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(sleeper) } }
        }
    }

    /** Moves time forward, resuming every sleep whose deadline has passed, in deadline order. */
    fun advance(by: Duration = Duration.ZERO) {
        val due = synchronized(lock) {
            time += by
            val ready = sleepers.filter { it.deadline <= time }.sortedBy { it.deadline }
            sleepers.removeAll(ready)
            ready
        }
        due.forEach { it.continuation.resume(Unit) }
    }

    /** Runs every pending sleep. */
    fun run() {
        val last = synchronized(lock) { sleepers.maxOfOrNull { it.deadline } } ?: return
        advance(last - now())
    }
}

/** Port of `@Dependency(\.date)`: wall-clock now. */
fun interface DateClient {
    fun now(): Instant

    companion object Key : DependencyKey<DateClient> {
        override val liveValue: DateClient = DateClient { Instant.now() }
        override val testValue: DateClient = DateClient {
            throw AssertionError("Unimplemented: DateClient. Override with DateClient { Instant.ofEpochSecond(...) }.")
        }
        fun constant(instant: Instant) = DateClient { instant }
    }
}

/** Port of `@Dependency(\.uuid)`. [incrementing] mirrors `UUIDGenerator.incrementing`. */
fun interface UuidClient {
    fun next(): UUID

    companion object Key : DependencyKey<UuidClient> {
        override val liveValue: UuidClient = UuidClient { UUID.randomUUID() }
        override val testValue: UuidClient = UuidClient {
            throw AssertionError("Unimplemented: UuidClient. Override with UuidClient.incrementing().")
        }

        /** 00000000-0000-0000-0000-000000000000, …-000000000001, … */
        fun incrementing(): UuidClient {
            var next = 0L
            return UuidClient { synchronized(this) { UUID(0, next++) } }
        }
    }
}
