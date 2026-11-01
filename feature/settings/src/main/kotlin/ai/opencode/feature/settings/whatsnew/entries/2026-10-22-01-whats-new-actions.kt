package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.whatsNewActions: WhatsNewEntry
    get() = smaller(
        id = "whats-new-actions",
        day = "2026-10-22",
        title = "Open and Try From What’s New",
        symbol = "gift",
        summary = "An entry’s page in What’s New has a button that opens its setting, like Themes, or starts a new session with a prompt typed in for you to try.",
    )
