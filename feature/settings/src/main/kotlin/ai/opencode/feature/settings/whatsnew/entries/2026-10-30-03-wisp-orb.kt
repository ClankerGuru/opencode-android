package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.wispOrb: WhatsNewEntry
    get() = smaller(
        id = "wisp-orb",
        day = "2026-10-30",
        title = "The Voice Orb Is Now Wisp",
        symbol = "circle.hexagongrid.fill",
        summary = "The call’s orb now has Wisp’s look: a glass lens over glowing dots that both voices light, with Wisp’s moods for connecting, listening, working, speaking and waiting on you. It’s the only orb style, so the old styles and Orb settings are gone.",
    )
