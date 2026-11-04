package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.autocompleteFastScroll: WhatsNewEntry
    get() = smaller(
        id = "autocomplete-fast-scroll",
        day = "2026-10-19",
        title = "Snappier Autocomplete",
        symbol = "at",
        summary = "With the Composer Autocomplete experiment on, suggestions keep up as you type, and dragging the list scrolls it without picking a row.",
    )
