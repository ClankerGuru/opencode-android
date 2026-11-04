package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.dragToPin: WhatsNewEntry
    get() = smaller(
        id = "drag-to-pin",
        day = "2026-10-18",
        title = "Drag a Session to Pin It",
        symbol = "pin",
        summary = "Hold and drag any session up into Pinned to pin it in that spot, or drag a pinned session down into Recents to unpin it.",
    )
