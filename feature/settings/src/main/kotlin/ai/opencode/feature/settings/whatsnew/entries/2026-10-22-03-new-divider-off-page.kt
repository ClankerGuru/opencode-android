package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newDividerOffPage: WhatsNewEntry
    get() = smaller(
        id = "new-divider-off-page",
        day = "2026-10-22",
        title = "New Only When Needed",
        symbol = "line.horizontal.star.fill.line.horizontal",
        summary = "With the New Messages Divider experiment on, New marks what arrived only when there’s more than fits on screen.",
    )
