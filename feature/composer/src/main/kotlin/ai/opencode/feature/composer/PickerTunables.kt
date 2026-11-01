package ai.opencode.feature.composer

import ai.opencode.core.motion.MotionSpring

/**
 * iOS `PickerTunables` (Picker Interactions Lab): the photo picker's knobs at their shipped
 * defaults. The lab's playground picker, model-sheet open (zoom), catalogue size and presets
 * are Tuning Panel controls, which Android doesn't port (its tuning is `:devtools`); the
 * standard sheet open is what ships, so `ModelChipZoomSource` has nothing to do here.
 */
object PickerTunables {
    /** The photo sheet's least height as a share of the screen (0: as shipped, the keyboard's place or `leastLift`). */
    const val PHOTO_HEIGHT = 0.0

    /** The grabber floats on the photos' top row, the grid inset evenly from the glass (off: its own band above the grid). */
    const val PHOTO_GRABBER_OVER_GRID = true

    /** The photo sheet's Add label (count and width) as picks change and the sheet expands. */
    val photoAdd = MotionSpring(0.3, 0.0)
}
