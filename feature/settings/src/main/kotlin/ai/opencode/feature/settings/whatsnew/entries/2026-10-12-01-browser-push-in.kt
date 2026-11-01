package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.browserPushIn: WhatsNewEntry
    get() = smaller(
        id = "browser-push-in",
        day = "2026-10-12",
        title = "In-App Browser Pages",
        symbol = "safari",
        summary = "With the In-App Browser experiment on, a link pushes its page in over the session. Swipe from the edge to go back.",
        tryIt = Destination.Open(SettingsDestination.Experiment("in-app-browser")),
    )
