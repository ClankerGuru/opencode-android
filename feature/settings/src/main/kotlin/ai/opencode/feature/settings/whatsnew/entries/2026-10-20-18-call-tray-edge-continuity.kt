package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayEdgeContinuity: WhatsNewEntry
    get() = smaller(
        id = "call-tray-edge-continuity",
        day = "2026-10-20",
        title = "Call Tray Motion",
        symbol = "waveform",
        summary = "The Call Tray’s waveform follows its rounded edge as it opens and moves into or out of the drawer.",
    )
