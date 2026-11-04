package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbFloats: WhatsNewEntry
    get() = smaller(
        id = "living-orb-floats",
        day = "2026-10-24",
        title = "Living Orb Floats Everywhere",
        symbol = "circle.hexagongrid",
        summary = "Living Orb is now the floating Call Style in Settings › Experimental › Calls. It’s bigger, stays above Settings and every screen, and moves with both voices, each in its own colour when you talk at once. Tap it to grow it into a card, drag up for the whole call and its transcript, and drag down to shrink it back. Orb, beneath Call Style, changes its look.",
    )
