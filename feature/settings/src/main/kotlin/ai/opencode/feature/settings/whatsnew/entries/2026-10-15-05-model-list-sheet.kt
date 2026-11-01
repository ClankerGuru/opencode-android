package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelListSheet: WhatsNewEntry
    get() = smaller(
        id = "model-list-sheet",
        day = "2026-10-15",
        title = "Model List as a Sheet",
        symbol = "rectangle.bottomhalf.inset.filled",
        summary = "With the New Model Picker experiment on, the message box turns into a sheet of models where the keyboard was, the keyboard goes down, and it folds back smoothly however fast you open and close it.",
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
