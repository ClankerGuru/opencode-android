package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionTunables

/** Parameters snapshot read by [AutocompleteSimulation]. */
data class AutocompleteParameters(
    val order: AutocompleteOrder = AutocompleteOrder.Bottom,
    val exitMode: AutocompleteExitMode = AutocompleteExitMode.Collapse,
    val keysInstant: Boolean = true,
    val reducedMotion: Boolean = false,

    // Timing comes from the motion roles: the glass and its content grow on `panelOpen` and fold
    // on `panelClose`; filtering rows fade in on `microIn` and out on `microOut`, and slots, rows
    // and height reflow on `collapse`, so typing stays crisp; the highlight slides on `swap`; a
    // chosen row flies into its chip on `flight`; a list pulled past its end lets go on `release`.
    val openDuration: Double = Motion.panelOpen.curve().duration,
    val openBounce: Double = Motion.panelOpen.curve().bounce,
    val closeDuration: Double = Motion.panelClose.curve().duration,
    val closeBounce: Double = Motion.panelClose.curve().bounce,
    val contentInDuration: Double = Motion.panelOpen.curve().duration,
    val contentInBounce: Double = Motion.panelOpen.curve().bounce,
    val contentOutDuration: Double = Motion.panelClose.curve().duration,
    val contentOutBounce: Double = Motion.panelClose.curve().bounce,
    val resizeDuration: Double = Motion.collapse.curve().duration,
    val resizeBounce: Double = Motion.collapse.curve().bounce,
    val reflowDuration: Double = Motion.collapse.curve().duration,
    val reflowBounce: Double = Motion.collapse.curve().bounce,
    val rowInDuration: Double = Motion.microIn.curve().duration,
    val rowInBounce: Double = Motion.microIn.curve().bounce,
    val rowOutDuration: Double = Motion.microOut.curve().duration,
    val rowOutBounce: Double = Motion.microOut.curve().bounce,
    val highlightDuration: Double = Motion.swap.curve().duration,
    val highlightBounce: Double = Motion.swap.curve().bounce,
    val flightDuration: Double = Motion.flight.curve().duration,
    val flightBounce: Double = Motion.flight.curve().bounce,
    val releaseDuration: Double = Motion.release.curve().duration,

    val widthLead: Double = MotionTunables.widthLead.value,
    val contentDelay: Double = Motion.contentDelay,
    val coalesce: Double = 0.0,
    val blur: Double = 4.0,
    val scale: Double = 0.96,
    val rowBlur: Double = 0.0,
    val rowScale: Double = 1.0,
    val flightArc: Double = 1.4,
    /** Under Reduce Motion every fade runs `Motion.reduced`, never slower than its spring. */
    val reduceFade: Double = Motion.reduced.curve().duration,

    val maxRows: Double = 5.5,
    val rowHeight: Double = 40.0,
    val padding: Double = 12.0,
    val rowInset: Double = 7.0,
    val iconSize: Double = 22.0,
    val edgeFade: Double = 24.0,
    val cornerRadius: Double = 28.0,
) {
    companion object {
        /** The roles' timing and the tunables' values. */
        fun current(reducedMotion: Boolean = false): AutocompleteParameters = with(ComposerAutocompleteTunables) {
            AutocompleteParameters(
                order = order,
                exitMode = exitMode,
                keysInstant = keysInstant,
                reducedMotion = reducedMotion,
                coalesce = coalesce,
                blur = blur,
                scale = scale,
                rowBlur = rowBlur,
                rowScale = rowScale,
                flightArc = flightArc,
                maxRows = maxRows,
                rowHeight = rowHeight,
                padding = padding,
                rowInset = rowInset,
                iconSize = iconSize,
                edgeFade = edgeFade,
                cornerRadius = composerCornerRadius,
            )
        }
    }
}
