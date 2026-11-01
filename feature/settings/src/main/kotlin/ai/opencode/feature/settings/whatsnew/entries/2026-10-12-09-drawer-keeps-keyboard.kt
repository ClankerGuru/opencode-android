package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerKeepsKeyboard: WhatsNewEntry
    get() = smaller(
        id = "drawer-keeps-keyboard",
        day = "2026-10-12",
        title = "Keyboard Slides With the Drawer",
        symbol = "keyboard",
        summary = "Opening the session list while typing keeps your message focused, and the keys slide aside with the session.",
    )
