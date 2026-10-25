package ai.opencode.core.ui

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningPreset
import ai.opencode.core.tuning.TuningPresets
import ai.opencode.core.tuning.TuningStore

/**
 * iOS `RollingNumberTunables` (`Core/RollingNumber/RollingNumber.swift`): the rolling number's
 * motion as live tunables (group `numbers`), with the Numbers presets. Same keys and defaults.
 */
object RollingNumberTunables {
    val duration = Tunable("numbers.duration", "Roll duration", default = 0.34, range = 0.1..0.8, step = 0.01)
    val bounce = Tunable("numbers.bounce", "Roll bounce", default = 0.0, range = 0.0..0.3, step = 0.01)
    val stagger = Tunable("numbers.stagger", "Place stagger", default = 0.025, range = 0.0..0.08, step = 0.005)
    val blurAmount = Tunable("numbers.blur", "Vertical smear", default = 1.0, range = 0.0..2.0, step = 0.05)
    val edgeFade = Tunable("numbers.edgeFade", "Reel edge fade", default = 0.18f, range = 0.0..0.35, step = 0.01)

    val feels = TuningPresets(
        "numbers.presets", "Numbers",
        listOf(
            TuningPreset("Native"),
            TuningPreset("Snappy", duration.preset(0.24), stagger.preset(0.015), blurAmount.preset(0.65), edgeFade.preset(0.16f)),
            TuningPreset("Gentle", duration.preset(0.46), stagger.preset(0.03), blurAmount.preset(1.05), edgeFade.preset(0.2f)),
            TuningPreset("Lively", duration.preset(0.3), bounce.preset(0.1), stagger.preset(0.02), blurAmount.preset(1.15), edgeFade.preset(0.2f)),
        ),
        resetting = listOf(duration.descriptor, bounce.descriptor, stagger.descriptor, blurAmount.descriptor, edgeFade.descriptor),
    )

    fun register(store: TuningStore = TuningStore.shared) {
        if (store.isEnabled) store.register(feels)
        store.register(duration, bounce, stagger, blurAmount)
        store.register(edgeFade)
    }

    /** The live style (iOS `style(in:)`). */
    fun style(store: TuningStore = TuningStore.shared): RollingNumberStyle {
        val blur = store.value(blurAmount)
        return RollingNumberStyle(
            duration = store.value(duration),
            bounce = store.value(bounce),
            motionBlur = blur > 0.01,
            blurAmount = blur,
            edgeFade = store.value(edgeFade),
            stagger = store.value(stagger),
        )
    }
}
