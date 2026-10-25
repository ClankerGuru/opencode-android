package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.TranscriptTunables
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A row's height easing from where it was to its measured height (iOS `RowGrowth`): no faster
 * at the start or the end than [maximumFrameChange] allows, so what moves with it eases in. A
 * new target bends the motion from its current height and speed. Heights in dp.
 */
class RowGrowth(
    from: Float,
    target: Float,
    time: Double,
    /** The row's width when it started: at another width it lands. */
    val width: Float,
    springDuration: Double? = null,
) {
    var height: Float = from
        private set
    var target: Float = target
        private set
    private var velocity = 0f
    private var time: Double = time
    private var start = 0.0
    private var from = 0f
    private var startVelocity = 0f
    private var duration = 0.0
    private val capturedMaxFrameChange = maximumFrameChange
    private val capturedMinDuration = minimumDuration
    private val capturedMaxDuration = maximumDuration
    val capturedSpeed: Double = BottomSpring.ARRIVAL_SPEED
    /** A row already drawn whose content changed (not one arriving). */
    var changedRow = false
    private val springOmega: Double? = springDuration?.let { 2 * PI / max(0.05, it) }
    private var omega = 0.0

    init {
        retarget(target)
    }

    private fun spring(t: Double): Pair<Float, Float> {
        val a = (from - target).toDouble()
        val b = startVelocity + omega * a
        val decay = exp(-omega * t)
        return (target + ((a + b * t) * decay).toFloat()) to ((b - omega * (a + b * t)) * decay).toFloat()
    }

    val isFinished: Boolean get() = time >= start + duration

    fun continues(atWidth: Float): Boolean = abs(atWidth - width) < 0.5f

    /** Absorbs a removed neighbour's height while keeping the current velocity. */
    fun absorb(removed: Float, target: Float) {
        height += removed
        retarget(target)
    }

    /** Starts a new curve from the current height and speed. */
    fun retarget(target: Float) {
        this.target = target
        from = height
        startVelocity = velocity
        start = time
        val distance = target - height
        if (springOmega != null) {
            if (startVelocity * distance <= 0) startVelocity = 0f
            omega = if (abs(distance) > 0.5f) min(springOmega, E * capturedSpeed / abs(distance)) else springOmega
            duration = 14 / omega
            return
        }
        var duration = (1.0 / 60) * sqrt(6 * abs(distance) / max(0.1f, capturedMaxFrameChange)).toDouble()
        duration = min(max(duration, capturedMinDuration), capturedMaxDuration)
        duration = max(duration, 1.5 * abs(distance) / max(1.0, capturedSpeed))
        if (startVelocity * distance > 0) duration = min(duration, (3 * distance / startVelocity).toDouble())
        this.duration = max(duration, 1.0 / 120)
        if (startVelocity * distance <= 0) {
            startVelocity = 0f
        } else if (abs(startVelocity) * this.duration > 3 * abs(distance)) {
            startVelocity = (3 * distance / this.duration).toFloat()
        }
    }

    fun advance(frame: Double, upTo: Double) {
        time = max(time, min(upTo, time + frame))
        if (springOmega != null) {
            val (h, v) = spring(time - start)
            height = max(0f, h)
            velocity = v
            val passed = (height - target) * (from - target) < 0
            if (passed || (abs(height - target) < 0.25f && (abs(velocity) < 15 || time >= start + duration))) {
                height = target
                velocity = 0f
                duration = time - start
            }
            return
        }
        val s = min(max((time - start) / duration, 0.0), 1.0).toFloat()
        val span = target - from
        val lead = startVelocity * duration.toFloat()
        height = from + lead * (s - 2 * s * s + s * s * s) + span * (3 * s * s - 2 * s * s * s)
        velocity = (lead * (1 - 4 * s + 3 * s * s) + span * 6 * (s - s * s)) / duration.toFloat()
        if (s >= 1) {
            height = target
            velocity = 0f
        }
        height = max(0f, height)
    }

    companion object {
        val maximumFrameChange: Float get() = TranscriptTunables.growthMaxFrameChange
        val minimumDuration: Double get() = TranscriptTunables.growthMinDuration
        val maximumDuration: Double get() = max(minimumDuration, TranscriptTunables.growthMaxDuration)
    }
}

/**
 * How far the view rides rows growing at the end in one frame (iOS `GrowthRide`): the view
 * follows the growth so rows below stay put on screen, never faster than arrivals glide.
 */
class GrowthRide {
    /** What the growth has put between the reader and the end, still to ride. */
    var deficit = 0f
        private set

    fun offset(from: Float, endBefore: Float, end: Float, step: Float): Float {
        val riding = from >= endBefore - 0.5f || deficit > 0.5f
        val target = if (riding) max(from, end) else from
        val next = min(target, from + step)
        deficit = max(0f, target - next)
        return next
    }

    fun reset() {
        deficit = 0f
    }
}
