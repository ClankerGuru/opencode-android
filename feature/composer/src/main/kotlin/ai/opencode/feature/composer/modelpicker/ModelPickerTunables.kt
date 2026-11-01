package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

// Port of iOS `Composer/ModelPicker/ModelPickerTunables.swift` (split out of ModelPickerOverlays in 17a991b1).
//
// iOS backs every `Tunable` with the Developer Mode Tuning Panel; Android has no tuning store
// (`:devtools` is not a port), so these are the baked defaults with iOS's names and values
// (lengths in dp, times in seconds). `register`/`unregister`, `sectionTitles` and `TuningPresets`: n/a.

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
