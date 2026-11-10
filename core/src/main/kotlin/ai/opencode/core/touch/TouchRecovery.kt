package ai.opencode.core.touch

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.AnimationTimeScale
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Port of iOS `TouchRecovery` (`OpenCode/Core/TouchRecovery.swift`): the last-resort net for a
 * session that stops taking touches. Each owner of something that can sit over the session audits
 * only the interaction it controls. A fault held for more than one second of animation time (and
 * never more than four real seconds, however slow lab motion runs) is released by that owner and
 * logged content-free as `transcript_touch_recovered owner=… reason=…`.
 *
 * [inspect] returns `null` when healthy or legitimately blocking (an open menu, a drag in flight);
 * `null` resets the grace period. Never infer dismissal from a transparent surface alone. A drag
 * that didn't scroll can [expedite] a fault that was already there.
 *
 * Android: Compose has no view hierarchy to disable, so only owners whose own state can leave a
 * surface taking touches after it's done use a watch (the Plus menu, the media viewer, the page
 * stack, the floating call, the transcript's mark hand-off). See `docs/parity/core.md` for the
 * owners that don't.
 */
class TouchRecovery(
    val owner: Owner,
    private val inspect: () -> Fault?,
    private val release: () -> Unit,
) {
    enum class Owner(val rawValue: String) {
        Transcript("transcript"),
        Drawer("drawer"),
        CatchUp("catch_up"),
        CallOrb("call_orb"),
        Sheet("sheet"),
        KeyboardComposer("keyboard_composer"),
        Zoom("zoom"),
        Browser("browser"),
        PlusMenu("plus_menu"),
    }

    enum class Reason(val rawValue: String) {
        Disabled("disabled"),
        Closed("closed"),
        Detached("detached"),
        Invisible("invisible"),
        Migrated("migrated"),
        Transition("transition"),
        Stranded("stranded"),
    }

    /**
     * What holds the touches, and why. Equal when the reason, the holder (by identity), the grace
     * and the start match. [startedAt] is when the fault really began (real seconds, the
     * live `Clock` domain), if its holder knows: the grace then runs from there, not from
     * the first sample that saw it.
     */
    class Fault(
        val reason: Reason,
        holder: Any,
        val grace: Fault.Grace = Fault.Grace.Animation,
        val startedAt: Double? = null,
    ) {
        /**
         * How long a fault may stand. [Animation] is the default grace; [Wall] a hard deadline in
         * real seconds, which a dead drag can't shorten (a hand-off still inside its longest run).
         */
        sealed interface Grace {
            data object Animation : Grace
            data class Wall(val seconds: Double) : Grace
        }

        private val identity: Any = holder
        private val holderType: Class<*> = holder.javaClass

        /**
         * The holder's type, for the log: never its content. Named only when logged, so a sample
         * that finds a fault allocates no strings.
         */
        val kind: String get() = holderType.simpleName.ifEmpty { "Object" }

        override fun equals(other: Any?): Boolean {
            if (other !is Fault) return false
            return reason == other.reason && identity === other.identity &&
                grace == other.grace && startedAt == other.startedAt
        }

        override fun hashCode(): Int = 31 * reason.hashCode() + System.identityHashCode(identity)
    }

    private var fault: Fault? = null
    private var since: Double? = null
    private var sinceWall: Double? = null
    private var streak = 0
    private var lastRelease: Double? = null
    private var resumeAt = Double.NEGATIVE_INFINITY
    private val logged = mutableSetOf<String>()

    fun reset() {
        fault = null
        since = null
        sinceWall = null
    }

    /**
     * A fault is standing, or a release is still backing off: the sampler keeps sampling until
     * both are over ([TouchRecoverySampler]).
     */
    fun isPending(wall: Double): Boolean = fault != null || wall < resumeAt

    /**
     * Also the deterministic test seam: no sleeping or frame clock. [now] is animation time,
     * [wall] real time (the same clock when omitted), both in seconds.
     */
    fun sample(now: Double, wall: Double = now): Boolean {
        val next = observe(now, wall) ?: return false
        val start = since ?: return false
        val startWall = sinceWall ?: return false
        val held = now - start
        val heldWall = wall - startWall
        val grace = next.grace
        if (grace is Fault.Grace.Wall) {
            if (heldWall <= grace.seconds) return false
        } else {
            if (held <= GRACE && heldWall <= WALL_GRACE) return false
        }
        return land(next, heldWall, wall, trigger = "grace")
    }

    /**
     * A drag that began at [touchBegan] (real time) found nothing scrolled: a fault already
     * standing when the finger came down is released without waiting out the rest of its
     * animation grace. Hard deadlines still hold.
     */
    fun expedite(touchBegan: Double, now: Double, wall: Double): Boolean {
        val next = observe(now, wall) ?: return false
        val startWall = sinceWall ?: return false
        if (startWall > touchBegan) return false
        val heldWall = wall - startWall
        val grace = next.grace
        if (grace is Fault.Grace.Wall && heldWall <= grace.seconds) return false
        return land(next, heldWall, wall, trigger = "dead_drag")
    }

    /** The standing fault, its grace restarted when it changed. */
    private fun observe(now: Double, wall: Double): Fault? {
        val previousRelease = lastRelease
        if (previousRelease != null && wall < previousRelease) forgive()
        if (wall < resumeAt) {
            reset()
            return null
        }
        val next = inspect()
        if (next == null) {
            reset()
            return null
        }
        val start = since
        val startWall = sinceWall
        if (next != fault || start == null || startWall == null || now < start || wall < startWall) {
            fault = next
            since = now
            sinceWall = min(wall, next.startedAt ?: wall)
            return null
        }
        return next
    }

    private fun land(next: Fault, heldWall: Double, wall: Double, trigger: String): Boolean {
        release()
        val releasedBefore = lastRelease
        if (releasedBefore != null && wall - releasedBefore > FORGIVE_AFTER) forgive()
        streak += 1
        lastRelease = wall
        resumeAt = wall + backoff(streak)
        // One line per fault per streak, so a blocker that keeps coming back can't flood the log.
        val key = "${next.reason.rawValue} ${next.kind}"
        if (logged.add(key)) {
            val heldText = String.format(Locale.US, "%.2f", heldWall)
            AppLog.app.error(
                "transcript_touch_recovered owner=${owner.rawValue} reason=${next.reason.rawValue} " +
                    "holder=${next.kind} held=$heldText streak=$streak trigger=$trigger",
            )
        }
        reset()
        return true
    }

    private fun forgive() {
        streak = 0
        lastRelease = null
        resumeAt = Double.NEGATIVE_INFINITY
        logged.clear()
    }

    companion object {
        const val GRACE = 1.0

        /** Slow motion stretches the grace, but never past this in real time. */
        const val WALL_GRACE = 4.0

        /** Five quiet minutes forgive a streak of repeat releases (real time). */
        const val FORGIVE_AFTER = 300.0

        /**
         * A release that has to repeat (the fault came straight back) waits longer each time, in
         * real time, so slow motion can't park the net for minutes.
         */
        fun backoff(streak: Int): Double = min(60.0, 2 * 2.0.pow(max(0, streak - 1).toDouble()))
    }
}

