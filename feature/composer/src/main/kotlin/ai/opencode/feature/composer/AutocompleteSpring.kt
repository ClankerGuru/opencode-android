package ai.opencode.feature.composer

import ai.opencode.core.motion.SpringSolver
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * Second-order spring ported from the Round 2 web study (`model.js`), iOS
 * `AutocompleteSpring`. Keeps position and velocity across retargets so interrupted opens,
 * filter updates and closes never jump. Stepped in closed form with
 * [SpringSolver.evaluate]; `lead` divides the duration so one axis can lead another.
 *
 * Mutable like the Swift value type; [copy] gives an independent value.
 */
data class AutocompleteSpring(
    var x: Double = 0.0,
    var v: Double = 0.0,
    var t: Double = x,
    var duration: Double = 0.3,
    var bounce: Double = 0.0,
    var lead: Double = 1.0,
) {
    constructor(initial: Double) : this(x = initial, t = initial)

    fun to(target: Double, duration: Double, bounce: Double, lead: Double = 1.0) {
        t = target
        this.duration = duration
        this.bounce = bounce
        this.lead = lead
    }

    fun snap(value: Double) {
        x = value
        t = value
        v = 0.0
    }

    fun isStill(eps: Double = 0.002): Boolean = abs(x - t) < eps && abs(v) < eps * 8

    fun step(dt: Double) {
        val d = max(0.001, duration / max(lead, 0.1))
        if (d <= 0.004) {
            x = t
            v = 0.0
            return
        }
        val omega = 2 * PI / d
        val zeta = max(0.01, if (bounce >= 0) 1 - bounce else 1 / (1 + bounce))
        val (nx, nv) = SpringSolver.evaluate(from = x, to = t, initialVelocity = v, omega = omega, zeta = zeta, elapsed = dt)
        x = nx
        v = nv
    }
}
