package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelPickerStandardSheet: WhatsNewEntry
    get() = smaller(
        id = "model-picker-standard-sheet",
        day = "2026-10-19",
        title = "Model Picker as a Standard Sheet",
        symbol = "cpu",
        summary = "With the New Model Picker experiment on, the model in the message box opens a standard sheet where each model has its own thinking-level menu. The earlier picker is now Custom Model Picker in Settings › Experimental.",
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
