package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.TranscriptTunables
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** Jump to latest: one glide of a fixed length, however far the end is (iOS `JumpGlide`). */
class JumpGlide(
    /** Signed: where the glide set out for, from where it started. */
    val distance: Float,
    val curve: Curve,
    val duration: Double = DURATION,
) {
    /** How far through [duration] the glide is. */
    var progress: Double = 0.0
        private set
    var elapsed: Double = 0.0
        private set

    /** The share of [remaining] to cover this frame, or null when the glide is done. */
    fun step(dt: Double, remaining: Float, pixel: Float, expired: Boolean): Float? {
        elapsed += dt
        if (abs(remaining) <= pixel || remaining * distance < 0 || expired) return null
        val planned = abs(distance) * (1 - curve.value(progress))
        if (abs(remaining) > planned + 2) {
            val fraction = min(1.0, (abs(remaining) / abs(distance)).toDouble())
            progress = min(progress, max(curve.progress(remaining = fraction), min(progress, curve.fastest)))
        }
        progress = max(progress, (elapsed - dt - MAXIMUM_EXTENSION) / duration)
        val next = min(1.0, progress + dt / duration)
        val done = curve.value(progress)
        progress = next
        val share = if (next >= 1 || done >= 1) 1.0 else (curve.value(next) - done) / (1 - done)
        return if (share >= 1) null else share.toFloat()
    }

    enum class Curve {
        /** The follower's critically damped spring, fitted to end at 1. */
        FromRest,
        /** Out of a cut: fastest at once, easing onto the end. */
        FastStart;

        fun value(t: Double): Double {
            if (t >= 1) return 1.0
            return when (this) {
                FromRest -> {
                    val k = STIFFNESS
                    (1 - (1 + k * t) * exp(-k * t)) / (1 - (1 + k) * exp(-k))
                }
                FastStart -> 1 - (1 - t).pow(4)
            }
        }

        /** Where the curve is fastest: rewinding before it would brake. */
        val fastest: Double get() = if (this == FromRest) 1 / STIFFNESS else 0.0

        /** The progress at which [remaining] of the way is left. */
        fun progress(remaining: Double): Double {
            var low = 0.0
            var high = 1.0
            repeat(24) {
                val mid = (low + high) / 2
                if (1 - value(mid) > remaining) low = mid else high = mid
            }
            return (low + high) / 2
        }

        companion object {
            /** The spring's ω × duration. */
            const val STIFFNESS = 7.0
        }
    }

    companion object {
        val DURATION: Double get() = TranscriptTunables.jumpDuration
        /** Ends within this many screens are glided to all the way from rest. */
        val REACH: Float get() = TranscriptTunables.jumpReach
        /** Farther ends are cut to this many screens above, then glided to. */
        val LEAD: Float get() = TranscriptTunables.jumpLead
        /** How much longer an end that grows on the way may make the glide. */
        const val MAXIMUM_EXTENSION = 0.2
    }
}

/** The slide that brings what arrived while the reader was away into view (iOS `NewMessagesReveal`). */
object NewMessagesReveal {
    /** A few lines take about 0.3 s, a screen about 0.55 s, and nothing more than 0.62 s. */
    fun duration(distance: Float, screen: Float): Double {
        val minD = TranscriptTunables.newMinDuration
        val maxD = max(minD, TranscriptTunables.newMaxDuration)
        val share = min(1.0, max(0.0, (distance / max(screen, 1f)).toDouble() / 1.25))
        return minD + (maxD - minD) * share
    }
}

/** What the follower is moving for (iOS `TranscriptListViewController.SpringKind`). */
enum class SpringKind(val rawValue: String) {
    Arrival("glide"),
    Viewport("viewport"),
    /** The reader settles back down onto an end that moved up. */
    Settle("settle"),
    Keyboard("keyboard"),
    Jump("jump"),
}

/**
 * A critically damped follower toward the live bottom, retargeted every frame (iOS
 * `BottomSpring`). Works in "offset" space: [step] takes the current scroll offset and the
 * target (the end) and returns where to move.
 */
class BottomSpring(var kind: SpringKind, current: Float, var target: Float, now: Double) {
    var omega: Double = if (kind == SpringKind.Arrival) ARRIVAL_OMEGA else JUMP_OMEGA
    val speed: Double = ARRIVAL_SPEED
    var velocity: Double = 0.0
    var deadline: Double = deadline(current, target, kind, now, omega, speed)

