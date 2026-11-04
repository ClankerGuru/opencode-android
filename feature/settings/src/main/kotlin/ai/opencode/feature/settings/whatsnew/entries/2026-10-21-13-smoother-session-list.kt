package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smootherSessionList: WhatsNewEntry
    get() = smaller(
        id = "smoother-session-list",
        day = "2026-10-21",
        title = "Smoother Session List",
        symbol = "list.bullet",
        summary = "The sidebar’s session list scrolls smoothly while sessions work, and holding a session opens its menu with the row staying its size.",
    )
