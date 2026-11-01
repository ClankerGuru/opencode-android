package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerDragFollowsFinger: WhatsNewEntry
    get() = smaller(
        id = "drawer-drag-follows-finger",
        day = "2026-10-27",
        title = "Sidebar Drags Follow Your Finger",
        symbol = "hand.point.up.left",
        summary = "A session you drag in the sidebar now stays right under your finger, rides above the header, scrolls the list at its edges, and springs into place when you let go.",
    )
