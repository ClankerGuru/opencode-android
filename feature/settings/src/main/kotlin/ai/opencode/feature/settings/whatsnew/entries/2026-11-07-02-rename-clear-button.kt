package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.renameClearButton: WhatsNewEntry
    get() = smaller(
        id = "rename-clear-button",
        day = "2026-11-07",
        title = "Clear a Title in One Tap",
        symbol = "xmark.circle",
        summary = "Rename Session and Rename Server now have a clear button, so you can start a new title without deleting the old one.",
    )
