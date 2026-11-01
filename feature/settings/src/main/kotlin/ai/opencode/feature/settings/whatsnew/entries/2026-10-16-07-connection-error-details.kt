package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.connectionErrorDetails: WhatsNewEntry
    get() = smaller(
        id = "connection-error-details",
        day = "2026-10-16",
        title = "Connection Errors Say What Failed",
        symbol = "network",
        summary = "Connection errors show the error code, address tried, and DNS result, with help when a tailnet name resolves outside Tailscale.",
    )
