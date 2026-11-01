package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.settingsSessionsPage: WhatsNewEntry
    get() = smaller(
        id = "settings-sessions-page",
        day = "2026-10-26",
        title = "Sessions Settings",
        symbol = "bubble.left.fill",
        summary = "Settings has a new Sessions page, where New Messages Divider sits above Return Key Sends, and Advanced is now two rows: Experimental and Developer.",
        tryIt = Destination.Open(SettingsDestination.Sessions),
    )
