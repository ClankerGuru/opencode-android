package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.loadingTouchRecovery: WhatsNewEntry
    get() = smaller(
        id = "loading-touch-recovery",
        day = "2026-11-04",
        title = "Scrolling After Loading",
        symbol = "hand.draw",
        summary = "Returning to the app during a loading transition restores transcript scrolling immediately.",
    )
