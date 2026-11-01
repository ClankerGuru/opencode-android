package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import ai.opencode.core.tuning.AnimationTimeScale
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// Port of iOS `Composer/ModelPicker/ModelPickerAnchors.swift` (split out of ModelPickerOverlays in 17a991b1):
// the Fused flight, stack and menu state, `ModelPickerAnchors` and `FusedPickerMotionTrace`.

// MARK: - Anchors, Flight, Stack & Menu State

/** Flying label state between the composer chip and the picked/current row. */
data class FusedLabelFlight(
    val id: Int,
    val direction: Direction,
    val modelID: String,
    val modelShortName: String,
    val levelLabel: String?,
    /** Root coordinates (px). */
    val fromName: Rect,
    val fromLevel: Rect?,
) {
    enum class Direction { Open, Close }
}

/** Active vertical level stack (tapped or dragged on a row's level, or dragged vertically on the composer chip). Positions are root px. */
data class FusedLevelStackState(
    val kind: SourceKind,
    val model: OpenCodeModel,
    val scale: ModelLevelScale,
    val startIndex: Int,
    val currentIndex: Int,
    val rawPosition: Double,
    val anchorX: Float,
    val anchorY: Float,
    val rightEdgeX: Float,
    val tapMode: Boolean,
    val isClosing: Boolean = false,
) {
    enum class SourceKind { Row, Chip }
}

/** Active long-press menu on a model row (held [FusedModelPickerTunables.holdToFavorite] s). */
data class FusedRowMenuState(
    val model: OpenCodeModel,
    val scale: ModelLevelScale,
    /** Root coordinates (px); [Rect.Zero] when unknown. */
    val rowFrame: Rect,
    val isClosing: Boolean = false,
)

/**
 * Port of iOS `ModelPickerAnchors`: where the composer's model controls and Fused rows rest
 * (root coordinates, px), plus the active Fused + Stack interaction state. A plain object, as on
 * iOS (per-frame motion stays out of reducers); its fades and the label flight run on [scope].
 * Create it with `remember { ModelPickerAnchors(scope) }` using `rememberCoroutineScope()`.
 */
@Stable
class ModelPickerAnchors(private val scope: CoroutineScope) {
    var levelControl by mutableStateOf(Rect.Zero)

    var chipFrame by mutableStateOf(Rect.Zero)
    var chipNameFrame by mutableStateOf(Rect.Zero)
    var chipLevelFrame by mutableStateOf(Rect.Zero)
    val rowFrames = mutableStateMapOf<String, Rect>()
    val rowNameFrames = mutableStateMapOf<String, Rect>()
    val rowLevelFrames = mutableStateMapOf<String, Rect>()
    val stackWordFrames = mutableStateMapOf<Int, Rect>()

    var flight by mutableStateOf<FusedLabelFlight?>(null)
        private set
    /** The flight's progress, 0 → 1 on [FusedModelPickerTunables.flight] (past 1 its overshoot). */
    val flightProgress = Animatable(0f)
    var levelStack by mutableStateOf<FusedLevelStackState?>(null)
    val levelStackAlpha = Animatable(0f)
    /** The stack's settle on release: the drawn position animating to its stop. */
    val levelStackSettle = Animatable(0f)
    var rowMenu by mutableStateOf<FusedRowMenuState?>(null)
        private set
    val rowMenuAlpha = Animatable(0f)
    /** Incremented on each open so the panel's list starts at the top even if reopened mid-fold. */
    var openGeneration by mutableIntStateOf(0)
        private set
    /** Snapshot of sections held while folding so Recent only reorders after close. */
    var frozenSections by mutableStateOf<List<ModelPickerSection>?>(null)

    /** The level stack is open and not folding away: it owns the touches. */
    val isLevelStackActive: Boolean get() = levelStack?.let { !it.isClosing } ?: false
    /** The row menu is open and not folding away: it owns the touches. */
    val isRowMenuActive: Boolean get() = rowMenu?.let { !it.isClosing } ?: false
    val isOverlayActive: Boolean get() = isLevelStackActive || isRowMenuActive

    /** The Fused list's scroll position (px) and whether it's moving, read by row gestures (not observed). */
    var listScrollOffset: Float = 0f
    var listIsScrolling: Boolean = false
    /** How far the Fused list has scrolled in all (px, both ways summed signed): a row's hold rules itself out past [HOLD_SLOP]. */
    var listScrollTravel: Float = 0f

    /**
     * What lies behind the level stack, for its feathered blur (iOS `ProgressiveBlur` over the
     * session). The session records it with [modelPickerBackdropSource]; until it does, the stack
     * draws its dim fill alone.
     */
    val backdrop = BlurSource()

    private var flightCounter = 0
    private var flightClearTask: Job? = null
    private var stackClearTask: Job? = null
    private var menuClearTask: Job? = null
    private var stackFadeTask: Job? = null
    private var menuFadeTask: Job? = null

