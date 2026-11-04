package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.interruptedHand: WhatsNewEntry
    get() = smaller(
        id = "interrupted-hand",
        day = "2026-10-19",
        title = "A Hand for Interrupted",
        symbol = "hand.raised.fill",
        summary = "An interrupted run and an interrupted shell now show a raised hand, and a shell you stop reads Interrupted instead of Stopped.",
    )
