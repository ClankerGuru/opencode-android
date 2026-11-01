package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionListQuietHierarchy: WhatsNewEntry
    get() = smaller(
        id = "session-list-quiet-hierarchy",
        day = "2026-10-14",
        title = "Easier-to-Scan Session List",
        symbol = "textformat.size",
        summary = "Pinned and Recents step back in smaller grey type, unread and waiting sessions stand out in medium weight, and read ones dim slightly.",
    )
