package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuTouchRelease: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-touch-release",
        day = "2026-11-05",
        title = "Closing the Attachment Menu",
        symbol = "hand.draw",
        summary = "Closing the attachment menu releases the session beneath it even during a layout change.",
    )
