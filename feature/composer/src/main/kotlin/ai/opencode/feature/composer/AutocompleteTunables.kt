package ai.opencode.feature.composer

import ai.opencode.core.motion.MotionSpring

/** iOS `AutocompleteOrder`: where the best match sits. */
enum class AutocompleteOrder(val rawValue: String) { Top("top"), Bottom("bottom") }

/** iOS `AutocompleteExitMode`: how a leaving row goes. */
enum class AutocompleteExitMode(val rawValue: String) { Fade("fade"), Collapse("collapse") }

/** iOS `AutocompleteCommandAction`: whether choosing a `/` command fills it in or runs it. */
enum class AutocompleteCommandAction(val rawValue: String) { Fill("fill"), Run("run") }

/**
 * Port of iOS `ComposerAutocompleteTunables` (D-105 option 3, "Composer Grows"), keys and
 * defaults under `composer.autocomplete.*`. iOS backs these with live `Tunable`s in the
 * Developer Mode Tuning Panel; Android has no tuning store, so these are the baked defaults
 * (lengths in dp, times in seconds). [presets] keeps the iOS tuning presets as parameter
 * transforms of [AutocompleteParameters].
 */
object ComposerAutocompleteTunables {
    /** `composer.autocomplete.order` "Best match". */
    val order = AutocompleteOrder.Bottom

    /** `composer.autocomplete.open` "Open". */
    val open = MotionSpring(0.3, 0.0)
    /** `composer.autocomplete.close` "Close". */
    val close = MotionSpring(0.22, 0.0)
    /** `composer.autocomplete.widthLead` "Width leads" (1…3). */
    const val widthLead = 1.85
    /** `composer.autocomplete.blur` "Entrance blur (pt)" (0…16). */
    const val blur = 4.0
    /** `composer.autocomplete.scale` "Entrance scale" (0.8…1). */
    const val scale = 0.96
    /** `composer.autocomplete.contentDelay` "Content delay (s)" (0…0.2). */
    const val contentDelay = 0.03
    /** `composer.autocomplete.contentIn` "Content in". */
    val contentIn = MotionSpring(0.28, 0.0)
    /** `composer.autocomplete.contentOut` "Content out". */
    val contentOut = MotionSpring(0.18, 0.0)

    /** `composer.autocomplete.reflow` "Slots open / close, rows glide". */
    val reflow = MotionSpring(0.14, 0.0)
    /** `composer.autocomplete.resize` "Height (when rows fade over)". */
    val resize = MotionSpring(0.14, 0.0)
    /** `composer.autocomplete.rowIn` "Row fades in". */
    val rowIn = MotionSpring(0.1, 0.0)
    /** `composer.autocomplete.rowOut` "Row fades out". */
    val rowOut = MotionSpring(0.06, 0.0)
    /** `composer.autocomplete.rowBlur` "New row blur (pt)" (0…8). */
    const val rowBlur = 0.0
    /** `composer.autocomplete.rowScale` "Row scale" (0.6…1). */
    const val rowScale = 1.0
    /** `composer.autocomplete.exitMode` "Leaving row". */
    val exitMode = AutocompleteExitMode.Collapse
    /** `composer.autocomplete.coalesce` "Commit bursts every (s)" (0…0.3). */
    const val coalesce = 0.0

    /** `composer.autocomplete.highlight` "Highlight moves". */
    val highlight = MotionSpring(0.14, 0.0)
    /** `composer.autocomplete.keysInstant` "Arrow keys move it at once". */
    const val keysInstant = true

    /** `composer.autocomplete.flight` "Flight". */
    val flight = MotionSpring(0.32, 0.0)
    /** `composer.autocomplete.flightArc` "Height leads" (1…3). */
    const val flightArc = 1.4
    /** `composer.autocomplete.commandAction` "A / command". */
    val commandAction = AutocompleteCommandAction.Fill
    /** `composer.autocomplete.runDelay` "Runs after landing (s)" (0…1). */
    const val runDelay = 0.35

    /** `composer.autocomplete.rowHeight` "Row height (pt)" (36…56). */
    const val rowHeight = 40.0
    /** `composer.autocomplete.iconSize` "Icon (pt)" (16…32). */
    const val iconSize = 22.0
    /** `composer.autocomplete.maxRows` "Rows before scrolling" (2…8). */
    const val maxRows = 5.5
    /** `composer.autocomplete.padding` "Highlight inset (pt)" (2…20). */
    const val padding = 12.0
    /** `composer.autocomplete.rowInset` "Icon inset in highlight (pt)" (2…24). */
    const val rowInset = 7.0
    /** `composer.autocomplete.edgeFade` "Scroll edge fade (pt)" (0…60). */
    const val edgeFade = 24.0

    /** `composer.autocomplete.reduceFade` "Reduce Motion fade (s)" (0.1…0.4). */
    const val reduceFade = 0.18

    /** `ComposerTunables.cornerRadius` (the desktop dock's 12; iOS 28), the surface the highlight sits concentric in. */
    const val composerCornerRadius = 12.0

    /** An iOS `TuningPreset`: a name and the parameters it sets (everything else resets to default). */
    data class Preset(val name: String, val apply: (AutocompleteParameters) -> AutocompleteParameters)

    /** iOS `composer.autocomplete.presets` "Autocomplete". Each applies on top of the defaults. */
    val presets: List<Preset> = listOf(
        Preset("Kit Motion") { it },
        Preset("Slower search (458)") {
            it.copy(
                reflowDuration = 0.34,
                resizeDuration = 0.32,
                rowInDuration = 0.24,
                rowOutDuration = 0.14,
                rowBlur = 2.0,
                rowScale = 0.97,
                coalesce = 0.1,
                highlightDuration = 0.2,
            )
        },
        Preset("Native today") {
            it.copy(
                openDuration = 0.34, openBounce = 0.12,
                closeDuration = 0.26, closeBounce = 0.0,
                widthLead = 1.6,
                blur = 10.0,
                scale = 0.92,
                contentDelay = 0.05,
                contentInDuration = 0.36, contentInBounce = 0.0,
                contentOutDuration = 0.28, contentOutBounce = 0.0,
                reflowDuration = 0.3, reflowBounce = 0.0,
                resizeDuration = 0.3, resizeBounce = 0.0,
                rowInDuration = 0.3, rowInBounce = 0.0,
                rowOutDuration = 0.2, rowOutBounce = 0.0,
                rowBlur = 0.0,
                rowScale = 0.72,
                exitMode = AutocompleteExitMode.Fade,
                coalesce = 0.0,
                highlightDuration = 0.3, highlightBounce = 0.0,
                keysInstant = false,
                flightDuration = 0.34, flightBounce = 0.0,
                flightArc = 1.45,
                // iOS also sets commandAction = fill and runDelay = 0.35 (both the defaults; the
                // reducer reads them from this object, not from the motion parameters).
                reduceFade = 0.18,
            )
        },
    )
}
