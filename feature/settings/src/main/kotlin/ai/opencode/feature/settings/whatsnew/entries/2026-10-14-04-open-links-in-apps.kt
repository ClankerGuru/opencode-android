package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.openLinksInApps: WhatsNewEntry
    get() = smaller(
        id = "open-links-in-apps",
        day = "2026-10-14",
        title = "Links Open in Their Apps",
        symbol = "arrow.up.forward.app",
        summary = "A link opens in the app that handles it, like GitHub or YouTube; with the In-App Browser experiment on, hold a link and choose Open in App Browser.",
        tryIt = Destination.Open(SettingsDestination.Experiment("in-app-browser")),
    )
