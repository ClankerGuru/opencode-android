package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.MotionSpring

// Port of iOS `Composer/ModelPicker/ModelPickerTunables.swift` (split out of ModelPickerOverlays in 17a991b1).
//
// iOS backs every `Tunable` with the Developer Mode Tuning Panel; Android has no tuning store
// (`:devtools` is not a port), so these are the baked defaults with iOS's names and values
// (lengths in dp, times in seconds). `register`/`unregister`, `sectionTitles` and `TuningPresets`: n/a.
// iOS 0fbb99f8 deleted `FusedModelPickerTunables`, `ModelListStyle` and the Sheet and Grow from
// Composer knobs with those pickers.

/**
 * iOS `ModelPickerTunables` (`modelPicker.*`): the model list's rests and glass, which One
 * Picker's detents ([ModelListDetent]), the photo sheet and the call's composer expansion still read.
 */
object ModelPickerTunables {
    val glassOpen = MotionSpring(0.42, 0.1)
    val glassClose = MotionSpring(0.3, 0.0)
    val restSpring = MotionSpring(0.42, 0.06)
    const val settle = 0.6
    const val dimDark = 0.35
    const val dimLight = 0.18

    /** `modelPicker.fused.mediumMax`: how high the photo sheet first rests with the keyboard down. */
    const val mediumMax = 470.0
    const val mediumFraction = 0.5
    const val mediumMinimum = 300.0
    const val headerGap = 6.0
    const val rubberMax = 48.0
    const val rubberSoftness = 160.0
    const val closeBelow = 0.55
    /** `modelPicker.sheet.flickCloses`: a flick down from full closes the sheet at this speed (dp/s). */
    const val flickCloses = 800.0

    const val barFadeStart = 10.0
    const val barFadeEnd = 50.0
    const val contentFadeStart = 34.0
    const val contentFadeEnd = 150.0
    const val revealBlur = 8.0

    fun glass(opening: Boolean): MotionSpring = if (opening) glassOpen else glassClose
}
