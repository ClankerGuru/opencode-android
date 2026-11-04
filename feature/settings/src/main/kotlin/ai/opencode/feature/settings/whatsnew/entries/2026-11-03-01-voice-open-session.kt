package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceOpenSession: WhatsNewEntry
    get() = smaller(
        id = "voice-open-session",
        day = "2026-11-03",
        title = "Switch Sessions by Voice",
        symbol = "arrow.triangle.swap",
        summary = "Asking your voice agent to open a session now closes the drawer and keeps your call connected.",
    )
