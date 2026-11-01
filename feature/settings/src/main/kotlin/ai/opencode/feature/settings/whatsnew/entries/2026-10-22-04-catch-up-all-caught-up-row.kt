package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.catchUpAllCaughtUpRow: WhatsNewEntry
    get() = smaller(
        id = "catch-up-all-caught-up-row",
        day = "2026-10-22",
        title = "All Caught Up",
        symbol = "checkmark.circle",
        summary = "Reading the last session in Catch Up takes you straight back to the sidebar, where Catch Up checks itself off as All Caught Up.",
    )