    fun startFlight(
        direction: FusedLabelFlight.Direction,
        model: OpenCodeModel?,
        level: String?,
        levelSourceRect: Rect? = null,
        reduceMotion: Boolean,
    ) {
        flightClearTask?.cancel()
        if (reduceMotion || model == null) {
            flight = null
            return
        }
        val scale = ModelLevelScale(model.variants)
        val levelText = if (scale.isAdjustable) ModelLevelScale.label(level) else null
        val fromName: Rect
        val fromLevel: Rect?
        when (direction) {
            FusedLabelFlight.Direction.Open -> {
                fromName = if (chipNameFrame != Rect.Zero) chipNameFrame else chipFrame
                fromLevel = chipLevelFrame.takeIf { it != Rect.Zero }
            }
            FusedLabelFlight.Direction.Close -> {
                fromName = rowNameFrames[model.id] ?: rowFrames[model.id] ?: chipNameFrame
                fromLevel = levelSourceRect ?: rowLevelFrames[model.id] ?: chipLevelFrame.takeIf { it != Rect.Zero }
            }
        }
        if (fromName == Rect.Zero) {
            flight = null
            return
        }
        flightCounter += 1
        val currentID = flightCounter
        flight = FusedLabelFlight(currentID, direction, model.id, model.shortName, levelText, fromName, fromLevel)
        flightClearTask = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flightProgress.snapTo(0f)
            launch { flightProgress.animateTo(1f, FusedModelPickerTunables.flight.spec()) }
            delay(wallMillis(FusedModelPickerTunables.flight.duration + 0.08))
            if (flight?.id == currentID) flight = null
        }
    }

    fun openLevelStack(
        kind: FusedLevelStackState.SourceKind,
        model: OpenCodeModel,
        level: String?,
        anchorX: Float,
        anchorY: Float,
        rightEdgeX: Float,
        tapMode: Boolean,
        reduceMotion: Boolean,
    ) {
        val scale = ModelLevelScale(model.variants)
        if (!scale.isAdjustable) return
        stackClearTask?.cancel()
        stackWordFrames.clear()
        val idx = scale.index(level)
        levelStack = FusedLevelStackState(
            kind = kind, model = model, scale = scale, startIndex = idx, currentIndex = idx,
            rawPosition = idx.toDouble(), anchorX = anchorX, anchorY = anchorY, rightEdgeX = rightEdgeX,
            tapMode = tapMode, isClosing = false,
        )
        stackFadeTask?.cancel()
        stackFadeTask = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            levelStackSettle.snapTo(0f)
            levelStackAlpha.snapTo(0f)
            levelStackAlpha.animateTo(1f, FusedModelPickerTunables.animation(FusedModelPickerTunables.scrubIn, reduceMotion))
        }
    }

    /** A drag through the stack, [deltaY] in dp (up positive). Returns the new stop when it changed. */
    fun updateLevelStackDrag(deltaY: Double): Int? {
        val stack = levelStack ?: return null
        if (stack.isClosing) return null
        val gap = max(FusedModelPickerTunables.stackGap, 20.0)
        val count = stack.scale.count
        val raw = stack.startIndex + deltaY / gap
        val clampedIdx = roundHalfAwayFromZero(raw).toInt().coerceIn(0, count - 1)
        val pull = FusedModelPickerTunables.detent
        val pos = when {
            raw < 0 -> -0.3 * (1 - exp(raw / 0.3 * 0.6))
            raw > count - 1 -> {
                val over = raw - (count - 1)
                (count - 1) + 0.3 * (1 - exp(-over / 0.3 * 0.6))
            }
            else -> raw - pull * sin(2 * PI * raw) / (2 * PI)
        }
        val changed = if (clampedIdx != stack.currentIndex) clampedIdx else null
        levelStack = stack.copy(currentIndex = clampedIdx, rawPosition = pos)
        return changed
    }

    /** Sets the stack's chosen stop (a tapped word). */
    fun setLevelStackIndex(index: Int) {
        levelStack = levelStack?.copy(currentIndex = index)
    }

    fun setLevelStackTapMode(tapMode: Boolean) {
        levelStack = levelStack?.copy(tapMode = tapMode)
    }

    fun closeLevelStack(commit: Boolean = false, reduceMotion: Boolean) {
        val stack = levelStack ?: return
        if (stack.isClosing) return
        stackClearTask?.cancel()
        // The drawn position settles on its stop (iOS animates `rawPosition` on `scrubSettle`):
        // [levelStackSettle] carries the remaining distance from 1 to 0.
        val from = stack.rawPosition
        val to = stack.currentIndex.toDouble()
        levelStack = stack.copy(isClosing = true, rawPosition = to)
        val outSpring = if (commit) FusedModelPickerTunables.contentOut else FusedModelPickerTunables.scrubOut
        stackFadeTask?.cancel()
        stackFadeTask = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch {
                levelStackSettle.snapTo((from - to).toFloat())
                levelStackSettle.animateTo(0f, FusedModelPickerTunables.animation(FusedModelPickerTunables.scrubSettle, reduceMotion))
            }
            levelStackAlpha.animateTo(0f, FusedModelPickerTunables.animation(outSpring, reduceMotion))
        }
        stackClearTask = scope.launch {
            delay(wallMillis(outSpring.duration + 0.05))
            levelStack = null
            stackWordFrames.clear()
        }
    }

    fun openRowMenu(model: OpenCodeModel, rowFrame: Rect, reduceMotion: Boolean) {
        menuClearTask?.cancel()
        rowMenu = FusedRowMenuState(model, ModelLevelScale(model.variants), rowFrame)
        menuFadeTask?.cancel()
        menuFadeTask = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rowMenuAlpha.snapTo(0f)
            rowMenuAlpha.animateTo(1f, FusedModelPickerTunables.animation(FusedModelPickerTunables.menuIn, reduceMotion))
        }
    }

    fun closeRowMenu(reduceMotion: Boolean) {
        val menu = rowMenu ?: return
        if (menu.isClosing) return
        menuClearTask?.cancel()
        rowMenu = menu.copy(isClosing = true)
        menuFadeTask?.cancel()
        menuFadeTask = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rowMenuAlpha.animateTo(0f, FusedModelPickerTunables.animation(FusedModelPickerTunables.scrubOut, reduceMotion))
        }
        menuClearTask = scope.launch {
            delay(wallMillis(FusedModelPickerTunables.scrubOut.duration + 0.05))
            rowMenu = null
        }
    }

    fun resetFusedState() {
        flightClearTask?.cancel()
        stackClearTask?.cancel()
        menuClearTask?.cancel()
        stackFadeTask?.cancel()
        menuFadeTask?.cancel()
        openGeneration += 1
        flight = null
        levelStack = null
        rowMenu = null
        frozenSections = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            levelStackAlpha.snapTo(0f)
            rowMenuAlpha.snapTo(0f)
        }
        stackWordFrames.clear()
        rowFrames.clear()
        rowNameFrames.clear()
        rowLevelFrames.clear()
    }

    /** The chip left the composer: its frames must not steer swipes or flights. */
    fun clearChipFrames() {
        chipFrame = Rect.Zero
        chipNameFrame = Rect.Zero
        clearChipLevelFrame()
    }

    /** The current model has no level to show on the chip. */
    fun clearChipLevelFrame() {
        chipLevelFrame = Rect.Zero
        levelControl = Rect.Zero
    }
}

