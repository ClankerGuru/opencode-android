package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pairOpensDrawer: WhatsNewEntry
    get() = smaller(
        id = "pair-opens-drawer",
        day = "2026-10-23",
        title = "Pick Up Where You Left Off",
        symbol = "desktopcomputer",
        summary = "After you pair a new computer, the sidebar opens on its recent sessions so you can choose one, instead of leaving you on an empty session.",
    )
