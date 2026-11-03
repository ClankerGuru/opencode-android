package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.steadierFollowing: WhatsNewEntry
    get() = smaller(
        id = "steadier-following",
        day = "2026-11-02",
        title = "Steadier Following",
        symbol = "arrow.down.to.line",
        summary = "A streaming reply stays pinned to the bottom without Working bouncing, and you can swipe the list from anywhere above the composer.",
    )
