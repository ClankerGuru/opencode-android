package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Port of iOS `Composer/ModelPicker/ModelPickerOverlays.swift`, split by area:
// - this file: tunables, list style/detents, anchors and Fused state, motion helpers;
// - ModelPickerOverlaysGlyph.kt: `ModelLevelGlyph`, `LevelArc`;
// - ModelPickerOverlaysChip.kt: `FusedModelChip`;
// - ModelPickerOverlaysPanel.kt: `FusedModelPickerPanel`, `FusedModelRow`;
// - ModelPickerOverlaysStack.kt: `FusedLevelStackOverlay`, `FusedRowMenuOverlay`, `FusedLabelFlightOverlay`.
//
// iOS backs every `Tunable` with the Developer Mode Tuning Panel; Android has no tuning store
// (`:devtools` is not a port), so these are the baked defaults with iOS's names and values
// (lengths in dp, times in seconds). `register`/`unregister` and `TuningPresets`: n/a.

/** iOS `FusedModelPickerTunables` (`modelPicker.fused.*`, the agreed Fused + Stack study, OC-397). */
object FusedModelPickerTunables {
    /** `layout` "Picker": 1 = fused. */
    const val layout = 1
    /** `levelStyle` "Level picker": 1 = stack. */
    const val levelStyle = 1
    const val stackGap = 40.0
    /** `allModels` "All models": 1 = scroll. */
    const val allModels = 1
    const val peekRows = 1.0
    /** `current` "Current model": 1 = fill, 2 = checkmark. */
    const val current = 1
    /** `levelPlace` "Level in rows": 1 = trailing. */
    const val levelPlace = 1
    /** `searchOrder` "Best match": 0 = top, 1 = bottom (reversed). */
    const val searchOrder = 0
    const val headers = true
    const val icons = true
    /** Whether the provider logo leads the model name on the composer chip (off by default). */
    const val chipProviderLogo = false
    const val chipGlyph = false
    const val chipChevron = false
    const val recents = 3.0

    val open = MotionSpring(0.34, 0.12)
    val close = MotionSpring(0.25, 0.0)
    const val widthLead = 1.5
    const val contentDelay = 0.04
    val contentIn = MotionSpring(0.3, 0.0)
    val contentOut = MotionSpring(0.15, 0.0)
    const val contentBlur = 8.0
    const val contentScale = 0.98

    val resize = MotionSpring(0.36, 0.0)
    val reflow = MotionSpring(0.4, 0.0)
    val rowIn = MotionSpring(0.25, 0.0)
    val rowOut = MotionSpring(0.16, 0.0)
    const val rowBlur = 3.0
    const val holdToFavorite = 0.45
    val menuIn = MotionSpring(0.28, 0.0)
    const val navShift = 56.0

    const val scrubThreshold = 8.0
    const val wordsLift = 50.0
    const val wordGap = 22.0
    const val wordSize = 17.0
    const val wordDim = 0.4
    const val detent = 0.6
    /** `scrubHighlight` "Highlight": 0 = none. */
    const val scrubHighlight = 0
    val scrubIn = MotionSpring(0.22, 0.0)
    val scrubOut = MotionSpring(0.18, 0.0)
    val scrubSettle = MotionSpring(0.25, 0.0)
    const val closeAfter = 0.0
    const val stripBlur = 14.0
    const val focusDim = 0.65

    val flight = MotionSpring(0.32, 0.08)
    const val mediumMax = 470.0
    const val grabSpace = 14.0
    const val chipMax = 200.0
    val chipRoll = MotionSpring(0.25, 0.0)
    val chipWidth = MotionSpring(0.25, 0.0)
    const val chipBlur = 4.0

    const val rowHeight = 48.0
    const val headerHeight = 30.0
    const val iconSize = 18.0
    const val nameSize = 17.0
    const val levelAlpha = 0.45
    const val panelInset = 16.0
    const val radius = 28.0
    const val padding = 6.0
    const val searchHeight = 44.0
    const val maxHeight = 600.0
    const val edgeFade = 24.0
    const val backdropDim = 0.0
    const val backdropBlur = 0.0
    const val reduceFade = 0.18

    /** iOS `animation(_:reduceMotion:)`: Reduce Motion is a [reduceFade] ease-in-out, else the spring. */
    fun <T> animation(spring: MotionSpring, reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) tween((reduceFade * 1000).roundToInt(), easing = Motion.EaseInOut) else spring.spec()
}

/** iOS `ModelListStyle`. */
enum class ModelListStyle(val rawValue: String) { Sheet("sheet"), Grow("grow") }

