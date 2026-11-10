package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.retarget

/**
 * The New divider's own motion while the reader sees it (iOS `NewDividerMotion`, in
 * `TranscriptListViewController+DividerMotion.swift`): leaving, it fades and its height closes on
 * a spring, with the rows around it riding along; entering (or turning back) is the same motion
 * in reverse, from wherever the springs are.
 */
class NewDividerMotion(direction: Direction, height: Double, alpha: Double) {
    enum class Direction { Leaving, Entering }

    var direction: Direction = direction
        private set

    /** Its height, as a share of its measured one. */
    val height = SpringSolver(
        Motion.layout,
        direction.phase,
        value = height,
        target = direction.target,
        positionEpsilon = 0.002,
        velocityEpsilon = 0.02,
    )
    val alpha = SpringSolver(
        Motion.swap,
        direction.phase,
        value = alpha,
        target = direction.target,
        positionEpsilon = 0.005,
        velocityEpsilon = 0.05,
    )

    /**
     * Read at rest at the end: the mark it answers once closed. Until then the reader taking the
     * list turns it back.
     */
    var reads: Int? = null

    /** Its mark has gone: the row stays only for this motion. */
    var held = false

    /** Heads the other way, keeping both springs' velocity. */
    fun turn(direction: Direction) {
        if (direction == this.direction) return
        this.direction = direction
        height.retarget(direction.target, Motion.layout, direction.phase)
        alpha.retarget(direction.target, Motion.swap, direction.phase)
    }

    val shownHeight: Double get() = height.value.coerceIn(0.0, 1.0)
    val shownAlpha: Double get() = alpha.value.coerceIn(0.0, 1.0)
    val isSettled: Boolean get() = height.isSettled && alpha.isSettled

    private val Direction.target: Double get() = if (this == Direction.Leaving) 0.0 else 1.0

    /**
     * Entering opens on `Motion.layout` and fades in on `Motion.swap`; leaving closes on their
     * exits (`Motion.collapse`, the quicker fade).
     */
    private val Direction.phase: MotionPhase get() = if (this == Direction.Entering) MotionPhase.Enter else MotionPhase.Exit
}
