package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.quickerSend: WhatsNewEntry
    get() = smaller(
        id = "quicker-send",
        day = "2026-11-01",
        title = "Quicker Send",
        symbol = "paperplane",
        summary = "A message you send now rises into its bubble along with the list, instead of waiting at the composer first.",
    )
