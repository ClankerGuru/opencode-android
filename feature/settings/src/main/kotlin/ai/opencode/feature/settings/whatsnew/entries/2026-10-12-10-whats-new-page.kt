package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.whatsNewPage: WhatsNewEntry
    get() = smaller(
        id = "whats-new-page",
        day = "2026-10-12",
        title = "What’s New, Simpler",
        symbol = "gift",
        summary = "What’s New opens as a page like Settings, with a page for each feature and fixes gathered here.",
    )
