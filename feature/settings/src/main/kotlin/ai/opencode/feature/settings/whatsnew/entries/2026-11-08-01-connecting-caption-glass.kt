package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.connectingCaptionGlass: WhatsNewEntry
    get() = smaller(
        id = "connecting-caption-glass",
        day = "2026-11-08",
        title = "Readable “Connecting via…”",
        symbol = "waveform",
        summary = "While a call connects through a server, its “Connecting via…” line sits on glass, so it no longer runs into the messages behind it.",
    )
