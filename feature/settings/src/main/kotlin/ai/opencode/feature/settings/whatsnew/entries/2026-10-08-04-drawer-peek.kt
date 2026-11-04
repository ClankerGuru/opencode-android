package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerPeek: WhatsNewEntry
    get() = feature(
        id = "drawer-peek",
        day = "2026-10-08",
        title = "When Sessions Were Active",
        symbol = "clock",
        tint = Tint.blue,
        summary = "Drag the session list toward the session to see when each was last active.",
        details = listOf(
            "Swiping left over the list closes it.",
        ),
        example = "Open the session list and drag it to the right",
    )
