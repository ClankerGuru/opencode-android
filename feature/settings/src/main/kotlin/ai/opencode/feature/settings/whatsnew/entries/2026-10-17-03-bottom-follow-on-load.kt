package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.bottomFollowOnLoad: WhatsNewEntry
    get() = smaller(
        id = "bottom-follow-on-load",
        day = "2026-10-17",
        title = "Stay at the Bottom",
        symbol = "arrow.down",
        summary = "Sessions stay at the bottom while delayed content loads.",
    )
