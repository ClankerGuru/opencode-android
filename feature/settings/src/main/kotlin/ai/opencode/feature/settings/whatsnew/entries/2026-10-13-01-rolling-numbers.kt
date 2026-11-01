package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.rollingNumbers: WhatsNewEntry
    get() = smaller(
        id = "rolling-numbers",
        day = "2026-10-13",
        title = "Rolling Numbers",
        symbol = "number",
        summary = "Live counts in the background-work capsule, the Session sheet, Used N rows, and Catch Up roll digit by digit.",
    )
