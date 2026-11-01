package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.experimentalGroups: WhatsNewEntry
    get() = smaller(
        id = "experimental-groups",
        day = "2026-10-19",
        title = "Grouped Experiments",
        symbol = "flask",
        summary = "Experiments in Settings → Experimental are grouped, and calls have one Call Style choice.",
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
