package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerTitleFade: WhatsNewEntry
    get() = smaller(
        id = "drawer-title-fade",
        day = "2026-10-21",
        title = "Fuller Session Titles",
        symbol = "text.alignleft",
        summary = "Long session titles in the sidebar run to the edge and fade out instead of stopping early with an ellipsis.",
    )
