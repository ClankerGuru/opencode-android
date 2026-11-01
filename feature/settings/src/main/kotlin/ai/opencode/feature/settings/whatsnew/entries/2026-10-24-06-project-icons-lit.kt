package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectIconsLit: WhatsNewEntry
    get() = smaller(
        id = "project-icons-lit",
        day = "2026-10-24",
        title = "Lit Project Icons",
        symbol = "app",
        summary = "With Project Icons enabled, sidebar icons catch the light: a soft highlight from the top, a fine glass edge and a gentle shadow.",
        tryIt = Destination.Open(SettingsDestination.Experiment("project-icons")),
    )
