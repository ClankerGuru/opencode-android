package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.feature.drawer.list.DrawerPinDrag.ItemKey
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * iOS ff935bef (`DrawerListController.apply`'s `movesPins`): Pin, Unpin and Move Up/Down outside a
 * drag (the menu, TalkBack) move the row to its new place while the rows between make room, all
 * in one move on `pinMoveSpring`; under Reduce Motion the rows that moved cross-fade in over
 * `pinMoveFade`. UIKit animates the snapshot; here [begin] notes where each visible row shows
 * before the store's change lays the list out anew, and [apply] (from the list's placement, so
 * the first frame already draws them where they were) starts each one there and springs its
 * offset to zero, through the pin drag's translations. A drag lands its own moves.
 */
internal class DrawerPinMove(private val pinDrag: DrawerPinDrag) {
    private var from: Map<ItemKey, Float>? = null
    private var reducedMotion = false
    private var job: Job? = null
    private val springs = HashMap<ItemKey, SpringSolver>()
    private val fading = HashSet<ItemKey>()
    private var fadeElapsed = 0.0
    private var generation = 0

    /** Each moving row's opacity under Reduce Motion (absent: 1). */
    val alphas = mutableStateMapOf<ItemKey, Float>()

    val isRunning: Boolean get() = job?.isActive == true

    /** The store is about to move pinned rows outside a drag: remember where the visible rows show. */
    fun begin(reducedMotion: Boolean) {
        if (pinDrag.activeSessionID != null || pinDrag.isDisplayLinkRunning) return
        val host = pinDrag.host ?: return
        this.reducedMotion = reducedMotion
        // Called from composition: the list's layout isn't something it should recompose on.
        from = Snapshot.withoutReadObservation {
            host.visibleItems().associate { it.key to it.minY + pinDrag.translation(it.key) }
        }
        // A list that never lays out again (it left the screen) doesn't keep a stale start.
        val begun = ++generation
        host.scope.launch {
            repeat(2) { withFrameNanos { } }
            if (generation == begun) from = null
        }
    }

    /** The list laid the move out: start each row where it showed and send it home. */
    fun apply() {
        val start = from ?: return
        from = null
        val host = pinDrag.host ?: return
        if (pinDrag.activeSessionID != null) return
        val spring = DrawerTunables.pinMoveSpring.spring
        for (item in host.visibleItems()) {
            val was = start[item.key]
            val offset = was?.let { it - item.minY } ?: 0f
            if (reducedMotion) {
                // Rows that moved or arrived fade in where they now are.
                if (was == null || abs(offset) > 0.5f) fading += item.key
                continue
            }
            if (abs(offset) <= 0.02f) continue
            springs[item.key] = SpringSolver.of(
                duration = max(spring.duration, 0.05),
                bounce = spring.bounce,
                value = offset.toDouble(),
                target = 0.0,
                positionEpsilon = 0.08,
                velocityEpsilon = 0.5,
            )
            pinDrag.setTranslation(item.key, offset)
        }
        if (reducedMotion) {
            fadeElapsed = 0.0
            if (DrawerTunables.pinMoveFade.value <= 0.0) fading.clear()
            fading.forEach { alphas[it] = 0f }
        }
        if (springs.isEmpty() && fading.isEmpty()) return
        job?.cancel()
        job = host.scope.launch {
            motionFrameLoop(frameDurationSeconds = host.frameDurationSeconds) { tick -> step(tick.dt) }
        }
    }

    /** One frame: offsets spring toward zero (they ride the rows, so a scroll mid-move is no matter). */
    internal fun step(dt: Double): MotionFrameAction {
        var settled = true
        for ((key, spring) in springs) {
            spring.step(dt)
            pinDrag.setTranslation(key, spring.value.toFloat())
            if (!spring.isSettled) settled = false
        }
        if (fading.isNotEmpty()) {
            fadeElapsed += dt
            val duration = DrawerTunables.pinMoveFade.value
            val progress = if (duration <= 0.0) 1f else (fadeElapsed / duration).toFloat().coerceIn(0f, 1f)
            val alpha = Motion.EaseInOut.transform(progress)
            fading.forEach { alphas[it] = alpha }
            if (progress < 1f) settled = false
        }
        if (!settled) return MotionFrameAction.Continue
        job = null
        clear()
        return MotionFrameAction.Stop
    }

    /** Leaves no offset or fade behind (a drag begins, or the list goes). */
    fun cancel() {
        job?.cancel()
        job = null
        from = null
        clear()
    }

    private fun clear() {
        springs.keys.forEach { pinDrag.setTranslation(it, 0f) }
        springs.clear()
        fading.clear()
        alphas.clear()
    }
}