/** iOS waits `AnimationTimeScale.shared.wallSeconds(forVirtual:)`: slow motion stretches the clears with the springs. */
private fun wallMillis(virtualSeconds: Double): Long =
    (AnimationTimeScale.shared.wallSeconds(virtualSeconds) * 1000).roundToInt().toLong()

/**
 * Records what lies behind the Fused level stack (mount on the session content under the
 * overlays, never on a parent of them) so the stack's backdrop is a real blur of it.
 */
fun Modifier.modelPickerBackdropSource(anchors: ModelPickerAnchors): Modifier = blurSource(anchors.backdrop)

/** How far a row's press may ride the list's scroll before its hold no longer counts (iOS `FusedModelRow.holdSlop`, dp). */
internal const val HOLD_SLOP = 10.0

/** `UIScrollView.DecelerationRate.normal`: the share of velocity kept per millisecond. */
const val UIKIT_DECELERATION_RATE_NORMAL = 0.998

/**
 * UIKit's throw projection, the one SwiftUI's `DragGesture.Value.predictedEndTranslation` applies
 * (Apple, WWDC18 “Designing Fluid Interfaces” `project(initialVelocity:decelerationRate:)`):
 * how far a throw at [velocity] (per second) coasts while decelerating at [decelerationRate]
 * per millisecond, `(v / 1000) · r / (1 − r)`.
 */
fun projectedTravel(velocity: Double, decelerationRate: Double = UIKIT_DECELERATION_RATE_NORMAL): Double =
    velocity / 1000.0 * decelerationRate / (1.0 - decelerationRate)

/** SwiftUI `predictedEndTranslation`: where a drag at [translation] released at [velocity] would come to rest. */
fun predictedEndTranslation(translation: Double, velocity: Double): Double = translation + projectedTravel(velocity)

// MARK: - Per-Frame Motion Trace Verification

/**
 * iOS `FusedPickerMotionTrace`: pure per-frame samplers for the chip↔row label flight, so its
 * trajectory can be checked for jumps (iOS unit tests). Kept: [labelPoint] places the flight.
 */
object FusedPickerMotionTrace {
    fun labelPoint(from: Rect, to: Rect, progress: Float, direction: FusedLabelFlight.Direction, liftPx: Float): Offset {
        val clamped = progress.coerceIn(0f, 1f)
        val lift = sin(PI * clamped).toFloat() * liftPx * (if (direction == FusedLabelFlight.Direction.Open) -1 else 1)
        val x = from.left + (to.left - from.left) * progress
        val y = from.center.y + (to.center.y - from.center.y) * progress + lift
        return Offset(x, y)
    }
}
