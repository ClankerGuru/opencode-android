package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.queuedMessagesFittedSheet: WhatsNewEntry
    get() = smaller(
        id = "queued-messages-fitted-sheet",
        day = "2026-10-14",
        title = "Tidier Queued Messages",
        symbol = "text.bubble",
        summary = "The queued messages sheet is only as tall as your queue, with numbered rows and quiet buttons to send a message now or cancel it.",
    )
