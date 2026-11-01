package ai.opencode.feature.voice

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableSpring
import ai.opencode.core.tuning.TuningStore

// One Shape (iOS `FloatingCallStages.swift`): the orb's own glass and dot field grow into a card
// above the composer, then into a sheet the height of the screen, on one vertical drag. One
// progress value runs 0 (orb), 1 (card) and 2 (full). Lengths in dp, times in seconds.

/** One Shape's tunables (iOS `FloatingCallStageTunables`). */
object FloatingCallStageTunables {
    val cardWidth = Tunable("call.stage.cardWidth", "Card width", default = 416.0, range = 300.0..440.0, step = 2.0)
    val cardHeight = Tunable("call.stage.cardHeight", "Card height", default = 440.0, range = 300.0..620.0, step = 2.0)
    val fullTop = Tunable("call.stage.fullTop", "Full sheet top", default = 70.0, range = 44.0..160.0, step = 1.0)
    val margin = Tunable("call.stage.margin", "Card side margin", default = 12.0, range = 0.0..32.0, step = 1.0)
    val cardRadius = Tunable("call.stage.cardRadius", "Card corner radius", default = 36.0, range = 12.0..56.0, step = 1.0)
    val fullRadius = Tunable("call.stage.fullRadius", "Sheet corner radius", default = 38.0, range = 12.0..56.0, step = 1.0)
    val cardBloomY = Tunable("call.stage.cardBloomY", "Card bloom centre", default = 166.0, range = 100.0..260.0, step = 1.0)
    val cardBloomRadius = Tunable("call.stage.cardBloomRadius", "Card bloom radius", default = 96.0, range = 40.0..160.0, step = 1.0)
    val fullBloomY = Tunable("call.stage.fullBloomY", "Sheet bloom centre", default = 150.0, range = 80.0..260.0, step = 1.0)
    val fullBloomRadius = Tunable("call.stage.fullBloomRadius", "Sheet bloom radius", default = 76.0, range = 30.0..160.0, step = 1.0)
    val cardFill = Tunable("call.stage.cardFill", "Card backing", default = 0.35, range = 0.0..1.0, step = 0.05)
    val sheetFill = Tunable("call.stage.sheetFill", "Sheet backing", default = 0.55, range = 0.0..1.0, step = 0.05)
    val dim = Tunable("call.stage.dim", "Dim behind the sheet", default = 0.4, range = 0.0..0.8, step = 0.05)
    val rubberBand = Tunable("call.stage.rubberBand", "Rubber band past the top", default = 0.55, range = 0.1..1.5, step = 0.05)
    val flickSpeed = Tunable("call.stage.flickSpeed", "Flick skips a stage above (pt/s)", default = 450.0, range = 150.0..1500.0, step = 25.0)
    val projection = Tunable("call.stage.projection", "Release projection (s)", default = 0.18, range = 0.0..0.5, step = 0.01)
    val open = TunableSpring("call.stage.open", "Grow spring", duration = 0.45, bounce = 0.0)
    val close = TunableSpring("call.stage.close", "Shrink spring", duration = 0.4, bounce = 0.0)
    val land = TunableSpring("call.stage.land", "Hang-up flight", duration = 0.38, bounce = 0.0)

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(cardWidth, cardHeight, fullTop, margin, cardRadius, fullRadius)
        store.register(cardBloomY, cardBloomRadius, fullBloomY, fullBloomRadius)
        store.register(cardFill, sheetFill, dim, rubberBand, flickSpeed, projection)
        store.register(open)
        store.register(close)
        store.register(land)
    }
}
