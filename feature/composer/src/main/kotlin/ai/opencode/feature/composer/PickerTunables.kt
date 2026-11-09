package ai.opencode.feature.composer

/**
 * iOS `PickerTunables` (Picker Interactions Lab): the photo picker's knobs at their shipped
 * defaults. The lab's catalogue size and presets are Tuning Panel controls, which Android
 * doesn't port (its tuning is `:devtools`).
 */
object PickerTunables {
    /** The photo sheet's least height as a share of the screen (0: as shipped, the keyboard's place or `leastLift`). */
    const val PHOTO_HEIGHT = 0.0

    /** The grabber floats on the photos' top row, the grid inset evenly from the glass (off: its own band above the grid). */
    const val PHOTO_GRABBER_OVER_GRID = true
}
