package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.simpleSessionLoading: WhatsNewEntry
    get() = smaller(
        id = "simple-session-loading",
        day = "2026-10-20",
        title = "Quieter Session Loading",
        symbol = "hourglass",
        summary = "A small, still logo fades in only when a session takes a moment to load, then fades into its messages.",
    )
