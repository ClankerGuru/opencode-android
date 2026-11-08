package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.openLinksFromCalls: WhatsNewEntry
    get() = smaller(
        id = "open-links-from-calls",
        day = "2026-11-01",
        title = "Open Links from Calls",
        symbol = "arrow.up.forward.app",
        summary = "Turn on Open Links from Calls in Settings › Experimental › Calls, and during a call an agent can offer a link, like an Uber ride: tap Open to open it in its app, or Not Now.",
    )