/**
 * Port of iOS `TouchRecoveryClient`: every live [TouchRecovery] is sampled by the process-wide
 * [TouchRecoverySampler], only in armed windows (0 Hz at rest). [watch] adds a recovery while the
 * call is suspended; [touched] (a finger came down: `MainActivity.dispatchTouchEvent`) and [arm]
 * (a transition) open a sampling window; [expedite] passes on a dead drag, though nothing on
 * Android detects one yet (see `docs/parity/devtools.md`). Tests get an inert client and drive
 * [TouchRecovery.sample] or a [TouchRecoverySampler] themselves.
 */
fun interface TouchRecoveryClient {
    suspend fun watch(recovery: TouchRecovery)

    fun expedite(touchBegan: Double) {}

    fun touched() {}

    fun arm() {}

    companion object Key : DependencyKey<TouchRecoveryClient> {
        override val liveValue: TouchRecoveryClient = object : TouchRecoveryClient {
            override suspend fun watch(recovery: TouchRecovery) {
                // The sampler is confined to the main thread, as iOS's is to the main actor.
                withContext(Dispatchers.Main.immediate) {
                    TouchRecoverySampler.shared.add(recovery)
                    try {
                        awaitCancellation()
                    } finally {
                        TouchRecoverySampler.shared.remove(recovery)
                    }
                }
            }

            override fun expedite(touchBegan: Double) = TouchRecoverySampler.shared.expedite(touchBegan)

            override fun touched() = TouchRecoverySampler.shared.touched()

            override fun arm() = TouchRecoverySampler.shared.arm()
        }
        override val testValue: TouchRecoveryClient = TouchRecoveryClient { awaitCancellation() }
    }
}

