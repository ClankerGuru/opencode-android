package ai.opencode.core.touch

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.AnimationTimeScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

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

        /** The holder's type, for the log: never its content. */
        val kind: String = holder.javaClass.simpleName.ifEmpty { "Object" }

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
 * Port of iOS `TouchRecoveryClient`: samples a live [TouchRecovery] four times a second in
 * animation time (slowed-down lab motion stretches the grace period with it, up to
 * [TouchRecovery.WALL_GRACE]) and real time, until cancelled. Tests get an inert client and drive
 * [TouchRecovery.sample] themselves. iOS's `expedite` hook is n/a: nothing on Android detects a
 * dead drag (see `docs/parity/devtools.md`).
 */
fun interface TouchRecoveryClient {
    suspend fun watch(recovery: TouchRecovery)

    companion object Key : DependencyKey<TouchRecoveryClient> {
        override val liveValue: TouchRecoveryClient = TouchRecoveryClient { recovery ->
            val clock = dependency(Clock)
            while (true) {
                val realSeconds = clock.now().inWholeNanoseconds / 1e9
                recovery.sample(AnimationTimeScale.shared.now(realSeconds), realSeconds)
                clock.sleep(SAMPLE_INTERVAL)
            }
        }
        override val testValue: TouchRecoveryClient = TouchRecoveryClient { awaitCancellation() }

        private val SAMPLE_INTERVAL = 250.milliseconds
    }
}

/**
 * A [TouchRecovery] watched while this composable is in the composition and the app is in the
 * foreground (iOS `TouchRecoverySampler`). Leaving or returning to the foreground starts every
 * grace period over (iOS resets on scene activation changes).
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(recovery, lifecycle) {
        val client = dependency(TouchRecoveryClient)
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
