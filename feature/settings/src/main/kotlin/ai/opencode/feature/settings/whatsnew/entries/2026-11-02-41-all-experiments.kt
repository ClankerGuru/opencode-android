package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.allExperiments: WhatsNewEntry
    get() = feature(
        id = "all-experiments",
        day = "2026-11-02",
        title = "All Experiments",
        symbol = "flask.fill",
        tint = Tint.gray,
        summary = "One switch at the top of Settings › Experimental turns every experiment on, including new ones as they arrive.",
        details = listOf(
            "Switch one experiment off to skip it; it stays off next time too.",
            "Turning All Experiments off puts back the switches you had before.",
            "Experiments that ask for a permission stay a choice of their own.",
            "Autocomplete, the plus menu’s photo sheet, the new server switch and project icons are now on for everyone.",
            "One Picker and Living Orb are plain switches, and Call Tray is gone.",
        ),
        example = "Open Settings › Experimental and switch on All Experiments",
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