/** iOS `ModelPickerTunables` (`modelPicker.*`, the legacy and shared list tunables). */
object ModelPickerTunables {
    val listStyle = ModelListStyle.Sheet

    /** iOS reads a DEBUG `-lab-model-list-style` override first; lab hooks are n/a here. */
    val activeListStyle: ModelListStyle get() = listStyle

    val glassOpen = MotionSpring(0.42, 0.1)
    val glassClose = MotionSpring(0.3, 0.0)
    val restSpring = MotionSpring(0.42, 0.06)
    const val settle = 0.6
    const val foldDrawn = 420
    const val dimDark = 0.35
    const val dimLight = 0.18

    const val mediumFraction = 0.5
    const val mediumMinimum = 300.0
    const val headerGap = 6.0
    const val rubberMax = 48.0
    const val rubberSoftness = 160.0
    const val closeBelow = 0.55
    /** `modelPicker.sheet.flickCloses`: a flick down from full closes the sheet at this speed (dp/s). */
    const val flickCloses = 800.0
    const val dragMinimum = 6.0
    const val scrollUpTrigger = 12.0
    const val pullDownTrigger = 60.0

    const val barFadeStart = 10.0
    const val barFadeEnd = 50.0
    const val contentFadeStart = 34.0
    const val contentFadeEnd = 150.0
    const val revealBlur = 8.0
    const val rowsAfterFrames = 1

    const val dismissInTap = true
    const val keyboardLead = 1
    const val ridesKeyboard = true
    const val refocusDelay = 0
    const val searchTakesKeyboard = true
    const val searchFocusDelay = 450

    const val searchHeight = 44.0
    const val searchInset = 14.0
    const val searchBottom = 8.0
    const val searchFade = 1.0
    const val headerBlur = 10.0
    const val headerBlurReach = 28.0
    const val pinnedInset = 6.0

    const val rowHeight = 44.0
    const val rowPadding = 2.0
    const val rowLeading = 22.0
    const val rowTrailing = 18.0
    const val rowSpacing = 12.0
    /** `rowFont` / `providerFont` / `headerFont`: `.body`, `.subheadline`, `.footnote` (see `OpenCodeTextStyles`). */
    const val sectionGap = 14.0
    const val highlight = 0.08
    const val highlightRadius = 14.0

    val levelOpen = MotionSpring(0.44, 0.08)
    val levelClose = MotionSpring(0.36, 0.0)
    val levelStep = MotionSpring(0.26, 0.12)
    const val levelBackdropFade = 0.32
    const val columnWidth = 64.0
    const val panelHeight = 150.0
    const val panelPadding = 12.0
    const val trackHeight = 44.0
    const val stopInset = 22.0
    const val holdDelay = 280
    const val tapSlop = 10.0
    const val closeAfterDrag = 160

    val pullOutOpen = MotionSpring(0.34, 0.12)
    val pullOutClose = MotionSpring(0.26, 0.0)
    const val pullOutSpacing = 34.0
    const val pullOutHeight = 40.0
    const val pullOutInset = 20.0
    const val pullOutLift = 34.0
    const val pullOutKnob = 32.0
    const val pullOutSurfaceDark = 0.17
    const val pullOutSurfaceLight = 1.0
    const val pullOutLabel = 6.0

    fun glass(opening: Boolean): MotionSpring = if (opening) glassOpen else glassClose
}

/** iOS `LevelScrubberStyle`. */
enum class LevelScrubberStyle(val rawValue: String) {
    BarPanel("barPanel"), SegmentedBars("segmentedBars"), StretchPill("stretchPill"), PullOut("pullOut");

    val coversComposer: Boolean get() = this != PullOut
}

/** iOS `LevelIconStyle`. */
enum class LevelIconStyle(val rawValue: String) {
    DotMatrixBars("dotMatrixBars"), DotMatrixWave("dotMatrixWave"), DotMatrixCore("dotMatrixCore"),
    RisingBars("risingBars"), FilledArc("filledArc"), NeedleGauge("needleGauge"),
}

/** iOS `LevelPlacement`. */
enum class LevelPlacement(val rawValue: String) { BesideMic("besideMic"), BesideModel("besideModel") }

