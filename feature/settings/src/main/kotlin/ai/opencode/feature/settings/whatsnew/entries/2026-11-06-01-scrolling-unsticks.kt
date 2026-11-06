package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.scrollingUnsticks: WhatsNewEntry
    get() = smaller(
        id = "scrolling-unsticks",
        day = "2026-11-06",
        title = "Scrolling Unsticks Itself",
        symbol = "hand.draw",
        summary = "A session that sometimes wouldn’t scroll until you switched to another one now scrolls again on its own.",
    )