    sealed interface Step {
        data class Move(val y: Float) : Step
        data object Settled : Step
        data class ShrunkEnd(val needsSettle: Boolean) : Step
    }

    /** Exact critically damped step from the current offset, so any other offset change is carried, not undone. */
    fun step(dt: Double, current: Float, target: Float, pixel: Double, now: Double): Step {
        if (abs(target - this.target) > 0.01f) {
            this.target = target
            deadline = deadline(current, target, kind, now, omega, speed)
        }
        val x0 = (current - target).toDouble()
        // Arrivals only ever move content forward.
        if (kind == SpringKind.Arrival && x0 >= 0) return Step.ShrunkEnd(x0 > 0.5)
        val v0 = velocity
        val decay = exp(-omega * dt)
        var x = (x0 + (v0 + omega * x0) * dt) * decay
        var v = (v0 - omega * (v0 + omega * x0) * dt) * decay
        if ((kind == SpringKind.Arrival || kind == SpringKind.Viewport || kind == SpringKind.Settle) && dt > 0) {
            val maxStep = min(speed * dt, abs(v0) * dt + MAXIMUM_FRAME_CHANGE * dt)
            if (abs(x - x0) > maxStep) {
                x = x0 + (x - x0).sign * maxStep
                v = (x - x0) / dt
            }
        }
        // Never overshoot.
        if ((x0 <= 0 && x > 0) || (x0 >= 0 && x < 0)) {
            x = 0.0
            v = 0.0
        }
        // Move at least a pixel, and land once within one.
        val minStep = pixel * min(1.0, ai.opencode.core.tuning.AnimationTimeScale.shared.scale)
        if (abs(x - x0) < minStep && dt > 0) {
            if (abs(x0) <= pixel) {
                x = 0.0
                v = 0.0
            } else {
                x = x0 + if (x0 < 0) minStep else -minStep
                v = (x - x0) / dt
            }
        }
        velocity = v
        if ((abs(x) <= pixel && abs(v) < 20) || now > deadline) return Step.Settled
        return Step.Move(target + x.toFloat())
    }

    companion object {
        /** The keyboard and pills above the composer: about 0.4 s. */
        val JUMP_OMEGA: Double get() = TranscriptTunables.viewportOmega
        /** Rows arriving at the bottom: about 0.35 s. */
        val ARRIVAL_OMEGA: Double get() = TranscriptTunables.arrivalOmega
        /** Arrivals move at most 18 pt per 60 Hz frame. */
        val ARRIVAL_SPEED: Double get() = TranscriptTunables.arrivalSpeedPointsPerSecond
        const val MAXIMUM_FRAME_CHANGE = 1.8 * 60.0
        /** Arrivals within this many screens glide; farther, they land at once. */
        val ARRIVAL_REACH: Float get() = TranscriptTunables.arrivalReach
        /** Lands on the bottom if the target stops moving and the spring has not. */
        const val TIMEOUT = 1.2
        val JUMP_TIMEOUT: Double get() = max(TIMEOUT, JumpGlide.DURATION + JumpGlide.MAXIMUM_EXTENSION + 0.5)
        const val OPENING_SETTLE = 0.75

        fun deadline(from: Float, to: Float, kind: SpringKind, now: Double, omega: Double = ARRIVAL_OMEGA, speed: Double = ARRIVAL_SPEED): Double {
            val base = max(max(TIMEOUT, 16.0 / max(1.0, omega)), abs(to - from) / max(1.0, speed) + 0.8)
            return if (kind == SpringKind.Jump) now + max(base, JUMP_TIMEOUT) else now + base
        }
    }
}

/** Space held under the finger drains on the keyboard's spring (iOS `HeldSpaceDrain`). */
class HeldSpaceDrain {
    private var velocity = 0.0

    fun step(held: Float, dt: Double, pixel: Float): Float {
        val omega = BottomSpring.JUMP_OMEGA
        val x0 = held.toDouble()
        val decay = exp(-omega * dt)
        val x = (x0 + (velocity + omega * x0) * dt) * decay
        velocity = (velocity - omega * (velocity + omega * x0) * dt) * decay
        if (x <= pixel) {
            velocity = 0.0
            return 0f
        }
        return x.toFloat()
    }
}
