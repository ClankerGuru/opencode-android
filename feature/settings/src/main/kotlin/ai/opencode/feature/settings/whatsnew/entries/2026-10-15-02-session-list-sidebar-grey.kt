package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionListSidebarGrey: WhatsNewEntry
    get() = smaller(
        id = "session-list-sidebar-grey",
        day = "2026-10-15",
        title = "Roomier Session List",
        symbol = "sidebar.left",
        summary = "The session list has a larger title and bigger grey Pinned and Recents headers with more room above them; open the drawer to see it.",
    )
