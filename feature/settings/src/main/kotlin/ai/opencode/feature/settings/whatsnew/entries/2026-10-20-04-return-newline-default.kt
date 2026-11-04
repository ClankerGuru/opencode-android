package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.returnNewlineDefault: WhatsNewEntry
    get() = smaller(
        id = "return-newline-default",
        day = "2026-10-20",
        title = "Return Adds a New Line",
        symbol = "return",
        summary = "Return now adds a new line by default. Turn on Return Key Sends in Settings › Sessions to send with Return, or use Ctrl+Enter on a hardware keyboard.",
        tryIt = Destination.Open(SettingsDestination.Sessions),
    )
