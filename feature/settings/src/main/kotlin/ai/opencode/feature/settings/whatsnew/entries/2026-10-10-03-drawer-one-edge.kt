package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerOneEdge: WhatsNewEntry
    get() = smaller(
        id = "drawer-one-edge",
        day = "2026-10-10",
        title = "Tidier Drawer",
        symbol = "sidebar.left",
        summary = "Rows start on one edge, status dots sit at the end of each row, and search is a button at the top.",
    )
