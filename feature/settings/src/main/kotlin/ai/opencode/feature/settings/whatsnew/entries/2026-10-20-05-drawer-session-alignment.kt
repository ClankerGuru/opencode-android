package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerSessionAlignment: WhatsNewEntry
    get() = smaller(
        id = "drawer-session-alignment",
        day = "2026-10-20",
        title = "Aligned Drawer Controls",
        symbol = "sidebar.left",
        summary = "Search, New Session and Settings line up with the session’s header and message box on phones and tablets.",
    )
