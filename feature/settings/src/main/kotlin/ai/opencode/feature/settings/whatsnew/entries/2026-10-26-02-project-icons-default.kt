package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectIconsDefault: WhatsNewEntry
    get() = smaller(
        id = "project-icons-default",
        day = "2026-10-26",
        title = "Project Icons On",
        symbol = "app",
        summary = "Project icons now show beside session titles. Turn them off in Settings › Experimental.",
        tryIt = Destination.Open(SettingsDestination.Experiment("project-icons")),
    )
