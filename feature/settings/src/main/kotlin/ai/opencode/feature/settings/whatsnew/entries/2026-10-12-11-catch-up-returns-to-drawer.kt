package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.catchUpReturnsToDrawer: WhatsNewEntry
    get() = smaller(
        id = "catch-up-returns-to-drawer",
        day = "2026-10-12",
        title = "Catch Up Returns to the Drawer",
        symbol = "rectangle.stack",
        summary = "Leaving Catch Up takes you back to the session list as you left it.",
    )