/**
 * Port of iOS `TouchRecoverySampler`: samples every live [TouchRecovery] four times a second in
 * animation time (slowed-down lab motion stretches the grace period with it, up to
 * [TouchRecovery.WALL_GRACE]) and real time, but only in windows: [arm] keeps it sampling for
 * [WINDOW] real seconds, and it stops once that has passed with no watch holding a fault or
 * backing off (0 Hz at rest). A finger coming down arms it ([touched], which also samples at once
 * when it was idle, so a fault standing when the finger came down counts as standing before it),
 * as do a new watch, the app returning to the foreground (each watch is added again) and an
 * owner's composition changing ([rememberTouchRecovery]). Main thread only.
 *
 * Android: iOS's window, keyboard and transcript-page notifications have no single equivalent;
 * an owner recomposing is the transition that can leave its fault behind.
 */
class TouchRecoverySampler(
    private val clock: () -> Time = ::liveClock,
    private val schedule: (tick: () -> Unit) -> (() -> Unit) = ::mainLooperTimer,
) {
    /** Animation time and real time, in seconds. */
    data class Time(val now: Double, val wall: Double)

    private val watches = LinkedHashSet<TouchRecovery>()
    private var stop: (() -> Unit)? = null
    private var armedUntil = Double.NEGATIVE_INFINITY

    /** Ticks since launch, for tests and the idle proof. */
    var ticks = 0
        private set

    val isRunning: Boolean get() = stop != null

    fun add(watch: TouchRecovery) {
        watches.add(watch)
        arm()
    }

    fun remove(watch: TouchRecovery) {
        watches.remove(watch)
    }

    /** Samples for the next [WINDOW] real seconds. */
    fun arm() {
        if (watches.isEmpty()) return
        armedUntil = max(armedUntil, clock().wall + WINDOW)
        if (stop != null) return
        stop = schedule { tick() }
    }

    /** A finger came down: sample now if idle, then arm. */
    fun touched() {
        if (stop == null) sample()
        arm()
    }

    fun tick() {
        ticks += 1
        val wall = sample()
        if (wall == null) {
            halt()
            return
        }
        if (wall < armedUntil) return
        for (watch in watches) {
            if (watch.isPending(wall)) return
        }
        halt()
    }

    fun expedite(touchBegan: Double) {
        val time = clock()
        for (watch in watches.toList()) watch.expedite(touchBegan, time.now, time.wall)
        arm()
    }

    /** Returns the real time sampled at, or null with no watch left. */
    private fun sample(): Double? {
        if (watches.isEmpty()) return null
        val time = clock()
        // A release can remove its own watch from the composition: sample a copy.
        for (watch in watches.toList()) watch.sample(time.now, time.wall)
        return time.wall
    }

    private fun halt() {
        stop?.invoke()
        stop = null
        armedUntil = Double.NEGATIVE_INFINITY
    }

    companion object {
        const val INTERVAL = 0.25

        /** Long enough for any animation-graced fault armed by a transition to be released, plus a second. */
        const val WINDOW = TouchRecovery.WALL_GRACE + 1

        val shared: TouchRecoverySampler by lazy { TouchRecoverySampler() }

        private val liveClockSource by lazy { dependency(Clock) }

        fun liveClock(): Time {
            val realSeconds = liveClockSource.now().inWholeNanoseconds / 1e9
            return Time(now = AnimationTimeScale.shared.now(realSeconds), wall = realSeconds)
        }

        /** Starts a repeating tick on the main looper and returns what stops it. */
        fun mainLooperTimer(tick: () -> Unit): () -> Unit {
            val handler = Handler(Looper.getMainLooper())
            val intervalMillis = (INTERVAL * 1_000).toLong()
            var stopped = false
            val repeating = object : Runnable {
                override fun run() {
                    tick()
                    if (!stopped) handler.postDelayed(this, intervalMillis)
                }
            }
            handler.postDelayed(repeating, intervalMillis)
            return {
                stopped = true
                handler.removeCallbacks(repeating)
            }
        }
    }
}

/**
 * A [TouchRecovery] watched while this composable is in the composition and the app is in the
 * foreground (iOS `TouchRecoverySampler`). Leaving or returning to the foreground starts every
 * grace period over (iOS resets on scene activation changes), and returning arms the sampler.
 * Every recomposition of the owner arms it too: its state changed, so a fault may be on its way.
 */
@Composable
fun rememberTouchRecovery(
    owner: TouchRecovery.Owner,
    inspect: () -> TouchRecovery.Fault?,
    release: () -> Unit,
): TouchRecovery {
    val currentInspect = rememberUpdatedState(inspect)
    val currentRelease = rememberUpdatedState(release)
    val recovery = remember(owner) {
        TouchRecovery(owner, inspect = { currentInspect.value() }, release = { currentRelease.value() })
    }
    val client = remember { dependency(TouchRecoveryClient) }
    SideEffect { client.arm() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(recovery, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            recovery.reset()
            try {
                client.watch(recovery)
            } finally {
                recovery.reset()
            }
        }
    }
    return recovery
}
