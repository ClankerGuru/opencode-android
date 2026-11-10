package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.quietReconnectingPill: WhatsNewEntry
    get() = smaller(
        id = "quiet-reconnecting-pill",
        day = "2026-11-05",
        title = "Steadier Launch",
        symbol = "arrow.triangle.2.circlepath",
        summary = "Opening the app no longer flashes “Reconnecting…” above the composer or nudges the session; the pill shows only when a reconnect takes a moment.",
    )
