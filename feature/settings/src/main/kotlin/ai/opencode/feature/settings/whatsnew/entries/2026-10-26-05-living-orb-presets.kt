package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbPresets: WhatsNewEntry
    get() = smaller(
        id = "living-orb-presets",
        day = "2026-10-26",
        title = "Orb Presets and Fancy",
        symbol = "circle.hexagongrid.fill",
        summary = "Orb, beneath Call Style in Settings › Experimental › Calls, now opens on Fancy, with a bigger live preview and a row of presets. Save your own, rename them, and copy or paste a setup to share it.",
        tryIt = Destination.Open(SettingsDestination.Experiment("living-orb")),
    )
