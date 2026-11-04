package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.catchUpFlicks: WhatsNewEntry
    get() = smaller(
        id = "catch-up-flicks",
        day = "2026-10-13",
        title = "Easier Swipes in Catch Up",
        symbol = "rectangle.stack",
        summary = "A quick flick or shorter drag in Catch Up sends the card on its way instead of snapping it back.",
    )
