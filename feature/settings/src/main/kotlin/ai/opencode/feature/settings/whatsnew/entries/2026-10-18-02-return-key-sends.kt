package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.returnKeySends: WhatsNewEntry
    get() = smaller(
        id = "return-key-sends",
        day = "2026-10-18",
        title = "Return Key Setting",
        symbol = "return",
        summary = "Turn off Return Key Sends in Settings when you want Return to add a new line, or use Command-Return and Shift-Return on a hardware keyboard.",
        tryIt = Destination.Open(SettingsDestination.Sessions),
    )
