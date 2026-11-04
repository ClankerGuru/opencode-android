package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.advancedSettingsOpen: WhatsNewEntry
    get() = smaller(
        id = "advanced-settings-open",
        day = "2026-10-20",
        title = "Simpler Advanced Settings",
        symbol = "flask",
        summary = "Experiments in Settings › Experimental are all in open groups, the model pickers are one Model Picker choice, and you can tap anywhere on a choice’s row to change it.",
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
