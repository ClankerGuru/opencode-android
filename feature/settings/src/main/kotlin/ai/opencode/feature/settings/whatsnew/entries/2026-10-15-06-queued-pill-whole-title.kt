package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.queuedPillWholeTitle: WhatsNewEntry
    get() = smaller(
        id = "queued-pill-whole-title",
        day = "2026-10-15",
        title = "Whole Queued Count",
        symbol = "text.bubble",
        summary = "The queued pill above the composer reads “2 queued” in full instead of cutting it to “2 queu…”, and rolling counts elsewhere keep every character too.",
    )
