package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionMentions: WhatsNewEntry
    get() = smaller(
        id = "session-mentions",
        day = "2026-10-23",
        title = "Mention Sessions",
        symbol = "bubble.left.and.bubble.right",
        summary = "With the Composer Autocomplete experiment on, type @ to mention another session by its title, as on desktop.",
    )
