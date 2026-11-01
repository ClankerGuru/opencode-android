package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerTextSelection: WhatsNewEntry
    get() = smaller(
        id = "composer-text-selection",
        day = "2026-10-29",
        title = "Select Text in the Composer",
        symbol = "character.cursor.ibeam",
        summary = "Dragging a selection’s handles or the loupe in a draft no longer puts the keyboard away or pulls the composer up or down.",
    )
