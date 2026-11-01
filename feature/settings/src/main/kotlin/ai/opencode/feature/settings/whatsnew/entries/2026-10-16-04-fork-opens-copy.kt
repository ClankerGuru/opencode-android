package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.forkOpensCopy: WhatsNewEntry
    get() = smaller(
        id = "fork-opens-copy",
        day = "2026-10-16",
        title = "Fork Opens the Copy",
        symbol = "arrow.triangle.branch",
        summary = "Fork in a session’s details opens the copy again.",
    )
