package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Port of iOS `Composer/ModelPicker/ModelPickerOverlays.swift`: what stayed after 17a991b1 split out
// ModelPickerTunables, ModelPickerAnchors, FusedModelChip, FusedModelPickerPanel, FusedModelRow and
// FusedPickerOverlays (each its own Kotlin file of the same name). `ModelLevelGlyph`/`LevelArc` are in
// ModelPickerOverlaysGlyph.kt, `ProgressiveBlur` in ModelPickerBlur.kt.

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