/** iOS `LevelScrubberTunables` (`composer.level.*`). Presets: n/a (Tuning Panel). */
object LevelScrubberTunables {
    val style = LevelScrubberStyle.PullOut
    /** iOS reads a DEBUG `-lab-level-style` override first; n/a here. */
    val activeStyle: LevelScrubberStyle get() = style
    val iconStyle = LevelIconStyle.FilledArc
    val placement = LevelPlacement.BesideModel
    /** `arcStroke` "Arc stroke (pt at 20 pt)". */
    const val arcStroke = 2.45
    /** `arcSpan` "Arc span (°)". */
    const val arcSpan = 240.0
    const val blurRadius = 22.0
    const val washOpacity = 0.62
}

/** iOS `ModelListDetent`: the grown list's two rests. */
enum class ModelListDetent {
    Medium, Large;

    data class Drag(val start: Double, val translation: Double)

    data class Rests(val large: Double) {
        val medium: Double
            get() = min(large, max(ModelPickerTunables.mediumMinimum, roundHalfAwayFromZero(large * ModelPickerTunables.mediumFraction)))

        fun extent(detent: ModelListDetent): Double = if (detent == Large) large else medium
    }

    val accessibilityValue: String get() = if (this == Large) "Full" else "Half"

    /** iOS `adjusted(_:)`: TalkBack's increment/decrement (+1 / −1, 0 keeps it). */
    fun adjusted(direction: Int): ModelListDetent = when {
        direction > 0 -> Large
        direction < 0 -> Medium
        else -> this
    }

    companion object {
        fun drag(drag: Drag?, drawnReach: Double, translation: Double): Drag =
            Drag(drag?.start ?: drawnReach, translation)

        fun settle(drag: Drag, predictedTranslation: Double?, rests: Rests): ModelListDetent? =
            rest(drag.start - (predictedTranslation ?: drag.translation), rests)

        fun reach(resting: Double, drag: Double, full: Double): Double {
            val raw = resting - drag
            if (raw <= full) return max(raw, 0.0)
            val over = raw - full
            return full + ModelPickerTunables.rubberMax * (1 - 1 / (over / max(ModelPickerTunables.rubberSoftness, 1.0) + 1))
        }

        fun rest(projected: Double, rests: Rests): ModelListDetent? {
            if (projected < rests.medium * ModelPickerTunables.closeBelow) return null
            return if (abs(projected - rests.large) < abs(projected - rests.medium)) Large else Medium
        }
    }
}

