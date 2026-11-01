package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuOverKeyboard: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-over-keyboard",
        day = "2026-10-26",
        title = "Photos Over the Keyboard",
        symbol = "keyboard",
        summary = "With New Plus Menu on, the + menu opens over the keyboard, and Photos grows from it into a sheet that runs to the screen’s edges, then folds back without putting the keyboard away.",
        tryIt = Destination.Open(SettingsDestination.Experiment("plus-menu-sheet")),
    )
