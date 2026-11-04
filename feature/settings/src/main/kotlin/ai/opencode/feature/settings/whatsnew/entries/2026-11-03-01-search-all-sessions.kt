package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.searchAllSessions: WhatsNewEntry
    get() = smaller(
        id = "search-all-sessions",
        day = "2026-11-03",
        title = "Find Older Sessions",
        symbol = "magnifyingglass",
        summary = "Search in the sidebar now looks through every session, not just the recent ones, and scrolling to the bottom loads older sessions.",
    )
