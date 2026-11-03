package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shareScreenWithAgents: WhatsNewEntry
    get() = smaller(
        id = "share-screen-with-agents",
        day = "2026-11-01",
        title = "Share Screen with Agents",
        symbol = "rectangle.on.rectangle",
        summary = "Turn on Share Screen with Agents in Settings › Experimental › Calls, and during a call an agent can ask to see the app: tap Share to send it a picture, or Not Now.",
        tryIt = Destination.Open(SettingsDestination.Experiment("share-screen-with-agents")),
    )
