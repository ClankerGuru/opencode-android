package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sendAfterAPause: WhatsNewEntry
    get() = smaller(
        id = "send-after-a-pause",
        day = "2026-10-26",
        title = "Smoother Send After a Pause",
        symbol = "paperplane",
        summary = "A message you send after a pause now rises straight from the composer into its bubble, instead of first dipping toward the composer under its time stamp.",
    )