/** iOS `ModelListScrollEdge`. */
enum class ModelListScrollEdge { ScrolledUp, PulledDown }

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
        flightClearTask = scope.launch {
            flightProgress.snapTo(0f)
            launch { flightProgress.animateTo(1f, FusedModelPickerTunables.flight.spec()) }
            delay(((FusedModelPickerTunables.flight.duration + 0.08) * 1000).roundToInt().toLong())
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
        stackFadeTask = scope.launch {
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
        stackFadeTask = scope.launch {
            launch {
                levelStackSettle.snapTo((from - to).toFloat())
                levelStackSettle.animateTo(0f, FusedModelPickerTunables.animation(FusedModelPickerTunables.scrubSettle, reduceMotion))
            }
            levelStackAlpha.animateTo(0f, FusedModelPickerTunables.animation(outSpring, reduceMotion))
        }
        stackClearTask = scope.launch {
            delay(((outSpring.duration + 0.05) * 1000).roundToInt().toLong())
            levelStack = null
            stackWordFrames.clear()
        }
    }

    fun openRowMenu(model: OpenCodeModel, rowFrame: Rect, reduceMotion: Boolean) {
        menuClearTask?.cancel()
        rowMenu = FusedRowMenuState(model, ModelLevelScale(model.variants), rowFrame)
        menuFadeTask?.cancel()
        menuFadeTask = scope.launch {
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
        menuFadeTask = scope.launch {
            rowMenuAlpha.animateTo(0f, FusedModelPickerTunables.animation(FusedModelPickerTunables.scrubOut, reduceMotion))
        }
        menuClearTask = scope.launch {
            delay(((FusedModelPickerTunables.scrubOut.duration + 0.05) * 1000).roundToInt().toLong())
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
        scope.launch {
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

    /** The exact spring position `x(t)` for a `Spring(duration:bounce:)` from 0 to 1 at [fps] Hz. */
    fun springPositions(duration: Double, bounce: Double, fps: Double = 120.0, totalSeconds: Double = 0.7): List<Double> {
        val omega = (2 * PI) / max(duration, 0.01)
        val zeta = max(if (bounce >= 0) 1 - bounce else 1 / (1 + abs(bounce)), 0.01)
        val dt = 1.0 / fps
        val steps = roundHalfAwayFromZero(totalSeconds * fps).toInt()
        var x = 0.0
        var v = 0.0
        val target = 1.0
        val out = mutableListOf(0.0)
        repeat(steps) {
            val x0 = x - target
            val v0 = v
            if (abs(zeta - 1) < 1e-4) {
                val decay = exp(-omega * dt)
                val a = v0 + omega * x0
                x = target + (x0 + a * dt) * decay
                v = (v0 - omega * a * dt) * decay
            } else if (zeta < 1) {
                val wd = omega * sqrt(1 - zeta * zeta)
                val decay = exp(-zeta * omega * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                x = target + decay * (x0 * c + ((v0 + zeta * omega * x0) / wd) * s)
                v = decay * (v0 * c - ((zeta * omega * v0 + omega * omega * x0) / wd) * s)
            }
            out += x
        }
        return out
    }

    /** The worst handoff snap or mid-flight jump spike across a point trace. */
    fun maxDiscontinuity(points: List<Offset>, expectedStart: Offset, expectedEnd: Offset): Float {
        val first = points.firstOrNull() ?: return 0f
        val last = points.last()
        val startSnap = hypot(first.x - expectedStart.x, first.y - expectedStart.y)
        val endSnap = hypot(last.x - expectedEnd.x, last.y - expectedEnd.y)
        val steps = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        var maxSpike = 0f
        if (steps.size >= 3) {
            for (idx in 1 until steps.size - 1) {
                val envelope = max(steps[idx - 1], steps[idx + 1]) * 1.5f
                if (steps[idx] > envelope) maxSpike = max(maxSpike, steps[idx] - envelope)
            }
        }
        return maxOf(startSnap, endSnap, maxSpike)
    }
}

// MARK: - Supporting Types for Composer

/** iOS `ModelPickerMotion`. */
object ModelPickerMotion {
    fun level(style: LevelScrubberStyle, opening: Boolean, reduceMotion: Boolean): MotionSpring? {
        if (style != LevelScrubberStyle.PullOut || reduceMotion) return if (opening) open(reduceMotion) else close(reduceMotion)
        return if (opening) ModelPickerTunables.pullOutOpen else ModelPickerTunables.pullOutClose
    }

    /** Null under Reduce Motion: a 0.2 s ease-in-out ([Motion.reducedFadeSpec]). */
    fun open(reduceMotion: Boolean): MotionSpring? = if (reduceMotion) null else ModelPickerTunables.levelOpen
    fun close(reduceMotion: Boolean): MotionSpring? = if (reduceMotion) null else ModelPickerTunables.levelClose

    /** iOS also honours a DEBUG `-lab-model-list-focus-delay-ms` override; n/a here. */
    val focusAfterFold: Duration get() = ModelPickerTunables.refocusDelay.milliseconds

    /** iOS also honours a DEBUG `-lab-model-list-drops-keyboard` override; n/a here. */
    val searchTakesKeyboard: Boolean get() = ModelPickerTunables.searchTakesKeyboard

    fun settle(reduceMotion: Boolean): Duration =
        if (reduceMotion) 220.milliseconds else ModelPickerTunables.foldDrawn.milliseconds
}

/** iOS `GrowingPanel`: closed, open, or closing (drawn until its fold lands). */
@Stable
class GrowingPanel(private val scope: CoroutineScope) {
    enum class Phase { Closed, Open, Closing }

    var phase by mutableStateOf(Phase.Closed)
        private set
    private var settle: Job? = null

    val isOpen: Boolean get() = phase == Phase.Open
    val isShown: Boolean get() = phase != Phase.Closed

    fun open() {
        settle?.cancel()
        phase = Phase.Open
    }

    fun close(reduceMotion: Boolean, drawnFor: Duration? = null) {
        if (phase != Phase.Open) return
        phase = Phase.Closing
        settle = scope.launch {
            delay(drawnFor ?: ModelPickerMotion.settle(reduceMotion))
            phase = Phase.Closed
        }
    }

    fun reset() {
        settle?.cancel()
        phase = Phase.Closed
    }
}

/** iOS `ComposerFocusRestore`: one pending "give the message field its keyboard back". */
class ComposerFocusRestore(private val scope: CoroutineScope) {
    private var task: Job? = null

    fun schedule(after: Duration, restore: () -> Unit) {
        cancel()
        task = scope.launch {
            delay(after)
            task = null
            restore()
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
    }
}

/** iOS `ModelLevelPress`: a retired press tracker kept as an inert seam. */
class ModelLevelPress {
    val isPressed: Boolean get() = false
    fun cancel() {}
}
