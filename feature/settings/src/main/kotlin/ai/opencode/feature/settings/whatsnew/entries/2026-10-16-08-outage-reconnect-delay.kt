package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.outageReconnectDelay: WhatsNewEntry
    get() = smaller(
        id = "outage-reconnect-delay",
        day = "2026-10-16",
        title = "Faster Reconnects",
        symbol = "network",
        summary = "Sessions catch up sooner when the server comes back after an outage.",
    )
